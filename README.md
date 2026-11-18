# DroneLink

Tiny Android app: joins the drone WiFi as a **local-only secondary connection**
via `WifiNetworkSpecifier`, so the phone keeps cellular internet + tailnet while
the drone is connected. The drone control engine and the MCP server live **in
the app** (v0.2) — no Termux, no relay, no laptop Python.

## Architecture (v0.2)

- `DroneLinkService` (ForegroundService, type `connectedDevice`) holds a
  `NetworkRequest` with `WifiNetworkSpecifier` for the drone SSID (open/owe).
  The system shows one consent panel per join — one human tap, then the request
  stays held while the service runs.
- Cellular stays the default network; the drone network comes up as secondary.
  The service holds a `TRANSPORT_CELLULAR+INTERNET` request for its lifetime
  (OxygenOS tears down the mobile PDN when any WiFi joins — app-strength
  mobile-data-always-on).
- **App-scoped network** (proven): only DroneLink's process can use the joined
  network.
- **DroneEngine** (`app/src/main/kotlin/com/dronelink/app/drone/DroneEngine.kt`)
  — faithful Kotlin port of `drone-ctl/drone_daemon.py` (the oracle): 25Hz
  control loop, 1Hz heartbeat, TC(9B)/GL(21B) framing, dead-man neutral,
  trajectory scripting. Sends via `network.bindSocket` to the ORIGINAL drone
  endpoint `192.168.1.1:7099`.
- **Tailscale VPN caveat** — if Tailscale's VPN applies to DroneLink (UID
  covered, non-bypassable), Android/netd rejects `network.bindSocket` with
  EPERM even though the join succeeded. The MCP error now says so and points
  at Tailscale › *Bypass VPN for apps*. See PROTOCOL.md window checklist.
- **McpServer** (`app/src/main/kotlin/com/dronelink/app/mcp/McpServer.kt`) —
  minimal MCP server (JSON-RPC 2.0, streamable-HTTP subset) on
  `0.0.0.0:8000`, reachable via the phone's tailnet IP. Methods: `initialize`,
  `tools/list`, `tools/call` (14 tools mirroring drone_mcp.py). Starts/stops
  with the service. See `PROTOCOL.md` for the conformance note.

## The icon

`ic_launcher_foreground.xml` is a hand-drawn stroke vector (adaptive icon, round-cap
lines) by **Neo** — the coding agent that built v0.1 (commit `2c9ffd1`, 2026-11-17).
The geometry: two nested semicircular arcs on the vertical midline (radii 22 and 14 —
the rotor domes) over a small circle below-left (the gimbal camera dot). Read it as a
minimal drone in profile: rotors overhead, camera ball beneath. Yes, the designer is an
AI agent — the fleet's build worker drew it in the same run that wrote the WiFi join.

## MCP endpoint

```
POST http://<phone-tailnet-ip>:8000/mcp
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"initialize"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status"}}
```

Dev smoke without the drone (USB): `adb forward tcp:8000 tcp:8000`, then hit
`http://127.0.0.1:8000/mcp`. Tools that need the drone (`takeoff`, `fly`, …)
return a JSON-RPC error (`-32000`, "drone network not joined") until the drone
AP is joined. `status` always works.

## Build

Requires JDK 17 (system Java 27 breaks Gradle 8.9) + Android SDK (platform 34,
build-tools 34.0.0).

```
export JAVA_HOME=/home/ekl/.hermes/profiles/neo/home/opt/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit tests (JVM, no device): `./gradlew testDebugUnitTest` — engine byte-tests
against the Python oracle + MCP JSON-RPC/HTTP tests.

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open app → SSID field (prefilled with `WIFI_8K_20505f`) → **Join drone WiFi**.
Grant location / nearby-wifi permissions when asked. Tap the system consent
panel. Status is in the persistent notification and the app screen (Refresh).

## Retired (v0.2)

- **Termux** + `drone_daemon.py` on the phone (engine moved in-app).
- **UDP relay** `127.0.0.1:17099 → 192.168.1.1:7099` (`UdpRelay.kt` deleted;
  the engine owns the bound socket directly — the relay retarget in the repo
  copy of the daemon is superseded).
- **Laptop Python**: `drone_mcp.py` / `drone_client_mcp.py` (agent's MCP
  client now targets the phone's tailnet IP port 8000).

## Evidence / gate

`evidence/` in this repo holds gate outputs. The drone window is only open when
Evan says the battery is charged — no drone-network testing before that.
