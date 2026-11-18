#!/usr/bin/env bash
# Mechanism test for DroneLink Phase 1 — HOME WIFI GATE.
# Run on the OnePlus (CPH2583) via adb while DroneLink is joined to a home AP
# (e.g. StrayScampsDen) with cellular data on.
#
# Must observe (the gate):
#   1. Active default network stays CELLULAR (rmnet_data*) — not wifi.
#   2. Internet works from the phone shell (ping 8.8.8.8 OK) — riding cellular.
#   3. Joined subnet reachable from a PLAIN shell socket (ping 192.168.1.150 =
#      taro3). This decides Phase 1 (suffices) vs Phase 2 (relay needed).
#
# Usage: adb shell sh /sdcard/mechanism-test.sh   (or push + run)
set -u

SUBNET_HOST="${1:-192.168.1.150}"

echo "=== 1. Active default network (must show rmnet_data*, NOT wlan) ==="
dumpsys connectivity | grep -E "Active default network|NetworkAgentInfo" | head -30

echo
echo "=== 2. Internet over cellular (plain ping) ==="
ping -c 3 -W 2 8.8.8.8 2>&1 | tail -3

echo
echo "=== 3. Joined subnet reachability from plain shell socket ==="
echo "target: ${SUBNET_HOST}"
ping -c 2 -W 2 "${SUBNET_HOST}" 2>&1 | tail -4

echo
echo "=== 4. Routing tables ==="
ip route show table all 2>/dev/null | grep -E "default|192\.168\.1" | head -20
