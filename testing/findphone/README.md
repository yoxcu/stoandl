# Find My Phone — watchapp

A tiny Pebble watchapp for stoandl's extension system: **UP rings this computer, DOWN stops it.**

It's a watchapp rather than a notification because PebbleOS only offers a notification's action menu
while it's on screen — a watchapp's buttons work whenever you open it. The app just sends an AppMessage
to the phone (a single `uint8` at key `0`: `1` = ring, `2` = stop); the host side is
[`examples/extensions/findphone.py`](../../examples/extensions/findphone.py), which registers this
app's UUID and plays/stops a sound.

UUID: `de72f1d0-1111-4a17-9a6b-0123456789ab` (must match the companion's `APP_UUID` and the
`extension.findphone.allow = appmessage:<uuid>` grant).

## Build & install

Uses the same SDK harness as `datalogtest` (builds with the Core Pebble SDK):

```sh
cd testing/findphone
pebble build
pebble install --phone <watch-ip>        # or: stoandl sideload build/findphone.pbw
```

## Wire up the companion

```sh
mkdir -p ~/.config/stoandl/ext
cp ../../examples/extensions/{stoandl_ext,findphone}.py ~/.config/stoandl/ext/
```

In `~/.config/stoandl/stoandl.conf`:

```ini
extensions.enabled        = findphone
extension.findphone.cmd   = /usr/bin/python3 %h/.config/stoandl/ext/findphone.py
extension.findphone.allow = appmessage:de72f1d0-1111-4a17-9a6b-0123456789ab
# optional: extension.findphone.sound = /usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga
```

Restart stoandl, open **Find My Phone** on the watch, press **UP** → the computer rings; **DOWN** stops.
