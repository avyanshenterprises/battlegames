'use strict';
const U = require('./util');

const MODES = { br: 1, cs: 1, custom: 1 };
/** Room rules + lobby state. Pure logic: no sockets. */
class Room {
  constructor(id, host, o) {
    this.id = id; this.host = host.uid; this.matchId = null;
    this.members = new Map();         // uid -> {uid,name,team,ready,session}
    this.invited = new Map();         // uid -> expiry ms
    this.fillBots = true;
    this.configure(Object.assign({ mode: 'br', teamSize: 4, priv: false, map: 'island' }, o), true);
    this.addMember(host);
  }
  /** returns error string or null */
  configure(o, initial) {
    const mode = o.mode !== undefined ? o.mode : this.mode;
    if (!MODES[mode]) return 'bad_mode';
    let teamSize = o.teamSize !== undefined ? o.teamSize : this.teamSize;
    const maxTs = mode === 'br' ? 4 : mode === 'cs' ? 4 : 8;
    if (!Number.isInteger(teamSize) || teamSize < 1 || teamSize > maxTs) return 'bad_team_size';
    let maxPlayers = mode === 'br' ? (o.maxPlayers !== undefined ? o.maxPlayers : (this.maxPlayers || 50)) : teamSize * 2;
    if (!Number.isInteger(maxPlayers) || maxPlayers < 1 || maxPlayers > 50) return 'bad_max_players';
    if (mode === 'br') maxPlayers = Math.max(maxPlayers, 1);
    if (!initial && this.members.size > maxPlayers) return 'too_many_members';
    const map = o.map !== undefined ? o.map : this.map;
    if (typeof map !== 'string' || !/^[a-z0-9_-]{1,24}$/i.test(map)) return 'bad_map';
    if (o.password !== undefined) this.pwHash = o.password ? U.sha256('room:' + o.password) : null;
    else if (initial) this.pwHash = null;
    if (o.priv !== undefined) this.priv = !!o.priv; else if (initial) this.priv = false;
    if (o.fillBots !== undefined) this.fillBots = !!o.fillBots;
    this.mode = mode; this.teamSize = teamSize; this.maxPlayers = maxPlayers; this.map = map;
    this.teamCount = mode === 'br' ? Math.ceil(maxPlayers / teamSize) : 2;
    if (!initial) this.rebalance();
    return null;
  }
  get hasPassword() { return !!this.pwHash; }
  teamFill(team) { let n = 0; for (const m of this.members.values()) if (m.team === team) n++; return n; }
  freeTeam() {
    let best = -1, bf = 1e9;
    for (let t = 0; t < this.teamCount; t++) {
      const f = this.teamFill(t);
      if (f < this.teamSize && (this.mode === 'br' ? f > 0 && f < bf : f < bf)) { best = t; bf = f; }
    }
    if (best >= 0 && this.mode === 'br') return best;
    if (best >= 0) return best;
    for (let t = 0; t < this.teamCount; t++) if (this.teamFill(t) < this.teamSize) return t;
    return -1;
  }
  addMember(session) {
    if (this.members.size >= this.maxPlayers) return 'room_full';
    const t = this.freeTeam();
    if (t < 0) return 'room_full';
    this.members.set(session.uid, { uid: session.uid, name: session.name, team: t, ready: false, session });
    return null;
  }
  rebalance() {
    for (const m of this.members.values()) if (m.team >= this.teamCount || this.teamFill(m.team) > this.teamSize) m.team = -1;
    for (const m of this.members.values()) if (m.team < 0) m.team = this.freeTeam();
  }
  setTeam(uid, team) {
    const m = this.members.get(uid);
    if (!m || !Number.isInteger(team) || team < 0 || team >= this.teamCount) return 'bad_team';
    if (m.team === team) return null;
    if (this.teamFill(team) >= this.teamSize) return 'team_full';
    m.team = team; m.ready = false; return null;
  }
  removeMember(uid) {
    this.members.delete(uid);
    if (this.host === uid && this.members.size) this.host = this.members.keys().next().value;
  }
  canJoin(uid, password) {
    if (this.matchId) return 'in_match';
    if (this.members.has(uid)) return null;
    if (this.members.size >= this.maxPlayers) return 'room_full';
    if (this.pwHash) {
      const inv = this.invited.get(uid);
      if (!(inv && inv > Date.now()) && (typeof password !== 'string' || U.sha256('room:' + password) !== this.pwHash)) return 'bad_password';
    }
    return null;
  }
  /** host start validation */
  canStart(uid) {
    if (uid !== this.host) return 'not_host';
    if (this.matchId) return 'in_match';
    for (const m of this.members.values()) if (m.uid !== this.host && !m.ready) return 'not_all_ready';
    if (this.mode !== 'br') {
      const a = this.teamFill(0), b = this.teamFill(1);
      if (a === 0 || b === 0) return 'need_two_teams';
    }
    return null;
  }
  publicInfo() { return { room: this.id, mode: this.mode, teamSize: this.teamSize, map: this.map, players: this.members.size, maxPlayers: this.maxPlayers, hasPassword: this.hasPassword }; }
  state() {
    return { t: 'room_state', room: this.id, mode: this.mode, teamSize: this.teamSize, maxPlayers: this.maxPlayers, map: this.map, priv: this.priv, hasPassword: this.hasPassword, fillBots: this.fillBots, teamCount: this.teamCount, host: this.host, inMatch: !!this.matchId,
      members: Array.from(this.members.values()).map((m) => ({ uid: m.uid, name: m.name, team: m.team, ready: m.ready || m.uid === this.host, host: m.uid === this.host })) };
  }
}
module.exports = { Room };
