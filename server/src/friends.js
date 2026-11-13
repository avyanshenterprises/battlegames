'use strict';
const fs = require('fs');
const path = require('path');

/** Persistent user directory: names, token hashes, friends, pending requests. JSON file, atomic writes. */
class FriendStore {
  constructor(dir) {
    this.dir = dir || null;
    this.file = dir ? path.join(dir, 'friends.json') : null;
    this.users = Object.create(null);
    this.timer = null;
    if (this.file) {
      try { this.users = Object.assign(Object.create(null), JSON.parse(fs.readFileSync(this.file, 'utf8')).users || {}); } catch (e) { /* first run */ }
    }
  }
  get(uid) { return this.users[uid] || null; }
  ensure(uid, name) {
    let u = this.users[uid];
    if (!u) { u = this.users[uid] = { name, tokenHash: null, friends: [], pending: [] }; this.dirty(); }
    else if (name && u.name !== name) { u.name = name; this.dirty(); }
    return u;
  }
  areFriends(a, b) { const u = this.users[a]; return !!u && u.friends.includes(b); }
  /** returns 'sent' | 'accepted' | 'already' | 'unknown' | 'self' */
  request(from, to) {
    if (from === to) return 'self';
    const a = this.users[from], b = this.users[to];
    if (!a || !b) return 'unknown';
    if (a.friends.includes(to)) return 'already';
    const i = a.pending.indexOf(to);           // they already asked me -> accept
    if (i >= 0) { this.accept(from, to); return 'accepted'; }
    if (!b.pending.includes(from)) b.pending.push(from);
    this.dirty();
    return 'sent';
  }
  accept(me, other) {
    const a = this.users[me], b = this.users[other];
    if (!a || !b) return false;
    const i = a.pending.indexOf(other);
    if (i < 0) return false;
    a.pending.splice(i, 1);
    if (!a.friends.includes(other)) a.friends.push(other);
    if (!b.friends.includes(me)) b.friends.push(me);
    const j = b.pending.indexOf(me); if (j >= 0) b.pending.splice(j, 1);
    this.dirty();
    return true;
  }
  decline(me, other) {
    const a = this.users[me]; if (!a) return false;
    const i = a.pending.indexOf(other); if (i < 0) return false;
    a.pending.splice(i, 1); this.dirty(); return true;
  }
  remove(me, other) {
    const a = this.users[me], b = this.users[other];
    if (!a) return false;
    let ch = false;
    let i = a.friends.indexOf(other); if (i >= 0) { a.friends.splice(i, 1); ch = true; }
    if (b) { i = b.friends.indexOf(me); if (i >= 0) { b.friends.splice(i, 1); ch = true; } }
    if (ch) this.dirty();
    return ch;
  }
  dirty() {
    if (!this.file || this.timer) return;
    this.timer = setTimeout(() => { this.timer = null; this.flush(); }, 250);
    if (this.timer.unref) this.timer.unref();
  }
  flush() {
    if (!this.file) return;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    try {
      fs.mkdirSync(this.dir, { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify({ users: this.users }));
      fs.renameSync(tmp, this.file);
    } catch (e) { console.error('friends save failed', e.message); }
  }
}
module.exports = { FriendStore };
