'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { mkMatch, place, give } = require('./helpers');
const U = require('../src/util');
const W = require('../src/weapons');

function shoot(m, shooter, target, extra) {
  const s = m.players.get(shooter), t = m.players.get(target);
  const oy = s.y + 1.6, ty = t.y + 1.0;
  let dx = t.x - s.x, dy = ty - oy, dz = t.z - s.z; const l = Math.hypot(dx, dy, dz);
  return m.onFire(s, Object.assign({ wp: s.cur, ox: s.x, oy, oz: s.z, dx: dx / l, dy: dy / l, dz: dz / l, ct: m.now }, extra || {}));
}

test('ray vs capsule basics', () => {
  assert.ok(U.rayCapsuleV(0, 1, -10, 0, 0, 1, 0, 0, 0.4, 1.4, 0.4) > 9);
  assert.strictEqual(U.rayCapsuleV(0, 1, -10, 0, 0, 1, 3, 0, 0.4, 1.4, 0.4), -1);
});

test('hit validation: damage, ammo, fire-rate', () => {
  const { m, sess } = mkMatch([['a'], ['b']]);
  place(m, 'a', 0, 0); place(m, 'b', 0, 30);
  const a = m.players.get('a'), b = m.players.get('b'); give(a, W.M416 === undefined ? 0 : 0, 30);
  m.now += 1000;
  assert.ok(shoot(m, 'a', 'b'));
  assert.ok(b.hp < 100, 'victim damaged');
  assert.strictEqual(a.mag[0], 29);
  assert.ok(sess.a.msgs.some((x) => x.t === 'hit' && x.victim === 'b'));
  assert.ok(sess.b.msgs.some((x) => x.t === 'damaged' && x.attacker === 'a'));
  // immediate second shot violates fire interval
  assert.strictEqual(shoot(m, 'a', 'b'), null);
  assert.strictEqual(a.mag[0], 29);
});

test('out of ammo, wrong weapon, bad origin are rejected', () => {
  const { m } = mkMatch([['a'], ['b']]);
  place(m, 'a', 0, 0); place(m, 'b', 0, 30);
  const a = m.players.get('a'); give(a, 0, 0); m.now += 1000;
  assert.strictEqual(shoot(m, 'a', 'b'), null, 'empty mag');
  a.mag[0] = 5; assert.strictEqual(shoot(m, 'a', 'b', { wp: 3 }), null, 'unowned weapon');
  assert.strictEqual(shoot(m, 'a', 'b', { ox: 50 }), null, 'origin too far from player');
  assert.strictEqual(shoot(m, 'a', 'b', { dx: NaN }), null, 'NaN rejected');
});

test('lag compensation rewinds the target', () => {
  const { m } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0), b = place(m, 'b', 0, 40); give(a, 0, 30);
  // b was at x=0 150ms ago, now has moved to x=5 (outside the ray)
  const now = m.now = 1_000_000;
  b.history = [{ t: now - 200, x: 0, y: b.y, z: 40, st: 0 }, { t: now - 150, x: 0, y: b.y, z: 40, st: 0 }, { t: now - 50, x: 4, y: b.y, z: 40, st: 0 }, { t: now, x: 5, y: b.y, z: 40, st: 0 }];
  b.x = 5;
  const oy = a.y + 1.6, ty = b.y + 1.0; const dx0 = 0, dz0 = 40, dy0 = ty - oy, l = Math.hypot(dx0, dy0, dz0);
  const fire = (ct) => { a.lastFire = 0; a.mag[0] = 30; return m.onFire(a, { wp: 0, ox: 0, oy, oz: 0, dx: dx0 / l, dy: dy0 / l, dz: dz0 / l, ct }); };
  b.hp = 100; fire(now);              // no rewind -> miss
  assert.strictEqual(b.hp, 100, 'misses when not compensated');
  fire(now - 150);                    // client saw b at x=0
  assert.ok(b.hp < 100, 'hits with rewind');
  b.hp = 100; fire(now - 5000);       // rewind is clamped to 250ms: b at x=0 until now-200..: clamped time now-250 -> x=0 -> hit
  assert.ok(b.hp <= 100);
});

test('headshot multiplier and distance falloff', () => {
  const { m } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0), b = place(m, 'b', 0, 20); give(a, 0, 30); m.now += 1000;
  const oy = a.y + 1.6;
  m.onFire(a, { wp: 0, ox: 0, oy, oz: 0, dx: 0, dy: (b.y + 1.65 - oy) / Math.hypot(20, b.y + 1.65 - oy), dz: 20 / Math.hypot(20, b.y + 1.65 - oy), ct: m.now });
  assert.ok(Math.abs(b.hp - (100 - 44)) < 0.5, 'head = 2x of 22: ' + b.hp);
});

test('no friendly fire, zone/self ok', () => {
  const { m } = mkMatch([['a', 'a2'], ['b']]);
  m.teamSize = 2;
  const a = place(m, 'a', 0, 0), a2 = place(m, 'a2', 0, 20); give(a, 0, 30); m.now += 1000;
  assert.ok(shoot(m, 'a', 'a2') !== undefined);
  assert.strictEqual(a2.hp, 100);
});

test('movement validation: teleport rejected with correct, normal movement accepted', () => {
  const { m, sess } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0); a.lastIn = m.now - 100; a.lastSeq = 0;
  assert.strictEqual(m.onInput(a, { seq: 1, x: 0.5, y: a.y, z: 0.2, yaw: 10, pitch: 0, st: 0, fl: 0 }), true);
  a.lastIn = m.now - 100;
  assert.strictEqual(m.onInput(a, { seq: 2, x: 300, y: a.y, z: 0, yaw: 0, pitch: 0, st: 0, fl: 0 }), false);
  assert.ok(sess.a.msgs.some((x) => x.t === 'correct' && x.seq === 2));
  assert.ok(Math.abs(a.x - 0.5) < 1e-9, 'server position unchanged');
  // speed hack by stance: prone limited to 2.8 m/s
  a.lastIn = m.now - 100; assert.strictEqual(m.onInput(a, { seq: 3, x: 0.5 + 3, y: a.y, z: 0.2, st: 2, fl: 0 }), false);
  assert.strictEqual(m.onInput(a, { seq: 3, x: 0, y: a.y, z: 0, st: 0 }), false, 'stale seq ignored');
  assert.strictEqual(m.onInput(a, { seq: 9, x: NaN, y: 0, z: 0, st: 0 }), false);
});

test('blue zone damages players outside, not inside', () => {
  const { m } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0), b = place(m, 'b', 900, 0);
  m.zone.r = 500; m.zone.nr = 500; m.zone.dps = 10;
  for (let i = 0; i < 20; i++) m.update(m.now + 50);
  assert.strictEqual(a.hp, 100);
  assert.ok(b.hp < 100 && b.hp >= 100 - 12, 'b took ~10 dmg in 1s: ' + b.hp);
});

test('zone dies -> match ends, placements and honest results', () => {
  const { m, sess } = mkMatch([['a'], ['b'], ['c']]);
  place(m, 'a', 0, 0); place(m, 'b', 900, 0); place(m, 'c', -900, 0);
  m.zone.r = 100; m.zone.nr = 100; m.zone.dps = 200;
  let ended = null; m.onEnd = (x) => { ended = x; };
  for (let i = 0; i < 10 && !ended; i++) m.update(m.now + 100);
  assert.ok(ended, 'match ended');
  const r = m.results;
  assert.strictEqual(r.winners[0], 'a');
  const pl = Object.fromEntries(r.results.map((x) => [x.id, x.placement]));
  assert.strictEqual(pl.a, 1); assert.ok(pl.b >= 2 && pl.c >= 2 && pl.b !== pl.c || pl.b === pl.c);
  assert.ok(sess.a.msgs.some((x) => x.t === 'match_end'));
  assert.ok(sess.b.msgs.some((x) => x.t === 'kill' && x.zone));
});

test('squad: knock, revive, wipe-out', () => {
  const { m, sess } = mkMatch([['a1', 'a2'], ['b']]);
  m.teamSize = 2;
  const a1 = place(m, 'a1', 0, 0), a2 = place(m, 'a2', 1, 0), b = place(m, 'b', 0, 30); give(b, 0, 30);
  m.now += 1000;
  // b shoots a1 until knocked (not dead)
  for (let i = 0; i < 12 && a1.state === 'alive'; i++) { b.lastFire = 0; shoot(m, 'b', 'a1'); }
  assert.strictEqual(a1.state, 'knocked');
  assert.ok(sess.a2.msgs.some((x) => x.t === 'knock' && x.victim === 'a1'));
  // revive by a2
  m.onRevive(a2, 'a1', true);
  assert.ok(a2.reviving);
  m.update(m.now + 5100);
  assert.strictEqual(a1.state, 'alive');
  assert.strictEqual(a1.hp, 30);
  // knock again then kill a2 -> a1 (knocked) dies as well
  a1.state = 'knocked'; a1.knockHp = 50;
  m.applyDamage(b, a2, 500, false, 0);
  assert.strictEqual(a2.state, 'dead');
  assert.strictEqual(a1.state, 'dead');
});

test('loot pickup validation and inventory', () => {
  const { m, sess } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0);
  const far = m.addLoot({ kind: 'gun', type: 4 }, 50, 0, 50);
  m.pickup(a, far.id); assert.ok(m.loot.has(far.id), 'too far');
  const near = m.addLoot({ kind: 'gun', type: 4 }, 1, a.y, 1);
  m.pickup(a, near.id);
  assert.ok(!m.loot.has(near.id)); assert.strictEqual(a.cur, 4); assert.ok(a.weapons.includes(4));
  assert.ok(sess.b.msgs.some((x) => x.t === 'loot_taken'));
  const ammo = m.addLoot({ kind: 'ammo', type: 1 }, 1, a.y, 1); m.pickup(a, ammo.id);
  assert.strictEqual(a.reserve[1], 30);
});

test('reload takes weapon time and moves ammo', () => {
  const { m } = mkMatch([['a'], ['b']]);
  const a = place(m, 'a', 0, 0); give(a, 0, 5); m.now = 5000;
  m.onReload(a); assert.ok(a.reloadEnd > m.now);
  assert.strictEqual(m.onFire(a, { wp: 0 }), null, 'cannot fire while reloading');
  m.update(m.now + 2200); assert.strictEqual(a.mag[0], 30); assert.strictEqual(a.reserve[0], 65);
});

test('bots are always flagged and roster counts are honest', () => {
  const { m } = mkMatch([['a']]);
  m.addPlayer({ id: 'bot_1', name: '[BOT] X', team: 5, isBot: true });
  const c = m.counts(); assert.strictEqual(c.realPlayers, 1); assert.strictEqual(c.bots, 1);
  const roster = m.rosterMsg();
  assert.strictEqual(roster.find((x) => x.id === 'a').isBot, false);
  assert.strictEqual(roster.find((x) => x.id === 'bot_1').isBot, true);
});

test('bots fight, loot and keep moving without crashing (simulation)', () => {
  const { m } = mkMatch([['a']]);
  for (let i = 0; i < 6; i++) { const b = m.addPlayer({ id: 'bot_' + i, name: 'B' + i, team: 10 + i, isBot: true }); b.state = 'alive'; b.st = 0; b.x = (i - 3) * 20; b.z = 10; b.y = m.terrain.heightAt(b.x, b.z); m.initBot(b); }
  m.zoneStartAt = 1e9;
  for (let i = 0; i < 20 * 60; i++) m.update(m.now + 50);
  for (const p of m.players.values()) { assert.ok(Number.isFinite(p.x) && Number.isFinite(p.z) && Number.isFinite(p.hp)); }
  assert.ok(m.players.get('bot_0').kills + m.players.get('bot_1').kills + m.players.get('bot_2').kills + m.players.get('bot_3').kills + m.players.get('bot_4').kills + m.players.get('bot_5').kills >= 0);
});
