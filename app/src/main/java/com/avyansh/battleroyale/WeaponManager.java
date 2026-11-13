package com.avyansh.battleroyale;

/** Three-slot inventory (primary, primary, sidearm - any weapon fits any slot) with a shared ammo pool per ammo type. */
public class WeaponManager {
    public static final int SLOTS = 3;
    private final Weapon[] slots = new Weapon[SLOTS];
    private final int[] pool = new int[Weapon.AMMO_NAMES.length];
    private int current = 0;
    private float cooldown, reloadTimer, recoilKick;
    private boolean reloading, triggerWasDown;

    public Weapon current() { return slots[current]; }
    public Weapon slot(int i) { return i >= 0 && i < SLOTS ? slots[i] : null; }
    public int currentIndex() { return current; }
    public boolean hasWeapon() { return current() != null; }
    public boolean isReloading() { return reloading; }
    public float reloadProgress() { Weapon w = current(); return !reloading || w == null ? 0f : 1f - Math.max(reloadTimer, 0f) / w.reloadTime; }

    // ------------------------------------------------------------------ ammo pool
    public int reserveOf(Weapon w) { return w == null ? 0 : pool[w.ammoType]; }
    public int ammoOfType(int t) { return t >= 0 && t < pool.length ? pool[t] : 0; }
    public void addAmmo(int type, int n) { if (type >= 0 && type < pool.length) pool[type] = Math.min(999, pool[type] + n); }

    // ------------------------------------------------------------------ pickup / drop
    public boolean pickup(int type) { return pickup(Weapon.create(type), false); }

    /** Adds to an empty slot (preferring the current one); if all slots are full the caller must request a swap. */
    public boolean pickup(Weapon incoming, boolean replaceCurrent) {
        if (incoming == null) return false;
        int at = -1;
        if (slots[current] == null) at = current;
        else for (int i = 0; i < SLOTS; i++) if (slots[i] == null) { at = i; break; }
        if (at < 0 && replaceCurrent) at = current;
        if (at < 0) return false;
        slots[at] = incoming; current = at; reloading = false; cooldown = 0.2f; triggerWasDown = false; return true;
    }

    /** Removes the selected weapon without resetting its magazine. */
    public Weapon dropCurrent() {
        Weapon old = slots[current];
        if (old == null) return null;
        slots[current] = null; reloading = false; reloadTimer = 0f; cooldown = 0.15f; triggerWasDown = false;
        for (int i = 1; i <= SLOTS; i++) { int k = (current + i) % SLOTS; if (slots[k] != null) { current = k; break; } }
        return old;
    }

    /** Replaces the selected slot and returns the weapon that was there. */
    public Weapon swapCurrent(Weapon incoming) {
        if (incoming == null) return null;
        Weapon old = slots[current]; slots[current] = incoming; reloading = false; reloadTimer = 0f; cooldown = 0.2f; triggerWasDown = false; return old;
    }
    public Weapon replaceCurrent(int type) { Weapon old = slots[current]; slots[current] = Weapon.create(type); reloading = false; return old; }
    public int slotCount() { int n = 0; for (Weapon w : slots) if (w != null) n++; return n; }

    public void resetForMatch() {
        for (int i = 0; i < SLOTS; i++) slots[i] = null;
        for (int i = 0; i < pool.length; i++) pool[i] = 0;
        current = 0; reloading = false; cooldown = 0f; triggerWasDown = false;
    }
    public void refillAll() { for (Weapon w : slots) if (w != null) w.refill(); reloading = false; cooldown = 0f; }

    // ------------------------------------------------------------------ per frame
    public void update(float dt) { if (cooldown > 0) cooldown -= dt; if (reloading) { reloadTimer -= dt; if (reloadTimer <= 0) finishReload(); } }

    public void switchNext() {
        for (int i = 1; i < SLOTS; i++) {
            int k = (current + i) % SLOTS;
            if (slots[k] != null) { current = k; reloading = false; cooldown = 0.2f; return; }
        }
    }

    public void reload() {
        Weapon w = current();
        if (w == null || reloading || w.ammo >= w.magSize || pool[w.ammoType] <= 0) return;
        reloading = true; reloadTimer = w.reloadTime;
    }

    private void finishReload() {
        Weapon w = current();
        if (w != null) {
            int n = Math.min(w.magSize - w.ammo, pool[w.ammoType]);
            w.ammo += n; pool[w.ammoType] -= n;
        }
        reloading = false;
    }

    public boolean tryFire(boolean down) {
        Weapon w = current();
        boolean pressed = down && !triggerWasDown;
        triggerWasDown = down;
        if (w == null || !down || (!w.auto && !pressed) || reloading || cooldown > 0) return false;
        if (w.ammo <= 0) { reload(); return false; }
        w.ammo--; cooldown = w.interval; recoilKick += w.recoil;
        return true;
    }

    /** Online: authoritative inventory from the server. w = weapon type per slot (-1 empty), mags[type] = rounds (-1 unknown). */
    public void applyServer(int[] w, int cur, int[] mags, int[] ammo) {
        int curSlot = -1;
        for (int i = 0; i < SLOTS; i++) {
            int t = i < w.length ? w[i] : -1;
            if (t < 0 || t >= Weapon.COUNT) { slots[i] = null; continue; }
            if (slots[i] == null || slots[i].type != t) { slots[i] = Weapon.create(t); reloading = false; }
            if (mags[t] >= 0) slots[i].ammo = mags[t];
            if (t == cur) curSlot = i;
        }
        for (int i = 0; i < pool.length && i < ammo.length; i++) pool[i] = ammo[i];
        if (curSlot >= 0) current = curSlot;
        else if (slots[current] == null) for (int i = 0; i < SLOTS; i++) if (slots[i] != null) { current = i; break; }
    }

    /** Online: server confirmed a reload / ammo change for the current weapon. */
    public void applyServerAmmo(int mag, int reserve) {
        Weapon w = current();
        if (w == null) return;
        w.ammo = mag; pool[w.ammoType] = reserve; reloading = false;
    }

    public float takeRecoil() { float r = recoilKick; recoilKick = 0; return r; }
}
