'use strict';
/** Public queue. Tickets are parties (1..teamSize players that must stay together). */
class Matchmaker {
  constructor(cfg, startFn) { this.cfg = cfg; this.start = startFn; this.tickets = []; }
  enqueue(sessions, mode, teamSize, now) {
    for (const s of sessions) this.cancel(s.uid);
    const tk = { uids: sessions.map((s) => s.uid), sessions, mode, teamSize, since: now || Date.now() };
    this.tickets.push(tk); return tk;
  }
  cancel(uid) {
    const i = this.tickets.findIndex((t) => t.uids.includes(uid));
    if (i < 0) return false;
    const tk = this.tickets[i];
    this.tickets.splice(i, 1); return tk;
  }
  has(uid) { return this.tickets.some((t) => t.uids.includes(uid)); }
  group(mode, teamSize) { return this.tickets.filter((t) => t.mode === mode && t.teamSize === teamSize); }
  /** pack parties into teams of <= teamSize (first-fit decreasing). returns teams: array of arrays of sessions */
  static pack(tickets, teamSize) {
    const units = tickets.slice().sort((a, b) => b.sessions.length - a.sessions.length);
    const teams = [];
    for (const tk of units) {
      let placed = false;
      for (const tm of teams) if (tm.length + tk.sessions.length <= teamSize) { tm.push(...tk.sessions); placed = true; break; }
      if (!placed) teams.push(tk.sessions.slice());
    }
    return teams;
  }
  status(now) {
    const out = [];
    for (const key of new Set(this.tickets.map((t) => t.mode + ':' + t.teamSize))) {
      const [mode, ts] = key.split(':'); const teamSize = +ts;
      const g = this.group(mode, teamSize);
      const waiting = g.reduce((n, t) => n + t.sessions.length, 0);
      const needed = mode === 'br' ? this.cfg.brMaxPlayers : teamSize * 2;
      const fill = (mode === 'br' ? this.cfg.brFillSeconds : this.cfg.csWaitSeconds) * 1000;
      for (const t of g) out.push({ tk: t, msg: { t: 'mm_status', queued: true, mode, teamSize, waiting, needed, eta: Math.max(0, Math.ceil((t.since + fill - now) / 1000)) } });
    }
    return out;
  }
  poll(now) {
    for (const key of new Set(this.tickets.map((t) => t.mode + ':' + t.teamSize))) {
      const [mode, ts] = key.split(':'); const teamSize = +ts;
      const g = this.group(mode, teamSize);
      if (!g.length) continue;
      if (mode === 'br') {
        const cap = this.cfg.brMaxPlayers;
        const chosen = []; let n = 0;
        for (const t of g) { if (n + t.sessions.length <= cap) { chosen.push(t); n += t.sessions.length; } }
        const oldest = Math.min(...g.map((t) => t.since));
        if (n >= cap || now - oldest >= this.cfg.brFillSeconds * 1000) this.launch(chosen, mode, teamSize, cap);
      } else {
        const need = teamSize * 2;
        // choose tickets which fit in two teams
        let chosen = [], n = 0;
        for (const t of g) { if (n + t.sessions.length <= need) { chosen.push(t); n += t.sessions.length; } }
        const teams = Matchmaker.pack(chosen, teamSize);
        const oldest = Math.min(...g.map((t) => t.since));
        if ((teams.length <= 2 && n >= need) || (now - oldest >= this.cfg.csWaitSeconds * 1000 && teams.length <= 2)) this.launch(chosen, mode, teamSize, need);
      }
    }
  }
  launch(chosen, mode, teamSize, cap) {
    for (const t of chosen) this.tickets.splice(this.tickets.indexOf(t), 1);
    const teams = Matchmaker.pack(chosen, teamSize);
    this.start({ mode, teamSize, teams, cap });
  }
}
module.exports = { Matchmaker };
