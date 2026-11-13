package com.avyansh.battleroyale;

/** Small helper: a player can only be in one place (room or queue) at a time. */
final class ServerRoomGuard {
	private ServerRoomGuard() {}
	static void leaveRoomIfAny() { if (ServerNet.room != null) ServerNet.leaveRoom(); }
}
