# DroneLink

Tiny Android app: joins the drone WiFi as a **local-only secondary connection**
via `WifiNetworkSpecifier`, so the phone keeps cellular internet + tailnet while
the drone is connected. Plan C per `SagerNet/plans/plan-c-dronelink-app.md`.

## Mechanism

- `DroneLinkService` (ForegroundService, type `connectedDevice`) holds a
  `NetworkRequest` with `WifiNetworkSpecifier` for the drone SSID (open/owe).
  The system shows one consent panel per join — one human tap, then the request
  stays held while the service runs.
- Cellular stays the default network; the drone network comes up as secondary.
  No hijack, no cellular PDN dormancy.
- **App-scoped network** (verified by Gopher): only DroneLink's process can use
  the joined network. So the app runs a localhost UDP relay:
  `127.0.0.1:17099` → `192.168.1.1:7099` via `network.bindSocket()` on the
  specifier network, replies relayed back to the last local sender. The Termux
  daemon targets the relay (one-line change, coordinate with Gopher).

## Build

Requires JDK 17 + Android SDK (platform 34, build-tools 34.0.0).

```
export JAVA_HOME=$(ls -d ~/opt/jdk-17* | head -1)
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open app → SSID field (prefilled with `WIFI_8K_20505f`) → **Join drone WiFi**.
Grant location / nearby-wifi permissions when asked. Tap the system consent
panel. Status is in the persistent notification and the app screen (Refresh).

## Home-WiFi mechanism test (GATE — before any drone battery)

Script: `scripts/mechanism-test.sh` (run via adb shell on the phone while
joined to a home AP, cellular on). Must observe:

1. Active default network stays cellular (`rmnet_data*`), NOT wifi.
2. Internet from the phone shell (ping 8.8.8.8) — riding cellular.
3. Informational: subnet probe (`192.168.1.150`) — expected UNREACHABLE from a
   plain shell socket (app-scoped network). Relay path is the deliverable:
   from the phone, `echo -n "hi" | nc -u 127.0.0.1 17099` and watch it arrive
   at the drone port (or use the daemon's `connect` cmd via its TCP :8000).

The drone window opens only after (1)+(2) pass.

## Evidence

Save gate output under `evidence/` (e.g. `evidence/home-test-YYYYMMDD.txt`).
