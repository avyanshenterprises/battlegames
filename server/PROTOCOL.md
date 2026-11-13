# Avyansh BR wire protocol v1

Transport: TCP, UTF-8, one JSON object per line ("\n" terminated). TCP_NODELAY on. Default port 7777.
Every message has `"t"` (type). Unknown types are ignored. Max line 16 KB. Clients are never trusted:
the server validates every field (type, range, finite number) and drops bad messages.

## Session
C>S `hello` {uid, name, token?, v:1}  -> S>C `welcome` {uid, token, serverTime, resume:{matchId}|null}
   - token is issued on first hello and must be presented on later hellos for the same uid (else `error` code "bad_token").
   - If uid is inside a running match (disconnected < 60s) the session is re-attached => reconnection; server sends `match_start` (with `resume:true`) + current state.
C>S `ping` {ts}  -> S>C `pong` {ts, serverTime}

## Account authentication
- `auth_register` {identity,password} and `auth_login` {identity,password} (passwords are TLS-protected; server stores scrypt hashes)
- `auth_resume` {token}
- Google OAuth uses HTTPS `/auth/google/start?code_challenge=...` -> Google-hosted account selection -> HTTPS `/auth/google/callback` -> app deep link with a short-lived one-use code -> `auth_google_exchange` {code,codeVerifier}. The server validates the Google ID token and PKCE verifier before returning `auth_ok`.
- `auth_logout` {uid,token}

## Economy
- `economy_get` -> `economy_state` {coins,diamonds,dailyAvailable,mission,transactions}
- `economy_daily_claim` -> daily reward (250 coins + 2 diamonds; 24-hour server-side cooldown)
- `economy_mission_claim` -> 150 coins after one server-recorded match per UTC day
- Match-end rewards are calculated from server-computed placement/kills and deduplicated by match ID.
- `economy_purchase` is rejected until a real server catalog is configured; real-money billing is not enabled.

## Friends
C>S `friend_add` {uid}            S>C `friend_request` {from, name}   (pending until accepted)
C>S `friend_accept` {uid} / `friend_decline` {uid} / `friend_remove` {uid}
C>S `friends_list`                -> S>C `friends` {friends:[{uid,name,online,room|null,inMatch}], pending:[{uid,name}]}
C>S `friend_search` {query}      -> S>C `friend_search_results` {results:[{uid,name}]} (max 20, username substring / UID prefix)
C>S `invite` {uid}                -> S>C (to target) `invite` {from, name, room}   (inviter must be in a room; only friends)
Friends persist in server/data/friends.json.

## Rooms (custom / private / public)
C>S `room_create` {mode:"br"|"cs"|"custom", teamSize:1..4 (custom 1..8), maxPlayers, priv:bool, password?:string, map:string}
C>S `room_join` {room, password?}
C>S `room_leave`  `room_ready` {ready:bool}  `room_team` {team:int}  `room_config` {mode?,teamSize?,map?,priv?,password?,maxPlayers?} (host only)
C>S `room_kick` {uid} (host)  `room_start` (host; all non-host members must be ready; both teams non-empty for cs/custom)
C>S `room_list` -> S>C `rooms` {rooms:[{room,mode,teamSize,map,players,maxPlayers,hasPassword}]} (public only)
S>C `room_state` {room, mode, teamSize, maxPlayers, map, priv, hasPassword, host, members:[{uid,name,team,ready,host}]}
S>C `room_closed` {reason}
Room id = 6 chars from ABCDEFGHJKLMNPQRSTUVWXYZ23456789. Modes: `br` (up to 50 on a room start; bots fill to 50), `cs` (clash squad: 2 teams of teamSize, no bots unless a team is short, 4 rounds best-of-7 is out of scope -> single round, win by elimination), `custom` (teams of teamSize, no zone-bot fill unless host sets `fillBots`).

## Matchmaking (public queue)
C>S `mm_join` {mode:"br"|"cs", teamSize:1|2|4} -> S>C `mm_status` {queued:true, mode, teamSize, waiting:n, needed:n, eta:secs}
C>S `mm_cancel` -> `mm_status` {queued:false}
BR: the queue starts a match when it has 50 real players, or when the oldest ticket has waited >= BR_FILL_SECONDS (default 30s, env) with >= 1 real player; then bots fill to 50. Squads (parties of a room) are queued together via `mm_join` sent by a room host while in a room (all members are enqueued as one party; they land on the same team).
CS: needs two full teams (2*teamSize real players); after CS_WAIT_SECONDS (45) fill the missing seats with bots (flagged).

## Match
S>C `match_start` {matchId, mode, map, seed:int, tickRate:20, snapRate:15, resume?:bool,
   you:{id, team, slot, x,y,z}, players:[{id,name,team,slot,isBot:bool}], zone:{...}, loot:[{id,type,kind,x,y,z}],
   counts:{players, realPlayers, bots, teams}}
 - `isBot` is ALWAYS set honestly. Real players are never described as bots and bots never as players. `counts.realPlayers` and `counts.bots` are reported separately.
 - World is a square [-half, +half] (half = 1536 m, both x and z). Flight phase: plane travels a line across the map at y=300; `phase` in zone/snapshot tells `plane`->`fall`->`play`. Server sets `planeStart:{x,z}`, `planeDir:{x,z}`, `planeSpeed`. Players send `jump` {} to leave plane (auto-jump at line end).
C>S `input` {seq, x,y,z, yaw, pitch, vx, vz, st:0 stand|1 crouch|2 prone|3 swim|4 fall|5 parachute, fl:bitmask(1 sprint,2 aim,4 fire,8 reload,16 onGround), wp:int}
   Client-reported kinematics; server validates speed (<= max by stance * 1.25 + tolerance), vertical speed, teleport distance, and map bounds, keeps its own accepted position, and sends `correct` {seq,x,y,z} when it rejects (client snaps/reconciles = client-side prediction + server reconciliation). Terrain height is trusted from the client within a plausible band, since the terrain generator runs on the client (documented limitation).
C>S `fire` {seq, ox,oy,oz, dx,dy,dz, wp, ct}   ct = client's estimate of server time when it fired (for lag compensation, clamped to <= 250 ms in the past).
   Server checks: alive, weapon equipped & owned, fire-rate (interval * 0.9), mag ammo > 0, origin within 2.5 m of server-known position, direction normalised, then ray-vs-capsule test against positions REWOUND to ct (position history ring buffer, 1 s). Shotguns: `pellets` rays with spread from weapon table (server RNG). Damage: weapon damage * headshot 2.0 * falloff (linear from 60% range to 100% range down to 70%). Friendly fire off (cs/br squads) except self. Hit -> S>C `hit` to shooter {victim, dmg, head, kill} and S>C `damaged` to victim {attacker, dmg, dir:yawDegOfAttacker}. Also S>C `shot` broadcast to players within 250 m of the shooter: {id, x,y,z, wp} (used for positional audio + the client-side 200 m direction indicator; the client decides what to show, server never reveals beyond 250 m).
C>S `reload` -> server enforces reload time; S>C `ammo` {wp, mag, reserve, reloading}
C>S `switch` {wp}  C>S `pickup` {lootId}  (must be within 3.5 m) -> S>C `loot_taken` {lootId, by} broadcast, `inventory` {weapons:[...], ammo:{...}, heals:n, armor:n, helmet:n} to owner
C>S `drop` {}  (drops the equipped gun as loot; server answers `loot_add` + `inventory`)
C>S `heal` {} (uses a medkit, 4.5 s, cancelled by damage/move beyond walking, +55 hp up to 100) -> `healing` {t}
C>S `melee` {dx,dz} (fists/machete, 2 m reach, 35 dmg, 0.6 s cooldown)
C>S `revive` {uid, start:bool} (teammate knocked, within 3 m, 5 s)  S>C `revive_progress`
C>S `marker` {x,z,kind:0 ping|1 enemy|2 loot|3 go} -> relayed to squad as `marker` {by,x,z,kind}
C>S `voice` {seq, data:base64 opus/pcm frame} -> relayed ONLY to alive squad mates as `voice` {from, seq, data} (voice chat transport; capture/codec on the client)
S>C `snap` {tick, t(server ms), phase, alive, aliveTeams, zone?:(only when changed), players:[[id,x,y,z,yaw,pitch,hp,st,fl,wp]]} 
   Interest management: players beyond 400 m are sent at 1/3 rate; squad mates always full rate and always included.
S>C `kill` {killer, killerName, victim, victimName, wp, head, assist?}   (kill feed)  `knock` {victim, by}   `revived` {uid}
S>C `zone` {phase, cx,cz,r, nx,nz,nr, tShrinkStart, tShrinkEnd, dps}   Blue zone: 6 phases, circle starts at r=1400 (play radius), shrinks to radii [900,550,300,140,60,0], wait [90,60,50,40,30,20]s and shrink [60,45,35,30,25,20]s per phase (x ZONE_TIME_SCALE), damage per sec [1,2,4,7,10,15] applied by the server to anyone outside.
S>C `match_end` {winnerTeam, winners:[ids], results:[{id,name,team,isBot,placement,kills,damage,surviveSec}], mode}
  - results are computed only by the server. Clients that disconnect permanently are marked dead after grace.
C>S `leave_match`  (forfeit; player is eliminated)
S>C `error` {code,msg}

## Bots
Server-side bots: names from a list, `isBot:true`. Bot AI: lootless-lite (spawn with a random weapon), move toward zone centre / random targets, fire at nearest visible enemy within weapon range with human-like inaccuracy and reaction time. Bots use the same damage pipeline. Bot count never exceeds `maxPlayers - realPlayers`.

## Tick
Server fixed tick 20 Hz; snapshots at 15 Hz (every tick, with 3/4 skip); position history 20 entries per player for lag compensation.

## Implementation notes (v1 as built)
* Bots: id `bot_N`, name prefixed `[BOT] `, `isBot:true` everywhere (roster, kill feed `killerIsBot/victimIsBot`, results). Bots form their own teams, never join a real player's team.
* Extra messages: `loot_add` {loot} (dropped weapons), `healed` {hp}, `healing` {t4|cancel}, `inventory` {inv:{weapons[2],cur,mags,ammo[6],heals,armor,helmet}}, `ammo`, `correct`, `revive_progress`.
* `snap` row layout: [id,x,y,z,yaw,pitch,hp,stCode,fl,wp]; stCode 0..5 stances, 6 knocked, 7 in plane, 8 dead. Bots' y is the true ground height. Also top-level: tick, ts, phase, alive, aliveTeams, hp, armor, ack (last accepted input seq), zr/zcx/zcz.
* Weapon ids 0..25 = Weapon.java, 26 = machete (always owned).
* `worldSeed` in match_start must equal GameSettings.WORLD_SEED (20261104).
* Rooms: `cs` and `custom` are two-team modes (maxPlayers = 2*teamSize); `fillBots` (default true) tops them/BR up with flagged bots on start.
* Not implemented: Clash Squad multi-round scoring (single elimination round), voice codec (relay only).
