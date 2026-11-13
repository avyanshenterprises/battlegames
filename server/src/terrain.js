'use strict';
/**
 * Port of TerrainGenerator.heightAt() (client) so the server knows the real ground height for bots,
 * spawn placement and loot. Same seed => same terrain (double precision here vs float in Java: differences are millimetres).
 */
const ISLAND_RADIUS = 1250;
const PLAY_RADIUS = ISLAND_RADIUS + 150;
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const lerp = (a, b, t) => a + (b - a) * t;
const smooth = (e0, e1, x) => { const t = clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); };
const ROADS = [[90, 60, -380, -260], [90, 60, 420, 380], [90, 60, -250, 330]];
const SITES = [[90, 60, 110], [-380, -260, 170], [420, 380, 150], [-250, 330, 130]];

class Terrain {
  constructor(seed) {
    this.seed = seed | 0;
    this.sites = SITES.map((s) => ({ x: s[0], z: s[1], radius: s[2], h: 0 }));
    for (const s of this.sites) s.h = clamp(this.rawHeight(s.x, s.z), 3.6, 14);
  }
  hash(x, z) {
    let h = (Math.imul(x, 374761393) + Math.imul(z, 668265263) + Math.imul(this.seed, 1274126177)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h ^= (h >>> 16);
    return (h & 0xFFFF) / 65535;
  }
  noise2(x, z) {
    const xi = Math.floor(x), zi = Math.floor(z);
    const fx = x - xi, fz = z - zi;
    const u = fx * fx * fx * (fx * (fx * 6 - 15) + 10);
    const v = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
    const a = this.hash(xi, zi), b = this.hash(xi + 1, zi), c = this.hash(xi, zi + 1), d = this.hash(xi + 1, zi + 1);
    return lerp(lerp(a, b, u), lerp(c, d, u), v);
  }
  fbm(x, z, oct) {
    let sum = 0, amp = 0.5, f = 1, norm = 0;
    for (let i = 0; i < oct; i++) { sum += this.noise2(x * f, z * f) * amp; norm += amp; amp *= 0.5; f *= 2.03; }
    return sum / norm;
  }
  siteMask(x, z) {
    let m = 0;
    for (const s of this.sites) {
      const d = Math.hypot(x - s.x, z - s.z);
      const v = 1 - smooth(s.radius * 0.75, s.radius * 1.35, d);
      if (v > m) m = v;
    }
    return m;
  }
  rawHeight(x, z) {
    const d = Math.hypot(x, z);
    const warp = this.noise2(x * 0.0013 + 17.3, z * 0.0013 - 9.1);
    const r = d / ISLAND_RADIUS * (1 + 0.35 * (warp - 0.5));
    const mask = 1 - smooth(0.60, 0.98, r);
    const far = smooth(150, 550, d);
    const base = this.fbm(x * 0.004, z * 0.004, 5);
    const hills = Math.max(0, base - 0.3) * 55 * (0.35 + 0.65 * far);
    const mn = this.fbm(x * 0.0012 + 40, z * 0.0012 + 40, 4);
    const mt = smooth(0.52, 0.72, mn) * smooth(500, 900, d);
    const ridge = 1 - Math.abs(2 * this.fbm(x * 0.006 + 5, z * 0.006 + 5, 4) - 1);
    const mount = mt * (40 + ridge * ridge * 120);
    const land = 3.2 + hills + mount + (base - 0.5) * 3;
    let h = lerp(-24, land, mask);
    const inland = smooth(0.5, 0.8, mask) * (1 - this.siteMask(x, z));
    const ld = Math.hypot(x - 230, z + 420) / 110;
    h = lerp(h, -5, (1 - smooth(0.55, 1.15, ld)) * inland);
    const rx = -520 + 140 * Math.sin(z * 0.006) + 60 * Math.sin(z * 0.017);
    h = lerp(h, -2.5, (1 - smooth(8, 32, Math.abs(x - rx))) * inland);
    return h;
  }
  heightNoRoad(x, z) {
    let h = this.rawHeight(x, z);
    for (const s of this.sites) {
      const d = Math.hypot(x - s.x, z - s.z);
      if (d < s.radius * 1.15) h = lerp(h, s.h, 1 - smooth(s.radius * 0.65, s.radius * 1.15, d));
    }
    return h;
  }
  nearestRoad(x, z, out) {
    let best = 1e18;
    for (const r of ROADS) {
      const abx = r[2] - r[0], abz = r[3] - r[1];
      let t = ((x - r[0]) * abx + (z - r[1]) * abz) / (abx * abx + abz * abz);
      t = clamp(t, 0, 1);
      const px = r[0] + abx * t, pz = r[1] + abz * t;
      const d2 = (x - px) * (x - px) + (z - pz) * (z - pz);
      if (d2 < best) { best = d2; out[0] = px; out[1] = pz; }
    }
    return Math.sqrt(best);
  }
  heightAt(x, z) {
    let h = this.heightNoRoad(x, z);
    const np = [0, 0];
    const d = this.nearestRoad(x, z, np);
    if (d < 16) {
      const t = 1 - smooth(6, 16, d);
      h = lerp(h, Math.max(1.4, this.heightNoRoad(np[0], np[1])), t);
    }
    return h;
  }
}
module.exports = { Terrain, PLAY_RADIUS, ISLAND_RADIUS };
