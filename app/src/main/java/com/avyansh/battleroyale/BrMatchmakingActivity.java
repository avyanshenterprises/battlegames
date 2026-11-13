package com.avyansh.battleroyale;

/** Screen 2: Battle Royale matchmaking (solo / duo / squad, up to 50 players). */
public class BrMatchmakingActivity extends MatchmakingActivity {
	@Override protected String screenTitle() { return "Battle Royale"; }
	@Override protected String mode() { return "br"; }
	@Override protected int[] teamSizes() { return new int[] { 1, 2, 4 }; }
	@Override protected String blurb() { return "Drop onto the island, loot up and be the last player or squad standing as the blue zone closes in."; }
}
