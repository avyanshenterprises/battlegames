'use strict';
const fs = require('fs');
const path = require('path');

/**
 * Server-authoritative soft-currency ledger. Every grant has an idempotency key,
 * balances are never accepted from clients, and writes are atomically replaced.
 */
class EconomyStore {
  constructor(dir) {
    this.file = path.join(dir || path.join(__dirname, '..', 'data'), 'economy.json');
    this.users = {};
    try { this.users = JSON.parse(fs.readFileSync(this.file, 'utf8')).users || {}; } catch (_) {}
  }
  dayKey(now) { return new Date(now).toISOString().slice(0, 10); }
  user(uid) {
    if (!this.users[uid]) this.users[uid] = {
      coins: 0, diamonds: 0, lastDailyClaimAt: 0, missionDay: '', missionMatches: 0,
      missionClaimed: false, owned: {}, ledger: []
    };
    const u = this.users[uid];
    if (!u.owned) u.owned = {};
    if (!u.ledger) u.ledger = [];
    return u;
  }
  snapshot(uid, now = Date.now()) {
    const u = this.user(uid), today = this.dayKey(now);
    if (u.missionDay !== today) { u.missionDay = today; u.missionMatches = 0; u.missionClaimed = false; this.save(); }
    return {
      t: 'economy_state', coins: u.coins, diamonds: u.diamonds,
      dailyAvailable: !u.lastDailyClaimAt || now - u.lastDailyClaimAt >= 24 * 60 * 60 * 1000,
      mission: { id: 'play_one_match', progress: Math.min(1, u.missionMatches), target: 1, claimed: u.missionClaimed, rewardCoins: 150 },
      owned: u.owned,
      transactions: u.ledger.slice(-20).reverse()
    };
  }
  transact(uid, currency, amount, reason, ref, now = Date.now()) {
    const u = this.user(uid);
    if (!['coins', 'diamonds'].includes(currency) || !Number.isSafeInteger(amount) || amount === 0) throw Error('invalid_transaction');
    if (ref && u.ledger.some(x => x.ref === ref)) return false;
    const next = u[currency] + amount;
    if (next < 0 || !Number.isSafeInteger(next)) throw Error('insufficient_funds');
    u[currency] = next;
    u.ledger.push({ id: require('crypto').randomBytes(8).toString('hex'), currency, amount, reason: String(reason).slice(0, 48), ref: ref || '', balance: next, at: now });
    if (u.ledger.length > 100) u.ledger.splice(0, u.ledger.length - 100);
    this.save();
    return true;
  }
  claimDaily(uid, now = Date.now()) {
    const u = this.user(uid);
    if (u.lastDailyClaimAt && now - u.lastDailyClaimAt < 24 * 60 * 60 * 1000) throw Error('daily_reward_already_claimed');
    u.lastDailyClaimAt = now;
    this.transact(uid, 'coins', 250, 'daily_login', 'daily:' + this.dayKey(now) + ':' + uid, now);
    this.transact(uid, 'diamonds', 2, 'daily_login', 'daily-diamonds:' + this.dayKey(now) + ':' + uid, now);
    this.save();
    return this.snapshot(uid, now);
  }
  claimMission(uid, now = Date.now()) {
    const u = this.user(uid), today = this.dayKey(now);
    if (u.missionDay !== today) { u.missionDay = today; u.missionMatches = 0; u.missionClaimed = false; }
    if (u.missionMatches < 1 || u.missionClaimed) throw Error('mission_not_complete');
    u.missionClaimed = true;
    this.transact(uid, 'coins', 150, 'mission_play_one_match', 'mission:play_one_match:' + today + ':' + uid, now);
    this.save();
    return this.snapshot(uid, now);
  }
  recordMatch(uid, matchId, result, now = Date.now()) {
    if (!uid || !matchId || !result) return false;
    const u = this.user(uid), ref = 'match:' + matchId;
    if (u.ledger.some(x => x.ref === ref)) return false;
    const place = Math.max(1, Number(result.placement) || 50);
    const kills = Math.max(0, Math.min(50, Number(result.kills) || 0));
    const placementBonus = place === 1 ? 250 : place <= 5 ? 120 : place <= 10 ? 60 : 25;
    const reward = placementBonus + kills * 10;
    this.transact(uid, 'coins', reward, 'match_reward', ref, now);
    const today = this.dayKey(now);
    if (u.missionDay !== today) { u.missionDay = today; u.missionMatches = 0; u.missionClaimed = false; }
    u.missionMatches = Math.min(1, u.missionMatches + 1);
    this.save();
    return true;
  }
  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ users: this.users }, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}
module.exports = { EconomyStore };
