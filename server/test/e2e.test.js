'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { startServer, Client, connectN, sleep } = require('./helpers');

test('hello issues a token; wrong token is rejected; bad uid rejected', async () => {
  const srv = await startServer();
  try {
    const a = new Client(srv.port, 'alice1'); const w = await a.connect();
    assert.ok(w.token && w.token.length >= 16); assert.strictEqual(w.uid, 'alice1');
    a.close(); await sleep(50);
    const a2 = new Client(srv.port, 'alice1'); await assert.rejects(a2.connect('wrongtoken'), /bad_token/);
    const a3 = new Client(srv.port, 'alice1'); a3.token = w.token; await a3.connect();
    const bad = new Client(srv.port, '!!'); await assert.rejects(bad.connect(), /bad_uid/);
  } finally { await srv.close(); }
});

test('rooms: password, ready, host start rules, teams, kick, config', async () => {
  const srv = await startServer();
  try {
    const [h, g, x] = await connectN(srv, ['host01', 'guest1', 'xtra01']);
    h.send({ t: 'room_create', mode: 'custom', teamSize: 2, priv: true, password: 'pw1', map: 'island' });
    const st = await h.once('room_state'); assert.strictEqual(st.members.length, 1); assert.ok(st.hasPassword); assert.strictEqual(st.maxPlayers, 4);
    assert.match(st.room, /^[A-HJ-NP-Z2-9]{6}$/);
    g.send({ t: 'room_join', room: st.room, password: 'bad' }); assert.strictEqual((await g.once('error')).code, 'bad_password');
    g.send({ t: 'room_join', room: st.room, password: 'pw1' });
    const st2 = await h.once('room_state', 2000, (m) => m.members.length === 2);
    h.send({ t: 'room_start' }); assert.strictEqual((await h.once('error')).code, 'not_all_ready');
    g.send({ t: 'room_ready', ready: true }); await h.once('room_state', 2000, (m) => m.members.find((q) => q.uid === 'guest1').ready);
    g.send({ t: 'room_team', team: 0 }); await h.once('room_state', 2000, (m) => m.members.find((q) => q.uid === 'guest1').team === 0);
    g.send({ t: 'room_ready', ready: true }); await h.once('room_state', 2000, (m) => m.members.find((q) => q.uid === 'guest1').ready);
    h.send({ t: 'room_start' }); assert.strictEqual((await h.once('error')).code, 'need_two_teams');
    g.send({ t: 'room_team', team: 1 }); await h.once('room_state', 2000, (m) => m.members.find((q) => q.uid === 'guest1').team === 1);
    g.send({ t: 'room_start' }); assert.strictEqual((await g.once('error')).code, 'not_host');
    g.send({ t: 'room_ready', ready: true }); await h.once('room_state', 2000, (m) => m.members.find((q) => q.uid === 'guest1').ready);
    // public room listing: private rooms are hidden
    x.send({ t: 'room_list' }); assert.strictEqual((await x.once('rooms')).rooms.length, 0);
    // kick
    x.send({ t: 'room_join', room: st.room, password: 'pw1' }); await h.once('room_state', 2000, (m) => m.members.length === 3);
    h.send({ t: 'room_kick', uid: 'xtra01' }); assert.strictEqual((await x.once('room_closed')).reason, 'kicked');
    // host starts: 1v1 custom with bots filling to 2 teams of 2
    h.send({ t: 'room_start' });
    const ms = await h.once('match_start'); const mg = await g.once('match_start');
    assert.strictEqual(ms.mode, 'custom'); assert.strictEqual(ms.counts.realPlayers, 2);
    assert.strictEqual(ms.players.filter((p) => !p.isBot).length, 2);
    assert.ok(ms.players.filter((p) => p.isBot).every((p) => p.name.startsWith('[BOT]')));
    assert.notStrictEqual(ms.you.team, mg.you.team);
  } finally { await srv.close(); }
});

test('room config by host only; invalid values rejected', async () => {
  const srv = await startServer();
  try {
    const [h, g] = await connectN(srv, ['host02', 'guest2']);
    h.send({ t: 'room_create', mode: 'br', teamSize: 4 }); const st = await h.once('room_state');
    g.send({ t: 'room_join', room: st.room }); await g.once('room_state');
    g.send({ t: 'room_config', teamSize: 2 }); assert.strictEqual((await g.once('error')).code, 'not_host');
    h.send({ t: 'room_config', teamSize: 9 }); assert.strictEqual((await h.once('error')).code, 'bad_team_size');
    h.send({ t: 'room_config', teamSize: 2, map: 'island' }); const s2 = await h.once('room_state', 2000, (m) => m.teamSize === 2); assert.strictEqual(s2.mode, 'br');
    g.send({ t: 'room_join', room: 'ZZZZZZ' }); assert.strictEqual((await g.once('error')).code, 'no_such_room');
  } finally { await srv.close(); }
});

test('BR matchmaking: fills to 50 with honestly flagged bots, results at end', async () => {
  const srv = await startServer({ zoneTimeScale: 0.001 });
  try {
    const [a] = await connectN(srv, ['solo01']);
    a.send({ t: 'mm_join', mode: 'br', teamSize: 1 });
    const q = await a.once('mm_status'); assert.ok(q.queued);
    const ms = await a.once('match_start', 4000);
    assert.strictEqual(ms.players.length, 50); assert.strictEqual(ms.counts.realPlayers, 1); assert.strictEqual(ms.counts.bots, 49);
    assert.strictEqual(ms.players.filter((p) => p.isBot).length, 49);
    assert.strictEqual(ms.players.find((p) => p.id === 'solo01').isBot, false);
    assert.ok(ms.loot.length > 40); assert.ok(ms.worldSeed === 20261104);
    // snapshots arrive
    const sn = await a.once('snap', 2000); assert.ok(sn.players.length >= 1); assert.ok(sn.alive <= 50);
    // we just wait until the zone / bots finish it (zone is extremely fast in this config); we must receive a match_end with honest results
    const end = await a.once('match_end', 60000);
    assert.strictEqual(end.results.length, 50);
    assert.ok(end.results.every((r) => typeof r.isBot === 'boolean'));
    assert.strictEqual(end.results.find((r) => r.id === 'solo01').isBot, false);
  } finally { await srv.close(); }
});

test('two real players: both in one match, never matched with bots as teammates', async () => {
  const srv = await startServer({ brFillSeconds: 0.5 });
  try {
    const [a, b] = await connectN(srv, ['duo001', 'duo002']);
    a.send({ t: 'mm_join', mode: 'br', teamSize: 2 }); b.send({ t: 'mm_join', mode: 'br', teamSize: 2 });
    const ma = await a.once('match_start', 4000), mb = await b.once('match_start', 4000);
    assert.strictEqual(ma.matchId, mb.matchId); assert.strictEqual(ma.counts.realPlayers, 2);
    assert.strictEqual(ma.you.team, mb.you.team, 'two solo players pack into one team of 2');
    const mates = ma.players.filter((p) => p.team === ma.you.team);
    assert.ok(mates.every((p) => !p.isBot));
  } finally { await srv.close(); }
});

test('mm_cancel and disconnect leave the queue', async () => {
  const srv = await startServer({ brFillSeconds: 100 });
  try {
    const [a, b] = await connectN(srv, ['can001', 'can002']);
    a.send({ t: 'mm_join', mode: 'br', teamSize: 1 }); await a.once('mm_status');
    a.send({ t: 'mm_cancel' }); assert.strictEqual((await a.once('mm_status', 2000, (m) => !m.queued)).queued, false);
    b.send({ t: 'mm_join', mode: 'br', teamSize: 1 }); await b.once('mm_status'); b.close(); await sleep(100);
    assert.strictEqual(srv.mm.tickets.length, 0);
  } finally { await srv.close(); }
});

test('gameplay over the wire: jump, input accepted, teleport corrected, loot, fire validated', async () => {
  const srv = await startServer({ zoneTimeScale: 100, planeSpeed: 5 });
  try {
    const [a] = await connectN(srv, ['play01']);
    a.send({ t: 'mm_join', mode: 'br', teamSize: 1 });
    const ms = await a.once('match_start', 4000);
    a.send({ t: 'jump' });
    const c0 = await a.once('correct', 2000); assert.strictEqual(c0.seq, 0);
    // fall to the ground gradually (client-side kinematics)
    let { x, y, z } = c0; let seq = 1;
    const ground = (x, z) => srv.terrain.heightAt(x, z);
    let corrections = 0; a.on = null;
    while (y > ground(x, z) + 1) { y = Math.max(ground(x, z), y - 4); a.send({ t: 'input', seq: seq++, x, y, z, yaw: 0, pitch: 0, st: 4, fl: 0, wp: 26 }); await sleep(55); }
    await sleep(100);
    assert.strictEqual(a.msgs.filter((m) => m.t === 'correct').length, 0, 'legit descent never corrected');
    // teleport hack
    a.send({ t: 'input', seq: seq++, x: x + 500, y: ground(x + 500, z), z, yaw: 0, pitch: 0, st: 0, fl: 0 });
    const c = await a.once('correct', 2000); assert.ok(Math.abs(c.x - x) < 1);
    // pick up a loot gun if one is near: teleport impossible, so use server internals to place loot at the player
    const match = srv.matches.get(ms.matchId); const p = match.players.get('play01');
    const gun = match.addLoot({ kind: 'gun', type: 0 }, p.x, p.y, p.z); a.send({ t: 'pickup', lootId: gun.id });
    const inv = await a.once('inventory', 2000); assert.ok(inv.inv.weapons.includes(0));
    // fire straight up into nothing: accepted ammo decrement
    a.send({ t: 'fire', seq: 1, ox: p.x, oy: p.y + 1.6, oz: p.z, dx: 0, dy: 1, dz: 0, wp: 0, ct: Date.now() });
    const am = await a.once('ammo', 2000); assert.strictEqual(am.mag, 29);
    // fire with a forged weapon id
    a.send({ t: 'fire', seq: 2, ox: p.x, oy: p.y + 1.6, oz: p.z, dx: 0, dy: 1, dz: 0, wp: 3, ct: Date.now() });
    await sleep(150); assert.strictEqual(match.players.get('play01').mag[0], 29);
  } finally { await srv.close(); }
});

test('reconnection: dropped player resumes the same match with the same token', async () => {
  const srv = await startServer({ planeSpeed: 5, graceSeconds: 30 });
  try {
    const a = new Client(srv.port, 'rec001'); await a.connect();
    a.send({ t: 'mm_join', mode: 'br', teamSize: 1 });
    const ms = await a.once('match_start', 4000);
    a.close(); await sleep(200);
    const match = srv.matches.get(ms.matchId); assert.strictEqual(match.players.get('rec001').connected, false);
    const a2 = new Client(srv.port, 'rec001'); a2.token = a.token;
    const w = await a2.connect(a.token); assert.strictEqual(w.resume.matchId, ms.matchId);
    const ms2 = await a2.once('match_start'); assert.ok(ms2.resume); assert.strictEqual(ms2.matchId, ms.matchId);
    assert.strictEqual(match.players.get('rec001').connected, true);
    assert.ok(ms2.state && typeof ms2.state.hp === 'number');
  } finally { await srv.close(); }
});

test('disconnect past grace period eliminates the player', async () => {
  const srv = await startServer({ planeSpeed: 5, graceSeconds: 0.3 });
  try {
    const a = new Client(srv.port, 'gone01'); await a.connect();
    a.send({ t: 'mm_join', mode: 'br', teamSize: 1 }); const ms = await a.once('match_start', 4000);
    a.close(); await sleep(900);
    assert.strictEqual(srv.matches.get(ms.matchId) ? srv.matches.get(ms.matchId).players.get('gone01').state : 'dead', 'dead');
  } finally { await srv.close(); }
});

test('friends: request, accept, list with presence, invite to room', async () => {
  const srv = await startServer();
  try {
    const [a, b] = await connectN(srv, ['fr_a01', 'fr_b01']);
    a.send({ t: 'friend_add', uid: 'fr_b01' });
    const rq = await b.once('friend_request'); assert.strictEqual(rq.from, 'fr_a01');
    b.send({ t: 'friend_accept', uid: 'fr_a01' });
    const fl = await a.once('friends', 2000, (m) => m.friends.length === 1); assert.strictEqual(fl.friends[0].uid, 'fr_b01'); assert.ok(fl.friends[0].online);
    a.send({ t: 'invite', uid: 'fr_b01' }); assert.strictEqual((await a.once('error')).code, 'not_in_room');
    a.send({ t: 'room_create', mode: 'br', teamSize: 4, priv: true, password: 'secret' }); const st = await a.once('room_state');
    a.send({ t: 'invite', uid: 'fr_b01' });
    const inv = await b.once('invite'); assert.strictEqual(inv.room, st.room);
    b.send({ t: 'room_join', room: inv.room }); // invited -> no password needed
    const st2 = await b.once('room_state'); assert.strictEqual(st2.members.length, 2);
    const [c] = await connectN(srv, ['fr_c01']);
    a.send({ t: 'invite', uid: 'fr_c01' }); assert.strictEqual((await a.once('error')).code, 'not_friends');
    c.send({ t: 'room_join', room: st.room }); assert.strictEqual((await c.once('error')).code, 'bad_password');
  } finally { await srv.close(); }
});

test('malformed / hostile input does not crash the server', async () => {
  const srv = await startServer();
  try {
    const a = new Client(srv.port, 'evil01'); await a.connect();
    for (const junk of ['{', 'null', '[]', '{"t":5}', '{"t":"input","x":"a"}', '{"t":"room_create","mode":{"$":1}}', '{"t":"room_join","room":null}', '{"t":"fire"}', '{"t":"mm_join","mode":"br","teamSize":"4"}', 'x'.repeat(20000)]) a.sock.write(junk + '\n');
    await sleep(100);
    a.send({ t: 'ping', ts: 5 }); const pong = await a.once('pong'); assert.strictEqual(pong.ts, 5);
  } finally { await srv.close(); }
});

test('flooding connection is dropped', async () => {
  const srv = await startServer({ rateBurst: 20, rateRefill: 5, rateKick: 50 });
  try {
    const a = new Client(srv.port, 'flood1'); await a.connect();
    for (let i = 0; i < 2000 && !a.closed; i++) { try { a.send({ t: 'ping', ts: i }); } catch (e) { break; } }
    await sleep(300); assert.ok(a.closed, 'connection closed');
  } finally { await srv.close(); }
});
