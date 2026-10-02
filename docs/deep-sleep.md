# Phones that deep-sleep (keeping the watch link across suspend)

A Linux phone saves most of its battery by suspending whenever the screen is off (s2idle), waking
only for a push message, a call or an alarm — a few seconds awake, then asleep again. stoandl has a
few features for that kind of host. They are harmless on a desktop, and the two that change how the
watch keeps its link (connection parameters, datalog pause) are off unless you turn them on. The
reference device is a OnePlus 6 on postmarketOS with Plasma Mobile ("Mode B" below).

**Status:** partially verified on hardware: OnePlus 6, postmarketOS, BlueZ 5.87, Pebble Time 2
(2026-09-29, and a night in Mode A 2026-09-30/10-01).

- **Mode A:** verified that notifications reach the watch while the phone is awake, and that the sleep
  guard holds its delay lock (`systemd-inhibit --list` shows `stoandl`, mode `delay`). A notification
  that arrived *with* a push wake — posted while the link was still down from the suspend — was never
  delivered in that test. This is addressed (**implemented, to be tested**): such a notification now
  goes out once the watch reconnects, if that happens within `notification.catch_up_minutes` (default
  60) — see [Mode A](#without-the-host-work-mode-a) below.
- **Mode B:** not yet tested.

Everything else on this page is unit/harness-tested off-device only. Test plan:
[TESTING.md §5.32](../TESTING.md); the Quiet Time pause (**implemented, to be tested**) in §5.37.

postmarketOS and Alpine use musl. The SQLite driver bundled with stoandl is built for glibc, and
older builds crash-loop right after the first pairing. stoandl works around this automatically on
musl (verified on the OnePlus 6). See [README → Requirements](../README.md#requirements).

## Two ways a watch can live with a sleeping phone

- **Mode A — stock kernel.** Linux disconnects every Bluetooth link on each suspend
  (`hci_suspend_sync()`), and the watch reconnects after each wake. It works, but the watch shows
  "disconnected" most of the time, actions from the watch don't reach a sleeping phone, and every wake
  pays for a full reconnect.
- **Mode B — link kept across suspend.** The Bluetooth controller keeps the LE link while the SoC
  sleeps. The phone wakes only when the watch actually sends something, and notifications to the watch
  go out during the wakes the phone has anyway (the push that carried the message). This needs kernel
  work on the host (see [Host prerequisites](#host-prerequisites-mode-b)); stoandl's part is below.

This page is about the LE link that BLE-native watches (Pebble Time 2, Pebble 2) use. Classic-era
watches (Pebble Time / Time Steel) connect over Bluetooth Classic instead
([configuration.md → Bluetooth Classic](configuration.md#bluetooth-classic)). For them, the wall-clock
scheduling works the same way, and the BR/EDR inquiry follows the same pairing-window rules as the BLE
scan. The sleep guard waits for their pending notification writes but not for the RFCOMM send queue. The
connection-parameter settings are LE-only, and whether a Classic link survives a suspend (Mode B) is
untested. All hardware testing so far used a BLE watch.

## What stoandl does

| Feature | Key(s) | Default | What it does |
|---|---|---|---|
| Sleep guard | `power.sleep_guard`, `power.sleep_guard_max_ms` | on, 3000 ms | Holds a logind **delay** inhibitor. When the system is about to suspend, stoandl waits (at most `sleep_guard_max_ms`) until watch traffic in flight is done — typically the notification the push wake just produced — then lets it sleep. A delay lock never makes a suspend fail. |
| Wall-clock scheduling | (automatic) | — | Weather, calendar and the daily firmware check count **real** time, not awake time, and run right after a resume when overdue — riding on a wake the phone has anyway. Never an RTC alarm. |
| No reconnect busywork | (automatic) | — | After a reconnect, weather is only refetched if older than `weather.interval`, health data only re-requested after 15 min. |
| Notification catch-up | `notification.catch_up_minutes` | 60 min | A reconnecting watch also gets the notifications it hasn't received from that window — in Mode A the ones a push wake produced while the link was still down. Never older than the daemon's start or the watch's pairing; nothing the watch already has is re-sent. See [configuration.md → Missed notifications](configuration.md#missed-notifications-catch-up). |
| Discovery warning | (automatic) | — | A warning is logged if another app runs Bluetooth discovery while the display is off. stoandl's own discovery (BLE scan / BR/EDR inquiry) only runs in a pairing window; that is an explicit, 2-minute request, so it scans with the display off too (e.g. `stoandl watch pair` over ssh). It is stopped at every PrepareForSleep — before the suspend while the sleep guard holds its delay lock (`power.sleep_guard`) — and not restarted until the resume. So a phone that suspends with the display off only discovers while it is awake: keep it awake while pairing (screen on, or `systemd-inhibit --what=sleep sleep 150`); after a suspend inside the window `watch pair` says `Searching again — the phone slept …`. |
| Slow, fixed connection parameters | `ble.conn_params`, `ble.conn_params_fast` | off | The watch keeps the link at your idle set (e.g. 500–520 ms, latency 0) and never asks to change it — every change request needs the phone, i.e. a wake. Needs the watch's Connection Parameters characteristic, which the Pebble Time 2 doesn't have (see below). |
| Datalog pause | `power.pause_datalog_screen_off` | off | While the display is off, the watch holds back its datalog (health data every 15 min); it arrives when the phone is used again. Saves ~4 wakes/h in Mode B. |
| Quiet Time pause | `power.quiet_time_link_off` | off | No watch link during the watch's **scheduled** Quiet Time once the display has been off for 10 min; back on when the window ends or the display comes on. Saves the idle link's cost at night (see [What an idle link costs](#what-an-idle-link-costs) and [Quiet Time pause](#quiet-time-pause)). |

Every key in this table is also in the GUI (Settings → Daemon configuration → Deep sleep, and
Notifications for the catch-up) and in `stoandl daemon set <key> <value>`, which validates the value
first (`ble.conn_params*` with the same check the daemon applies at startup). All of them except
`power.pause_datalog_screen_off` and `power.quiet_time_link_off` take effect only after a daemon
restart; those two apply at once.

Log lines to look for (`/tmp/stoandl.log`): `Sleep guard on`, `PrepareForSleep: held the suspend …`,
`Notification catch-up: sending N unsent notification(s) created after …` (only when a reconnect has
something to catch up on; the time is UTC), `connected and services resolved (N ms after connect())`
(how long a reconnect took), `watch-managed connection parameters: idle …`,
`link parameters now …`, `link at idle parameters`, `Watch datalog sends paused/resumed`,
`Quiet Time: watch connection paused until …` / `… back on (…)`. With `STOANDL_LOG=DEBUG` every
suspend/resume is logged.

### What an idle link costs

A BLE connection stays alive only through a short radio exchange at a fixed interval, the *connection
event*, even when nobody has anything to send. The central (the phone) opens every event by
transmitting and waits for a reply. The watch may skip events when it has nothing to send (*peripheral
latency*); the phone may not, because it can't know which events the watch will skip. So latency saves
power on the watch only, and the phone's cost follows the **interval**:

| Link | Phone events per second | Watch events per second |
|---|---|---|
| 15 ms, latency 0 (the watch's fast set) | 67 | 67 |
| 30–45 ms, latency 3 (the Pebble Time 2's idle set) | 22–33 | 6–8 |
| 250 ms, latency 0 | 4 | 4 |
| 500 ms, latency 0 | 2 | 2 |

Notifications and requests only come on top. On the OnePlus 6 a night in Mode B with the watch connected
and no messages drew up to 8.3 mA more than a night in Mode A. That is an upper bound for the link: other
things changed between the two nights too. Whether a slower interval saves in proportion is not
measured yet; the controller may stay in a high-power state while it serves a link at all. The two
handles stoandl has: no link when nobody needs one ([Quiet Time pause](#quiet-time-pause)), and a slower
interval where the watch lets the phone choose it (below).

### Connection parameters — read this before turning them on

These keys work through the Pebble Pairing Service's Connection Parameters characteristic
(`00000005-328e-…`). The Pebble Time 2 (Core firmware, NimBLE) doesn't have it: there the keys have no
effect, and stoandl says so once per run (WARN `this watch has no Connection Parameters
characteristic`; with the keys unset a DEBUG line says the watch manages the link parameters itself).
The firmware still has the code behind the characteristic; only its GATT entry is missing.

**Without the characteristic the watch manages its own parameters**, and Linux accepts every valid
request from it (PebbleOS `gap_le_connect_params.c`, `bt_conn_mgr.c`):

| Watch state | Interval | Peripheral latency | Supervision |
|---|---|---|---|
| idle (default) | 30–45 ms | 3 | 6 s |
| busy: GATT discovery after a connect (up to 30 s, then 10 s), PPoG and app/firmware transfers | 15 ms | 0 | 6 s |

The watch asks for the idle set again 2 s after the last busy phase ends, retries a refused request up to
three times, and re-checks after every update. So the link runs at 15 ms for the first ~10–40 s after a
connect and after each transfer, and at 30–45 ms otherwise. BlueZ's `/etc/bluetooth/main.conf [LE]`
(`MinConnectionInterval`, `MaxConnectionInterval`, `ConnectionLatency`, `ConnectionSupervisionTimeout`)
only sets the parameters a connection *starts* with; the watch replaces them within seconds. It still
gives a better start (6 s supervision instead of 420 ms). (These are the firmware's sets; a btmon
capture on the OnePlus 6 hasn't confirmed them on the wire yet.)

On a watch that has the characteristic (older Pebbles, or Core firmware with a PebbleOS patch that
adds it), upstream libpebble3 tells it "the phone manages the parameters"
and never changes them, so the link keeps whatever it had at connect — often the watch's 15 ms
bulk-transfer set (about 67 radio events per second, forever). stoandl doesn't: with the keys unset it
writes nothing, and the watch keeps managing with its own sets, as above.
`ble.conn_params = 500,520,0,6000` lets the watch manage the parameters with that set in all three of
its response-time slots instead: it converges once and stays there.

On Linux the host has to accept the watch's request:

1. `/etc/bluetooth/main.conf`, section `[LE]`: `MaxConnectionInterval=416` (= 520 ms / 1.25) and
   `ConnectionSupervisionTimeout=600` (6 s). Without it the kernel rejects anything above 50 ms.
   Restart bluetoothd (`systemctl restart bluetooth`) and reconnect the watch.
2. **The LL trap.** The kernel rejects an LL "remote connection parameter request" whose max exceeds
   the max the connection was *created* with — and that is the device's last accepted parameters,
   which bluetoothd persists (`/var/lib/bluetooth/<adapter>/<watch>/info`, `[ConnectionParameters]`).
   If a connection ever ends right after the watch's own 15 ms discovery request, every later
   connection starts at 15 ms and can never slow down. stoandl detects this 60 s after connect and logs
   a WARN (`link still at …`). Fixes: remove the `[ConnectionParameters]` group from that file and
   restart bluetoothd, or carry the kernel patch "K5" (accept up to the adapter's
   `MaxConnectionInterval`; in the OnePlus 6 kernel since r9, `r9-0018`).
3. `ble.conn_params_fast` (a fast set during the connect handshake and bulk transfers) makes the trap
   likely — every boost is an accepted 15 ms request. Only use it with K5, or after btmon shows your
   watch uses L2CAP signalling instead of the LL procedure.

Trade-off: at 500 ms, watch buttons that talk to the phone (dismiss, music) react up to ~0.5 s later.
250 ms (`200,250,0,6000`) is a middle ground.

### Quiet Time pause

`power.quiet_time_link_off = true` (GUI: Deep sleep → *Pause watch connection during Quiet Time*) keeps
the watch disconnected during the watch's own **scheduled** Quiet Time: the weekday and weekend windows
you set on the watch or with `stoandl settings`. stoandl reads them from the synced watch settings and
applies the watch's rule: the day's own schedule decides (Saturday and Sunday the weekend one), a window
whose end is before its start runs overnight. Manual and calendar Quiet Time don't pause the link.

The link is paused while all of these hold, and comes back as soon as one doesn't:

- the window is active and the display has been off for 10 minutes (a display that is off when stoandl
  starts counts, so a restart inside the window stays paused);
- calls can't interrupt Quiet Time. With *Quiet Time interruptions* set to phone calls the watch would
  buzz for a call, so the link stays on (logged once per window);
- no pairing window, firmware or language update or call is running.

The pause starts once pending watch traffic is delivered. On a phone that sleeps through the window's
start or end, it starts or ends at the first wake after it, or at once when the display comes on. The
sleep guard lets the disconnect, and the reconnect after it, finish before a suspend. While paused there
is no standing `Device1.Connect()`, so the controller shouldn't scan for the watch either (to be
confirmed with btmon, [TESTING.md §5.37](../TESTING.md)).

What you lose while paused: calls and phone-side alarms don't reach the watch (the watch's own alarms
ring as usual); wrist actions (dismiss, reply, music, find phone) wait for the reconnect; health data,
weather and time sync arrive at the reconnect. Notifications posted during the pause reach the watch when
the link is back: paused time doesn't count towards `notification.catch_up_minutes`, so the watch gets
everything since the pause began (and the usual window before it), as if it had stayed connected in Quiet
Time. The watch skips a vibration within 3 s of the previous one, so the morning burst buzzes about
once. Notifications from before a daemon restart are not caught up (the catch-up never reaches back past
the daemon's start).

The pause is held in memory only: the watch's connect goal is untouched, so turning the option off, or a
crash, never leaves a watch that won't reconnect. Log lines: `Quiet Time: watch connection paused until
07:00`, `Quiet Time: watch connection back on (display on)`, and libpebble3's `holding every watch
disconnected` / `connections released`.

## Host prerequisites (Mode B)

stoandl can't keep a link alive across suspend on its own. On the OnePlus 6 (SDM845, WCN3990) that
takes, besides the settings above:

- kernel: the Bluetooth UART's runtime PM in system sleep (upstream `qcom_geni_serial` force suspend),
  a UART RX wake interrupt in the device tree, `hci_qca` keeping links across suspend (opt-in module
  parameter `hci_uart.qca_keep_links_on_suspend`) and reporting IBS wake indications as wakeup events;
- powerdevil: treat a Bluetooth wake as a dark ("Network") wake, so the display stays off;
- no Bluetooth settings page or other discovery session left open with the screen off.

### Switching between Mode A and Mode B

`qca_keep_links_on_suspend` is read-only at runtime (mode 0444), so switching modes means reloading
the Bluetooth driver. Some kernels fail to power Bluetooth on again after a reload (OnePlus 6 kernel
"r9": only a reboot brings it back), so prefer setting the parameter at boot (below). stoandl can keep
running across a reload. While no adapter is powered it pauses its connection attempts (INFO
`Bluetooth adapters: no adapter`, `stoandl watch list` says Bluetooth is off), and it reconnects on its
own once the controller is configured again. It doesn't touch the pairing: a failed connect never
deletes a bond. The first hardware test did lose a valid pairing here, through a check that has
since been removed — **implemented, to be tested** ([TESTING.md §5.32c, §5.32g](../TESTING.md)). Stopping
stoandl around the reload is optional; it only keeps those log lines out.

```sh
cat /sys/module/hci_uart/parameters/qca_keep_links_on_suspend   # current mode: Y or 1 = B, N or 0 = A
systemctl --user stop stoandl                                   # optional, see above
sudo rmmod hci_uart
sudo modprobe hci_uart qca_keep_links_on_suspend=1              # 0 for Mode A
sudo systemctl restart bootmac@bluetooth                        # postmarketOS only, see below
bluetoothctl show                                               # wait for its address and "Powered: yes"
systemctl --user start stoandl                                  # optional
```

On postmarketOS, `bootmac` sets the controller's public address only at boot. After a reload the
controller stays unconfigured (no public address) and unusable until you restart
`bootmac@bluetooth`. To pick a mode at boot, set the parameter in `/etc/modprobe.d/` (`options hci_uart
qca_keep_links_on_suspend=1`) or on the kernel command line (`hci_uart.qca_keep_links_on_suspend=1`);
neither has been tried yet.

## Without the host work (Mode A)

Without those, you're in Mode A. The settings here still help:

- Wall-clock scheduling keeps weather, calendar and the firmware check on time across suspends.
- The sleep guard holds a suspend until a notification that is already on its way to a connected watch
  has arrived. It doesn't hold a suspend for a reconnect.
- Expect the link to be up only on the longer wakes. One night on the OnePlus 6 (7 h, no messages):
  261 wakes, median 1.2 s awake (90th percentile 2.0 s); 28 suspend drops, but only 23 full sessions
  (a reconnect that got as far as syncing), 23–75 min apart. The watch costs about 7 mA overnight
  (31.5 mA against about 24.4 mA with Bluetooth off).
- A notification that arrives with a push wake is posted while the link is still down from the
  suspend. stoandl sends it once the watch has reconnected (in the first test, the link was back about
  8 s after the wake; about 5 s of that was the connector noticing the link late, which is fixed —
  **implemented, to be tested**, [TESTING.md §5.32h](../TESTING.md) — the ~2.9 s from the wake to the
  reconnect attempt remain).
  The catch-up is bounded, so a long disconnect doesn't replay old notifications. If the phone suspends
  again before the reconnect completes, the notification goes out at the next connect, as long as that
  falls within the catch-up window (`notification.catch_up_minutes`, default 60, set to cover those
  gaps; 0 = off) — see
  [configuration.md → Missed notifications](configuration.md#missed-notifications-catch-up).
  **Implemented, to be tested** ([TESTING.md §5.32b](../TESTING.md)).
