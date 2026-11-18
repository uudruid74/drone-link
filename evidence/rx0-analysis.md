# rx:0 root-cause analysis + packet-evidence plan (v0.3.0)

Task t_c35de434, step 1. Goal: chase the maiden-flight `rx:0` (no telemetry
ever returned) with packet-level evidence, not guesses.

## Flight facts (0.2.1, 2026-11-17/18)

| Fact | Value | Meaning |
|---|---|---|
| `sent` | 2737 | ~25Hz control loop ran for ~110s → the control socket had a live, valid binding |
| `connected` | true | `transport.bind()` succeeded (bindSocket or EPERM-inherited path) |
| `rx` | 0 | ZERO datagrams ever received on the control socket |
| trajectory | ran | the drone MANIFESTLY received our control packets (it flew) |
| heartbeat | 01 01 @1Hz | sent once engine connected (gated behind an MCP tool call) |

Key inference: the drone accepted and acted on our UDP control, but never sent
anything back that our socket saw. The uplink works; the telemetry downlink
does not reach the app.

## Hypothesis ranking (with the evidence that would confirm each)

1. **Telemetry goes to a fixed port / broadcast, not to the sender's ephemeral
   source port (most likely).** Toy drones in this family (CooingDV / RC FPV)
   often stream telemetry to a fixed client port (or broadcast) rather than
   echoing back to the ephemeral port the control packet came from. Our control
   socket binds an ephemeral local port → we only receive replies addressed to
   THAT port. Control works because the drone doesn't need to reply; telemetry
   is lost because it is addressed elsewhere.
   *Confirm:* telemetry probe (below) captures datagrams on 7070/7098/7099/8080.
   *Fix if confirmed:* bind the engine's receiver to the discovered fixed port
   (probe data tells us which).

2. **Heartbeat gated behind MCP connect (fixed in v0.3.0).** Before any MCP
   tool call, NOTHING was sent to the drone. If the drone requires a steady
   early heartbeat to arm its telemetry TX (some toys sleep until they see the
   handshake), rx:0 is the direct consequence when agents connect late or not
   at all. The v0.3.0 autonomous heartbeat starts the moment the join succeeds,
   service-side, and survives MCP disconnects. This is suspect #1 per the spec
   and is now fixed regardless of other hypotheses.

3. **Return-path routing quirk on the phone.** The drone replies to the source
   IP:port it saw; Android routes the reply back over the bound netId. If the
   socket's netId were wrong (e.g., EPERM-inherited socket not actually pinned),
   sends would still work if the default route happened to reach the drone LAN —
   but that is not the case here (the drone LAN is app-scoped; a wrong-netId
   socket would have 100% loss, and the drone would NOT have flown). So this
   hypothesis is effectively DISPROVEN by the trajectory running. Recorded for
   completeness; the transport now logs `bind_mode` + `local_address`, so the
   next flight records which bind path won.

## What v0.3.0 ships for evidence capture

- `DroneNetworkTransport` logs per-bind: `bind_mode` (bindSocket vs
  inherited-eperm-bypass), `local_address`, and per-receive `last_rx_source`
  (the source IP:port of any datagram that DOES arrive) — all in the scrolling
  log and the MCP status extras.
- `TelemetryProbe`: passive wildcard UDP listeners on 7070, 7098, 7099, 8080
  (scoped onto the drone network). Every datagram is logged with source
  IP:port + hex. If the drone streams telemetry to a fixed port, the probe
  catches it and hypothesis 1 is confirmed with the exact port in hand.
- Engine `rx` handler logs every received datagram (len + hex) to the scrolling
  log, so a non-zero rx is visible even without a status poll.
- The debug log is exportable (app: Copy / Share), so a flight can be captured
  and handed over as evidence.

## Next-flight evidence protocol (device run)

1. Join, wait for "autonomous heartbeat started" in the log.
2. Let it sit 60s WITHOUT any MCP call → check `rx` (should now be >0 if the
   drone streams telemetry unsolicited; if still 0, the drone waits for
   something else — probe logs show whether ANY UDP arrives from it).
3. Call `connect` + `status`: record `sent`, `rx`, `bind_mode`, `local_address`.
4. Read the probe lines (`probe` tag): any `udp :7070 <- 192.168.1.1:xxxx`
   means telemetry lands on 7070 → set receiver port accordingly.
5. Export the log, attach to the task.

## Acceptance status

- Suspect #1 (gated heartbeat) → **fixed** (autonomous heartbeat).
- Fixed-port telemetry → evidence capture instrumented; confirmation needs one
  device run (drone/phone not attached to this worker).
- Wrong-netId socket → **disproven** (trajectory ran).
