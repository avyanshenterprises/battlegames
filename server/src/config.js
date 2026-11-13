'use strict';
const path = require('path');
const env = process.env;
function n(name, def) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const v = Number(raw);
  return Number.isFinite(v) ? v : def;
}
/** All tunables. Every value can be overridden by environment variable or by constructor options (tests). */
module.exports = function loadConfig(overrides) {
  return Object.assign({
    port: n('TCP_PORT', 7777),
    httpPort: n('PORT', 8080),
    host: env.HOST || '0.0.0.0',
    tickRate: n('TICK_RATE', 20),
    snapRate: n('SNAP_RATE', 15),
    brFillSeconds: n('BR_FILL_SECONDS', 30),
    csWaitSeconds: n('CS_WAIT_SECONDS', 45),
    brMaxPlayers: n('BR_MAX_PLAYERS', 50),
    graceSeconds: n('GRACE_SECONDS', 60),
    planeSeconds: n('PLANE_SECONDS', 0),      // 0 = derive from planeSpeed
    planeSpeed: n('PLANE_SPEED', 85),         // m/s
    fallSeconds: n('FALL_SECONDS', 20),       // no-zone grace after the plane leaves the map
    zoneTimeScale: n('ZONE_TIME_SCALE', 1),   // 0.1 = ten times faster zone
    lootPerPlayer: n('LOOT_PER_PLAYER', 6),
    mapHalf: n('MAP_HALF', 1536),
    mmIntervalMs: n('MM_INTERVAL_MS', 500),
    dataDir: env.DATA_DIR !== undefined ? env.DATA_DIR : path.join(__dirname, '..', 'data'),
    rateBurst: n('RATE_BURST', 240),
    rateRefill: n('RATE_REFILL', 120),
    rateKick: n('RATE_KICK', 400),            // dropped messages within one second before the connection is closed
    maxConnections: n('MAX_CONNECTIONS', 2000),
    helloTimeoutMs: n('HELLO_TIMEOUT_MS', 10000),
    maxLine: 16 * 1024,
    allowLegacyAuth: env.ALLOW_LEGACY_AUTH === '1',
    log: env.QUIET === '1' ? false : true
  }, overrides || {});
};
