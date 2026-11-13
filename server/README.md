# Avyansh BR – authoritative multiplayer server

Node.js 20+ authoritative server. It supports **both** transports:
- Raw TCP, newline-delimited JSON on `TCP_PORT` (default `7777`) for existing clients.
- WebSocket text frames on HTTP `PORT` (default `8080`), path `/ws`. HTTPS/WSS is terminated by the hosting provider/reverse proxy.

The Android client accepts either `host:7777`, `ws://host:8080/ws`, or `wss://your-service.example/ws`.
No npm dependencies are required.

## Run locally
    node src/server.js
    npm test
    npm run load

Health check: `http://localhost:8080/health`
WebSocket endpoint: `ws://localhost:8080/ws`
Raw TCP endpoint: `127.0.0.1:7777`

## Deploy to an HTTP/WebSocket host (example: Render Web Service)
1. Push this project to a Git repository.
2. Create a **Web Service** (not Static Site) and set the Root Directory to `server`.
3. Build Command: `npm install` (there are no runtime dependencies).
4. Start Command: `node src/server.js`.
5. The host provides `PORT`; the server binds its HTTP/WebSocket listener to that port and binds its private raw TCP listener to `TCP_PORT` (default 7777).
6. After deploy, open `https://YOUR-SERVICE.onrender.com/health`. It should return JSON with `ok:true`.
7. In the Android app, Settings -> Dedicated game server, enter:
   `wss://YOUR-SERVICE.onrender.com/ws`

**Free-host limitations:** free web services may sleep when idle, have CPU/RAM limits, restart, and do not guarantee continuous real-time match availability. A WebSocket connection may be interrupted when the instance sleeps/restarts. For a public 50-player production match, use a continuously running game server host with suitable CPU/network capacity. A Web Service's public WebSocket endpoint does not expose the raw TCP port.

## Deploy on a VPS / Docker
For raw TCP, expose TCP 7777. For WebSocket, expose HTTP 8080 (or configure `PORT`).
    docker build -t avyansh-br .
    docker run -d --restart=always -p 8080:8080 -p 7777:7777 -v br-data:/data avyansh-br

## Google sign-in configuration
Google account selection is performed by Google's hosted OAuth consent/account chooser in the system browser; no Google password is requested or handled by the app. Configure a **Web application OAuth client** in Google Cloud Console and add this exact authorized redirect URI:
`https://YOUR-SERVICE.onrender.com/auth/google/callback`

Set these server environment variables:
- `GOOGLE_CLIENT_ID`: OAuth web client ID
- `GOOGLE_CLIENT_SECRET`: OAuth web client secret (server only; never put it in the APK)
- `GOOGLE_REDIRECT_URI`: exact HTTPS callback URL above

The app opens `https://YOUR-SERVICE/auth/google/start`; the backend validates OAuth state, exchanges the authorization code, validates the Google ID token audience/issuer/expiry/verified email, links by stable Google `sub`, and returns a short-lived one-use PKCE-bound app login code. The app exchanges that code over WSS. The server stores no Google password or Google access token. If these environment variables are missing, Google sign-in returns a clear configuration error and email/password login remains available.

## Android client
Settings accepts:
- `host:7777` – legacy raw TCP
- `ws://host:8080/ws` – unencrypted WebSocket (testing only)
- `wss://domain.example/ws` – secure WebSocket (recommended for public hosting)

Firebase REST lobby code remains in the project and can still be used when the dedicated-server address is left empty. The WebSocket endpoint connects to this project's authoritative Node game server; it does not replace Firebase Authentication or turn Firebase into a game simulation server.

## Economy
Authenticated WebSocket messages `economy_get`, `economy_daily_claim`, and `economy_mission_claim` return server-authoritative balances. Daily claim grants 250 coins + 2 diamonds once per 24 hours. Server-computed match results grant coins (placement + kills), with a match-ID idempotency key. The daily mission grants 150 coins after one server-recorded match. Balances and a bounded transaction ledger persist in `DATA_DIR/economy.json`. Real-money billing is not enabled. Store purchases remain disabled until actual cosmetic SKUs/assets are configured.

## Environment variables
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`: Google OAuth Web client configuration
- `PORT: HTTP/WebSocket listener (default `8080`, supplied by web hosts)
- `TCP_PORT`: legacy raw TCP listener (default `7777`)
- `HOST`: bind address (default `0.0.0.0`)
- `TICK_RATE` (20), `SNAP_RATE` (15), `BR_FILL_SECONDS` (30), `CS_WAIT_SECONDS` (45), `BR_MAX_PLAYERS` (50), `GRACE_SECONDS` (60)
- `PLANE_SPEED` (85), `ZONE_TIME_SCALE` (1), `LOOT_PER_PLAYER` (6), `MAP_HALF` (1536), `MAX_CONNECTIONS` (2000), `DATA_DIR`, `QUIET=1`

## Notes
- One Node process runs all matches on one event loop. Actual capacity depends on host CPU, memory and network.
- Friends are stored in `DATA_DIR/friends.json`; player tokens are stored hashed.
- The server validates movement, weapons, ammo, fire rate, hit detection, loot and zone damage. Terrain height remains client-reported within a plausibility band (see protocol limitation).

## Deploy from an Android phone
1. Install Termux and Git, unzip the project, and push the `BattleRoyale` folder to a new GitHub repository.
2. In Render, choose **New -> Blueprint** and select that repository (the included `render.yaml` creates the free Web Service), or choose **New -> Web Service** and set Root Directory to `server`.
3. Wait for deploy, then open `https://YOUR-SERVICE.onrender.com/health`.
4. In the game Settings field, enter `wss://YOUR-SERVICE.onrender.com/ws`.
