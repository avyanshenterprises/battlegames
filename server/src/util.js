'use strict';
const crypto = require('crypto');

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
/** finite number within [min,max] or null */
function num(v, min, max) { return isNum(v) && v >= min && v <= max ? v : null; }
/** integer within [min,max] or null */
function int(v, min, max) { return Number.isInteger(v) && v >= min && v <= max ? v : null; }
function cleanName(s, fallback) {
  if (typeof s !== 'string') return fallback || 'Player';
  s = s.replace(/[\u0000-\u001f\u007f<>"\\]/g, '').trim();
  if (s.length > 14) s = s.slice(0, 14);
  return s.length ? s : (fallback || 'Player');
}
const UID_RE = /^[A-Za-z0-9_-]{3,32}$/;
const validUid = (u) => typeof u === 'string' && UID_RE.test(u);
const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
const randomToken = () => crypto.randomBytes(16).toString('hex');
const ROOM_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function randomRoomId(rand) {
  let s = '';
  for (let i = 0; i < 6; i++) s += ROOM_ALPHABET[Math.floor((rand ? rand() : Math.random()) * ROOM_ALPHABET.length)];
  return s;
}
const normYaw = (y) => ((y % 360) + 360) % 360;

function raySphere(ox, oy, oz, dx, dy, dz, cx, cy, cz, r) {
  const mx = ox - cx, my = oy - cy, mz = oz - cz;
  const b = mx * dx + my * dy + mz * dz;
  const c = mx * mx + my * my + mz * mz - r * r;
  if (c > 0 && b > 0) return -1;
  const disc = b * b - c;
  if (disc < 0) return -1;
  let t = -b - Math.sqrt(disc);
  if (t < 0) t = 0;
  return t;
}
/**
 * Ray (origin o, unit direction d) against a vertical capsule: axis x=cx,z=cz from y0 to y1, radius r.
 * Returns the distance t >= 0 of the first hit or -1.
 */
function rayCapsuleV(ox, oy, oz, dx, dy, dz, cx, cz, y0, y1, r) {
  let best = -1;
  const mx = ox - cx, mz = oz - cz;
  const a = dx * dx + dz * dz;
  if (a > 1e-12) {
    const b = mx * dx + mz * dz;
    const c = mx * mx + mz * mz - r * r;
    const disc = b * b - a * c;
    if (disc >= 0) {
      let t = (-b - Math.sqrt(disc)) / a;
      if (t < 0 && c <= 0) t = 0;
      if (t >= 0) {
        const y = oy + dy * t;
        if (y >= y0 && y <= y1) best = t;
      }
    }
  }
  let t = raySphere(ox, oy, oz, dx, dy, dz, cx, y0, cz, r);
  if (t >= 0 && (best < 0 || t < best)) best = t;
  if (y1 > y0) {
    t = raySphere(ox, oy, oz, dx, dy, dz, cx, y1, cz, r);
    if (t >= 0 && (best < 0 || t < best)) best = t;
  }
  return best;
}
module.exports = { mulberry32, isNum, num, int, cleanName, validUid, sha256, randomToken, randomRoomId, normYaw, rayCapsuleV, raySphere };
