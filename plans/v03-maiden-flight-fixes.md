# DroneLink v0.3.0 — post-maiden-flight (Gopher/Evan spec 2026-11-18)

**Context:** maiden flight proved the stack — drone joined ("Joined" on screen), socket binds
(EPERM bypass works in 0.2.1), heartbeats + control flow (`sent:2737`, trajectory ran,
`connected:true`). The one bug: **`rx:0` — no telemetry ever returned** despite correct
join + heartbeat. Protocol says: join AP → open UDP 7099 → heartbeat `01 01`/s → drone
streams telemetry back on the same socket. We did all of it, yet rx:0. **That is the
chase-the-bug item.** The rest is features.

## Requirements (Evan, verbatim intent)

1. **Autonomous heartbeat** — the heartbeat loop must run LOCALLY in the app (starts with
   the service), NOT gated behind an MCP `connect` call. Must survive MCP/agent
   disconnects and restarts. (Likely related to the rx:0 bug — check whether the
   heartbeat the drone needs is actually flowing without a connect.)
2. **`emergency_land` = kill switch** — after `emergency_land`, STOP the MCP server until
   app restart; no further tool calls accepted. (Today it only pulses land + locks
   neutral 4s.)
3. **`fly_home` (Return-to-Home)** — protocol flag `+32` (`isFastReturn`, byte 6).
   `FLAG_RETURN = 0x20` in DroneEngine + a `fly_home` MCP tool + an app button.
4. **RTSP proxy through DroneLink** — `192.168.1.1:7070/webcam` is reachable ONLY via the
   phone's bound socket; the laptop can't reach it over tailnet. DroneLink proxies the
   video so FPV works from the tailnet. (Today `video_url` just returns a useless URL.)
5. **`snapshot` tool + button** — the feed is MJPEG (frames delimited `0xFF 0xD8` …
   `0xFF 0xD9`): parse JPEG boundaries, stdlib only, NO extra native libs (Evan: skip if
   it needed installs — it doesn't). Rides the same proxy path as #4.
6. **On-screen debug display** — connection state, command history, scrolling log,
   EXPORTABLE (copy/save) for debugging.

## Protocol facts (source: RC-Swamp working.md / ToyDroneController CONTROL_PROTOCOL.md)
- Return to Home: `+32` (isFastReturn), byte 6 flags
- MJPEG frames: start `0xFF 0xD8`, end `0xFF 0xD9`
- RTSP flags: `-rtsp_transport udp -fflags nobuffer -flags low_delay -strict experimental`
- Drone IP `192.168.1.1`, UDP control 7099, RTSP 7070/webcam
- HTTP media endpoint for snapshots if needed: `http://192.168.1.1/PHOTO/O/<file>.jpg`

## Laws
- **VERSION-BUMP LAW (Evan): every code change bumps versionName + versionCode; NO
  unbumped build goes to device test.** Verify the label on-device before any test.
- Targeted tests only (hermes-test-cow once fixed; else TMPDIR=/home/ekl/tmp-pytest).
- Commit each green step. No fallbacks. Evidence truth.
- Toolchain: JAVA_HOME=/home/ekl/.hermes/profiles/neo/home/opt/jdk-17.0.21+9 (system
  Java 27 breaks Gradle 8.9), SDK via local.properties.

## Acceptance
- rx:0 root-caused + fixed (or, if it's a drone-side quirk, EVIDENCE of where the
  telemetry dies — packet-level, not guesses)
- 1-6 implemented; version bumped (0.3.0); device evidence for the flight-critical ones
  (heartbeat autonomous, emergency_land kill switch, fly_home)
