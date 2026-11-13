'use strict';
const test=require('node:test');const assert=require('node:assert/strict');const fs=require('fs'),os=require('os'),path=require('path');
const {AccountStore}=require('../src/accounts');
test('account registration, duplicate prevention, login, resume, expiry and revoke',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'avyansh-auth-'));const a=new AccountStore(dir);
 const first=await a.register('player_one','StrongPassword123!');
 assert.ok(first.uid);assert.equal(a.validate(first.uid,first.token).username,'player_one');
 await assert.rejects(()=>a.register('PLAYER_ONE','StrongPassword123!'),/already exists/);
 await assert.rejects(()=>a.login('player_one','WrongPassword123!'),/Invalid username/);
 const logged=await a.login('player_one','StrongPassword123!');
 assert.equal(a.resume(logged.token).uid,first.uid);
 a.users[first.uid].tokenExpires=Date.now()-1;a.save();assert.equal(a.validate(first.uid,logged.token),null);
 const fresh=await a.login('player_one','StrongPassword123!');assert.equal(a.revoke(first.uid,fresh.token),true);assert.equal(a.validate(first.uid,fresh.token),null);
 const emailAccount=await a.register('mailbox@example.com','EmailPassword123!');
 assert.equal(a.resume(emailAccount.token).username,'mailbox');
 fs.rmSync(dir,{recursive:true,force:true});
});

test('multiplayer hello rejects an unauthenticated identity and accepts an account token',async()=>{
 const {GameServer}=require('../src/server');const dir=fs.mkdtempSync(path.join(os.tmpdir(),'avyansh-gs-'));
 const game=new GameServer({dataDir:dir,log:false,allowLegacyAuth:false});
 const account=await game.accounts.register('wire_player','WirePassword123!');
 const bad={destroyed:false,write(){},destroy(){this.destroyed=true;},end(){this.destroyed=true;}};
 const badCtx={sess:null};game.onHello(bad,badCtx,{uid:account.uid,name:account.username,token:'wrong'});
 assert.equal(bad.destroyed,true);assert.equal(badCtx.sess,null);
 const sent=[];const good={destroyed:false,write(v){sent.push(v);},destroy(){this.destroyed=true;},end(){this.destroyed=true;}};
 const ctx={sess:null};game.onHello(good,ctx,{uid:account.uid,name:account.username,token:account.token});
 assert.ok(ctx.sess);assert.ok(sent.some(v=>JSON.parse(v).t==='welcome'));
 await game.close();fs.rmSync(dir,{recursive:true,force:true});
});

test('Google identity accounts use stable provider subject and cannot create duplicates',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'avyansh-google-')); const a=new AccountStore(dir);
 const profile={sub:'google-subject-123456789',email:'google.player@example.com',email_verified:true};
 const first=await a.loginGoogle(profile);
 const second=await a.loginGoogle(profile);
 assert.equal(first.uid,second.uid);
 assert.equal(a.users[first.uid].googleSub,profile.sub);
 assert.equal(a.resume(second.token).uid,first.uid);
 await assert.rejects(()=>a.loginGoogle({sub:profile.sub,email:profile.email,email_verified:false}),/not verified/);
 fs.rmSync(dir,{recursive:true,force:true});
});

test('Google OAuth start requires PKCE and app exchange code is one-use and verifier-bound',async()=>{
 const crypto=require('crypto');
 const {GameServer}=require('../src/server');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'avyansh-google-oauth-'));
 const old={id:process.env.GOOGLE_CLIENT_ID,secret:process.env.GOOGLE_CLIENT_SECRET,redirect:process.env.GOOGLE_REDIRECT_URI};
 process.env.GOOGLE_CLIENT_ID='web-client-id';
 process.env.GOOGLE_CLIENT_SECRET='server-secret';
 process.env.GOOGLE_REDIRECT_URI='https://game.example/auth/google/callback';
 const game=new GameServer({dataDir:dir,log:false});
 try {
  const verifier=crypto.randomBytes(32).toString('base64url');
  const challenge=crypto.createHash('sha256').update(verifier).digest('base64url');
  let status=0, body='';
  const res={writeHead(code){status=code;},end(value){body=String(value||'');}};
  game.googleStart(new URL('https://game.example/auth/google/start?code_challenge='+challenge),res);
  assert.equal(status,200);
  const authUrl=new URL(JSON.parse(body).authUrl);
  assert.equal(authUrl.searchParams.get('client_id'),'web-client-id');
  assert.equal(authUrl.searchParams.get('prompt'),'select_account');
  const state=authUrl.searchParams.get('state');
  assert.ok(game.googleOAuthStates.get(state).challenge===challenge);
  const account=await game.accounts.loginGoogle({sub:'google-subject-oauth-1',email:'oauth@example.com',email_verified:true});
  const code=crypto.randomBytes(32).toString('base64url');
  game.googleLoginCodes.set(code,{result:account,challenge,expiresAt:Date.now()+60000});
  const sent=[];
  const sock={write(v){sent.push(v);},destroy(){},end(){}};
  await game.dispatch(sock,{sess:null},{t:'auth_google_exchange',code,codeVerifier:'wrong'});
  assert.equal(JSON.parse(sent.pop()).t,'auth_error');
  game.googleLoginCodes.set(code,{result:account,challenge,expiresAt:Date.now()+60000});
  await game.dispatch(sock,{sess:null},{t:'auth_google_exchange',code,codeVerifier:verifier});
  assert.equal(JSON.parse(sent.pop()).t,'auth_ok');
  assert.equal(game.googleLoginCodes.has(code),false);
 } finally {
  if(old.id===undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID=old.id;
  if(old.secret===undefined) delete process.env.GOOGLE_CLIENT_SECRET; else process.env.GOOGLE_CLIENT_SECRET=old.secret;
  if(old.redirect===undefined) delete process.env.GOOGLE_REDIRECT_URI; else process.env.GOOGLE_REDIRECT_URI=old.redirect;
  await game.close(); fs.rmSync(dir,{recursive:true,force:true});
 }
});

test('friend search returns only matching registered account identifiers',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'avyansh-search-'));
 const {GameServer}=require('../src/server'); const game=new GameServer({dataDir:dir,log:false});
 try {
  const a=await game.accounts.register('search_alpha','StrongPassword123!');
  await game.accounts.register('other_bravo','StrongPassword123!');
  const sent=[]; const session={uid:'requester',send(m){sent.push(m);}};
  game.friendSearch(session,{query:'alpha'});
  assert.equal(sent.length,1);
  assert.equal(sent[0].t,'friend_search_results');
  assert.equal(sent[0].results.length,1);
  assert.equal(sent[0].results[0].uid,a.uid);
 } finally { await game.close(); fs.rmSync(dir,{recursive:true,force:true}); }
});
