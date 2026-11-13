'use strict';
/** Mirrors Weapon.java DEFS (ids 0..25) and adds id 26 = melee (always owned). */
const CAT = { AR: 0, SMG: 1, SHOTGUN: 2, SNIPER: 3, DMR: 4, LMG: 5, PISTOL: 6, MELEE: 7 };
const AMMO_BOX = [30, 30, 30, 10, 30, 10];
const AMMO_TYPES = 6;
const MELEE = 26;
// name, cat, ammoType, damage, interval(s), mag, reload(s), spread, range(m), pellets, auto, weight
const ROWS = [
  ['M416', 0, 0, 22, 0.088, 30, 2.1, 0.012, 140, 1, true, 10],
  ['P92', 6, 2, 20, 0.170, 15, 1.4, 0.010, 80, 1, false, 6],
  ['S12K', 2, 3, 8, 0.350, 5, 2.4, 0.060, 40, 8, false, 3],
  ['AWM', 3, 5, 110, 2.000, 5, 3.4, 0.0010, 360, 1, false, 1],
  ['AKM', 0, 1, 30, 0.105, 30, 2.4, 0.015, 140, 1, true, 8],
  ['SCAR-L', 0, 0, 24, 0.095, 30, 2.2, 0.011, 145, 1, true, 7],
  ['Groza', 0, 1, 28, 0.080, 30, 2.3, 0.011, 145, 1, true, 2],
  ['Beryl M762', 0, 1, 29, 0.088, 30, 2.5, 0.016, 140, 1, true, 5],
  ['Micro UZI', 1, 2, 13, 0.050, 25, 1.9, 0.020, 70, 1, true, 7],
  ['UMP45', 1, 4, 19, 0.090, 25, 2.0, 0.014, 85, 1, true, 7],
  ['Vector', 1, 4, 16, 0.055, 19, 1.8, 0.016, 80, 1, true, 4],
  ['Tommy Gun', 1, 4, 18, 0.085, 30, 2.6, 0.018, 80, 1, true, 4],
  ['S686', 2, 3, 11, 0.280, 2, 2.4, 0.052, 40, 9, false, 5],
  ['S1897', 2, 3, 10, 0.900, 5, 2.8, 0.055, 42, 9, false, 4],
  ['Kar98k', 3, 1, 82, 1.700, 5, 3.0, 0.0015, 320, 1, false, 5],
  ['M24', 3, 1, 88, 1.800, 5, 3.2, 0.0012, 330, 1, false, 3],
  ['SKS', 4, 1, 38, 0.120, 10, 2.8, 0.006, 220, 1, false, 4],
  ['Mini 14', 4, 0, 34, 0.110, 20, 2.6, 0.006, 220, 1, false, 4],
  ['SLR', 4, 1, 44, 0.140, 10, 2.9, 0.006, 230, 1, false, 3],
  ['Mk14 EBR', 4, 1, 40, 0.095, 10, 3.0, 0.007, 230, 1, true, 1],
  ['M249', 5, 0, 20, 0.072, 100, 5.0, 0.020, 150, 1, true, 1],
  ['DP-28', 5, 1, 24, 0.100, 47, 4.2, 0.018, 150, 1, true, 2],
  ['P1911', 6, 4, 24, 0.200, 7, 1.5, 0.010, 80, 1, false, 5],
  ['R1895', 6, 1, 46, 0.850, 7, 2.6, 0.007, 95, 1, false, 3],
  ['R45', 6, 4, 40, 0.600, 6, 2.4, 0.008, 90, 1, false, 2],
  ['Desert Eagle', 6, 4, 50, 0.500, 7, 2.0, 0.009, 95, 1, false, 1],
  ['Machete', 7, -1, 35, 0.600, 0, 0, 0, 2, 1, false, 0]
];
const DEFS = ROWS.map((r, id) => ({
  id, name: r[0], cat: r[1], ammo: r[2], damage: r[3], interval: r[4], mag: r[5], reload: r[6],
  spread: r[7], range: r[8], pellets: r[9], auto: r[10], weight: r[11], reserve: r[5] * 2
}));
const COUNT = 26; // ids that are real guns
function randomGun(rng, filter) {
  let total = 0;
  for (let i = 0; i < COUNT; i++) if (!filter || filter(DEFS[i])) total += DEFS[i].weight;
  let roll = rng() * total;
  for (let i = 0; i < COUNT; i++) {
    if (filter && !filter(DEFS[i])) continue;
    roll -= DEFS[i].weight;
    if (roll < 0) return i;
  }
  return 0;
}
module.exports = { CAT, DEFS, COUNT, MELEE, AMMO_BOX, AMMO_TYPES, randomGun };
