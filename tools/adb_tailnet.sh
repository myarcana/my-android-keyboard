#!/usr/bin/env bash
# Pair and connect adb to the phone over the tailnet, then hand the address to tools/deploy.sh.
#
#   tools/adb_tailnet.sh pair    <host> <pair-port> <code>   one time, after enabling the toggle
#   tools/adb_tailnet.sh persist <host> <debug-port>         once per boot: pin adbd to port 5555
#   tools/adb_tailnet.sh connect [host] [port]               every time; defaults to 5555
#   tools/adb_tailnet.sh status  [host]                      what is reachable right now
#
# The intended sequence after a reboot is `pair` (only if the phone forgot us), then `persist`
# once, and `connect` from then on. `persist` is what removes the need to read a new random port
# off the phone for every single connection.
#
# Why this is fiddlier than `adb connect phone:5555`:
#
#   * Wireless debugging only arms while the phone is associated with a *Wi-Fi network*. On
#     cellular the toggle refuses with "please connect to a wifi network", and Tailscale does
#     not satisfy it -- the check is on the Wi-Fi radio, not on connectivity. This is the one
#     step that cannot be done remotely.
#   * Android 11+ requires pairing before an address is connectable, with a 6-digit code shown
#     under "Pair device with pairing code".
#   * The pairing port and the debugging port are different, and both are random per session.
#   * Both reset when wireless debugging is toggled off or the phone reboots. Pairing usually
#     survives; the port does not, which is why `connect` is the one you rerun.
#
# Once paired, the phone is reachable at its stable Tailscale IP from anywhere on the tailnet,
# which is the whole point: no shared LAN required.

set -euo pipefail

SDK=${ANDROID_HOME:-}
if [[ -z "$SDK" ]]; then
    for c in "$(dirname "$0")/../../android-sdk" /opt/homebrew/share/android-commandlinetools \
             "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/android-sdk-min; do
        [[ -d "$c/platform-tools" ]] && { SDK=$(cd "$c" && pwd); break; }
    done
fi
ADB=${ADB:-$SDK/platform-tools/adb}
[[ -x "$ADB" ]] || ADB=$(command -v adb || true)
[[ -n "$ADB" && -x "$ADB" ]] || { echo "error: adb not found; set ADB or ANDROID_HOME" >&2; exit 1; }

# The phone's Tailscale IP. Override with TS_HOST for a different device.
DEFAULT_HOST=${TS_HOST:-100.121.46.71}

cmd=${1:-status}

# The fixed port `adb tcpip` puts adbd on. 5555 is the conventional one.
FIXED_PORT=${FIXED_PORT:-5555}

case "$cmd" in
pair)
    host=${2:-$DEFAULT_HOST}; port=${3:?pairing port (Settings -> Wireless debugging -> Pair device with pairing code)}; code=${4:?6-digit code}
    "$ADB" pair "$host:$port" "$code"
    echo "paired. Now: tools/adb_tailnet.sh persist $host <debug-port>"
    ;;
persist)
    # The one command that ends the whole port-and-code treadmill.
    #
    # Wireless debugging hands out a *random* port and drops the moment Wi-Fi changes or the
    # screen locks, so every reconnect needs a fresh port read off the phone. `adb tcpip` instead
    # restarts adbd listening on a fixed port, and that listener is not tied to the Wireless
    # debugging toggle: it survives Wi-Fi going away entirely (verified by toggling Wi-Fi off --
    # the port stayed open), which also means it works over cellular on the tailnet.
    #
    # It does NOT survive a reboot: adbd goes back to USB-only, because making it permanent needs
    # persist.adb.tcp.port, which is root-only and this phone is a `user` build. After a reboot,
    # re-run this once over a fresh Wireless debugging session.
    host=${2:-$DEFAULT_HOST}; port=${3:?current wireless-debugging port}
    "$ADB" connect "$host:$port" >/dev/null 2>&1 || true
    "$ADB" -s "$host:$port" tcpip "$FIXED_PORT"
    sleep 4
    "$ADB" connect "$host:$FIXED_PORT"
    "$ADB" -s "$host:$FIXED_PORT" shell getprop ro.product.model
    echo
    echo "adbd is now on the fixed port $FIXED_PORT until the phone reboots."
    echo "deploy with:  DEVICE=$host:$FIXED_PORT tools/deploy.sh"
    ;;
connect)
    # With no port, assume the fixed one that `persist` set up -- the ordinary case.
    host=${2:-$DEFAULT_HOST}; port=${3:-$FIXED_PORT}
    for i in 1 2 3 4 5; do
        out=$("$ADB" connect "$host:$port" 2>&1 | grep -v daemon || true)
        echo "$out"
        [[ "$(${ADB} -s "$host:$port" get-state 2>/dev/null)" == device ]] && break
        sleep 3
    done
    "$ADB" -s "$host:$port" shell getprop ro.product.model
    echo
    echo "deploy with:  DEVICE=$host:$port tools/deploy.sh"
    ;;
status)
    host=${2:-$DEFAULT_HOST}
    if command -v tailscale >/dev/null 2>&1; then
        echo "--- tailnet ---"
        tailscale status | grep -iE "android|$host" || echo "(phone not listed)"
    fi
    echo "--- adb ---"
    "$ADB" devices -l
    ;;
*)
    sed -n '2,22p' "$0"
    exit 1
    ;;
esac
