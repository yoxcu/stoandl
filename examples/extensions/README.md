# stoandl extensions — examples

Extensions are host-side "companion apps" that drive watch notifications (and replies/actions), and/or
act as a PebbleKit-style companion to a watchapp. stoandl spawns each one as a child process and talks
to it over a small newline-delimited JSON-RPC protocol on stdio. Write them in any language; these
examples are Python.

See [`../../docs/extensions.md`](../../docs/extensions.md) for the full protocol and design.

## How extensions are laid out

Each extension lives in its own directory `~/.config/stoandl/ext/<name>/`. The default entry point is
`<name>.py` (run with `python3`, with that directory as the working dir, so `import stoandl_ext` finds
its sibling). `extensions.enabled` in `stoandl.conf` is the run-list; the `stoandl ext` commands manage
it for you. There's **no sandbox and no capability config** — an extension runs as you, like any script.

Overrides (optional): `extension.<name>.cmd = …` in `stoandl.conf`, or a `manifest.json` in the
extension dir (`{ "cmd": "node index.js", "config": { … } }`) for self-contained, non-Python extensions.

## Files

- **`stoandl_ext.py`** — a thin helper that wraps the wire protocol (handshake, `notify`, the
  `on_action`/`on_reply`/`on_dismiss`/`on_watch_connected` callbacks, and the watchapp verbs
  `register_app`/`send_app_message`/`launch_app`/`install_pbw` + `on_app_message`). Ship a copy in your
  extension dir.
- **`stoandl_ext.go`** — the same helper for Go (`package main`): copy it next to your extension and
  drive it from your own service loop. Used by `matrix/`.
- **`matrix/`** — a git submodule ([yoxcu/stoandl-matrix](https://github.com/yoxcu/stoandl-matrix)): a
  **Matrix** extension (Go) — messages on the wrist + canned replies, decrypting E2EE rooms. Logs in as a
  second device of your account; builds to a static, musl-clean binary (`mautrix-go` + pure-Go `goolm`).
  Notify-only reply extension, no watchapp. Has its own README + `package.sh`. After cloning stoandl, run
  `git submodule update --init` to populate it.
- **`findphone/`** — a git submodule ([yoxcu/findphone](https://github.com/yoxcu/findphone)): the
  *Find My Phone* watchapp + its companion, a complete, forkable boilerplate. UP rings this computer,
  DOWN stops it. Has its own README, build, and `package.sh`. After cloning stoandl, run
  `git submodule update --init` to populate it.

## Install find-my-phone (one shot)

```sh
cd findphone && ./package.sh                 # builds the .pbw + makes findphone.tar.gz (needs the Pebble SDK)
stoandl ext install findphone/findphone.tar.gz   # extracts to ext/findphone/, sideloads the .pbw,
                                                 # enables + starts it — no daemon restart
```

Open **Find My Phone** on the watch → **UP** rings the computer, **DOWN** stops it.

Manage it live: `stoandl ext list`, `stoandl ext disable findphone`, `stoandl ext enable findphone`,
`stoandl ext restart findphone`, `stoandl ext uninstall findphone`.

## Writing your own (sketch)

```python
from stoandl_ext import Extension
ext = Extension()

def on_message(room, sender, text):           # called from your own service loop
    ext.notify(app_name="Matrix", title=sender, body=text,
               canned_replies=["OK", "On my way", "Later"], ext_token=room)

ext.on_reply = lambda item_id, text: my_service_send(room, text)   # send via your own account
ext.on_initialize = start_my_service_loop      # ext.config has your extension.<name>.* settings
ext.run("matrix", capabilities=["notify"])     # capabilities are informational
```

Package it (`<name>/<name>.py` + `<name>/stoandl_ext.py`, plus a `<name>/<name>.pbw` if it has a
watchapp) into a `.tar.gz`/`.zip` and `stoandl ext install` it.

Rules of thumb:
- **Never `print()`** — stdout is the protocol wire. Use `ext.log(...)` (goes to stderr → stoandl's log).
- Per-app **mute/style** applies to you automatically (your `app_name` is the key) — `stoandl notif mute
  <app>` and the wrist "Mute" action work on your notifications for free.
- Replies are **canned** (the watch has no keyboard; the action menu is only on the *live* notification,
  not in history). You receive the chosen/dictated string in `on_reply`; send it back via your own
  service account — stoandl doesn't (and can't) reply on the desktop notification bus.
- A persistent "tap me anytime" trigger (like find-my-phone) needs a **watchapp**, since notification
  actions vanish once the notification scrolls into history.
