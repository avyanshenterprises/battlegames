'use strict';
const net = require('net');
const http = require('http');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const loadConfig = require('./config');
const U = require('./util');
const { FriendStore } = require('./friends');
const { AccountStore } = require('./accounts');
const { EconomyStore } = require('./economy');
const { Room } = require('./rooms');
const { Matchmaker } = require('./matchmaker');
const { Match, BOT_NAMES } = require('./match');
const { Terrain } = require('./terrain');
const W = require('./weapons');


/**
 * Minimal RFC 6455 text-frame adapter. It deliberately uses only Node built-ins,
 * so the server can run on HTTP/WebSocket hosts without npm dependencies.
 * Game protocol remains one JSON object per text frame.
 */
class WebSocketPeer extends EventEmitter {
  constructor(socket, head) {
    super();
    this.raw = socket;
    this.destroyed = false;
    this.buffer = Buffer.alloc(0);
    this.frag = [];
    this.fragOpcode = 0;
    socket.on('data', (b) => this.consume(b));
    socket.on('error', (e) => this.emit('error', e));
    socket.on('close', () => { this.destroyed = true; this.emit('close'); });
    if (head && head.length) this.consume(head);
  }
  setNoDelay() { return this; }
  setEncoding() { return this; }
  setKeepAlive() { return this; }
  get writable() { return !this.destroyed && this.raw.writable; }
  write(line) {
    if (this.destroyed) return false;
    const text = String(line).replace(/\n$/, '');
    return this.sendFrame(0x1, Buffer.from(text, 'utf8'));
  }
  sendFrame(op, payload) {
    if (this.destroyed) return false;
    const n = payload.length;
    let h;
    if (n < 126) { h = Buffer.from([0x80 | op, n]); }
    else if (n <= 65535) { h = Buffer.alloc(4); h[0] = 0x80 | op; h[1] = 126; h.writeUInt16BE(n, 2); }
    else { h = Buffer.alloc(10); h[0] = 0x80 | op; h[1] = 127; h.writeBigUInt64BE(BigInt(n), 2); }
    try { this.raw.write(Buffer.concat([h, payload])); return true; } catch (_) { return false; }
  }
  consume(chunk) {
    if (this.destroyed) return;
    this.buffer = Buffer.concat([this.buffer, chunk]);
    if (this.buffer.length > 1024 * 1024) return this.destroy();
    while (this.buffer.length >= 2) {
      const b0 = this.buffer[0], b1 = this.buffer[1];
      const fin = !!(b0 & 0x80), op = b0 & 0x0f, masked = !!(b1 & 0x80);
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buffer.length < 4) return; len = this.buffer.readUInt16BE(2); off = 4; }
      else if (len === 127) {
        if (this.buffer.length < 10) return;
        const big = this.buffer.readBigUInt64BE(2);
        if (big > 65536n) return this.destroy();
        len = Number(big); off = 10;
      }
      if (!masked || len > 65536) return this.destroy();
      if (this.buffer.length < off + 4 + len) return;
      const mask = this.buffer.subarray(off, off + 4); off += 4;
      const data = Buffer.from(this.buffer.subarray(off, off + len));
      this.buffer = this.buffer.subarray(off + len);
      for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
      if (op === 0x8) { this.sendFrame(0x8, data.subarray(0, 125)); return this.destroy(); }
      if (op === 0x9) { this.sendFrame(0xA, data); continue; }
      if (op === 0xA) continue;
      if (op === 0x1 || op === 0x2) {
        if (!fin) { this.fragOpcode = op; this.frag = [data]; continue; }
        if (op !== 0x1) return this.destroy();
        this.emit('data', data.toString('utf8') + '\n');
      } else if (op === 0x0) {
        if (!this.fragOpcode) return this.destroy();
        this.frag.push(data);
        if (fin) {
          const opcode = this.fragOpcode, joined = Buffer.concat(this.frag);
          this.frag = []; this.fragOpcode = 0;
          if (opcode !== 0x1) return this.destroy();
          this.emit('data', joined.toString('utf8') + '\n');
        }
      } else return this.destroy();
    }
  }
  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    try { this.raw.end(); } catch (_) { try { this.raw.destroy(); } catch (__) {} }
  }
}

class Session {
  constructor(uid, name) {
    this.uid = uid; this.name = name; this.socket = null; this.room = null; this.match = null; this.player = null;
    this.tokens = 240; this.lastRefill = Date.now(); this.dropped = 0; this.dropWin = 0; this.lastSeen = Date.now(); this.buf = ''; this.authExpiresAt = 0;
  }
  get online() { return !!this.socket; }
  send(msg) {
    const s = this.socket; if (!s || s.destroyed) return;
    try { s.write(JSON.stringify(msg) + '\n'); } catch (e) { /* socket gone */ }
  }
}

class GameServer {
  constructor(options) {
    this.cfg = loadConfig(options);
    this.friends = new FriendStore(this.cfg.dataDir || null);
    this.accounts = new AccountStore(this.cfg.dataDir || null);
    this.economy = new EconomyStore(this.cfg.dataDir || null);
    this.sessions = new Map();
    this.rooms = new Map();
    this.matches = new Map();
    this.terrain = new Terrain(require('./match').WORLD_SEED);
    this.mm = new Matchmaker(this.cfg, (r) => this.startQueuedMatch(r));
    this.matchSeq = 0;
    this.sockets = new Set();
    this.googleOAuthStates = new Map();
    this.googleLoginCodes = new Map();
    this.googleSigningKeys = null;
    this.googleSigningKeysExpiry = 0;
    this.server = net.createServer((s) => this.onConnection(s));
    this.httpPort = this.cfg.httpPort == null ? Number(process.env.PORT || 8080) : this.cfg.httpPort;
    this.httpServer = http.createServer((req, res) => {
      this.handleHttp(req, res).catch((e) => {
        if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json', 'cache-control': 'no-store' });
        try { res.end(JSON.stringify({ error: 'server_error' })); } catch (_) {}
        this.log('HTTP route error', e && e.stack);
      });
    });
    this.httpServer.on('upgrade', (req, socket, head) => {
      const key = req.headers['sec-websocket-key'];
      if (req.url !== '/ws' || req.headers.upgrade?.toLowerCase() !== 'websocket' || !key) {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n'); socket.destroy(); return;
      }
      const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
      socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
      this.onConnection(new WebSocketPeer(socket, head));
    });
    this.stats = { tickMs: 0, tickMax: 0, ticks: 0 };
  }
  log(...a) { if (this.cfg.log) console.log(new Date().toISOString(), ...a); }
  listen() {
    return new Promise((res, rej) => {
      let tcpReady = false, httpReady = false, settled = false;
      const fail = (e) => { if (!settled) { settled = true; rej(e); } };
      const ready = () => {
        if (settled || !tcpReady || !httpReady) return;
        settled = true;
        this.tickTimer = setInterval(() => this.tickAll(), Math.round(1000 / this.cfg.tickRate));
        this.mmTimer = setInterval(() => { this.mm.poll(Date.now()); }, this.cfg.mmIntervalMs);
        this.statusTimer = setInterval(() => this.pushMmStatus(), 1000);
        this.gcTimer = setInterval(() => this.gc(), 30000);
        for (const t of [this.tickTimer, this.mmTimer, this.statusTimer, this.gcTimer]) if (t.unref) t.unref();
        this.log('TCP listening on', this.cfg.host + ':' + this.port, 'WebSocket listening on', this.cfg.host + ':' + this.wsPort + '/ws');
        res(this.port);
      };
      this.server.once('error', fail);
      this.httpServer.once('error', fail);
      this.server.listen(this.cfg.port, this.cfg.host, () => { this.port = this.server.address().port; tcpReady = true; ready(); });
      this.httpServer.listen(this.httpPort, this.cfg.host, () => { this.wsPort = this.httpServer.address().port; httpReady = true; ready(); });
    });
  }
  close() {
    clearInterval(this.tickTimer); clearInterval(this.mmTimer); clearInterval(this.statusTimer); clearInterval(this.gcTimer);
    this.friends.flush();
    for (const s of this.sockets) s.destroy();
    return Promise.all([
      new Promise((r) => { if (!this.server.listening) return r(); this.server.close(() => r()); }),
      new Promise((r) => { if (!this.httpServer.listening) return r(); this.httpServer.close(() => r()); })
    ]);
  }

  // ------------------------------------------------------------------ transport
  onConnection(sock) {
    if (this.sockets.size >= this.cfg.maxConnections) { sock.destroy(); return; }
    sock.setNoDelay(true); sock.setEncoding('utf8'); sock.setKeepAlive(true, 15000);
    this.sockets.add(sock);
    const ctx = { sess: null, buf: '', tokens: this.cfg.rateBurst, last: Date.now(), dropped: 0, dropT: Date.now() };
    const helloTimer = setTimeout(() => { if (!ctx.sess) sock.destroy(); }, this.cfg.helloTimeoutMs);
    sock.on('data', (chunk) => {
      ctx.buf += chunk;
      if (ctx.buf.length > this.cfg.maxLine * 4 && ctx.buf.indexOf('\n') < 0) { sock.destroy(); return; }
      let i;
      while ((i = ctx.buf.indexOf('\n')) >= 0) {
        const line = ctx.buf.slice(0, i); ctx.buf = ctx.buf.slice(i + 1);
        if (!line.length) continue;
        if (line.length > this.cfg.maxLine) continue;
        // token bucket
        const now = Date.now();
        ctx.tokens = Math.min(this.cfg.rateBurst, ctx.tokens + (now - ctx.last) / 1000 * this.cfg.rateRefill); ctx.last = now;
        if (ctx.tokens < 1) {
          if (now - ctx.dropT > 1000) { ctx.dropT = now; ctx.dropped = 0; }
          if (++ctx.dropped > this.cfg.rateKick) { sock.destroy(); return; }
          continue;
        }
        ctx.tokens -= 1;
        let m; try { m = JSON.parse(line); } catch (e) { continue; }
        if (!m || typeof m !== 'object' || typeof m.t !== 'string') continue;
        try { this.dispatch(sock, ctx, m); } catch (e) { this.log('dispatch error', m.t, e && e.stack); }
      }
    });
    sock.on('error', () => {});
    sock.on('close', () => {
      clearTimeout(helloTimer); this.sockets.delete(sock);
      if (ctx.sess && ctx.sess.socket === sock) { ctx.sess.socket = null; this.onOffline(ctx.sess); }
    });
  }
  err(sock, code, msg) { try { sock.write(JSON.stringify({ t: 'error', code, msg: msg || code }) + '\n'); } catch (e) {} }

  dispatch(sock, ctx, m) {
    if (m.t === 'auth_register' || m.t === 'auth_login' || m.t === 'auth_resume' || m.t === 'auth_logout' || m.t === 'auth_google' || m.t === 'auth_google_exchange') return this.onAccountAuth(sock, ctx, m);
    if (m.t === 'hello') return this.onHello(sock, ctx, m);
    const s = ctx.sess;
    if (!s) return this.err(sock, 'no_hello');
    if (s.authExpiresAt && Date.now() >= s.authExpiresAt) { this.err(sock, 'session_expired'); sock.destroy(); return; }
    s.lastSeen = Date.now();
    switch (m.t) {
      case 'ping': return s.send({ t: 'pong', ts: m.ts, serverTime: Date.now() });
      case 'friend_add': return this.friendAdd(s, m);
      case 'friend_search': return this.friendSearch(s, m);
      case 'friend_accept': if (this.friends.accept(s.uid, String(m.uid))) { this.pushFriends(s); const o = this.sessions.get(String(m.uid)); if (o) this.pushFriends(o); } return;
      case 'friend_decline': this.friends.decline(s.uid, String(m.uid)); return this.pushFriends(s);
      case 'friend_remove': this.friends.remove(s.uid, String(m.uid)); this.pushFriends(s); { const o = this.sessions.get(String(m.uid)); if (o) this.pushFriends(o); } return;
      case 'friends_list': return this.pushFriends(s);
      case 'economy_get': return s.send(this.economy.snapshot(s.uid));
      case 'economy_daily_claim': {
        try { return s.send(this.economy.claimDaily(s.uid)); }
        catch (e) { return s.send({ t: 'economy_error', code: String(e.message || 'daily_claim_failed') }); }
      }
      case 'economy_mission_claim': {
        try { return s.send(this.economy.claimMission(s.uid)); }
        catch (e) { return s.send({ t: 'economy_error', code: String(e.message || 'mission_claim_failed') }); }
      }
      case 'economy_purchase':
        return s.send({ t: 'economy_error', code: 'store_catalog_not_configured', message: 'No purchasable cosmetic catalog is configured on this server.' });
      case 'invite': return this.invite(s, m);
      case 'room_create': return this.roomCreate(s, m);
      case 'room_join': return this.roomJoin(s, m);
      case 'room_leave': return this.roomLeave(s, 'left');
      case 'room_ready': return this.roomReady(s, m);
      case 'room_team': return this.roomTeam(s, m);
      case 'room_config': return this.roomConfig(s, m);
      case 'room_kick': return this.roomKick(s, m);
      case 'room_start': return this.roomStart(s);
      case 'room_list': return s.send({ t: 'rooms', rooms: Array.from(this.rooms.values()).filter((r) => !r.priv && !r.matchId).slice(0, 50).map((r) => r.publicInfo()) });
      case 'mm_join': return this.mmJoin(s, m);
      case 'mm_cancel': return this.mmCancel(s);
      case 'leave_match': if (s.match && s.player) s.match.leave(s.player); return;
      default: return this.matchMsg(s, m);
    }
  }

  // ------------------------------------------------------------------ HTTP utility + Google OAuth browser hand-off
  async handleHttp(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/health' || url.pathname === '/') {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify({ ok: true, service: 'avyansh-battle-royale', transport: 'websocket', path: '/ws' }));
      return;
    }
    if (url.pathname === '/auth/google/start' && req.method === 'GET') return this.googleStart(url, res);
    if (url.pathname === '/auth/google/callback' && req.method === 'GET') return this.googleCallback(url, res);
    res.writeHead(404, { 'cache-control': 'no-store' }); res.end('not found');
  }

  googleStart(url, res) {
    for (const [k, v] of this.googleOAuthStates) if (v.expiresAt < Date.now()) this.googleOAuthStates.delete(k);
    for (const [k, v] of this.googleLoginCodes) if (v.expiresAt < Date.now()) this.googleLoginCodes.delete(k);
    const challenge = url.searchParams.get('code_challenge') || '';
    const clientId = process.env.GOOGLE_CLIENT_ID || '';
    const redirectUri = process.env.GOOGLE_REDIRECT_URI || '';
    if (!clientId || !redirectUri || !process.env.GOOGLE_CLIENT_SECRET || !/^[A-Za-z0-9_-]{43}$/.test(challenge)) {
      res.writeHead(503, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify({ error: 'google_oauth_not_configured' })); return;
    }
    const state = crypto.randomBytes(24).toString('base64url');
    this.googleOAuthStates.set(state, { expiresAt: Date.now() + 5 * 60 * 1000, challenge });
    const auth = new URL('https://accounts.google.com/o/oauth2/v2/auth');
    auth.searchParams.set('client_id', clientId);
    auth.searchParams.set('redirect_uri', redirectUri);
    auth.searchParams.set('response_type', 'code');
    auth.searchParams.set('scope', 'openid email profile');
    auth.searchParams.set('state', state);
    auth.searchParams.set('prompt', 'select_account');
    auth.searchParams.set('access_type', 'online');
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ authUrl: auth.toString() }));
  }

  async googleCallback(url, res) {
    const state = url.searchParams.get('state') || '';
    const stateEntry = this.googleOAuthStates.get(state);
    this.googleOAuthStates.delete(state);
    if (!stateEntry || stateEntry.expiresAt < Date.now()) {
      res.writeHead(302, { location: 'avyansh://google-auth?error=state_expired', 'cache-control': 'no-store' }); res.end(); return;
    }
    const oauthError = url.searchParams.get('error');
    if (oauthError) {
      res.writeHead(302, { location: 'avyansh://google-auth?error=' + encodeURIComponent(oauthError), 'cache-control': 'no-store' }); res.end(); return;
    }
    const code = url.searchParams.get('code') || '';
    if (!code) {
      res.writeHead(302, { location: 'avyansh://google-auth?error=missing_code', 'cache-control': 'no-store' }); res.end(); return;
    }
    const form = new URLSearchParams({
      code,
      client_id: process.env.GOOGLE_CLIENT_ID || '',
      client_secret: process.env.GOOGLE_CLIENT_SECRET || '',
      redirect_uri: process.env.GOOGLE_REDIRECT_URI || '',
      grant_type: 'authorization_code'
    });
    const exchange = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: form
    });
    if (!exchange.ok) throw Error('Google authorization code exchange failed');
    const tokens = await exchange.json();
    const profile = await this.verifyGoogleIdToken(String(tokens.id_token || ''));
    const result = await this.accounts.loginGoogle(profile);
    const loginCode = crypto.randomBytes(32).toString('base64url');
    this.googleLoginCodes.set(loginCode, { result, challenge: stateEntry.challenge, expiresAt: Date.now() + 2 * 60 * 1000 });
    res.writeHead(302, { location: 'avyansh://google-auth?code=' + encodeURIComponent(loginCode), 'cache-control': 'no-store' });
    res.end();
  }

  // ------------------------------------------------------------------ session
  async verifyGoogleIdToken(idToken) {
    const clientId = process.env.GOOGLE_CLIENT_ID || '';
    if (!clientId) throw Error('Google sign-in is not configured on this server');
    const parts = String(idToken || '').split('.');
    if (parts.length !== 3 || idToken.length > 8192) throw Error('Invalid Google ID token');
    let header, payload;
    try {
      header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
      payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    } catch (_) { throw Error('Malformed Google ID token'); }
    if (header.alg !== 'RS256' || !header.kid) throw Error('Unsupported Google ID token signature');
    if (!this.googleSigningKeys || this.googleSigningKeysExpiry < Date.now()) {
      const response = await fetch('https://www.googleapis.com/oauth2/v3/certs');
      if (!response.ok) throw Error('Unable to retrieve Google signing certificates');
      const certSet = await response.json();
      const keys = new Map();
      for (const cert of certSet.keys || []) {
        if (!cert.kid || !Array.isArray(cert.x5c) || !cert.x5c[0]) continue;
        const pem = '-----BEGIN CERTIFICATE-----\\n' + cert.x5c[0].match(/.{1,64}/g).join('\\n') + '\\n-----END CERTIFICATE-----';
        keys.set(cert.kid, new crypto.X509Certificate(pem).publicKey);
      }
      const cache = String(response.headers.get('cache-control') || '').match(/max-age=(\d+)/i);
      this.googleSigningKeys = keys;
      this.googleSigningKeysExpiry = Date.now() + (cache ? Math.min(86400, Number(cache[1])) : 3600) * 1000;
    }
    const publicKey = this.googleSigningKeys.get(header.kid);
    if (!publicKey) throw Error('Unknown Google signing key');
    const signed = Buffer.from(parts[0] + '.' + parts[1], 'utf8');
    const signature = Buffer.from(parts[2], 'base64url');
    if (!require('crypto').verify('RSA-SHA256', signed, publicKey, signature)) throw Error('Google ID token signature is invalid');
    const now = Math.floor(Date.now() / 1000);
    const issuerOk = payload.iss === 'accounts.google.com' || payload.iss === 'https://accounts.google.com';
    const audienceOk = payload.aud === clientId || (Array.isArray(payload.aud) && payload.aud.includes(clientId));
    const authorizedPartyOk = !Array.isArray(payload.aud) || payload.aud.length <= 1 || payload.azp === clientId;
    const issuedAt = Number(payload.iat || 0);
    const verified = payload.email_verified === true || payload.email_verified === 'true';
    if (!issuerOk || !audienceOk || !authorizedPartyOk || Number(payload.exp || 0) <= now ||
        issuedAt <= 0 || issuedAt > now + 60 || !verified ||
        typeof payload.sub !== 'string' || !payload.sub || typeof payload.email !== 'string') {
      throw Error('Google identity token validation failed');
    }
    return { sub: payload.sub, email: payload.email, email_verified: true };
  }

  async onAccountAuth(sock, ctx, m) {
    if (ctx.sess) return this.err(sock, 'already_authenticated');
    try {
      let result;
      if (m.t === 'auth_logout') {
        const revoked=this.accounts.revoke(String(m.uid||''),String(m.token||''));
        if (revoked) { const active=this.sessions.get(String(m.uid||'')); if (active && active.socket) active.socket.destroy(); }
        sock.write(JSON.stringify({t:'auth_logout_ok',revoked})+'\n'); return;
      }
      if (m.t === 'auth_google_exchange') {
        const code = String(m.code || '');
        const entry = this.googleLoginCodes.get(code);
        this.googleLoginCodes.delete(code);
        const verifier = String(m.codeVerifier || '');
        const challenge = verifier ? crypto.createHash('sha256').update(verifier).digest('base64url') : '';
        if (!entry || entry.expiresAt < Date.now() || challenge !== entry.challenge) throw Error('Google login code expired or invalid; try again');
        const r = entry.result;
        sock.write(JSON.stringify({ t:'auth_ok', uid:r.uid, username:r.username, token:r.token, expiresAt:r.expiresAt }) + '\n');
        return;
      }
      if (m.t === 'auth_register') result = await this.accounts.register(m.identity, m.password);
      else if (m.t === 'auth_login') result = await this.accounts.login(m.identity, m.password);
      else if (m.t === 'auth_google') {
        const profile = await this.verifyGoogleIdToken(String(m.idToken || ''));
        result = await this.accounts.loginGoogle(profile);
      } else result = this.accounts.resume(m.token);
      if (m.t === 'auth_login' || m.t === 'auth_register' || m.t === 'auth_google') {
        const previous = this.sessions.get(result.uid);
        if (previous && previous.socket) previous.socket.destroy();
      }
      sock.write(JSON.stringify({ t:'auth_ok', uid:result.uid, username:result.username, token:result.token, expiresAt:result.expiresAt }) + '\n');
    } catch (e) {
      sock.write(JSON.stringify({ t:'auth_error', code:'auth_failed', message:String(e.message || 'Authentication failed').slice(0,120) }) + '\n');
    }
  }

  onHello(sock, ctx, m) {
    if (ctx.sess) return;
    if (!U.validUid(m.uid)) return this.err(sock, 'bad_uid');
    const account = this.accounts.validate(m.uid, m.token);
    if (!account && !this.cfg.allowLegacyAuth) { this.err(sock, 'bad_token', 'Authentication required'); return sock.destroy(); }
    const name = U.cleanName(account ? account.username : m.name, 'Player');
    const rec = this.friends.ensure(m.uid, name);
    let token = m.token;
    if (!account) {
      if (rec.tokenHash) {
        if (typeof m.token !== 'string' || U.sha256(m.token) !== rec.tokenHash) { this.err(sock, 'bad_token'); return sock.end(); }
      } else { token = U.randomToken(); rec.tokenHash = U.sha256(token); this.friends.dirty(); }
    }
    let s = this.sessions.get(m.uid);
    if (!s) { s = new Session(m.uid, name); this.sessions.set(m.uid, s); }
    if (s.socket && s.socket !== sock) { const old = s.socket; s.socket = null; try { old.destroy(); } catch (e) {} }
    s.name = name; s.socket = sock; ctx.sess = s; s.lastSeen = Date.now(); s.authExpiresAt = account ? account.expiresAt : 0;
    const resume = s.match && s.player && !s.match.ended ? { matchId: s.match.id } : null;
    s.send({ t: 'welcome', uid: s.uid, token, serverTime: Date.now(), resume, v: 1 });
    if (resume) {
      s.match.reconnect(s.player, s);
      s.send(s.match.startMsgFor(s.player, true));
      this.log('resume', s.uid, s.match.id);
    } else if (s.room) { s.send(s.room.state()); }
    this.notifyFriendsPresence(s);
  }
  onOffline(s) {
    if (this.mm.has(s.uid)) this.mmCancel(s, true);
    if (s.match && s.player && !s.match.ended) { s.match.disconnect(s.player); }
    else if (s.room) this.roomLeave(s, 'disconnected');
    this.notifyFriendsPresence(s);
  }
  gc() {
    const now = Date.now();
    for (const [uid, s] of this.sessions) if (!s.online && !s.room && !(s.match && !s.match.ended) && now - s.lastSeen > 300000) this.sessions.delete(uid);
  }

  // ------------------------------------------------------------------ friends
  friendSearch(s, m) {
    const q = String(m.query || '').trim().toLowerCase();
    if (q.length < 2 || q.length > 32) return s.send({ t: 'friend_search_results', results: [] });
    const results = [];
    for (const u of Object.values(this.accounts.users)) {
      if (u.uid === s.uid) continue;
      const username = String(u.username || '');
      if (username.toLowerCase().includes(q) || u.uid.toLowerCase().startsWith(q)) {
        results.push({ uid: u.uid, name: username });
        if (results.length >= 20) break;
      }
    }
    s.send({ t: 'friend_search_results', results });
  }

  friendAdd(s, m) {
    const to = String(m.uid);
    const r = this.friends.request(s.uid, to);
    if (r === 'unknown' || r === 'self') return s.send({ t: 'error', code: 'friend_' + r });
    const o = this.sessions.get(to);
    if (r === 'sent' && o) o.send({ t: 'friend_request', from: s.uid, name: s.name });
    if (r === 'accepted' && o) this.pushFriends(o);
    this.pushFriends(s);
  }
  pushFriends(s) {
    const u = this.friends.get(s.uid); if (!u) return;
    const row = (id) => { const o = this.sessions.get(id); const rec = this.friends.get(id); return { uid: id, name: rec ? rec.name : id, online: !!(o && o.online), room: o && o.room ? o.room.id : null, inMatch: !!(o && o.match && !o.match.ended) }; };
    s.send({ t: 'friends', friends: u.friends.map(row), pending: u.pending.map((id) => ({ uid: id, name: (this.friends.get(id) || {}).name || id })) });
  }
  notifyFriendsPresence(s) {
    const u = this.friends.get(s.uid); if (!u) return;
    for (const id of u.friends) { const o = this.sessions.get(id); if (o && o.online) this.pushFriends(o); }
  }
  invite(s, m) {
    const to = String(m.uid);
    if (!s.room) return s.send({ t: 'error', code: 'not_in_room' });
    if (!this.friends.areFriends(s.uid, to)) return s.send({ t: 'error', code: 'not_friends' });
    const o = this.sessions.get(to);
    if (!o || !o.online) return s.send({ t: 'error', code: 'friend_offline' });
    s.room.invited.set(to, Date.now() + 300000);
    o.send({ t: 'invite', from: s.uid, name: s.name, room: s.room.id, mode: s.room.mode });
  }

  // ------------------------------------------------------------------ rooms
  roomBroadcast(room) { const st = room.state(); for (const m of room.members.values()) m.session.send(st); }
  roomCreate(s, m) {
    if (s.match && !s.match.ended) return s.send({ t: 'error', code: 'in_match' });
    if (this.mm.has(s.uid)) this.mmCancel(s);
    if (s.room) this.roomLeave(s, 'left');
    let id; do { id = U.randomRoomId(); } while (this.rooms.has(id));
    const o = { mode: m.mode || 'br', teamSize: m.teamSize != null ? m.teamSize : (m.mode === 'br' || !m.mode ? 4 : 1), priv: !!m.priv, map: m.map || 'island' };
    if (typeof m.password === 'string' && m.password.length) { if (m.password.length > 16) return s.send({ t: 'error', code: 'bad_password' }); o.password = m.password; }
    if (m.maxPlayers != null) o.maxPlayers = m.maxPlayers;
    if (m.fillBots !== undefined) o.fillBots = m.fillBots;
    const bad = this.validateRoomOpts(o); if (bad) return s.send({ t: 'error', code: bad });
    const probe = new Room(id, s, o);
    this.rooms.set(id, probe); s.room = probe;
    this.roomBroadcast(probe);
  }
  validateRoomOpts(o) { const r = new Room('XXXXXX', { uid: 'x', name: 'x' }, {}); return r.configure(o); }
  roomJoin(s, m) {
    if (s.match && !s.match.ended) return s.send({ t: 'error', code: 'in_match' });
    const room = this.rooms.get(String(m.room || '').toUpperCase());
    if (!room) return s.send({ t: 'error', code: 'no_such_room' });
    const e = room.canJoin(s.uid, m.password);
    if (e) return s.send({ t: 'error', code: e });
    if (s.room && s.room !== room) this.roomLeave(s, 'left');
    if (this.mm.has(s.uid)) this.mmCancel(s);
    if (!room.members.has(s.uid)) { const e2 = room.addMember(s); if (e2) return s.send({ t: 'error', code: e2 }); }
    s.room = room; room.invited.delete(s.uid);
    this.roomBroadcast(room);
  }
  roomLeave(s, reason) {
    const room = s.room; if (!room) return;
    s.room = null;
    if (room.matchId && s.match && !s.match.ended) { /* stays in the match */ }
    room.removeMember(s.uid);
    if (!room.members.size) { this.rooms.delete(room.id); return; }
    this.roomBroadcast(room);
    s.send({ t: 'room_closed', reason });
  }
  roomReady(s, m) {
    const room = s.room; if (!room || room.matchId) return;
    const mem = room.members.get(s.uid); mem.ready = !!m.ready; this.roomBroadcast(room);
  }
  roomTeam(s, m) {
    const room = s.room; if (!room || room.matchId) return;
    const e = room.setTeam(s.uid, m.team); if (e) return s.send({ t: 'error', code: e });
    this.roomBroadcast(room);
  }
  roomConfig(s, m) {
    const room = s.room; if (!room || room.host !== s.uid || room.matchId) return s.send({ t: 'error', code: 'not_host' });
    const o = {}; for (const k of ['mode', 'teamSize', 'map', 'priv', 'password', 'maxPlayers', 'fillBots']) if (m[k] !== undefined) o[k] = m[k];
    if (o.password !== undefined && (typeof o.password !== 'string' || o.password.length > 16)) return s.send({ t: 'error', code: 'bad_password' });
    const e = room.configure(o); if (e) return s.send({ t: 'error', code: e });
    for (const mem of room.members.values()) mem.ready = false;
    this.roomBroadcast(room);
  }
  roomKick(s, m) {
    const room = s.room; if (!room || room.host !== s.uid) return s.send({ t: 'error', code: 'not_host' });
    const uid = String(m.uid); if (uid === s.uid || !room.members.has(uid)) return;
    const k = room.members.get(uid).session; k.room = null; room.removeMember(uid); k.send({ t: 'room_closed', reason: 'kicked' }); this.roomBroadcast(room);
  }
  roomStart(s) {
    const room = s.room; if (!room) return s.send({ t: 'error', code: 'not_in_room' });
    const e = room.canStart(s.uid); if (e) return s.send({ t: 'error', code: e });
    for (const m of room.members.values()) if (!m.session.online) return s.send({ t: 'error', code: 'member_offline' });
    const teams = new Map();
    for (const m of room.members.values()) { if (!teams.has(m.team)) teams.set(m.team, []); teams.get(m.team).push(m.session); }
    const fill = room.mode === 'br' ? (room.fillBots ? this.cfg.brMaxPlayers : 0) : (room.fillBots ? room.teamSize * 2 : 0);
    const arr = Array.from(teams.keys()).sort((a, b) => a - b).map((k) => teams.get(k));
    const match = this.createMatch({ mode: room.mode, teamSize: room.teamSize, teams: arr, cap: Math.max(fill, room.members.size), map: room.map, room });
    room.matchId = match.id;
    this.roomBroadcast(room);
  }

  // ------------------------------------------------------------------ matchmaking
  mmJoin(s, m) {
    if (s.match && !s.match.ended) return s.send({ t: 'error', code: 'in_match' });
    const mode = m.mode === 'cs' ? 'cs' : m.mode === 'br' ? 'br' : null;
    const teamSize = [1, 2, 4].includes(m.teamSize) ? m.teamSize : null;
    if (!mode || !teamSize) return s.send({ t: 'error', code: 'bad_mm' });
    let party = [s];
    if (s.room) {
      if (s.room.host !== s.uid) return s.send({ t: 'error', code: 'not_host' });
      party = Array.from(s.room.members.values()).map((x) => x.session).filter((x) => x.online);
      if (party.length > teamSize) return s.send({ t: 'error', code: 'party_too_big' });
    }
    this.mm.enqueue(party, mode, teamSize, Date.now());
    this.pushMmStatus();
  }
  mmCancel(s, quiet) {
    const tk = this.mm.cancel(s.uid);
    if (tk && !quiet) for (const x of tk.sessions) x.send({ t: 'mm_status', queued: false });
    else if (!tk && !quiet) s.send({ t: 'mm_status', queued: false });
  }
  pushMmStatus() { for (const { tk, msg } of this.mm.status(Date.now())) for (const s of tk.sessions) s.send(msg); }
  startQueuedMatch(r) { this.createMatch(r); }

  // ------------------------------------------------------------------ match lifecycle
  createMatch({ mode, teamSize, teams, cap, map, room }) {
    const id = 'M' + (++this.matchSeq).toString(36).toUpperCase() + U.randomToken().slice(0, 4).toUpperCase();
    const match = new Match({ id, mode, teamSize, maxPlayers: cap, map, terrain: this.terrain, onEnd: (m) => this.onMatchEnd(m, room) }, this.cfg);
    let teamId = 0;
    const real = [];
    for (const members of teams) { for (const s of members) { const p = match.addPlayer({ id: s.uid, name: s.name, team: teamId, session: s }); s.match = match; s.player = p; if (s.room && s.room !== room) { /* leave stale room */ } real.push(s); } teamId++; }
    // bots only fill empty seats and are always flagged
    const n = Math.max(0, cap - real.length);
    const names = BOT_NAMES.slice().sort(() => Math.random() - 0.5);
    let bi = 0;
    // complete partially filled real teams? No: bots form their own teams, so a real player is never paired with a bot "friend".
    let slots = n, inTeam = 0;
    while (slots > 0) {
      const b = match.addPlayer({ id: 'bot_' + (++bi), name: '[BOT] ' + names[(bi - 1) % names.length], team: teamId, isBot: true });
      slots--; inTeam++;
      if (inTeam >= teamSize) { teamId++; inTeam = 0; }
    }
    match.finalizeRoster();
    this.matches.set(id, match);
    for (const s of real) { s.send({ t: 'mm_status', queued: false }); s.send(match.startMsgFor(s.player, false)); }
    this.log('match', id, mode, 'real', real.length, 'bots', n);
    return match;
  }
  onMatchEnd(match, room) {
    // Match rewards are derived only from server-computed results and are idempotent by match ID.
    try {
      const rows = match.results && Array.isArray(match.results.results) ? match.results.results : [];
      for (const row of rows) {
        if (!row || row.isBot) continue;
        const player = match.players.get(row.id);
        const session = player && player.session;
        if (!session || !session.uid) continue;
        this.economy.recordMatch(session.uid, match.id, row);
        session.send(this.economy.snapshot(session.uid));
      }
    } catch (e) { this.log('economy match reward error', e && e.stack); }
    const t = setTimeout(() => {
      this.matches.delete(match.id);
      for (const p of match.players.values()) if (p.session && p.session.match === match) { p.session.match = null; p.session.player = null; }
      if (room && this.rooms.get(room.id) === room) { room.matchId = null; for (const m of room.members.values()) m.ready = false; this.roomBroadcast(room); }
    }, 2000);
    if (t.unref) t.unref();
  }
  tickAll() {
    const t0 = process.hrtime.bigint();
    const now = Date.now();
    for (const m of this.matches.values()) { try { m.update(now); } catch (e) { this.log('match error', e && e.stack); m.ended = true; } }
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    this.stats.ticks++; this.stats.tickMs += ms; if (ms > this.stats.tickMax) this.stats.tickMax = ms;
  }
  matchMsg(s, m) {
    const mt = s.match, p = s.player;
    if (!mt || !p || mt.ended) return;
    switch (m.t) {
      case 'input': { mt.now = Date.now(); return void mt.onInput(p, m); }
      case 'jump': return mt.onJump(p);
      case 'fire': {
        mt.now = Date.now();
        const r = mt.onFire(p, m);
        if (r) s.send({ t: 'ammo', wp: p.cur, mag: p.mag[p.cur] || 0, reserve: p.reserve[W.DEFS[p.cur].ammo], reloading: false });
        return;
      }
      case 'reload': mt.now = Date.now(); return mt.onReload(p);
      case 'switch': return mt.onSwitch(p, m.wp);
      case 'pickup': return mt.pickup(p, m.lootId);
      case 'drop': return mt.onDrop(p);
      case 'heal': mt.now = Date.now(); return mt.onHeal(p);
      case 'melee': mt.now = Date.now(); return mt.onMelee(p, m);
      case 'revive': mt.now = Date.now(); return mt.onRevive(p, String(m.uid), !!m.start);
      case 'marker': return mt.onMarker(p, m);
      case 'voice': return mt.onVoice(p, m);
      default: return;
    }
  }
}

if (require.main === module) {
  const srv = new GameServer();
  srv.listen().catch((e) => { console.error('listen failed', e.message); process.exit(1); });
  const stop = () => { srv.close().then(() => process.exit(0)); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
  setInterval(() => { const s = srv.stats; if (s.ticks) { srv.log('stats', 'sessions', srv.sessions.size, 'rooms', srv.rooms.size, 'matches', srv.matches.size, 'tick avg ms', (s.tickMs / s.ticks).toFixed(2), 'max', s.tickMax.toFixed(2)); s.tickMs = 0; s.ticks = 0; s.tickMax = 0; } }, 60000).unref();
}
module.exports = { GameServer, Session };
