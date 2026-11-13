package com.avyansh.battleroyale;

/** Screen 3: Clash Squad matchmaking (1v1, 2v2, 4v4). */
public class CsMatchmakingActivity extends MatchmakingActivity {
	@Override protected String screenTitle() { return "Clash Squad"; }
	@Override protected String mode() { return "cs"; }
	@Override protected int[] teamSizes() { return new int[] { 1, 2, 4 }; }
	@Override protected String blurb() { return "Fast team deathmatch: eliminate the other team before they eliminate yours."; }
}
