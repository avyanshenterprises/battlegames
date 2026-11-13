'use strict';
const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { GameServer } = require('../src/server');
const loadConfig = require('../src/config');
const { Match } = require('../src/match');

const FAST = { port: 0, httpPort: 0, host: '127.0.0.1', log: false, dataDir: null, tickRate: 20, snapRate: 20, brFillSeconds: 0.3, csWaitSeconds: 0.3,
  planeSpeed: 20000, fallSeconds: 0, zoneTimeScale: 0.02, mmIntervalMs: 50, graceSeconds: 1, lootPerPlayer: 2, allowLegacyAuth: true };
async function startServer(extra) {
  const opts = Object.assign({}, FAST, extra || {});
  const testDir = opts.dataDir || fs.mkdtempSync(path.join(os.tmpdir(), 'avyansh-server-test-'));
  opts.dataDir = testDir;
  const s = new GameServer(opts);
  const close = s.close.bind(s);
  s.close = async function() { await close(); if (!extra || !extra.dataDir) fs.rmSync(testDir, { recursive: true, force: true }); };
  await s.listen();
  return s;
}

class Client {
  constructor(port, uid, name) { this.port = port; this.uid = uid; this.name = name || uid; this.msgs = []; this.waiters = []; this.token = null; this.buf = ''; }
  connect(token) {
    return new Promise((res, rej) => {
      this.sock = net.connect(this.port, '127.0.0.1', () => { this.send({ t: 'hello', uid: this.uid, name: this.name, token: token || this.token || undefined, v: 1 }); });
      this.sock.setEncoding('utf8'); this.sock.setNoDelay(true);
      this.sock.on('data', (d) => {
        this.buf += d; let i;
        while ((i = this.buf.indexOf('\n')) >= 0) { const l = this.buf.slice(0, i); this.buf = this.buf.slice(i + 1); if (l) this.onMsg(JSON.parse(l)); }
      });
      this.sock.on('error', rej);
      this.sock.on('close', () => { this.closed = true; });
      this.once('welcome').then((m) => { this.token = m.token; res(m); }, rej);
      this.once('error', 1500).then((m) => rej(new Error(m.code)), () => {});
    });
  }
  onMsg(m) {
    this.msgs.push(m);
    for (const w of this.waiters.slice()) if (w.pred(m)) { this.waiters.splice(this.waiters.indexOf(w), 1); clearTimeout(w.timer); w.res(m); }
  }
  send(m) { this.sock.write(JSON.stringify(m) + '\n'); }
  /** wait for the next message (or an already-received unread one) of type t matching optional predicate */
  once(t, ms, pred) {
    const test = (m) => m.t === t && (!pred || pred(m));
    const found = this.msgs.findIndex((m) => test(m));
    if (found >= 0) { return Promise.resolve(this.msgs.splice(found, 1)[0]); }
    return new Promise((res, rej) => {
      const w = { pred: (m) => { if (test(m)) { const i = this.msgs.indexOf(m); if (i >= 0) this.msgs.splice(i, 1); return true; } return false; }, res };
      w.timer = setTimeout(() => { this.waiters.splice(this.waiters.indexOf(w), 1); rej(new Error('timeout waiting for ' + t + ' (have: ' + this.msgs.map((x) => x.t).join(',') + ')')); }, ms || 3000);
      this.waiters.push(w);
    });
  }
  close() { try { this.sock.destroy(); } catch (e) {} }
}
async function connectN(srv, names) { const out = []; for (const n of names) { const c = new Client(srv.port, n); await c.connect(); out.push(c); } return out; }

/** Build a Match directly (no sockets) with fake sessions that record messages. */
function mkMatch(teams, over) {
  const cfg = loadConfig(Object.assign({}, FAST, over || {}));
  const m = new Match({ id: 'T1', mode: 'br', teamSize: 1, maxPlayers: 10, seed: 7 }, cfg);
  const sess = {};
  let team = 0;
  for (const members of teams) { for (const id of members) { const s = { msgs: [], send(x) { this.msgs.push(x); } }; sess[id] = s; m.addPlayer({ id, name: id, team, session: s }); } team++; }
  m.finalizeRoster();
  m.spreadRand = () => 0.5;   // deterministic aim in unit tests (no spread)
  m.loot.clear();
  m.phase = 'play';
  for (const p of m.players.values()) { p.state = 'alive'; p.st = 0; p.jumped = true; p.y = m.terrain.heightAt(p.x, p.z); }
  m.zone.phase = 0; m.zone.cx = 0; m.zone.cz = 0; m.zone.r = 5000; m.zone.nr = 5000; m.zone.shrinkStart = 1e9; m.zone.shrinkEnd = 1e9; m.zone.dps = 5;
  m.zoneStartAt = 1e9;
  return { m, sess };
}
function place(m, id, x, z) { const p = m.players.get(id); p.x = x; p.z = z; p.y = m.terrain.heightAt(x, z); p.history = [{ t: m.now, x: p.x, y: p.y, z: p.z, st: p.st }]; return p; }
function give(p, wp, mag) { const W = require('../src/weapons'); p.weapons[0] = wp; p.cur = wp; p.mag[wp] = mag == null ? W.DEFS[wp].mag : mag; p.reserve[W.DEFS[wp].ammo] = 90; }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
module.exports = { startServer, Client, connectN, mkMatch, place, give, sleep, FAST };
