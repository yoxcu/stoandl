# stoandl extensions — examples

Extensions are host-side "companion apps" that drive watch notifications (and replies/actions) without
needing a watchapp. stoandl spawns each one as a child process and talks to it over a small
newline-delimited JSON-RPC protocol on stdio. Write them in any language; these examples are Python.

See [`../../docs/extensions.md`](../../docs/extensions.md) for the full protocol and design.

## Files

- **`stoandl_ext.py`** — a thin helper that wraps the wire protocol (handshake, `notify`, and the
  `on_action`/`on_reply`/`on_dismiss`/`on_watch_connected` callbacks). Copy it next to your extension.
- **`findphone.py`** — *Find My Phone*: a persistent watch notification whose "Ring" action plays a
  sound on this computer until "Stop". The watch→host mirror of the built-in find-my-watch; no watchapp.

## Try find-my-phone

```sh
mkdir -p ~/.config/stoandl/ext
cp stoandl_ext.py findphone.py ~/.config/stoandl/ext/
```

Add to `~/.config/stoandl/stoandl.conf`:

```ini
extensions.enabled      = findphone
extension.findphone.cmd = /usr/bin/python3 %h/.config/stoandl/ext/findphone.py
```

Restart the daemon (`systemctl --user restart stoandl`, or just run it). With a watch connected you'll
see a "Find My Phone" notification; open it on the wrist and choose **Ring phone**.

## Writing your own (sketch)

```python
from stoandl_ext import Extension
ext = Extension()

def on_message(room, sender, text):           # called from your own service loop
    ext.notify(app_name="Matrix", title=sender, body=text,
               canned_replies=["OK", "On my way", "Later"], ext_token=room,
               on_item=lambda item_id: remember(item_id, room))

ext.on_reply = lambda item_id, text: my_service_send(room_for(item_id), text)
ext.on_initialize = start_my_service_loop      # ext.config has your extension.<name>.* settings
ext.run("matrix", capabilities=["notify"])
```

Rules of thumb:
- **Never `print()`** — stdout is the protocol wire. Use `ext.log(...)` (goes to stderr → stoandl's log).
- Per-app **mute/style** applies to you automatically (your `app_name` is the key) — `stoandl notif mute
  <app>` and the wrist "Mute" action work on your notifications for free.
- Replies are **canned** (the watch has no keyboard); voice dictation is added when a transcription
  backend is wired. You receive the chosen/dictated string in `on_reply`.
- Send the reply back through **your own** service account — stoandl doesn't (and can't) reply on the
  desktop notification bus.
- Capabilities are **user-granted** in `stoandl.conf` (`extension.<name>.allow`); `notify` is the
  default. Untrusted extensions can be sandboxed with `extension.<name>.confine = true`.

> **Phase note:** this is the MVP (notify + reply/actions). Watchapp AppMessage companions
> (`sendAppMessage`/`onAppMessage`) and a `stoandl ext` CLI are planned next — see the design doc.
