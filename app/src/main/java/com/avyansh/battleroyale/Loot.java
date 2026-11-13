package com.avyansh.battleroyale;

/**
 * A world pickup. Item ids below WEAPON_BASE are gear/consumables, WEAPON_BASE + weaponId is a gun.
 * Weapon drops retain their live magazine when moved between inventory and ground.
 */
public class Loot {
    // healing
    public static final int BANDAGE = 0, FIRST_AID = 1, MEDKIT = 2, PAINKILLER = 3, ENERGY_DRINK = 4;
    // body armour, helmets, backpacks (levels 1-3)
    public static final int VEST_1 = 5, VEST_2 = 6, VEST_3 = 7, HELMET_1 = 8, HELMET_2 = 9, HELMET_3 = 10,
        BACKPACK_1 = 11, BACKPACK_2 = 12, BACKPACK_3 = 13;
    // ammo (one id per ammo type)
    public static final int AMMO_BASE = 14;   // 14..19 = Weapon.AMMO_556 .. AMMO_300
    public static final int WEAPON_BASE = 100;
    public static final int ITEM_COUNT = 20;

    public final int type;
    public final float x, y, z;
    public float spawnTime = 0f;
    public boolean taken = false;
    /** Dedicated-server loot id (-1 = local loot). */
    public int serverId = -1;
    public final Weapon weaponItem;

    public Loot(int type, float x, float y, float z) { this(type, x, y, z, null); }
    public Loot(int type, float x, float y, float z, Weapon item) { this.type = type; this.x = x; this.y = y; this.z = z; this.weaponItem = item; }

    public boolean isWeapon() { return type >= WEAPON_BASE; }
    public int weaponType() { return type - WEAPON_BASE; }
    public boolean isAmmo() { return type >= AMMO_BASE && type < AMMO_BASE + 6; }
    public int ammoType() { return type - AMMO_BASE; }
    public boolean isHeal() { return type >= BANDAGE && type <= ENERGY_DRINK; }
    public boolean isVest() { return type >= VEST_1 && type <= VEST_3; }
    public boolean isHelmet() { return type >= HELMET_1 && type <= HELMET_3; }
    public boolean isBackpack() { return type >= BACKPACK_1 && type <= BACKPACK_3; }
    /** 1..3 for vest/helmet/backpack, else 0. */
    public int level() {
        if (isVest()) return type - VEST_1 + 1;
        if (isHelmet()) return type - HELMET_1 + 1;
        if (isBackpack()) return type - BACKPACK_1 + 1;
        return 0;
    }
}
