'use strict';
const fs=require('fs'), path=require('path'), crypto=require('crypto');
const {promisify}=require('util'); const scrypt=promisify(crypto.scrypt);
class AccountStore {
 constructor(dir){this.file=path.join(dir||path.join(__dirname,'..','data'),'accounts.json');this.users={};this.byName={};this.byEmail={};
  try{this.users=JSON.parse(fs.readFileSync(this.file,'utf8')).users||{};}catch(_){}
  for(const [uid,u] of Object.entries(this.users)){this.byName[u.username.toLowerCase()]=uid;if(u.email)this.byEmail[u.email.toLowerCase()]=uid;}
 }
 async hash(password,salt){const out=await scrypt(password,salt,64,{N:16384,r:8,p:1,maxmem:64*1024*1024});return out.toString('hex');}
 async register(identity,password){
  if(typeof identity!=='string'||identity.trim().length<3||identity.trim().length>64)throw Error('Enter a username or email (3–64 characters)');
  if(typeof password!=='string'||password.length<10||password.length>128)throw Error('Password must be 10–128 characters');
  const email=identity.includes('@')?identity.trim().toLowerCase():'';
  const username=email?email.split('@')[0]:identity.trim();
  if(!/^[a-zA-Z0-9_.-]{3,24}$/.test(username))throw Error('Username must be 3–24 letters, numbers, dots, underscores or hyphens');
  if(email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))throw Error('Invalid email address');
  if(this.byName[username.toLowerCase()]||(email&&this.byEmail[email]))throw Error('Account already exists');
  const uid=crypto.randomBytes(12).toString('hex'),salt=crypto.randomBytes(16).toString('hex');
  const u={uid,username,email,salt,passwordHash:await this.hash(password,salt),tokenHash:'',tokenExpires:0,createdAt:Date.now()};
  this.users[uid]=u;this.byName[username.toLowerCase()]=uid;if(email)this.byEmail[email]=uid;this.save();return this.issue(u);
 }
 async login(identity,password){
  const key=String(identity||'').trim().toLowerCase(),uid=this.byName[key]||this.byEmail[key],u=uid&&this.users[uid];
  if(!u||!u.passwordHash||!u.salt||typeof password!=='string')throw Error('Invalid username/email or password');
  const actual=await this.hash(password,u.salt);
  if(!crypto.timingSafeEqual(Buffer.from(actual,'hex'),Buffer.from(u.passwordHash,'hex')))throw Error('Invalid username/email or password');
  return this.issue(u);
 }
 async loginGoogle(profile){
  if(!profile||typeof profile.sub!=='string'||profile.sub.length<8)throw Error('Invalid Google identity');
  const sub=profile.sub, email=String(profile.email||'').trim().toLowerCase();
  if(!email||profile.email_verified!==true)throw Error('Google email is not verified');
  let u=Object.values(this.users).find(x=>x.googleSub===sub);
  if(!u){
   const existingId=email&&this.byEmail[email], existing=existingId&&this.users[existingId];
   if(existing){ if(existing.googleSub&&existing.googleSub!==sub)throw Error('Google identity is already linked to another account'); existing.googleSub=sub; u=existing; }
   else {
    let base=(email.split('@')[0]||'player').toLowerCase().replace(/[^a-z0-9_.-]/g,'').slice(0,18);
    if(base.length<3)base='player';
    let username=base;
    while(this.byName[username.toLowerCase()]) username=base.slice(0,18)+'_'+crypto.randomBytes(2).toString('hex');
    const uid=crypto.randomBytes(12).toString('hex');
    u={uid,username,email,googleSub:sub,salt:'',passwordHash:'',tokenHash:'',tokenExpires:0,createdAt:Date.now()};
    this.users[uid]=u; this.byName[username.toLowerCase()]=uid; this.byEmail[email]=uid;
   }
   this.save();
  }
  return this.issue(u);
 }
 issue(u){const token=crypto.randomBytes(32).toString('hex');u.tokenHash=crypto.createHash('sha256').update(token).digest('hex');u.tokenExpires=Date.now()+30*24*60*60*1000;this.save();return {uid:u.uid,username:u.username,token,expiresAt:u.tokenExpires};}
 resume(token){if(typeof token!=='string'||token.length<32)throw Error('Session expired');const hash=crypto.createHash('sha256').update(token).digest('hex');
  const u=Object.values(this.users).find(x=>x.tokenHash===hash);if(!u||u.tokenExpires<Date.now()){if(u){u.tokenHash='';u.tokenExpires=0;this.save();}throw Error('Session expired');}
  return {uid:u.uid,username:u.username,token,expiresAt:u.tokenExpires};
 }
 revoke(uid,token){const u=this.users[uid];if(!u||!token)return false;const hash=crypto.createHash('sha256').update(token).digest('hex');if(u.tokenHash!==hash)return false;u.tokenHash='';u.tokenExpires=0;this.save();return true;}
 validate(uid,token){try{const r=this.resume(token);return r.uid===uid?r:null;}catch(_){return null;}}
 save(){fs.mkdirSync(path.dirname(this.file),{recursive:true});const tmp=this.file+'.tmp';fs.writeFileSync(tmp,JSON.stringify({users:this.users},null,2),{mode:0o600});fs.renameSync(tmp,this.file);}
}
module.exports={AccountStore};
