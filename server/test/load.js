'use strict';
// 50 real (fake) TCP clients in one BR match, each sending 20 input/s and firing; reports tick cost + bandwidth.
const { startServer, Client } = require('./helpers');
(async () => {
  const srv = await startServer({ planeSpeed: 5, brFillSeconds: 100, zoneTimeScale: 100, rateBurst: 600 });
  const N = 50, clients = [];
  for (let i = 0; i < N; i++) { const c = new Client(srv.port, 'load' + String(i).padStart(3, '0')); await c.connect(); c.bytes = 0; c.sock.on('data', (d) => { c.bytes += d.length; }); clients.push(c); }
  clients.forEach((c) => c.send({ t: 'mm_join', mode: 'br', teamSize: 1 }));
  const ms = await Promise.all(clients.map((c) => c.once('match_start', 10000)));
  const match = srv.matches.get(ms[0].matchId);
  console.log('match players', match.players.size, 'real', match.counts().realPlayers, 'bots', match.counts().bots);
  const st = clients.map((c, i) => { const p = match.players.get(c.uid); return { c, x: 0, z: 0, y: 0, seq: 1, ang: i }; });
  clients.forEach((c) => c.send({ t: 'jump' }));
  await new Promise((r) => setTimeout(r, 300));
  for (const s of st) { const p = match.players.get(s.c.uid); s.x = p.x; s.y = p.y; s.z = p.z; }
  srv.stats = { tickMs: 0, tickMax: 0, ticks: 0 };
  const t0 = Date.now(); let corrections = 0;
  const iv = setInterval(() => {
    for (const s of st) {
      const p = match.players.get(s.c.uid); if (!p || p.state === 'dead') continue;
      const gy = srv.terrain.heightAt(s.x, s.z);
      if (s.y > gy + 0.5) s.y = Math.max(gy, s.y - 3); else { s.x += Math.cos(s.ang) * 0.2; s.z += Math.sin(s.ang) * 0.2; s.y = srv.terrain.heightAt(s.x, s.z); }
      s.c.send({ t: 'input', seq: s.seq++, x: s.x, y: s.y, z: s.z, yaw: s.ang * 57, pitch: 0, st: s.y > gy + 0.6 ? 4 : 0, fl: 0 });
    }
  }, 50);
  await new Promise((r) => setTimeout(r, 10000));
  clearInterval(iv);
  const secs = (Date.now() - t0) / 1000;
  corrections = clients.reduce((n, c) => n + c.msgs.filter((m) => m.t === 'correct').length, 0);
  const kb = clients.reduce((n, c) => n + c.bytes, 0) / 1024 / secs / N;
  const s = srv.stats;
  console.log(`ticks ${s.ticks} avg ${(s.tickMs / s.ticks).toFixed(2)} ms  max ${s.tickMax.toFixed(2)} ms  (budget 50 ms)`);
  console.log(`downstream per client ${kb.toFixed(1)} KB/s, corrections ${corrections}, alive ${match.aliveCount()}`);
  await srv.close(); process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
