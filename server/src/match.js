'use strict';
const W = require('./weapons');
const U = require('./util');
const { Terrain, PLAY_RADIUS } = require('./terrain');

const WORLD_SEED = 20261104; // must equal GameSettings.WORLD_SEED on the client
const ZONE_RADII = [900, 550, 300, 140, 60, 0];
const ZONE_WAIT = [90, 60, 50, 40, 30, 20];
const ZONE_SHRINK = [60, 45, 35, 30, 25, 20];
const ZONE_DPS = [1, 2, 4, 7, 10, 15];
const HISTORY_MS = 1000, MAX_REWIND_MS = 250;
const HEIGHTS = [1.8, 1.2, 0.6, 1.0, 1.8, 1.8]; // by stance index (stand,crouch,prone,swim,fall,parachute)
const MAX_SPEED = [9.5, 5.5, 2.8, 4.5, 75, 30];
const BOT_NAMES = ['Rohit', 'Aarav', 'Kabir', 'Vihaan', 'Ishaan', 'Zoya', 'Meera', 'Arjun', 'Dev', 'Tara', 'Neel', 'Riya', 'Sahil', 'Kavya', 'Yash', 'Anaya', 'Veer', 'Diya', 'Karan', 'Naina', 'Ravi', 'Sana', 'Om', 'Pari', 'Jai', 'Nisha', 'Lakshya', 'Mira', 'Rudra', 'Esha'];

/** One running match. Pure simulation: it talks to the outside world only through `send(uid,msg)` callbacks. */
class Match {
  constructor(opts, cfg) {
    this.cfg = cfg;
    this.id = opts.id;
    this.mode = opts.mode;
    this.map = opts.map || 'island';
    this.teamSize = opts.teamSize;
    this.maxPlayers = opts.maxPlayers;
    this.seed = opts.seed != null ? opts.seed : (Math.random() * 2 ** 31) | 0;
    this.rng = U.mulberry32(this.seed);
    this.terrain = opts.terrain || new Terrain(WORLD_SEED);
    this.onEnd = opts.onEnd || (() => {});
    this.players = new Map();       // id -> P
    this.loot = new Map();          // id -> item
    this.nextLoot = 1;
    this.tick = 0;
    this.t0 = Date.now();
    this.now = this.t0;
    this.phase = 'plane';           // plane -> play -> ended
    this.ended = false;
    this.placementNext = 0;         // assigned from the back: first dead = last place
    this.markerSeq = 0;
    this.killLog = [];
    this.forcedClock = null;
    const half = cfg.mapHalf;
    // flight line: random angle across the island centre
    const ang = this.rng() * Math.PI * 2;
    const dx = Math.cos(ang), dz = Math.sin(ang);
    const off = (this.rng() - 0.5) * 500;
    const len = PLAY_RADIUS * 2 + 200;
    this.plane = { sx: -dx * len / 2 - dz * off, sz: -dz * len / 2 + dx * off, dx, dz, speed: cfg.planeSpeed, alt: 300, len };
    this.planeEnd = this.plane.len / this.plane.speed;
    // zone
    this.zone = { phase: -1, cx: 0, cz: 0, r: PLAY_RADIUS, nx: 0, nz: 0, nr: PLAY_RADIUS, shrinkStart: 0, shrinkEnd: 0, dps: 0, fromCx: 0, fromCz: 0, fromR: PLAY_RADIUS, waitEnd: 0 };
    this.zoneStartAt = (this.planeEnd + cfg.fallSeconds) / Math.max(0.01, 1);
    this.zoneDirty = true;
  }

  get sec() { return (this.now - this.t0) / 1000; }

  // ------------------------------------------------------------------ roster
  /** entries: {id,name,team,isBot,session?} */
  addPlayer(e) {
    const p = {
      id: e.id, name: e.name, team: e.team, slot: 0, isBot: !!e.isBot, session: e.session || null,
      x: 0, y: this.plane.alt, z: 0, yaw: 0, pitch: 0, vx: 0, vz: 0, st: 5, fl: 0,
      hp: 100, knockHp: 0, armor: 0, helmet: 0, state: 'plane', // plane|alive|knocked|dead
      weapons: [-1, -1, -1], cur: W.MELEE, mag: {}, reserve: [0, 0, 0, 0, 0, 0], heals: 0,
      kills: 0, damage: 0, lastFire: 0, reloadEnd: 0, reloadWp: -1, healEnd: 0, meleeAt: 0,
      reviving: null, revivedBy: null, reviveEnd: 0,
      history: [], lastIn: 0, lastSeq: 0, corrections: 0, placement: 0, diedAt: 0, connected: true, discAt: 0,
      jumped: false, ai: null, zoneAcc: 0
    };
    this.players.set(p.id, p);
    return p;
  }
  finalizeRoster() {
    // slots per team in order
    const bySlot = {};
    for (const p of this.players.values()) { bySlot[p.team] = (bySlot[p.team] || 0); p.slot = ++bySlot[p.team]; }
    this.placementNext = this.players.size;
    const half = 0.5 * this.plane.len;
    // everyone starts on the plane at its beginning
    for (const p of this.players.values()) { p.x = this.plane.sx; p.z = this.plane.sz; p.y = this.plane.alt; }
    this.spawnLoot();
    for (const p of this.players.values()) if (p.isBot) this.initBot(p);
    this.zoneTimeline();
  }
  teamsAlive() {
    const s = new Set();
    for (const p of this.players.values()) if (p.state !== 'dead') s.add(p.team);
    return s.size;
  }
  aliveCount() { let n = 0; for (const p of this.players.values()) if (p.state !== 'dead') n++; return n; }
  counts() {
    let real = 0, bots = 0;
    const teams = new Set();
    for (const p of this.players.values()) { if (p.isBot) bots++; else real++; teams.add(p.team); }
    return { players: real + bots, realPlayers: real, bots, teams: teams.size };
  }
  rosterMsg() {
    return Array.from(this.players.values()).map((p) => ({ id: p.id, name: p.name, team: p.team, slot: p.slot, isBot: p.isBot }));
  }
  startMsgFor(p, resume) {
    const o = { t: 'match_start', matchId: this.id, mode: this.mode, map: this.map, seed: this.seed, worldSeed: WORLD_SEED,
      tickRate: this.cfg.tickRate, snapRate: this.cfg.snapRate, resume: !!resume,
      you: { id: p.id, team: p.team, slot: p.slot, x: p.x, y: p.y, z: p.z },
      players: this.rosterMsg(), zone: this.zoneMsg(), counts: this.counts(),
      plane: { sx: this.plane.sx, sz: this.plane.sz, dx: this.plane.dx, dz: this.plane.dz, speed: this.plane.speed, alt: this.plane.alt, len: this.plane.len },
      serverTime: this.now, loot: Array.from(this.loot.values()).map(lootMsg) };
    if (resume) { o.state = this.fullState(p); }
    return o;
  }
  fullState(p) { return { hp: p.hp, armor: p.armor, helmet: p.helmet, st: p.state, inv: this.inventoryOf(p), phase: this.phase }; }

  // ------------------------------------------------------------------ loot
  spawnLoot() {
    const n = Math.floor(this.players.size * this.cfg.lootPerPlayer) + 40;
    let tries = 0;
    while (this.loot.size < n && tries++ < n * 20) {
      const a = this.rng() * Math.PI * 2, r = Math.sqrt(this.rng()) * (PLAY_RADIUS - 30);
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      const y = this.terrain.heightAt(x, z);
      if (y < 0.4) continue; // no loot in water
      // villages/cities have more loot: accept extra spawn near sites by bias already via retry density
      this.addLoot(this.randomLootKind(), x, y + 0.3, z);
    }
  }
  randomLootKind() {
    const r = this.rng();
    if (r < 0.32) return { kind: 'gun', type: W.randomGun(this.rng) };
    if (r < 0.62) return { kind: 'ammo', type: Math.floor(this.rng() * W.AMMO_TYPES) };
    if (r < 0.78) return { kind: 'heal', type: 0 };
    if (r < 0.90) return { kind: 'armor', type: 1 + Math.floor(this.rng() * 3) };
    return { kind: 'helmet', type: 1 + Math.floor(this.rng() * 3) };
  }
  addLoot(k, x, y, z, extra) {
    const id = this.nextLoot++;
    const it = Object.assign({ id, kind: k.kind, type: k.type, x, y, z }, extra || {});
    this.loot.set(id, it);
    return it;
  }
  inventoryOf(p) {
    return { weapons: p.weapons.slice(), cur: p.cur, mags: Object.assign({}, p.mag), ammo: p.reserve.slice(), heals: p.heals, armor: p.armor, helmet: p.helmet };
  }
  sendInv(p) { this.to(p, { t: 'inventory', inv: this.inventoryOf(p) }); }
  pickup(p, lootId) {
    if (p.state !== 'alive') return;
    const it = this.loot.get(lootId);
    if (!it) return;
    if (Math.hypot(it.x - p.x, it.z - p.z) > 3.5 || Math.abs(it.y - p.y) > 4) return;
    switch (it.kind) {
      case 'gun': {
        const def = W.DEFS[it.type];
        if (p.weapons.includes(it.type)) { p.reserve[def.ammo] += def.mag; break; }
        let slot = p.weapons.indexOf(-1);
        if (slot < 0) { // replace the held gun, drop it
          slot = p.weapons.indexOf(p.cur) >= 0 ? p.weapons.indexOf(p.cur) : 0;
          this.dropWeapon(p, p.weapons[slot]);
        }
        p.weapons[slot] = it.type;
        p.mag[it.type] = it.mag != null ? it.mag : def.mag;
        if (p.cur === W.MELEE || p.cur === -1) p.cur = it.type;
        break;
      }
      case 'ammo': p.reserve[it.type] = Math.min(300, p.reserve[it.type] + W.AMMO_BOX[it.type]); break;
      case 'heal': if (p.heals >= 5) return; p.heals++; break;
      case 'armor': if (it.type <= p.armor) return; p.armor = it.type; break;
      case 'helmet': if (it.type <= p.helmet) return; p.helmet = it.type; break;
      default: return;
    }
    this.loot.delete(lootId);
    this.broadcast({ t: 'loot_taken', lootId, by: p.id });
    this.sendInv(p);
  }
  /** Player drops the equipped gun (it becomes loot with its remaining magazine). */
  onDrop(p) {
    if (p.state !== 'alive') return;
    const wp = p.cur;
    if (wp < 0 || wp >= W.COUNT) return;
    const slot = p.weapons.indexOf(wp);
    if (slot < 0) return;
    p.weapons[slot] = -1;
    this.dropWeapon(p, wp);
    const next = p.weapons.find((w) => w >= 0);
    p.cur = next !== undefined ? next : W.MELEE;
    p.reloadEnd = 0;
    this.sendInv(p);
  }
  dropWeapon(p, wp) {
    if (wp < 0 || wp >= W.COUNT) return;
    this.addLoot({ kind: 'gun', type: wp }, p.x + (this.rng() - 0.5), p.y + 0.3, p.z + (this.rng() - 0.5), { mag: p.mag[wp] || 0 });
    delete p.mag[wp];
    this.broadcast({ t: 'loot_add', loot: lootMsg(Array.from(this.loot.values()).pop()) });
  }

  // ------------------------------------------------------------------ zone
  zoneTimeline() {
    this.zone.phase = -1; this.zone.waitEnd = this.zoneStartAt;
  }
  advanceZone() {
    const z = this.zone, s = this.sec, k = this.cfg.zoneTimeScale;
    if (z.phase === -1 && s >= this.zoneStartAt) { this.beginPhase(0, s); return; }
    if (z.phase >= 0 && s >= z.shrinkEnd) {
      z.cx = z.nx; z.cz = z.nz; z.r = z.nr;
      if (z.phase + 1 < ZONE_RADII.length) this.beginPhase(z.phase + 1, s);
    } else if (z.phase >= 0 && s >= z.shrinkStart) {
      const f = (s - z.shrinkStart) / Math.max(0.001, z.shrinkEnd - z.shrinkStart);
      z.cx = z.fromCx + (z.nx - z.fromCx) * f; z.cz = z.fromCz + (z.nz - z.fromCz) * f; z.r = z.fromR + (z.nr - z.fromR) * f;
    }
  }
  beginPhase(i, s) {
    const z = this.zone, k = this.cfg.zoneTimeScale;
    z.phase = i; z.fromCx = z.cx; z.fromCz = z.cz; z.fromR = z.r;
    z.nr = ZONE_RADII[i];
    const maxOff = Math.max(0, z.r - z.nr) * 0.85;
    const a = this.rng() * Math.PI * 2, d = Math.sqrt(this.rng()) * maxOff;
    z.nx = z.cx + Math.cos(a) * d; z.nz = z.cz + Math.sin(a) * d;
    z.shrinkStart = s + ZONE_WAIT[i] * k; z.shrinkEnd = z.shrinkStart + ZONE_SHRINK[i] * k; z.dps = ZONE_DPS[i];
    this.zoneDirty = true;
  }
  zoneMsg() {
    const z = this.zone;
    return { phase: z.phase, cx: z.cx, cz: z.cz, r: z.r, nx: z.nx, nz: z.nz, nr: z.nr, tShrinkStart: z.shrinkStart, tShrinkEnd: z.shrinkEnd, dps: z.phase < 0 ? 0 : z.dps, now: this.sec };
  }
  outsideZone(p) { return Math.hypot(p.x - this.zone.cx, p.z - this.zone.cz) > this.zone.r; }

  // ------------------------------------------------------------------ io
  to(p, msg) { if (p.session && p.connected && p.session.send) p.session.send(msg); }
  broadcast(msg, except) { for (const p of this.players.values()) if (!p.isBot && p !== except) this.to(p, msg); }
  toTeam(team, msg, except) { for (const p of this.players.values()) if (p.team === team && !p.isBot && p !== except) this.to(p, msg); }

  // ------------------------------------------------------------------ input
  /** Validate client-reported kinematics. Returns true when accepted, false (and sends `correct`) when rejected. */
  onInput(p, m) {
    if (this.ended || p.state === 'dead' || p.state === 'plane') return false;
    const seq = U.int(m.seq, 0, 2 ** 31) ; if (seq == null || seq <= p.lastSeq) return false;
    const x = U.num(m.x, -4000, 4000), y = U.num(m.y, -200, 1000), z = U.num(m.z, -4000, 4000);
    if (x == null || y == null || z == null) return false;
    const st = U.int(m.st, 0, 5) == null ? 0 : m.st;
    p.lastSeq = seq;
    const nowMs = this.now;
    const dt = Math.min(1, Math.max(0.02, (nowMs - (p.lastIn || nowMs - 50)) / 1000));
    p.lastIn = nowMs;
    if (p.state === 'knocked') { // knocked players only crawl
      const d = Math.hypot(x - p.x, z - p.z);
      if (d > 2 * dt + 1.0) return this.correct(p, seq);
    } else {
      const lim = MAX_SPEED[st] * 1.3 * dt + 1.2;
      const d = Math.hypot(x - p.x, z - p.z);
      if (d > lim) return this.correct(p, seq);
      const dy = y - p.y;
      const vlim = (st >= 4 ? 90 : 22) * dt + 2.5;
      if (Math.abs(dy) > vlim) return this.correct(p, seq);
      const lim2 = this.cfg.mapHalf;
      if (Math.abs(x) > lim2 || Math.abs(z) > lim2) return this.correct(p, seq);
    }
    // phase gating: no falling states once landed? (client decides) - but altitude plausibility vs terrain is checked
    if (st < 4 && y > this.terrain.heightAt(x, z) + 60) return this.correct(p, seq);
    p.x = x; p.y = y; p.z = z;
    p.yaw = U.num(m.yaw, -720, 720) == null ? p.yaw : m.yaw;
    p.pitch = U.num(m.pitch, -90, 90) == null ? p.pitch : m.pitch;
    p.vx = U.num(m.vx, -100, 100) || 0; p.vz = U.num(m.vz, -100, 100) || 0;
    p.st = st; p.fl = U.int(m.fl, 0, 255) || 0;
    if (p.healEnd && Math.hypot(p.vx, p.vz) > 4) { p.healEnd = 0; this.to(p, { t: 'healing', cancel: true }); }
    if (p.reviving && Math.hypot(p.vx, p.vz) > 2.5) this.cancelRevive(p);
    return true;
  }
  correct(p, seq) {
    p.corrections++;
    this.to(p, { t: 'correct', seq, x: p.x, y: p.y, z: p.z });
    return false;
  }
  onJump(p) {
    if (p.state !== 'plane') return;
    const t = this.sec;
    const s = Math.min(this.plane.len, t * this.plane.speed);
    p.state = 'alive'; p.jumped = true; p.st = 4; p.lastIn = this.now;
    p.x = this.plane.sx + this.plane.dx * s; p.z = this.plane.sz + this.plane.dz * s; p.y = this.plane.alt;
    p.lastSeq = 0;
    this.to(p, { t: 'correct', seq: 0, x: p.x, y: p.y, z: p.z });
  }
  onSwitch(p, wp) {
    if (p.state !== 'alive') return;
    if (wp !== W.MELEE && !p.weapons.includes(wp)) return;
    p.cur = wp; p.reloadEnd = 0; p.healEnd = 0;
    p.lastFire = Math.max(p.lastFire, this.now - 100) + 100 - 100;
  }
  onReload(p) {
    if (p.state !== 'alive' || p.cur === W.MELEE || p.reloadEnd) return;
    const d = W.DEFS[p.cur];
    if ((p.mag[p.cur] || 0) >= d.mag || p.reserve[d.ammo] <= 0) return;
    p.reloadEnd = this.now + d.reload * 1000; p.reloadWp = p.cur;
    this.to(p, { t: 'ammo', wp: p.cur, mag: p.mag[p.cur] || 0, reserve: p.reserve[d.ammo], reloading: true });
  }
  finishReload(p) {
    const d = W.DEFS[p.reloadWp];
    p.reloadEnd = 0;
    if (!d || p.cur !== p.reloadWp) return;
    const need = d.mag - (p.mag[p.cur] || 0), take = Math.min(need, p.reserve[d.ammo]);
    p.mag[p.cur] = (p.mag[p.cur] || 0) + take; p.reserve[d.ammo] -= take;
    this.to(p, { t: 'ammo', wp: p.cur, mag: p.mag[p.cur], reserve: p.reserve[d.ammo], reloading: false });
  }
  onHeal(p) {
    if (p.state !== 'alive' || p.heals <= 0 || p.healEnd || p.hp >= 100) return;
    p.healEnd = this.now + 4500; this.to(p, { t: 'healing', t4: 4.5 });
  }
  onRevive(p, targetId, start) {
    if (!start) { this.cancelRevive(p); return; }
    const o = this.players.get(targetId);
    if (!o || o.team !== p.team || o.state !== 'knocked' || p.state !== 'alive') return;
    if (Math.hypot(o.x - p.x, o.z - p.z) > 3) return;
    p.reviving = o.id; p.reviveEnd = this.now + 5000; o.revivedBy = p.id;
    this.to(p, { t: 'revive_progress', target: o.id, secs: 5 }); this.to(o, { t: 'revive_progress', by: p.id, secs: 5 });
  }
  cancelRevive(p) {
    if (!p.reviving) return;
    const o = this.players.get(p.reviving); if (o) o.revivedBy = null;
    p.reviving = null; p.reviveEnd = 0;
    this.to(p, { t: 'revive_progress', cancel: true });
  }
  onMarker(p, m) {
    const x = U.num(m.x, -4000, 4000), z = U.num(m.z, -4000, 4000), k = U.int(m.kind, 0, 3);
    if (x == null || z == null || k == null || p.state === 'dead') return;
    this.toTeam(p.team, { t: 'marker', by: p.id, x, z, kind: k, id: ++this.markerSeq });
  }
  onVoice(p, m) {
    if (p.state === 'dead' || typeof m.data !== 'string' || m.data.length > 4096) return;
    this.toTeam(p.team, { t: 'voice', from: p.id, seq: m.seq | 0, data: m.data }, p);
  }
  onMelee(p, m) {
    if (p.state !== 'alive' || this.now - p.meleeAt < 580) return;
    p.meleeAt = this.now;
    const d = W.DEFS[W.MELEE];
    let best = null, bd = 2.3;
    for (const o of this.players.values()) {
      if (o === p || o.state === 'dead' || o.team === p.team) continue;
      const dist = Math.hypot(o.x - p.x, o.z - p.z);
      if (dist < bd && Math.abs(o.y - p.y) < 2.5) { bd = dist; best = o; }
    }
    if (best) this.applyDamage(p, best, d.damage, false, W.MELEE);
  }

  // ------------------------------------------------------------------ combat
  posAt(o, t) {
    const h = o.history;
    if (!h.length) return { x: o.x, y: o.y, z: o.z, st: o.st };
    if (t >= h[h.length - 1].t) return h[h.length - 1];
    if (t <= h[0].t) return h[0];
    for (let i = h.length - 1; i > 0; i--) {
      if (h[i - 1].t <= t) {
        const a = h[i - 1], b = h[i], f = (t - a.t) / Math.max(1, b.t - a.t);
        return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f, z: a.z + (b.z - a.z) * f, st: b.st };
      }
    }
    return h[0];
  }
  /** Lag-compensated hitscan. */
  onFire(p, m) {
    if (this.ended || p.state !== 'alive') return null;
    const wp = U.int(m.wp, 0, W.COUNT - 1);
    if (wp == null || wp !== p.cur || !p.weapons.includes(wp)) return null;
    const d = W.DEFS[wp];
    if (p.reloadEnd) return null;
    if (this.now - p.lastFire < d.interval * 1000 * 0.9) return null;
    if ((p.mag[wp] || 0) <= 0) return null;
    const ox = U.num(m.ox, -4000, 4000), oy = U.num(m.oy, -200, 1000), oz = U.num(m.oz, -4000, 4000);
    let dx = U.num(m.dx, -1, 1), dy = U.num(m.dy, -1, 1), dz = U.num(m.dz, -1, 1);
    if ([ox, oy, oz, dx, dy, dz].some((v) => v == null)) return null;
    if (Math.hypot(ox - p.x, oz - p.z) > 2.5 || Math.abs(oy - (p.y + 1.6)) > 3.2) return null; // origin must be at the shooter
    const len = Math.hypot(dx, dy, dz);
    if (len < 0.5 || len > 1.5) return null;
    dx /= len; dy /= len; dz /= len;
    p.lastFire = this.now; p.mag[wp]--; p.healEnd = 0;
    this.broadcastShot(p, wp);
    const ct = U.num(m.ct, 0, 1e15);
    let t = this.now - MAX_REWIND_MS;
    if (ct != null) t = Math.min(this.now, Math.max(this.now - MAX_REWIND_MS, ct));
    else t = this.now;
    const results = [];
    for (let k = 0; k < d.pellets; k++) {
      let rx = dx, ry = dy, rz = dz;
      {   // server-side spread for every weapon (clients cannot send perfectly accurate shots); ADS tightens it
        const sp = d.spread * ((p.fl & 2) ? 0.35 : 1);
        const sr = this.spreadRand || Math.random;
        rx += (sr() - 0.5) * 2 * sp; ry += (sr() - 0.5) * 2 * sp; rz += (sr() - 0.5) * 2 * sp;
        const l = Math.hypot(rx, ry, rz); rx /= l; ry /= l; rz /= l;
      }
      const hit = this.traceRay(p, ox, oy, oz, rx, ry, rz, d.range, t);
      if (hit) results.push(hit);
    }
    // merge pellet hits by victim
    const per = new Map();
    for (const h of results) {
      const dist = h.dist, f = dist <= d.range * 0.6 ? 1 : 1 - 0.3 * Math.min(1, (dist - d.range * 0.6) / (d.range * 0.4));
      const dmg = d.damage * f * (h.head ? 2.0 : 1);
      const cur = per.get(h.victim) || { dmg: 0, head: false };
      cur.dmg += dmg; cur.head = cur.head || h.head; per.set(h.victim, cur);
    }
    for (const [victim, r] of per) this.applyDamage(p, victim, r.dmg, r.head, wp);
    return { hits: per.size };
  }
  traceRay(shooter, ox, oy, oz, dx, dy, dz, range, t) {
    let best = null;
    for (const o of this.players.values()) {
      if (o === shooter || o.state === 'dead' || o.team === shooter.team) continue;
      const pos = this.posAt(o, t);
      const h = HEIGHTS[pos.st] != null ? HEIGHTS[pos.st] : 1.8;
      const tt = U.rayCapsuleV(ox, oy, oz, dx, dy, dz, pos.x, pos.z, pos.y + 0.4, pos.y + h - 0.4, 0.4);
      if (tt < 0 || tt > range) continue;
      if (!best || tt < best.dist) {
        if (this.terrainBlocks(ox, oy, oz, dx, dy, dz, tt)) continue;   // hills stop bullets (buildings are client-side only)
        const hy = oy + dy * tt;
        best = { victim: o, dist: tt, head: hy > pos.y + h * 0.82 };
      }
    }
    return best;
  }
  /** true when the ground rises above the ray before it travels `dist` metres */
  terrainBlocks(ox, oy, oz, dx, dy, dz, dist) {
    for (let t = 2; t < dist - 1; t += 2) {
      if (this.terrain.heightAt(ox + dx * t, oz + dz * t) > oy + dy * t + 0.2) return true;
    }
    return false;
  }
  broadcastShot(p, wp) {
    for (const o of this.players.values()) {
      if (o.isBot || o === p || !o.connected) continue;
      if (Math.hypot(o.x - p.x, o.z - p.z) <= 250) this.to(o, { t: 'shot', id: p.id, x: p.x, y: p.y + 1.6, z: p.z, wp });
    }
    // bots "hear" shots as well
    for (const o of this.players.values()) if (o.isBot && o.state === 'alive' && o.team !== p.team && Math.hypot(o.x - p.x, o.z - p.z) < 120) { if (o.ai) { o.ai.heard = { x: p.x, z: p.z, t: this.now }; } }
  }
  applyDamage(attacker, victim, dmg, head, wp, zone) {
    if (victim.state === 'dead' || this.ended) return;
    if (!zone && attacker && attacker.team === victim.team && attacker !== victim) return; // no friendly fire
    // armour / helmet
    if (!zone) {
      if (head) dmg *= (1 - 0.2 * victim.helmet);
      else dmg *= (1 - 0.15 * victim.armor);
    }
    dmg = Math.round(dmg * 10) / 10;
    if (dmg <= 0) return;
    if (attacker) attacker.damage += dmg;
    if (victim.healEnd) { victim.healEnd = 0; this.to(victim, { t: 'healing', cancel: true }); }
    let dir = 0;
    if (attacker && attacker !== victim) dir = Math.round(((Math.atan2(attacker.x - victim.x, -(attacker.z - victim.z)) * 180 / Math.PI) + 360) % 360);
    if (victim.state === 'knocked') {
      victim.knockHp -= dmg;
      if (attacker) this.to(attacker, { t: 'hit', victim: victim.id, dmg, head, kill: victim.knockHp <= 0 });
      if (victim.knockHp <= 0) this.kill(attacker, victim, wp, head, zone);
      return;
    }
    victim.hp -= dmg;
    const dead = victim.hp <= 0;
    if (attacker && attacker !== victim) this.to(attacker, { t: 'hit', victim: victim.id, dmg, head, kill: dead && !this.willKnock(victim) });
    this.to(victim, { t: 'damaged', attacker: attacker ? attacker.id : null, dmg, dir, hp: Math.max(0, victim.hp) });
    if (dead) {
      if (this.willKnock(victim)) this.knock(attacker, victim, wp);
      else this.kill(attacker, victim, wp, head, zone);
    }
  }
  willKnock(v) {
    if (this.teamSize <= 1 || v.state !== 'alive') return false;
    for (const o of this.players.values()) if (o !== v && o.team === v.team && o.state === 'alive') return true;
    return false;
  }
  knock(attacker, v, wp) {
    v.state = 'knocked'; v.hp = 0; v.knockHp = 100; v.healEnd = 0; v.reloadEnd = 0; v.st = 2;
    if (v.reviving) this.cancelRevive(v);
    this.broadcast({ t: 'knock', victim: v.id, by: attacker ? attacker.id : null });
    this.feed(attacker, v, wp, false, true);
  }
  kill(attacker, v, wp, head, zone) {
    if (v.state === 'dead') return;
    const wasKnocked = v.state === 'knocked';
    v.state = 'dead'; v.hp = 0; v.diedAt = this.sec; v.healEnd = 0; v.reloadEnd = 0;
    v.placement = this.placementNext--; 
    if (v.reviving) this.cancelRevive(v);
    if (attacker && attacker !== v) attacker.kills++;
    // drop weapons as loot
    for (const w of v.weapons) if (w >= 0) this.dropWeapon(v, w);
    v.weapons = [-1, -1];
    this.broadcast({ t: 'kill', killer: attacker ? attacker.id : null, killerName: attacker ? attacker.name : (zone ? 'Blue Zone' : ''), victim: v.id, victimName: v.name, wp: wp == null ? -1 : wp, head: !!head, zone: !!zone, victimIsBot: v.isBot, killerIsBot: attacker ? attacker.isBot : false });
    this.knockSweep(v.team);
    this.checkEnd();
  }
  feed(attacker, v, wp, head, knock) { /* knocks are surfaced via `knock` message; kill feed entry only on elimination */ }
  /** when the last non-knocked teammate is gone the knocked ones die as well */
  knockSweep(team) {
    let anyAlive = false;
    for (const o of this.players.values()) if (o.team === team && o.state === 'alive') anyAlive = true;
    if (anyAlive) return;
    for (const o of this.players.values()) if (o.team === team && o.state === 'knocked') this.kill(null, o, W.MELEE, false, true);
  }

  // ------------------------------------------------------------------ bots
  initBot(b) {
    const gun = W.randomGun(this.rng, (d) => d.cat !== W.CAT.SNIPER);
    b.weapons[0] = gun; b.cur = gun; b.mag[gun] = W.DEFS[gun].mag; b.reserve[W.DEFS[gun].ammo] = W.DEFS[gun].mag * 3;
    b.ai = { tx: 0, tz: 0, retarget: 0, target: null, react: 0, heard: null, jumpAt: this.planeEnd * (0.15 + this.rng() * 0.7), acc: 0.55 + this.rng() * 0.3 };
  }
  botStep(b, dt) {
    const ai = b.ai;
    if (b.state === 'plane') { if (this.sec >= ai.jumpAt) this.onJump(b); return; }
    if (b.state !== 'alive') return;
    const ground = this.terrain.heightAt(b.x, b.z);
    if (b.y > ground + 1.2) { // free-fall then parachute towards the zone centre
      const dxz = Math.hypot(this.zone.nx - b.x, this.zone.nz - b.z) || 1;
      b.st = b.y > ground + 120 ? 4 : 5;
      const sp = b.st === 4 ? 45 : 12, vs = b.st === 4 ? 55 : 8;
      b.x += (this.zone.nx - b.x) / dxz * Math.min(sp, dxz / dt) * dt; b.z += (this.zone.nz - b.z) / dxz * Math.min(sp, dxz / dt) * dt;
      b.y = Math.max(ground, b.y - vs * dt);
      if (b.y <= ground + 0.05) { b.st = 0; this.loadHistory(b); }
      return;
    }
    b.st = 0; b.y = ground;
    const now = this.now;
    // loot nearby (cheap scan)
    if (!ai.lootCheck || now > ai.lootCheck) {
      ai.lootCheck = now + 700;
      let bestL = null, bd = 25;
      for (const it of this.loot.values()) { const d = Math.hypot(it.x - b.x, it.z - b.z); if (d < bd) { bd = d; bestL = it; } }
      if (bestL && (bestL.kind !== 'gun' || b.weapons.indexOf(-1) >= 0)) { ai.tx = bestL.x; ai.tz = bestL.z; ai.loot = bestL.id; }
      else ai.loot = 0;
    }
    if (ai.loot) { const it = this.loot.get(ai.loot); if (it && Math.hypot(it.x - b.x, it.z - b.z) < 2.5) this.pickup(b, ai.loot); if (!it) ai.loot = 0; }
    // threat
    let tgt = ai.target && this.players.get(ai.target);
    if (!tgt || tgt.state === 'dead' || Math.hypot(tgt.x - b.x, tgt.z - b.z) > 150) tgt = null;
    if (!ai.scan || now > ai.scan) {
      ai.scan = now + 400; let bd2 = (W.DEFS[b.cur] || W.DEFS[0]).range * 0.9;
      for (const o of this.players.values()) {
        if (o === b || o.state === 'dead' || o.team === b.team || o.state === 'plane') continue;
        const d = Math.hypot(o.x - b.x, o.z - b.z);
        if (d < bd2 && d < 110) { bd2 = d; if (tgt !== o) { tgt = o; ai.react = now + 350 + this.rng() * 500; } }
      }
      ai.target = tgt ? tgt.id : null;
    }
    let speed = 4.5;
    if (tgt) {
      const dxz = Math.hypot(tgt.x - b.x, tgt.z - b.z) || 1;
      b.yaw = Math.atan2(tgt.x - b.x, -(tgt.z - b.z)) * 180 / Math.PI;
      if (dxz > 30) { ai.tx = tgt.x; ai.tz = tgt.z; } else { ai.tx = b.x + (this.rng() - 0.5) * 6; ai.tz = b.z + (this.rng() - 0.5) * 6; speed = 2.5; }
      if (now >= ai.react) this.botShoot(b, tgt, dxz);
    } else if (ai.heard && now - ai.heard.t < 4000 && !ai.loot) { ai.tx = ai.heard.x; ai.tz = ai.heard.z; }
    else if (!ai.loot && (now > ai.retarget || Math.hypot(ai.tx - b.x, ai.tz - b.z) < 3)) {
      ai.retarget = now + 6000 + this.rng() * 8000;
      const inside = Math.hypot(b.x - this.zone.cx, b.z - this.zone.cz) < this.zone.r * 0.8;
      const cx = inside ? b.x : this.zone.nx, cz = inside ? b.z : this.zone.nz, R = inside ? 80 : this.zone.nr * 0.5;
      const a = this.rng() * Math.PI * 2, r2 = Math.sqrt(this.rng()) * R;
      ai.tx = cx + Math.cos(a) * r2; ai.tz = cz + Math.sin(a) * r2;
    }
    if (this.outsideZone(b) && !tgt) { ai.tx = this.zone.nx; ai.tz = this.zone.nz; speed = 6.5; }
    if (b.reloadEnd && this.now >= b.reloadEnd) this.finishReload(b);
    if (b.cur !== W.MELEE && (b.mag[b.cur] || 0) === 0) this.onReload(b);
    if (b.healEnd && this.now >= b.healEnd) this.doHealDone(b);
    if (b.hp < 55 && b.heals > 0 && !tgt) this.onHeal(b);
    // move (avoid water)
    const dxz = Math.hypot(ai.tx - b.x, ai.tz - b.z);
    if (dxz > 1.2 && !b.reloadEnd) {
      const nx = b.x + (ai.tx - b.x) / dxz * speed * dt, nz = b.z + (ai.tz - b.z) / dxz * speed * dt;
      if (this.terrain.heightAt(nx, nz) > 0.2) { if (!tgt) b.yaw = Math.atan2(ai.tx - b.x, -(ai.tz - b.z)) * 180 / Math.PI; b.x = nx; b.z = nz; b.vx = (nx - b.x) / dt; }
      else ai.retarget = 0;
    }
    b.y = this.terrain.heightAt(b.x, b.z);
  }
  loadHistory() {}
  botShoot(b, t, dist) {
    const d = W.DEFS[b.cur]; if (!d || b.cur === W.MELEE) return;
    if (b.reloadEnd || (b.mag[b.cur] || 0) <= 0 || this.now - b.lastFire < d.interval * 1000 * (d.auto ? 1.6 : 1.8)) return;
    // aim with human-like error, grows with distance
    const err = (1 - b.ai.acc) * 0.035 + dist * 0.00035;
    const ex = t.x + (this.rng() - 0.5) * 2 * err * dist, ez = t.z + (this.rng() - 0.5) * 2 * err * dist;
    const oy = b.y + 1.6, ty = t.y + 1.2 + (this.rng() - 0.5) * 0.4;
    let dx = ex - b.x, dy = ty - oy, dz = ez - b.z; const l = Math.hypot(dx, dy, dz) || 1;
    this.onFire(b, { wp: b.cur, ox: b.x, oy, oz: b.z, dx: dx / l, dy: dy / l, dz: dz / l, ct: this.now });
  }
  doHealDone(p) {
    p.healEnd = 0;
    if (p.heals > 0 && p.state === 'alive') { p.heals--; p.hp = Math.min(100, p.hp + 55); this.to(p, { t: 'healed', hp: p.hp }); this.sendInv(p); }
  }

  // ------------------------------------------------------------------ main loop
  update(nowMs) {
    if (this.ended) return;
    const prev = this.now;
    this.now = nowMs;
    const dt = Math.min(0.25, (this.now - prev) / 1000) || 1 / this.cfg.tickRate;
    this.tick++;
    const sec = this.sec;
    // plane phase
    if (this.phase === 'plane') {
      const s = Math.min(this.plane.len, sec * this.plane.speed);
      for (const p of this.players.values()) if (p.state === 'plane') { p.x = this.plane.sx + this.plane.dx * s; p.z = this.plane.sz + this.plane.dz * s; p.y = this.plane.alt; }
      if (sec >= this.planeEnd) { for (const p of this.players.values()) if (p.state === 'plane') this.onJump(p); this.phase = 'play'; }
    }
    this.advanceZone();
    for (const p of this.players.values()) {
      if (p.state === 'dead') continue;
      if (p.isBot) this.botStep(p, dt);
      else {
        if (p.reloadEnd && this.now >= p.reloadEnd) this.finishReload(p);
        if (p.healEnd && this.now >= p.healEnd) this.doHealDone(p);
      }
      if (p.reviving) {
        const o = this.players.get(p.reviving);
        if (!o || o.state !== 'knocked') this.cancelRevive(p);
        else if (this.now >= p.reviveEnd) {
          o.state = 'alive'; o.hp = 30; o.knockHp = 0; o.st = 0; o.revivedBy = null; p.reviving = null;
          this.broadcast({ t: 'revived', uid: o.id, by: p.id });
        }
      }
      if (p.state === 'knocked') { // bleed out
        p.knockHp -= dt * 2;
        if (p.knockHp <= 0 && !p.revivedBy) { this.kill(null, p, W.MELEE, false, true); continue; }
        if (p.knockHp < 0) p.knockHp = 0;
      }
      // blue zone
      if (this.zone.phase >= 0 && p.state !== 'plane' && this.outsideZone(p)) {
        p.zoneAcc += this.zone.dps * dt;
        if (p.zoneAcc >= 1) { const q = Math.floor(p.zoneAcc); p.zoneAcc -= q; this.applyDamage(null, p, q, false, -1, true); if (this.ended) return; }
      }
      // disconnect grace
      if (!p.isBot && !p.connected && this.now - p.discAt > this.cfg.graceSeconds * 1000) this.kill(null, p, -1, false, true);
      // lag comp history
      p.history.push({ t: this.now, x: p.x, y: p.y, z: p.z, st: p.st });
      while (p.history.length && p.history[0].t < this.now - HISTORY_MS) p.history.shift();
    }
    this.checkEnd();
    // snapshots
    const every = Math.max(1, Math.round(this.cfg.tickRate / this.cfg.snapRate));
    if (!this.ended && this.tick % every === 0) this.sendSnapshots();
    if (this.zoneDirty) { this.zoneDirty = false; this.broadcast({ t: 'zone', ...this.zoneMsg() }); }
  }
  sendSnapshots() {
    const far = (this.tick / Math.max(1, Math.round(this.cfg.tickRate / this.cfg.snapRate))) % 3 === 0;
    const alive = this.aliveCount(), aliveTeams = this.teamsAlive();
    const z = this.zone;
    for (const me of this.players.values()) {
      if (me.isBot || !me.connected) continue;
      const rows = [];
      for (const o of this.players.values()) {
        if (o.state === 'dead' && o !== me) continue;
        const dist = Math.hypot(o.x - me.x, o.z - me.z);
        if (o !== me && o.team !== me.team && dist > 400) continue;   // interest management: far enemies are never sent (no wallhack data)
        if (o !== me && o.team !== me.team && dist > 200 && !far) continue;   // mid range at 1/3 rate
        const stCode = o.state === 'knocked' ? 6 : o.state === 'plane' ? 7 : o.state === 'dead' ? 8 : o.st;
        rows.push([o.id, r2(o.x), r2(o.y), r2(o.z), r1(o.yaw), r1(o.pitch), Math.max(0, Math.round(o.hp)), stCode, o.fl, o.cur]);
      }
      this.to(me, { t: 'snap', tick: this.tick, ts: this.now, phase: this.phase, alive, aliveTeams, hp: Math.round(me.hp), armor: me.armor, ack: me.lastSeq, players: rows, zr: r1(z.r), zcx: r1(z.cx), zcz: r1(z.cz) });
    }
  }
  checkEnd() {
    if (this.ended || this.phase === 'plane') return;
    const teams = new Set();
    let realAlive = 0;
    for (const p of this.players.values()) if (p.state !== 'dead') { teams.add(p.team); if (!p.isBot) realAlive++; }
    // a match with no real player left ends too (all bots remaining -> bots win, results still honest)
    if (teams.size <= 1 || realAlive === 0) this.finish(teams.size === 1 ? Array.from(teams)[0] : -1);
  }
  finish(winnerTeam) {
    if (this.ended) return;
    this.ended = true; this.phase = 'ended';
    const winners = [];
    let place = 1;
    for (const p of this.players.values()) if (p.team === winnerTeam && p.state !== 'dead') { winners.push(p.id); p.placement = 1; }
    // teams placement: rank dead teams by time the last member died
    const teamDeath = new Map();
    for (const p of this.players.values()) if (p.team !== winnerTeam) teamDeath.set(p.team, Math.max(teamDeath.get(p.team) || 0, p.state === 'dead' ? p.diedAt : this.sec));
    const order = Array.from(teamDeath.entries()).sort((a, b) => b[1] - a[1]).map((e) => e[0]);
    const teamPlace = new Map(); if (winnerTeam >= 0) teamPlace.set(winnerTeam, 1);
    order.forEach((tm, i) => teamPlace.set(tm, (winnerTeam >= 0 ? 2 : 1) + i));
    const results = Array.from(this.players.values()).map((p) => ({ id: p.id, name: p.name, team: p.team, isBot: p.isBot, placement: teamPlace.get(p.team) || 0, kills: p.kills, damage: Math.round(p.damage), surviveSec: Math.round(p.state === 'dead' ? p.diedAt : this.sec) }));
    results.sort((a, b) => a.placement - b.placement || b.kills - a.kills);
    this.results = { t: 'match_end', matchId: this.id, mode: this.mode, winnerTeam, winners, results, durationSec: Math.round(this.sec) };
    this.broadcast(this.results);
    this.onEnd(this);
  }
  leave(p) { if (p.state !== 'dead') this.kill(null, p, -1, false, true); }
  disconnect(p) { p.connected = false; p.discAt = this.now; p.fl = 0; }
  reconnect(p, session) { p.session = session; p.connected = true; p.lastIn = 0; }
}
const r1 = (v) => Math.round(v * 10) / 10, r2 = (v) => Math.round(v * 100) / 100;
function lootMsg(i) { return { id: i.id, type: i.type, kind: i.kind, x: r2(i.x), y: r2(i.y), z: r2(i.z), mag: i.mag }; }
module.exports = { Match, WORLD_SEED, ZONE_RADII, BOT_NAMES, MAX_SPEED };
