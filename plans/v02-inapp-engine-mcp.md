# DroneLink v0.2 — the daemon + MCP move into the app (Evan 2026-11-18)

**Ruling (Evan):** "Instead of a relay, why not build the mcp into dronelink? I'd rather not
need to open termux and run a shell script. We already need dronelink and tailscale."
Architecture confirmed: the drone LAN is APP-SCOPED (proven 2026-11-18: shell ping to
192.168.1.1 = 100% loss while DroneLink holds the join), so the control engine MUST live
in the app that owns the bound socket. The relay was a bridge for the Termux daemon; with
the engine in-app, the relay concept dies.

**End-state:** phone = DroneLink (WiFi join + cellular keep-alive + drone engine + MCP
over HTTP on the tailnet) + the Tailscale app. Laptop = Tailscale + the agent's MCP
client config. RETIRED: Termux, drone_daemon.py (phone), the UDP relay, drone_mcp.py and
drone_client_mcp.py (laptop).

## What to build (in /home/ekl/Documents/Programming/drone-link, on top of v0.1)

1. **The engine** — port `drone-ctl/drone_daemon.py` (380 lines) to Kotlin inside the
   existing service. It is "a faithful port of drone_mcp.py's Drone class (packet
   framing, ...)": UDP packets to the drone, 25Hz control loop (CTRL_PERIOD 0.04), 1Hz
   heartbeat (HB_PERIOD 1.0), stick center C=128, flags TAKEOFF=0x01 LAND=0x02 STOP=0x04
   FLIP=0x08 HEADLESS=0x10 CALIBRATE=0x80. Keep behavior IDENTICAL (port, don't redesign)
   — the framing details are the drone's contract. Send via the bound socket
   (`network.bindSocket`) to 192.168.1.1:7099 — the ORIGINAL target; the 127.0.0.1:17099
   relay retarget in the repo copy is superseded by this (revert-note it).
2. **MCP over HTTP** — a minimal MCP server (JSON-RPC 2.0, streamable-HTTP transport
   subset: POST for request/response is enough; SSE optional) on port 8000, served on all
   interfaces (reachable via the phone's tailnet IP). Required methods: `initialize`,
   `tools/list`, `tools/call`. The **12 tools** are the `@mcp.tool()` set in
   `drone-ctl/drone_mcp.py:286-400` — port names, schemas, and behavior VERBATIM (read
   that file as the contract). Tool handlers call the engine directly.
3. **Lifecycle** — the MCP server starts/stops with the existing ForegroundService.
   Command/status flows: MCP tool → engine → UDP. Return values mirror drone_mcp.py's
   (the agent's expectations don't change).
4. **Nothing else changes**: the join (WifiNetworkSpecifier), the cellular keep-alive,
   the notification, the UI all stay as v0.1.

## Verification (no drone battery spent on dev cycles)

- Unit/local: engine packet bytes vs drone_daemon.py outputs for identical inputs (frame
  the comparison as a small test — the Python is the oracle).
- MCP: `initialize` + `tools/list` + one `tools/call` (e.g. status) against the app over
  adb-reverse or the tailnet — from the laptop, real MCP client or a curl JSON-RPC.
- The relay from v0.1 is retired; remove it or leave it dormant (simplest wins).

## The flight question (tonight)

The Termux one-paste path (patched drone_daemon.py → 127.0.0.1:17099 relay) is staged and
can fly NOW; v0.2 replaces it permanently. Do not block the drone window on this build.

## Laws

- Port faithfully; observable protocol behavior must not drift (the Python files are the
  oracle and the contract).
- Commit each green step; targeted tests only; two repeated failures → stop and report.
- Evidence truth. The toolchain: JAVA_HOME=/home/ekl/.hermes/profiles/neo/home/opt/jdk-17.0.21+9
  (Gradle 8.9 rejects the system Java 27), SDK via local.properties (profiles/neo/home/android-sdk).
