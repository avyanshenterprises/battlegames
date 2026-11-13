'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EconomyStore } = require('../src/economy');

test('economy: daily reward is idempotent, mission rewards require server match progress, match payout cannot duplicate', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'avy-economy-'));
  try {
    const e = new EconomyStore(dir), uid = 'player-1', now = Date.UTC(2026, 10, 13, 12);
    let s = e.snapshot(uid, now);
    assert.equal(s.coins, 0); assert.equal(s.diamonds, 0); assert.equal(s.dailyAvailable, true);
    s = e.claimDaily(uid, now);
    assert.equal(s.coins, 250); assert.equal(s.diamonds, 2); assert.equal(s.dailyAvailable, false);
    assert.throws(() => e.claimDaily(uid, now + 1000), /daily_reward_already_claimed/);
    assert.throws(() => e.claimMission(uid, now), /mission_not_complete/);
    assert.equal(e.recordMatch(uid, 'match-1', { placement: 1, kills: 3 }, now + 5000), true);
    assert.equal(e.recordMatch(uid, 'match-1', { placement: 1, kills: 3 }, now + 5001), false);
    s = e.snapshot(uid, now + 6000);
    assert.equal(s.coins, 250 + 250 + 30);
    assert.equal(s.mission.progress, 1);
    s = e.claimMission(uid, now + 7000);
    assert.equal(s.coins, 680);
    assert.equal(s.mission.claimed, true);
    const reloaded = new EconomyStore(dir).snapshot(uid, now + 8000);
    assert.equal(reloaded.coins, 680);
    assert.equal(reloaded.diamonds, 2);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
