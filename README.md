# DroneLink

Turns an **Android phone into a wireless MCP server** for a CooingDV / RC FPV
WiFi toy drone. The phone joins the drone's WiFi as a **local-only secondary
connection** (`WifiNetworkSpecifier`), so it keeps cellular internet + tailnet
while the drone is connected. The drone control engine and the MCP server live
**in the app** — no Termux, no relay, no laptop Python.

The laptop (or any agent) reaches the phone's MCP server over the tailnet at
`http://<phone-tailnet-ip>:8000/mcp` and drives the drone. **This is a
replacement for the closed-source RC FPV app** — a scriptable control surface.

## Chipset / protocol target

- **Chipset:** Jieli WiFi module (CooingDV firmware). Same family speaks the
  RC FPV / RC UFO / KY UFO protocol.
- **Drone IP:** `192.168.1.1` · **Control + telemetry:** UDP `7099`
- **Video:** `rtsp://192.168.1.1:7070/webcam` (MJPEG over RTSP)
- **Heartbeat:** `01 01` every 1s
- **WiFi:** drone broadcasts its own SSID (e.g. `8K_20505f` / `WIFI-UFO-XXXXXX`);
  join it first.
- **Flight packet (TC, 9 bytes):** `03 66 roll pitch throttle yaw FLAGS CKSUM 99`
  - roll/yaw: 128 center, 0 left, 255 right · pitch: 128 center, 0 fwd, 255 back
  - throttle: 0 idle … 255 max · CKSUM = `roll ^ pitch ^ throttle ^ yaw ^ FLAGS`
  - FLAGS: `0x01` takeoff · `0x02` land · `0x04` e-stop · `0x08` flip ·
    `0x10` headless · `0x80` gyro calibrate
- **Telemetry:** 5-byte status on the same socket. First byte auto-detects the
  variant (`90–101, 103, 82, 85, 88` → GL 21-byte; otherwise TC 9-byte).
  **No battery/voltage/IMU fields exist** (confirmed by live probe) — so there is
  no closed-loop position hold; the drone drifts.

Protocol sources (reverse-engineered):
- [Buff220/e88pro-reverse-RC-FPV-](https://github.com/Buff220/e88pro-reverse-RC-FPV-)
- [CraxCurl/RC-Swamp](https://github.com/CraxCurl/RC-Swamp) `working.md`
- [tonilopezortiz78/ToyDroneController](https://github.com/tonilopezortiz78/ToyDroneController) `docs/CONTROL_PROTOCOL.md`

## Architecture (v0.2+)

- `DroneLinkService` (ForegroundService) holds a `NetworkRequest` with
  `WifiNetworkSpecifier` for the drone SSID (open/owe). One consent panel per
  join — one human tap, then the request stays held while the service runs.
- Cellular stays the default network; the drone network comes up as secondary.
  The service holds a `TRANSPORT_CELLULAR+INTERNET` request for its lifetime
  (OxygenOS tears down the mobile PDN when any WiFi joins).
- **App-scoped network** (proven): only DroneLink's process can use the joined
  network.
- **DroneEngine** (`app/src/main/kotlin/com/dronelink/app/drone/DroneEngine.kt`)
  — faithful Kotlin port of `drone-ctl/drone_daemon.py` (the oracle): 25Hz
  control loop, 1Hz heartbeat, TC(9B)/GL(21B) framing, dead-man neutral,
  trajectory scripting. Sends via `network.bindSocket` to `192.168.1.1:7099`.
- **McpServer** (`app/src/main/kotlin/com/dronelink/app/mcp/McpServer.kt`) —
  minimal MCP server (JSON-RPC 2.0, streamable-HTTP subset) on `0.0.0.0:8000`,
  reachable via the phone's tailnet IP. Starts/stops with the service.

## Download

The build artifacts are in the repo so you can grab them directly:

- **APK (v0.6.13 — latest):** [`dist/dronelink-debug.apk`](dist/dronelink-debug.apk)
  — install on the phone. (Raw:
  `https://raw.githubusercontent.com/uudruid74/drone-link/main/dist/dronelink-debug.apk`)
- **sing-box config:** [`config/sing-box-drone-gateway.json`](config/sing-box-drone-gateway.json)
  — the SFA profile that makes the phone a tailnet node and split-tunnels the
  drone subnet.

## Installation (Android phone → wireless MCP server)

**The network plan:** the phone must be reachable by the laptop *and* still talk
to the drone. A single VPN can't do both unless it can exclude the drone subnet
— that's what sing-box does via a **Tailscale endpoint**. Tailscale's own app
can't exclude the drone's local-only network; sing-box's route rule can.

### 1. Install sing-box (SFA) on the phone
- **SFA** (`SagerNet/sing-box-for-android`) is the sing-box client for Android.
  Get it from GitHub releases or F-Droid, install it.
- SFA provides the **Tailscale endpoint** (sing-box 1.12+) — it makes the phone a
  tailnet node itself, so the laptop reaches it at a `100.x` IP with **no
  separate Tailscale app** (the Tailscale app would capture `192.168.1.0/24`
  into its tunnel and break drone comms).

### 2. Import the sing-box config
- Copy [`config/sing-box-drone-gateway.json`](config/sing-box-drone-gateway.json)
  to the phone. In SFA: **Profile → Import → local file**, select the json.
- Edit the config before importing if needed:
  - `endpoints[0].auth_key` — a Tailscale auth key from your tailnet (or leave
    empty; SFA logs a login URL the first time).
  - `endpoints[0].hostname` — keep `sfa-drone-gateway` (the tailnet node name
    the laptop targets) or rename it and update the IP below.
- The config's route rule sends `192.168.1.0/24` **direct** (outbound `direct`),
  so the drone subnet is split-tunnelled and DroneLink can reach the drone
  while everything else rides the tunnel.

### 3. Find the phone's tailnet IP
- After SFA connects, note the phone's tailnet `100.x` IPv4 (e.g. `100.122.172.27`).
  This is the address the laptop targets. Run `tailscale status` on the laptop
  or check the SFA connection screen.

### 4. Install DroneLink
- Install [`dist/dronelink-debug.apk`](dist/dronelink-debug.apk) on the phone:
  ```bash
  adb install -r dist/dronelink-debug.apk
  ```
  (or side-load the APK).

### 5. Fire up DroneLink
- Open the app → SSID field (prefilled, e.g. `8K_20505f`) → **Join drone WiFi**.
- Grant location / nearby-wifi permissions when asked. Tap the system consent
  panel. Status shows in the persistent notification and the app screen.
- The DroneLink service starts the MCP server on `:8000` and binds the drone
  socket onto the joined network.

### 6. Connect from the laptop
```bash
# MCP endpoint (tailnet IPv4 from step 3)
curl -s -X POST http://<phone-tailnet-ip>:8000/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"status"}}'
```
Dev smoke without the drone (USB): `adb forward tcp:8000 tcp:8000`, then hit
`http://127.0.0.1:8000/mcp`.

## MCP endpoint

```
POST http://<phone-tailnet-ip>:8000/mcp
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"initialize"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status"}}
```

## Snapshot & video feed

The camera is **on the drone** (`rtsp://192.168.1.1:7070/webcam`, MJPEG over
RTSP). Two MCP surfaces expose it:

- **`snapshot`** — returns the latest complete JPEG frame, base64-encoded:
  ```json
  {"ok": true, "size": 11632, "jpeg_base64": "<base64>"}
  ```
  Decode the **`jpeg_base64`** field (⚠️ *not* `image` — a script that checks for
  `image` silently writes nothing). This is the ground-truth way to confirm what
  the drone sees: it's a real, decodable frame.
- **`video_url`** — the proxied MJPEG FPV stream URL (the app's RTSP→MJPEG proxy
  serves on `:8080`; `video_url` returns the exact address for a browser/VLC).

```bash
# grab a frame
curl -s -X POST http://<phone-tailnet-ip>:8000/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"snapshot"}}'
```

## MCP tools

| Tool | Purpose |
|---|---|
| `connect` / `disconnect` | open/close the control loop |
| `takeoff` | arm + auto-takeoff (⚠️ test with props removed) |
| `land` | auto-land |
| `emergency_land` | e-stop + 4s neutral-lock (kill switch; stops the server) |
| `fly_home` | Return-to-Home |
| `hover(enable, throttle?)` | **altitude-hold toggle (WIP)** — sustains throttle + camera-feedback stabilization; see [Hover mode](#hover-mode-altitude-hold-wip) |
| `fly(roll,pitch,throttle,yaw)` | raw sticks, -1..1 |
| `trajectory(waypoints)` | timed flight script at 25 Hz (the AI control surface) |
| `stop_trajectory` | clear script → neutral |
| `camera(1\|2)` | switch front/rear |
| `headless(on)` | toggle headless mode |
| `calibrate` | gyro calibration |
| `set_gear(1\|2\|3)` | slow / normal / fast |
| `status` | variant, axes, telemetry, sent/rx counts |
| `video_url` | proxied MJPEG FPV URL |
| `snapshot` | latest complete frame (base64 JPEG) |
| `debug_log` | app ring buffer (service/transport/probe/rtsp) |

## Why trajectory mode is the right AI control surface

Cheap WiFi FPV has too much latency for real-time joystick control from an agent
— reacting to a stale video feed is a losing game. Trajectory mode means the
agent plots a path once (a timed sequence of stick values) and the drone
executes it autonomously. No latency loop, no joystick. The MCP's `trajectory()`
uploads the script and the drone flies it.

## Hover mode (altitude hold, WIP)

`hover` is a **WIP altitude-hold toggle** modelled on the vendor's "Fixed Height"
(H-pin) mode. When enabled, the control loop **sustains throttle** so the drone
holds altitude instead of decaying to neutral — which on a cheap drone means
*dropping*. It is **not** a true position hold (the 5-byte telemetry has no
IMU/baro/GPS).

- **`hover(enable: bool, throttle?: int)`** — `enable` toggles it on/off;
  `throttle` (0–127, default **64** = vendor H-pin center) tunes the hold
  strength for flight tests.
- **Stabilization is camera-feedback:** the loop derives a motion signal from
  frame-difference between consecutive frames and runs a (P)D controller to
  correct vertical bob (~10 Hz cadence, per-second velocity).
- **Overrides:** any `land` / `emergency_land` / `fly_home` pulse, and the kill
  gate, always win over an active hover.
- **Verification:** altitude hold is confirmed by camera **motion** (stable at
  altitude = holding). The telemetry itself cannot confirm flight.

**Status — 2026-11-24 flight test:** the hover lifted cleanly (~6 ft) and produced
the **most controlled flight so far** — stable heading, no chaotic back/sideways
wobble. But it **translated straight forward** rather than station-keeping (it
cruised toward the back fence). So: **vertical + heading hold work; forward-
velocity cancellation is not yet implemented.** That's the next tuning target, and
the control thresholds are still largely guessed — fine-tune after forward
correction lands. Treat hover as experimental; keep a hand on `emergency_land`.

## Build

Requires JDK 17 (system Java 27 breaks Gradle 8.9) + Android SDK (platform 34,
build-tools 34.0.0).

```bash
export JAVA_HOME=/home/ekl/.hermes/profiles/neo/home/opt/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (copy to `dist/` for release).

Unit tests (JVM, no device): `./gradlew testDebugUnitTest` — engine byte-tests
against the Python oracle + MCP JSON-RPC/HTTP tests.

## Safety rules

1. **Test with the propellers removed first.** The drone obeys whatever packets
   it gets, including bugs.
2. **Fly somewhere open.** Keep a hand on `emergency_land`.
3. **Dead-man neutral:** if no `fly`/`trajectory` command for `STALE` (0.4s),
   sticks return to center/throttle-0. During an active trajectory the script
   keeps the sticks alive.
4. Join the drone's WiFi **before** opening the control socket — order matters.

## Tailscale VPN caveat

If Tailscale's VPN applies to DroneLink (UID covered, non-bypassable),
Android/netd rejects `network.bindSocket` with EPERM even though the join
succeeded. The MCP error says so and points at Tailscale › *Bypass VPN for
apps*. The sing-box endpoint + route-rule config above is the preferred fix
(no Tailscale app to fight).

## Retired (v0.2)

- **Termux** + `drone_daemon.py` on the phone (engine moved in-app).
- **UDP relay** `127.0.0.1:17099 → 192.168.1.1:7099` (the engine owns the bound
  socket directly).
- **Laptop Python**: `drone_mcp.py` / `drone_client_mcp.py` — the agent's MCP
  client now targets the phone's tailnet IP port 8000.

## Evidence / gate

`evidence/` in this repo holds gate outputs. The drone window is only open when
Evan says the battery is charged — no drone-network testing before that.

## Flight verification (2026-11-19)

Verified on live drone WiFi (`8K_20505f`) with build `9e77a58`:

- **Takeoff + trajectory sustain** — PASSED. `takeoff` sends the arm flag
  (`FLAG_TAKEOFF`) together with `TAKEOFF_THROTTLE=60`; the `trajectory` then
  advances on its own (`trajectory_index` moves 0→2→cleared) and holds non-zero
  throttle through the climb/descent. The drone lifted ~2-4 ft, held, and landed.
- **Root cause of the earlier crash / no-lift** — `requireConnected()` gated
  `connect()` on `!connected`, but `bindAndStartAutonomous()` already set
  `connected=true` without setting `running=true`. So `running` stayed false and
  the control loop never advanced trajectories or held throttle. Fixed in
  `9e77a58` by calling `connect()` unconditionally.

## Known issues (open)

- **Hover forward drift (WIP)** — the new hover mode holds altitude and heading
  via camera-feedback stabilization, but does **not** yet cancel horizontal
  **forward** velocity: in the 2026-11-24 flight test the drone lifted cleanly
  (~6 ft) and flew in a straight line forward (toward the back fence) instead of
  station-keeping. Vertical + yaw hold work; forward correction is the next
  target, and the PID thresholds are still guessed (fine-tune after forward
  correction lands). See [Hover mode](#hover-mode-altitude-hold-wip).
- **Drone drift / no position hold** — the 5-byte TC status carries **no
  IMU / baro / GPS / position** fields, so there is no true closed-loop position
  hold. Hover is an *altitude/heading* hold built from camera frame-diff, not a
  position fix. Calibration + trim reduce but don't eliminate drift; some drift
  is inherent to the airframe.

### Resolved

- **Corrupt snapshot frames** — was MJPEG/RTP reassembly producing malformed
  JPEGs (valid SOI, no APP0, raw entropy). Fixed (`7367573` + the RFC 2435
  per-packet header strip): the frame is now a valid decodable JPEG, and
  `snapshot` returns real frames — see [Snapshot & video feed](#snapshot--video-feed).

## Debug log access

The MCP `debug_log` tool returns the app's ring buffer (service / transport /
probe / rtsp / heartbeat lines). Watch for:
- `[rtsp] rtp: pkts=… frames=…` — camera frame counters (frames>0 but black/corrupt
  = source-side reassembly issue; frames=0 despite PLAY ok = camera not streaming).
- `[transport] bound mode=bindSocket` — confirms the engine bound to the drone net.
- `[video] proxy up: :8080` — confirms the RTSP→MJPEG proxy is serving.
