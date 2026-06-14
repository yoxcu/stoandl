#!/usr/bin/env bash
# Soak test for the PPoG / reconnect wedges.
#  - Fires a unique desktop notification every INTERVAL seconds (stoandl forwards it
#    to the watch) as a steady data trickle + per-ping delivery signal.
#  - Calls `stoandl apps` each iteration as a connection heartbeat (it only lists the
#    locker when the watch is actually connected+synced — so the output tells us, with
#    a timestamp, exactly when we were connected vs not).
#  - Every APP_EVERY notifications, runs install -> launch -> remove of an app (heavy
#    watch<->phone burst — the real app-install scenario).
#
# Every line is timestamped [HH:MM:SS] so it aligns 1:1 with /tmp/stoandl.log.
#
# Usage:  ./soak-notifs.sh [interval_secs] [app.pbw] [app_name]
#   interval_secs : seconds between notifications        (default 45)
#   app.pbw       : .pbw to cycle install/remove         (optional; omit = notifs only)
#   app_name      : name/uuid for launch + remove        (default: pbw basename w/o .pbw)
# Stop: Ctrl-C
#
# No `set -e` on purpose — stoandl/notify commands WILL fail when the watch isn't
# connected, and we want the loop (and the heartbeat) to keep going.

set -uo pipefail
interval="${1:-45}"
pbw="${2:-}"
app_name="${3:-}"
app_every=4   # run an app cycle every Nth notification (~ every interval*app_every secs)

ts()  { date +%H:%M:%S; }
log()  { echo "[$(ts)] $*"; }
# Run a command, prefixing every line of its output with a fresh timestamp.
run()  { log "\$ $*"; "$@" 2>&1 | while IFS= read -r l; do echo "[$(ts)]   $l"; done; }

if [[ -n "$pbw" ]]; then
  if [[ ! -f "$pbw" ]]; then echo "pbw not found: $pbw" >&2; exit 1; fi
  [[ -z "$app_name" ]] && app_name="$(basename "$pbw" .pbw)"
  log "app cycle every $((interval * app_every))s: install/launch/remove '$app_name' from $pbw"
else
  log "(notifications only — pass a .pbw as arg 2 to also cycle app install/remove)"
fi

log "starting; notification every ${interval}s, connection heartbeat via 'stoandl apps'. Ctrl-C to stop."
i=1
while true; do
  log "===== iteration $i ====="
  run stoandl apps                       # connection heartbeat (lists locker only when connected)

  notify-send "stoandl soak" "ping #$i — $(ts)"
  log "sent ping #$i"

  if [[ -n "$pbw" && $((i % app_every)) -eq 0 ]]; then
    run stoandl sideload "$pbw"
    sleep 5
    run stoandl launch "$app_name"
    sleep 8
    run stoandl remove "$app_name"
  fi

  i=$((i + 1))
  sleep "$interval"
done
