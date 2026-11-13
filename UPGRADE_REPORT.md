# Avyansh Battle Royale – upgrade pass report

## Project architecture discovered
- Android native Java application, package `com.avyansh.battleroyale`.
- Custom OpenGL ES 3 renderer/gameplay engine with GLB asset loading and Java game simulation. This is **not** a Unity or Unreal project.
- Node.js authoritative multiplayer backend with raw TCP and WebSocket (`/ws`) transports.
- Existing game already contains player movement, camera, crouch/run/jump, animation mixer, weapon firing, server-side hit validation, rooms, matchmaking, friends/invites, positional audio and directional gunfire HUD.

## Changes made in this pass

### App entry and session gate
- Moved the Android launcher intent filter from `LobbyActivity` to `MainActivity`.
- Added the Google OAuth return deep link to `MainActivity`.
- Removed unused Firebase SDK initialization and Firebase manifest providers; the existing legacy Firebase Realtime Database path uses the project's REST client.
- Kept the existing account-session token storage and server-authentication gate.

### Google account authentication
- Added a Google-hosted account-selection flow launched in the system browser (the user is never asked for a Google password).
- Added server endpoints `GET /auth/google/start` and `GET /auth/google/callback`.
- The backend exchanges the OAuth authorization code using server-only client credentials, verifies the Google ID token RS256 signature against Google's published X.509 signing keys, checks issuer/audience/expiry/verified email, and keys accounts by stable Google `sub`.
- Added one-use, two-minute app login codes bound to an Android-generated PKCE verifier.
- Added automatic account creation and linking for a previously registered matching verified email.
- The backend never sends Google access/refresh tokens to the Android app.
- Existing username/password authentication remains available.

### Gunfire direction and audio
- Raised the HUD directional gunfire indicator limit from 150 m to 200 m.
- Capped every weapon-category gunfire sound range at 200 m (some categories retain shorter ranges), with the existing distance attenuation, stereo pan, occlusion and weapon-specific sound mapping.
- The server's existing 250 m shot-event broadcast radius remains sufficient for clients to apply their 200 m local cutoff.

### Lobby and friends
- Replaced the lobby's decorative Battle Island map card with a Friends entry card.
- Connected the card and top Friends action to the existing dedicated friends screen.
- Added backend player search by username substring or UID prefix (up to 20 results), plus Android result rows and Add actions.
- Preserved existing friend requests, accept/decline, remove, online/in-match status and room invites.
- Added the lobby's dedicated-server message pump so friends/economy messages continue to update while the lobby is visible.

### Navigation
- Removed the content entrance animation from the shared screen scaffold.
- Made the lobby friends drawer open/close immediately.

### Economy
- Added a persistent server-side economy ledger in `server/data/economy.json` (created on first use).
- Added server-authoritative coin/diamond balances, daily reward (250 coins + 2 diamonds every 24 hours), daily mission (play one completed server match, reward 150 coins), and match rewards based only on server-computed placement/kills.
- Match rewards are idempotent by match ID; client-provided balances/reward amounts are never accepted.
- Added a Wallet & Rewards screen and lobby balance chips.
- Real-money billing is not enabled. Purchases are deliberately rejected until the project has a real cosmetic catalog and corresponding game assets/entitlements.

### Dependency cleanup
- Removed unused OkHttp/Okio and Firebase BOM dependencies from the Android app build.
- The new Google OAuth hand-off and WebSocket auth exchange use Android/Java platform APIs and the existing native WebSocket client.

## Tests performed
- Node.js syntax check: passed.
- Local RFC 6455 `/ws` upgrade test (101 + accept hash, invalid path 404): passed.
- Backend test suite: **33 passed, 0 failed**.
- Pure-Java client logic/live-server suite: **81 passed, 0 failed**.
- Android XML resource parse: **95 XML files parsed successfully**.
- Manifest inspection: `MainActivity` is the sole launcher activity.

## Not verified / remaining requirements

1. **Android APK/device build was not performed.** The uploaded archive has no `gradle-wrapper.jar`, and this environment has no usable Gradle installation or Android SDK. Codeware Studio device compilation was not available here. No APK is included.
2. **Google OAuth needs deployment credentials.** Configure `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `GOOGLE_REDIRECT_URI` on the Node server. Register the exact HTTPS callback URL in Google Cloud Console. This implementation uses Google's hosted browser account selector, not Credential Manager's native Android bottom-sheet picker.
3. **Store purchases are disabled.** No real cosmetic catalog/assets or entitlement-to-rendering pipeline were present. The server rejects purchase requests instead of pretending a purchase worked. No Play Billing or real-money payment is wired.
4. **Friend profile pictures are not yet sourced.** Existing friend rows still use the project's generic person avatar; presence, username, UID, search and requests are live.
5. **Daily mission scope is intentionally small:** one completed match per UTC day. More missions require agreed definitions and rewards.
6. **Prone movement/animation was not added.** The supplied client player model and animation assets expose crouch/run/jump/fall and strafing support, but no prone player animation asset or complete prone state in the Android Player simulation. The server protocol has a prone stance slot, but enabling it only server-side would desynchronize the client.
7. **Multiplayer movement and animation systems were preserved, not rewritten.** The supplied project already had camera-relative forward/strafe input, acceleration, jump/collision handling, crouch/run, animation mixing and server movement validation. A full new animation pass needs additional matching animation clips and device testing.
8. **Google OAuth, live WSS deployment, audio on physical hardware, and multiplayer device-to-device play still require deployment credentials and real Android devices.** Passing off-device tests is not a substitute for those tests.

## Server setup additions
Set:
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GOOGLE_REDIRECT_URI=https://YOUR_HOST/auth/google/callback`

Keep `GOOGLE_CLIENT_SECRET` on the backend only. Do not put it in Android resources or the APK.

The economy file is stored under `DATA_DIR/economy.json`. Back up the complete `DATA_DIR` directory, including existing account/friend data.
