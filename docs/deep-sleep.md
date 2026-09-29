# Phones that deep-sleep (keeping the watch link across suspend)

A Linux phone saves most of its battery by suspending whenever the screen is off (s2idle), waking
only for a push message, a call or an alarm — a few seconds awake, then asleep again. stoandl has a
few features for that kind of host. They are harmless on a desktop, and most are off unless you turn
them on. The reference device is a OnePlus 6 on postmarketOS with Plasma Mobile ("Mode B" below).

**Status:** implemented, compiles, unit/harness-tested off-device; **not yet verified on hardware**
(see [TESTING.md §5.32](../TESTING.md)).

postmarketOS and Alpine use musl. The SQLite driver bundled with stoandl is built for glibc, and
older builds crash-loop right after the first pairing. stoandl now works around this automatically on
musl (**implemented, to be tested**). See [README → Requirements](../README.md#requirements).

## Two ways a watch can live with a sleeping phone

- **Mode A — stock kernel.** Linux disconnects every Bluetooth link on each suspend
  (`hci_suspend_sync()`), and the watch reconnects after each wake. It works, but the watch shows
  "disconnected" most of the time, actions from the watch don't reach a sleeping phone, and every wake
  pays for a full reconnect.
- **Mode B — link kept across suspend.** The Bluetooth controller keeps the LE link while the SoC
  sleeps. The phone wakes only when the watch actually sends something, and notifications to the watch
  go out during the wakes the phone has anyway (the push that carried the message). This needs kernel
  work on the host (see [Host prerequisites](#host-prerequisites-mode-b)); stoandl's part is below.

stoandl talks to BLE-native watches (Pebble Time 2, Pebble 2) over BLE only — no Bluetooth Classic —
so everything here is about one LE link.

## What stoandl does

| Feature | Key(s) | Default | What it does |
|---|---|---|---|
| Sleep guard | `power.sleep_guard`, `power.sleep_guard_max_ms` | on, 3000 ms | Holds a logind **delay** inhibitor. When the system is about to suspend, stoandl waits (at most `sleep_guard_max_ms`) until watch traffic in flight is done — typically the notification the push wake just produced — then lets it sleep. A delay lock never makes a suspend fail. |
| Wall-clock scheduling | (automatic) | — | Weather, calendar and the daily firmware check count **real** time, not awake time, and run right after a resume when overdue — riding on a wake the phone has anyway. Never an RTC alarm. |
| No reconnect busywork | (automatic) | — | After a reconnect, weather is only refetched if older than `weather.interval`, health data only re-requested after 15 min. |
| Notification catch-up | `notification.catch_up_minutes` | 10 min | A reconnecting watch also gets the notifications it hasn't received from that window — in Mode A the ones a push wake produced while the link was still down. Never older than the daemon's start or the watch's pairing; nothing the watch already has is re-sent. See [configuration.md → Missed notifications](configuration.md#missed-notifications-catch-up). |
| Screen gate | `power.screen_gate` | on | The pairing-window BLE scan / BR/EDR inquiry run only while the display is on; a warning is logged if another app runs Bluetooth discovery while the display is off. |
| Slow, fixed connection parameters | `ble.conn_params`, `ble.conn_params_fast` | off | The watch keeps the link at your idle set (e.g. 500–520 ms, latency 0) and never asks to change it — every change request needs the phone, i.e. a wake. |
| Datalog pause | `power.pause_datalog_screen_off` | off | While the display is off, the watch holds back its datalog (health data every 15 min); it arrives when the phone is used again. Saves ~4 wakes/h in Mode B. |

Log lines to look for (`/tmp/stoandl.log`): `Sleep guard on`, `PrepareForSleep: held the suspend …`,
`watch-managed connection parameters: idle …`, `link parameters now …`, `link at idle parameters`,
`Watch datalog sends paused/resumed`. With `STOANDL_LOG=DEBUG` every suspend/resume is logged.

### Connection parameters — read this before turning them on

Upstream libpebble3 tells the watch "the phone manages the parameters" and never changes them, so the
link keeps whatever it had at connect — often the watch's 15 ms bulk-transfer set (about 67 radio
events per second, forever). `ble.conn_params = 500,520,0,6000` instead lets the watch manage the
parameters with that set in all three of its response-time slots: it converges once and stays there.

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
   `MaxConnectionInterval`; `bt-0005` in the OnePlus 6 notes).
3. `ble.conn_params_fast` (a fast set during the connect handshake and bulk transfers) makes the trap
   likely — every boost is an accepted 15 ms request. Only use it with K5, or after btmon shows your
   watch uses L2CAP signalling instead of the LL procedure.

Trade-off: at 500 ms, watch buttons that talk to the phone (dismiss, music) react up to ~0.5 s later.
250 ms (`200,250,0,6000`) is a middle ground.

## Host prerequisites (Mode B)

stoandl can't keep a link alive across suspend on its own. On the OnePlus 6 (SDM845, WCN3990) that
takes, besides the settings above:

- kernel: the Bluetooth UART's runtime PM in system sleep (upstream `qcom_geni_serial` force suspend),
  a UART RX wake interrupt in the device tree, `hci_qca` keeping links across suspend (opt-in module
  parameter) and reporting IBS wake indications as wakeup events;
- powerdevil: treat a Bluetooth wake as a dark ("Network") wake, so the display stays off;
- no Bluetooth settings page or other discovery session left open with the screen off.

Without those, the settings here still help Mode A (the sleep guard delivers queued notifications
before the phone sleeps; wall-clock scheduling keeps the weather fresh).
