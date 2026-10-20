# Extensions (companion apps) — design

> **Status: implemented (Phases 0–4), on `main`.** Architectural decisions locked 2026-06-17.
> This is the spec the implementation tracks; the per-phase status is at the bottom (Phase 5 = polish TODO).

## Goal

Let users plug in their own integrations — Matrix, Signal, Discord, SMS, "find my phone", anything —
**host-side**, the way Android PebbleKit companion apps work, but able to drive **notifications** so a
watchapp is **not** required. An extension can do any subset of:

- **(a)** push notifications to the watch (with named actions + canned replies), **no watchapp**;
- **(b)** receive a callback when the user acts on the watch (chosen reply text / action / dismiss) and
  send the response back through *that service's own account* — stoandl can't own
  `org.freedesktop.Notifications` on GNOME/KDE, so per-protocol send-back is the only and intended path;
- **(c)** be a PebbleKit-style companion to a watchapp UUID it ships — AppMessage send/receive, launch,
  and `.pbw` install.

## Architecture: out-of-process, stdio JSON-RPC

**Each extension is a child process stoandl spawns and supervises, speaking newline-delimited
JSON-RPC 2.0 over stdin/stdout** ("PebbleKit over pipes"). A thin per-language helper (e.g. a ~40-line
`stoandl_ext.py`) is shipped so authors copy a working template instead of hand-rolling the wire format.

Why this over the alternatives that were evaluated and rejected:

- **In-process JVM plugin SPI** — definitionally can't run a native Python/Go/Rust extension, and a
  plugin's `System.exit`/OOM/segfault/CPU-spin takes down the BLE link (the daemon's whole job).
- **Declarative config + hook commands** — a blocking spawn-and-await would run *inline* in the
  unbuffered notification `SharedFlow` collector, so a slow hook wedges BlobDB/PutBytes/music/health;
  it also reused the `#`-truncating config parser for command lines and offered shell-template
  substitution of watch-typed reply text (an injection surface).
- **D-Bus bus interface** — viable, but heavier for a solo, test-less project (bus-name claiming,
  `.service` activation, SO_PEERCRED/polkit, `a{sv}`/Variant type-loss on AppMessage dicts, and a
  broadcast-signal variant leaks reply text to any bus peer). stdio makes the daemon the **sole peer**
  — a smaller, more auditable trust boundary.

stdio + a persistent supervised child + **mandatory async dispatch off the protocol read thread**
structurally avoids the BLE-wedge and isolates third-party failures behind an OS process boundary.

## The wire protocol

One JSON object per line, framed by `\n`, JSON-RPC 2.0. The child reads on **stdin**, writes on
**stdout**, and logs to **stderr** (folded into logback under a per-extension tag — never print to
stdout, it corrupts the frame). Requests carry `id`; fire-and-forget host→ext callbacks are JSON-RPC
*notifications* (no `id`).

### Layout, discovery & handshake

Each extension is a directory `<configDir>/ext/<name>/`. The default entry point is `<name>.py` (run
with `python3`, cwd = that dir, so `import stoandl_ext` finds its sibling). `extensions.enabled` in
`stoandl.conf` is the run-list; the `stoandl ext` commands edit it. **No sandbox, no capability config**
— an extension runs as you, like any script.

**Per-extension settings live in the extension's own dir, not `stoandl.conf`.** A `config` file at
`<configDir>/ext/<name>/config` (`key = value`, `#` comments, leading `~`/`%h` expand to home, no
`extension.<name>.` prefix — it's already scoped) holds that extension's homeserver/tokens/options. It's
**preserved across reinstalls** (`stoandl ext install` won't clobber it). So `stoandl.conf` stays at just
`extensions.enabled`. Settings resolve as: a `manifest.json`'s `config` (author defaults, for a
self-contained archive: `{ "name":…, "cmd":…, "config":{…}, "requiresConfig":true, "watchapps":["app.pbw"] }`) **<** a
leftover `extension.<name>.<key>` in `stoandl.conf` (back-compat) **<** the `config` file (the user's
place, which the GUI and CLI write, so it wins). The launch command (`cmd`, for non-Python extensions)
may come from any of those layers too, with `stoandl.conf` first so a broken `config` can be overridden.
On install, enable and restart, the status line ends with where to configure it: for an extension with a
`configSchema`, "Settings: in the stoandl app (Apps → Extensions → <name> → Settings) or stoandl ext config
<name>"; otherwise "Settings: <configDir>/ext/<name>/config (start from config.example), then stoandl ext
restart <name>".

**GUI-facing manifest fields (optional).** A `"description"` (a one-line summary) shows in a front-end's
plugin list, and a `"configSchema"` lets a GUI render a **native settings form** for the extension
instead of pointing the user at the `config` file. It's a JSON array of typed fields:

```jsonc
"configSchema": [
  {"key":"homeserver", "type":"string", "label":"Homeserver URL",
   "description":"Your Matrix server, e.g. https://matrix.org",
   "help":"The address you sign in to in Element or NeoChat. Ask your server admin if unsure."},
  {"key":"password",   "type":"string", "label":"Password",  "secret":true, "required":true},
  {"key":"notify",     "type":"enum",   "label":"Notify on",  "options":["pushrules","mentions","all"]}
]
```

Field `type` is `string`, `bool`, `int`, or `enum` (+`options`). Optional `description` is one line shown
under the field, `help` the longer text behind its help button (the GUI renders both). A GUI reads the
effective values (manifest `config` defaults overlaid by the `config` file), shows the form, and writes
changes back — **merged** into the `config` file, booleans and ints with their JSON types — then the daemon
restarts the extension if it is running. In the GUI an `int` field steps by 1 and an `enum` opens a picker;
an enum whose stored value isn't one of its options is saved as the first option. Without a `configSchema`
a front-end just reports "no settings" (there is no hosted config page for extensions — the never-built
`url` backend and its `ExtOpenConfig` were removed; the daemon's config-page server serves PKJS settings
pages only).

**`stoandl ext config`** is the CLI form of the same three methods:

```
stoandl ext config matrix                       # list the settings (secrets show only whether they're set)
stoandl ext config matrix homeserver            # print one
stoandl ext config matrix homeserver https://…  # set one (checked against its type and options)
stoandl ext config matrix password              # a secret: prompted without echo, never taken from argv
stoandl ext config matrix password --unset      # clear (a secret is removed from the store)
```

The daemon restarts the extension when it runs, or starts it once its required settings are there.
Without a `configSchema` it lists and sets the plain keys of the extension's `config`.

**Secrets.** A `string` field with `"secret": true` is kept in the daemon's secret store (the system
keyring, else the 0600 `<configDir>/secrets` file) under `ext:<name>:<key>`, never in the `config` file:

- At spawn its value goes into **that child's** `initialize.params.config.<key>` and nowhere else.
- `ExtGetConfig` reports it as `""`, plus a reserved `_secrets` key listing the secret keys that are
  stored (`"api_key"` or `""`). In `ExtSetConfig`, `""` leaves a secret unchanged (a form posts every
  field), JSON `null` removes it, anything else stores it.
- A secret found in plaintext in the `config` file (hand-edited, or from before this rule) is moved into
  the store and its line deleted, at daemon start, at spawn and on the next settings read or write. One
  in `stoandl.conf` (`extension.<name>.<key>`) is used only while nothing is stored, with a warning;
  stoandl.conf isn't rewritten, but `stoandl support` redacts it. A manifest default for a secret is
  ignored.
- `stoandl ext uninstall <name> --delete-config` removes the stored secrets too; `--keep-config` keeps
  them. Backups never carry them (the `secrets` file is excluded, the keyring is never backed up).

**`required`** (schema field): when any field is `required`, the extension starts only once every
required field has a non-empty value (a secret counts when stored); otherwise `requiresConfig` below
applies.

**`requiresConfig`** (manifest): an extension that can't run until configured (e.g. Matrix needs a
homeserver) declares `"requiresConfig": true`. If it's started without any user settings (no `config`
file, no stored secret and no `extension.<name>.*`), the daemon **does not spawn it** — it logs a warning and posts a
"<name> needs setup" *desktop* notification, "<name> can't start until it's set up. Settings: …" with the
same pointer as above (stoandl's passive monitor bridges it to the watch, so it's not pushed directly to
the watch; `alerts.extensions` silences it), and `install`/`enable`/`restart`
return a clear "not started — needs configuration" status instead of a fake failure. Configure it (the
GUI form or `stoandl ext config` start it by themselves), or edit its `config` and run
`stoandl ext restart <name>`. (Extensions whose settings are all optional, like find-my-phone's `sound`,
omit the flag and start normally.)

**`watchapps`** (manifest): bundled `.pbw` filenames stoandl keeps installed for the extension, e.g.
`"watchapps": ["matrix-preview.pbw"]`. Declared apps are **managed**: the daemon (re)installs them on every
watch connect and on `enable` (idempotently — it skips the sideload when the locker already holds that
version, so bump the `.pbw`'s version to ship an update), and **removes** them on `disable`/`uninstall`. An
extension that bundles a `.pbw` *without* declaring it falls back to the legacy behaviour — sideloaded once
at install, removed at uninstall, but not re-synced on connect or removed on disable. The usual way to add one:

```
stoandl ext install findphone.tar.gz   # extract to ext/findphone/, sideload a bundled .pbw,
                                        # enable (append to extensions.enabled) + hotplug-start
stoandl ext install ./examples/extensions/findphone   # or straight from an unpacked directory
stoandl ext list | enable <n> | disable <n> | restart <n> | uninstall <n> | config <n> [<key> [<value>]]
# uninstall also removes a bundled watchapp from the watch (reads the .pbw's UUID → removeApp), and if
# the extension has a `config`, prompts to keep it (default yes; reinstall restores it) — or pass
# --keep-config / --delete-config to skip the prompt.
```

`ext install` takes an archive (`.tar.gz`/`.tgz`/`.tar`/`.zip`) or a directory. A directory is copied with
symlinks kept as symlinks (never followed, so an uninstall never touches what a link points at),
`.git`/`.hg`/`.svn` skipped and the entry point's exec bit kept, up to 512 MB of files (anything bigger is
refused with a hint to install an archive of just the extension). A path inside, or containing, the
install location `<configDir>/ext/<name>` is refused (`… overlaps the install location …`). Either way the
new copy is staged and swapped in only once complete, so a failed install leaves the previous version
installed and running; the extension's `config` is carried over.

`ExtensionManager` spawns each enabled child and does the **`initialize` handshake first** (host→ext
events are dropped until it lands — the Android `onListenerConnected` analog):

```jsonc
// host -> ext (request)
{"jsonrpc":"2.0","id":0,"method":"initialize",
 "params":{"protocolVersion":1,
           "config":{"homeserver":"…","token_file":"…"},   // this extension's settings (manifest + conf)
           "watch":{"connected":true}}}
// ext -> host (result = manifest; capabilities/egress are informational, logged, not enforced)
{"jsonrpc":"2.0","id":0,
 "result":{"name":"matrix","protocolVersion":1,
           "capabilities":["notify"],
           "egress":["matrix.example.org:443"],
           "cannedReplyDefaults":["OK","On my way","Call you later"]}}
```

The manifest is informational (there's no grant to exceed): with no sandbox, a capability gate would be
friction without real security, so it was dropped — `extensions.enabled` is the only knob.

### notify + actions + canned reply + dismiss

```jsonc
// ext -> host (request)
{"jsonrpc":"2.0","id":7,"method":"notify","params":{
  "extToken":"room!abc:42",          // child's own correlation tag, echoed back verbatim
  "appName":"Matrix",                // THE policy key — feeds the per-app mute/style store
  "title":"Alice","body":"dinner?","subtitle":"#weekend",
  "iconCode":"NotificationGeneric","color":"Red","vibe":"double",  // optional; per-app override wins
  "actions":[{"id":"archive","label":"Archive"}],
  "reply":{"cannedReplies":["OK","On my way","Later"],"label":"Reply"},
  "image":"data:image/png;base64,iVBOR…",   // optional picture: data: URI, file: URI or absolute path
  "urgent":true}}                    // optional: not held back by notification.only_when_locked
// host -> ext (result)
{"jsonrpc":"2.0","id":7,"result":{"itemId":"e3b0c442-…"}}   // daemon-issued correlation id
```

`urgent` (optional, default false) sends the notification even while `notification.only_when_locked`
holds the rest back because the device is in use. Set it only for something the user is waiting for on
the wrist, such as the answer to a question they just asked there; a held-back notification is dropped,
not delayed.

`image` (optional) is a picture shown in the notification on watches that can (Pebble Time 2 / Round 2,
firmware 4.35+) while `notification.images` is on: PNG, GIF or BMP, and JPEG where the JRE has it. The
daemon decodes and stores it once and the watch pulls it, sized for its screen, when the card shows.
`onWatchConnected` carries `{"notificationImages": true|false}`: whether a picture will show on the watch
that just connected (re-sent on every connect), so an extension can use its own fallback otherwise (the
Matrix extension's Preview watchapp).

`subtitle` (optional) is the large bold line under the title, and it is always kept. The app name
follows `notification.app_name` (or the app's own override, `stoandl notif style <app> --app-name`): by
default a small line under the title, in the subtitle slot only when the extension sends no subtitle,
and never when it would repeat the title or the subtitle.

`reply.cannedReplies` may be empty: the host then uses `notification.canned_replies`. The list is cut
after the last whole item that fits the firmware's 512 bytes. `reply.label` (optional, default `Reply`)
is the action's title on the watch. The older `allowVoice` field is accepted and ignored: the watch
offers voice on any model with a microphone regardless.

Callbacks (host→ext notifications, dispatched **async** — never blocking the read loop):

```jsonc
{"method":"onAction", "params":{"extToken":"room!abc:42","itemId":"e3b0…","action":"archive"}}
{"method":"onReply",  "params":{"extToken":"room!abc:42","itemId":"e3b0…","text":"On my way"}}
{"method":"onDismiss","params":{"extToken":"room!abc:42","itemId":"e3b0…"}}
```

`closeNotification {itemId}` lets a child proactively clear a notif (message read elsewhere). The watch marks
the item dismissed (libpebble3 `markNotificationRead`, whose STATE_READ flag is the firmware's *dismissed*
status bit), which takes it out of the watch's notification list.

### AppMessage / watchapp companion

```jsonc
// ext -> host
{"id":11,"method":"registerApp","params":{"uuid":"6f1c…"}}      // arms inbound; UUID must be in grant
{"id":12,"method":"sendAppMessage","params":{"uuid":"6f1c…",
   "data":{"0":{"t":"cstring","v":"Berlin"},"1":{"t":"uint8","v":21}}}}  // -> {"result":"ack"|"nack"|"timeout"}
{"id":13,"method":"launchApp","params":{"uuid":"6f1c…"}}        // / stopApp   -> {"ok":true}
{"id":14,"method":"installPbw","params":{"path":"/abs/app.pbw"}}        // -> {"ok":true}  (idempotent: skips if the locker already has that UUID at the .pbw's version — safe to call on every onWatchConnected; bump the version to ship an update)
// host -> ext (NOTIFICATION). The daemon ACKs the watch on receipt, then forwards; fire-and-forget.
{"method":"onAppMessage","params":{"uuid":"6f1c…","transactionId":7,
   "data":{"0":{"t":"int","v":12}}}}
```

Dict keys are integers-as-JSON-strings (`AppMessageDictionary = Map<Int,Any>`). **Values are
typed-tagged** `{"t":"uint8|int|uint|cstring|bytes","v":…}` — JSON has one number type but
libpebble3/C watchapps distinguish `uint8` vs `int32` (`dict_read_uint8` vs `dict_read_int`); bare
numbers would silently mismatch.

## Capabilities & config

- **Opt-in, off by default.** Nothing spawns unless `extensions.enabled` lists it (and a `<name>/`
  dir / `cmd` resolves an entry point).
- **No capability grant, no sandbox.** An extension runs as your user — like any script you run — so a
  permission gate would be friction without real security. The manifest's `capabilities` are
  informational. (Trust comes from *you* installing it.)
- Per-extension config (tokens, homeserver) is injected only into that one child's `initialize` —
  never shared, so an extension can't read another's secrets via stoandl.
- AppMessage UUIDs aren't gated; if two extensions register the same watchapp UUID they'd both observe
  it (we read the broadcast packet flow, so no one *steals* — see below). Cross-extension UUID
  arbitration is a possible future refinement.

## Reply UX on a keyboard-less watch

No free text crosses the wire. A `reply` block produces a Pebble `Response` (0x03) action carrying the
`CannedResponse` (0x08) attribute: on the wrist the user opens the notification → SELECT → **Reply** →
the canned list the extension supplied (plus **Voice** dictation on mic'd watches). The chosen string —
or the dictated transcript — comes back in the action invocation's attributes; the daemon extracts it
and delivers `onReply{text}`. Named non-reply actions are `Generic` actions returning `onAction{action}`.
Degrades correctly: mic-less hardware shows only the canned list; voice falls back to canned.

> **Voice:** dictation needs `voice.backend` set in stoandl (a local speech engine or an HTTP service,
> [docs/voice.md](voice.md)); with it off the watch says "Enable voice in the settings page". The
> dictated text reaches `onReply` exactly like a canned reply.

### Serving plugin sources and actions

An extension can serve [plugin](plugins.md) data to watchfaces and watchapps. It declares a `plugin` block
in its `initialize` result, in the same shape as a `.pbw`'s plugin manifest:

```jsonc
// ext -> host: the initialize result
{"jsonrpc":"2.0","id":0,"result":{"name":"Matrix","capabilities":["notify"],
  "plugin":{"description":"Rooms with unread messages",
    "sources":[{"category":"messages","items":["room"],"properties":{"name":["shortText"],"unread":["numericValue","shortText"]},
                "supportsMultiple":true,"callerPermissions":[{"name":"Notifications"}],"suggestedRefreshIntervalSec":60}],
    "actions":[{"name":"mark_read","targets":["messages/room"],"callerPermissions":[{"name":"Notifications"}]}]}}}
// host -> ext: a read (when something subscribes, on sourceChanged, and every suggestedRefreshIntervalSec)
{"jsonrpc":"2.0","id":5,"method":"readSource","params":{"category":"messages","item":"room","properties":["name","unread"]}}
{"jsonrpc":"2.0","id":5,"result":{"instances":[{"instanceId":"!abc","properties":{"name":{"shortText":{"text":"Family"}},
  "unread":{"numericValue":{"value":3},"shortText":{"text":"3"}}}}]}}
// host -> ext: an action
{"jsonrpc":"2.0","id":6,"method":"invokeAction","params":{"action":"mark_read","args":{"instanceId":"!abc"}}}
{"jsonrpc":"2.0","id":6,"result":{"ok":true,"text":"Marked read","refreshed":["messages/room"]}}
// ext -> host: the data changed, read again
{"jsonrpc":"2.0","method":"sourceChanged","params":{"category":"messages","item":"room"}}
```

The plugin's uuid is stable per extension name unless the block sets `"uuid"`. It is listed as kind
`extension` (`stoandl plugins`, the GUI's Plugins segment) and goes away while the extension isn't running.
An installed extension is trusted; its `callerPermissions` decide what a watchapp must be granted to read
or invoke. Requests time out after 15 s.

### transcribe (speech to text for the extension)

```jsonc
// ext -> host (request); the file is read by the daemon, WAV or Ogg/Opus
{"jsonrpc":"2.0","id":21,"method":"transcribe","params":{"path":"/tmp/voice-note.ogg","language":"de"}}
// host -> ext
{"jsonrpc":"2.0","id":21,"result":{"text":"bin gleich da"}}
{"jsonrpc":"2.0","id":21,"error":{"message":"off"}}              // voice.backend = off
{"jsonrpc":"2.0","id":21,"error":{"message":"unavailable: voice.command is empty"}}
```

The daemon's own speech-to-text (`voice.*`), so an extension needs no engine, codec or network access of
its own. `language` defaults to `voice.language`. Up to a minute; answered asynchronously. The Matrix
extension uses it for voice notes.

### synthesize (text to speech for the extension's watchapp)

```jsonc
// ext -> host (request); format is "pebble-adpcm" (default) or "wav", language is optional (de, en, de-DE…)
{"jsonrpc":"2.0","id":41,"method":"synthesize","params":{"text":"Berlin is the capital.","language":"en","format":"pebble-adpcm"}}
// host -> ext
{"jsonrpc":"2.0","id":41,"result":{"blocks":["AAA/ABfJm0EUyYuB…", "…"],"ms":1840}}   // pebble-adpcm: base64 blocks
{"jsonrpc":"2.0","id":41,"result":{"path":"/run/user/1000/stoandl/tts/synth-….wav","ms":1840}}  // wav
{"jsonrpc":"2.0","id":41,"error":{"message":"off"}}                    // tts.backend = off
{"jsonrpc":"2.0","id":41,"error":{"message":"unavailable: no voice chosen (tts.local_voice)"}}
{"jsonrpc":"2.0","id":41,"error":{"message":"busy"}}                   // another consumer holds the engine
{"jsonrpc":"2.0","id":41,"error":{"message":"cancelled"}}              // this extension's newer synthesize replaced it
```

The daemon's own text to speech (`tts.*`, [voice.md](voice.md#text-to-speech)), so an extension needs no
engine of its own. `pebble-adpcm` returns the speech as watch-speaker blocks (IMA ADPCM, 8 kHz, at most
2000 bytes each, base64), ready to stream one per AppMessage; `wav` returns a 16-bit WAV at the engine's
rate, kept for 10 minutes. The text is cut to 2000 characters. `language` picks the voice
([voice.md → Languages](voice.md#voices)): `tts.local_voice` when it speaks the language, else the first
installed voice that does. One synthesis runs at a time, with a 25 s engine deadline (inside the 30 s a
client like the assistant waits):

- `busy` means **another** consumer (another extension, or D-Bus `Speak`) is synthesising; the request is
  not queued, so a client pacing a reading knows at once.
- A new `synthesize` from the **same** extension cancels its own one still in flight (the engine is
  killed) and that one is answered `cancelled`. A client that pipelines sentences, or a user who asks to
  replay, therefore always gets its latest request served.
- The system is kept awake while it synthesises.

Streaming the blocks to a watchapp follows [speaker-stream.md](speaker-stream.md): the Go helper wraps the
call as `Ext.Synthesize(text, language, cb)`, and `SpeakerPump` streams the blocks, packed several to a
message and paced, with START/END/ABORT and a sender-chosen start (HOLD/PLAY), to a watchapp that vendors
`examples/extensions/watchlib/stoandl_speaker.{c,h}`. A sender may go at
most 30 s between two speaker messages of a stream; the watch drops a stream after 35 s without one. The
speaker keys `0x7FFF0000`–`0x7FFF0003` are reserved in every watchapp that vendors the receiver; app-defined
AppMessage keys must stay below them. The [assistant](assistant.md) reads its answers this way.

A daemon older than `synthesize` never answers it (newer daemons answer any unknown method at once, below),
so a client should time out the first call (the assistant treats 30 s without an answer as "text to speech
unavailable" and stops reading unasked).

### Unknown methods

A request whose method the daemon doesn't know is answered at once with the JSON-RPC error `-32601`
`method not found: <method>`, so an extension written for a newer daemon can tell and fall back instead of
waiting.

### keepAwake / release (hold off a suspend)

```jsonc
// ext -> host (requests)
{"jsonrpc":"2.0","id":31,"method":"keepAwake","params":{"why":"answering a question","maxMs":60000}}
{"jsonrpc":"2.0","id":31,"result":{"token":"3f0c9a2e-…"}}
{"jsonrpc":"2.0","id":32,"method":"release","params":{"token":"3f0c9a2e-…"}}
{"jsonrpc":"2.0","id":32,"result":{}}
```

For short work a suspend would break, such as a network request the watch is waiting on. Each hold is a
logind sleep *block* (`<name>: <why>`; `why` is cut to 80 characters): a suspend asked for meanwhile is
refused, so keep it short and release it. `maxMs` is required, `1..60000` (larger is clamped); the hold
ends by itself after it. To hold longer, take a new token before the old one expires. `release` of an
unknown or expired token still answers `{}`. At most 4 holds per extension (a fifth is the error
`keepAwake: too many holds`), and every hold ends when the child exits or is stopped.

The per-notification handler returns an **immediate optimistic** `TimelineActionResult(true, ResultSent,
"Sent")` to the watch and fires `onReply`/`onAction` to the child **asynchronously** (the child then
talks to its own service). Honesty seam: the wrist says "Sent" before the service confirms; a downstream
failure is surfaced by a follow-up `notify()`, not by the original result screen.

## The single `pushToWatch` choke point (orthogonality)

The existing passive desktop-notification bridge and every extension funnel through **one** extracted
helper — `pushToWatch(appName, title, body, subtitle, actions, canned, styleSource, ownerRoute)` —
carved out of the block currently fused inside `DbusNotificationListenerConnection`'s `collect{}`. That
helper is the **only** place that: lazy-adds the `NotificationAppItem` keyed by `appName`, applies
`isMutedNow()` (drop-before-send — an extension *cannot* bypass mute), applies per-app icon/color/vibe,
pins `ANDROID_NOTIFICATIONS_UUID`, attaches the per-item `actionHandlers` map, and records the **one**
owner route map `itemId → {ownerId, extToken}` (generalizing today's `itemIdToDbusId` /
`itemIdToMutePkg`).

So `stoandl notif mute Matrix` and the wrist "Mute <app>" action work on extension notifications **for
free** (anything an extension's per-item handler doesn't claim falls through to the global
`DbusNotificationActionHandler`). There is no second notification pipeline and no second mute store —
the desktop monitor (`ownerId="desktop"`), firmware notifications, and every extension all call
`pushToWatch`. The desktop bridge becomes "the trusted built-in extension," conceptually (it is *not*
rewritten as a child process — it's trusted, in-tree, and uniquely needs `BecomeMonitor` + the
`itemId→dbusId`→`CloseNotification` round-trip).

## Worked examples

### Matrix (Python, notify + reply)

```python
import sys, json, threading
def send(o): sys.stdout.write(json.dumps(o)+"\n"); sys.stdout.flush()
def log(m): sys.stderr.write(m+"\n")            # NEVER print to stdout — it corrupts the frame
rooms = {}                                       # itemId -> room (kept in-process, no marshalling)

for line in sys.stdin:
    msg = json.loads(line); m = msg.get("method")
    if m == "initialize":
        cfg = msg["params"]["config"]
        send({"jsonrpc":"2.0","id":msg["id"],"result":{
            "name":"matrix","protocolVersion":1,"capabilities":["notify"],
            "egress":[cfg["homeserver"].split("//")[1]+":443"],
            "cannedReplyDefaults":["OK","On my way","Call you later"]}})
        threading.Thread(target=sync_loop, args=(cfg,), daemon=True).start()
    elif m == "onReply":
        p = msg["params"]; matrix_send(rooms[p["itemId"]], p["text"])   # own egress, own account
    elif m == "onDismiss":
        matrix_mark_read(rooms.get(msg["params"]["itemId"]))
    elif m == "shutdown":
        break

def on_incoming_dm(room, sender, text):           # called from sync_loop
    rid = next_id()
    send({"jsonrpc":"2.0","id":rid,"method":"notify","params":{
        "extToken":room,"appName":"Matrix","title":sender,"body":text,
        "reply":{"cannedReplies":["OK","On my way","Later"]}}})
    # read the {id:rid,result:{itemId}} reply in the reader loop and set rooms[itemId]=room
```

### Find my phone (the previously-dropped watch→host direction, ~20 lines)

The symmetric mirror of the working find-my-watch (`Call.RingingCall`):

```python
if m == "initialize":
    send({"jsonrpc":"2.0","id":msg["id"],"result":{"name":"findphone","capabilities":["notify"],"egress":[]}})
    send({"jsonrpc":"2.0","id":1,"method":"notify","params":{   # one persistent notif with a Ring action
        "extToken":"fp","appName":"Find My Phone","title":"Find My Phone",
        "body":"Ring this computer from your wrist.",
        "actions":[{"id":"ring","label":"Ring phone"},{"id":"stop","label":"Stop"}]}})
elif m == "onAction":
    a = msg["params"]["action"]
    if   a == "ring": subprocess.Popen(["paplay","--loop","/usr/share/sounds/alarm.oga"])
    elif a == "stop": subprocess.run(["pkill","-f","paplay"])
```

No watchapp, no `.pbw`, no fork change — the watch-action-triggers-host-code seam that made us drop
find-my-phone before is exactly what the extension system provides.

## Security & lifecycle

- **Failure isolation by OS process boundary** — segfault/OOM/CPU-spin/deadlock in third-party code
  can't touch the daemon heap or the BLE link; worst case is a dead pipe the supervisor reaps. Each
  extension runs in its own child coroutine scope; `shutdown` cancels it and destroys the process.
- **No third-party code on the protocol thread** — every watch→ext callback returns an immediate
  result/ACK and dispatches the real work async to the child (enqueued).
- **Restart / quarantine** — per-child supervisor: restart with exponential backoff; after 5 rapid
  failures it quarantines (until `stoandl ext restart <name>`). The quarantine raises an
  `alerts.extensions` desktop alert, "<name> stopped", with the child's last stderr line ("Last error: …")
  and a **Restart** button. `ExtList` carries that last stderr line too (its 9th column, kept across
  restarts and the quarantine), so `stoandl ext list` and the GUI show why a crashed extension failed, with
  a Restart button in the GUI.
- **No sandbox** — the child runs as your UID with full FS/network, like any script you run; trust comes
  from you installing it. (A future opt-in `systemd-run --scope` wrap could be added per extension if a
  use case appears, but it's deliberately not a default knob.)
- **Hotplug CLI** — `StoandlControl.{ExtList,ExtInstall,ExtUninstall,ExtEnable,ExtDisable,ExtRestart}`
  drive `ExtensionManager` live: install/enable/disable/restart **without a daemon restart**. Enable/
  disable edit the `extensions.enabled` line in stoandl.conf (only that line) for persistence; the
  process is started/stopped immediately.

## libpebble3 impact: none

Verified public commonMain symbols carry the whole design (no fork change):

- `LibPebble.sendNotification(notification, actionHandlers: Map<UByte, CustomTimelineActionHandler>?)`
  (`LibPebble.kt:99/446` → `actionOverrides.setActionHandlers(itemId, …)`); per-item
  precedence-then-fallthrough at `TimelineActionManager.kt:96`. stoandl already uses this exact pattern
  in `FirmwareControl.kt:264-273` (the firmware "Update" button).
- `ConnectedPebble.AppMessages.{sendAppMessage, sendAppMessageResult, inboundAppMessages(uuid)}`
  (`PebbleDevice.kt:129-131`); `AppRunState.{launchApp, stopApp}`.
- `Locker.sideloadApp(pbwPath: Path)` (`LibPebble.kt:294`).
- Canned reply: `cannedResponse{}` + `Response`/`CannedResponse` (0x08).

Everything new lives in the stoandl outer repo: `ExtensionManager` (supervisor — framing, a
writer-Channel+Mutex, backoff, timeouts, stderr→logback, init buffering), the extracted `pushToWatch`
helper, the one `itemId→owner` route map, the typed-tagged AppMessage codec, and the `Ext*` CLI methods.

**One optional, named hook** only if reality bites: make per-item action overrides survive reconnect
without re-push (only if the §"deferred" re-push proves lossy on hardware). (The voice
`TranscriptionProvider` gap is closed: `voice/StoandlTranscriptionProvider`.)

## Phased plan (each phase shippable)

- **Phase 0 — Refactor (no behavior change). ✅ done.** `WatchNotifier` choke point + `WatchActionRouter`
  + shared `NotifRouteTable`, collapsing `itemIdToDbusId`/`itemIdToMutePkg`. Desktop notifications
  unchanged.
- **Phase 1 — MVP: notify-only extensions (goals a + b). ✅ done.** `ExtensionManager` supervisor +
  `initialize` handshake + `notify`/`onAction`/`onReply`/`onDismiss`/`onWatchConnected`/
  `closeNotification`, async dispatch, restart/backoff + quarantine, stderr→logback. Reference
  `stoandl_ext.py` helper.
- **Phase 2 — Dismiss hygiene + routes. ✅ done.** Dismiss `markForDeletion`s (no resync);
  owner registry + token-in-route. `onWatchConnected` settle delay. Routes were later persisted (see
  "Actions from the history").
- **Phase 3 — Watchapp companion (goal c). ✅ done, hardware-verified.** `registerApp`/`sendAppMessage`/
  `onAppMessage` (ACK-on-receipt, via the broadcast packet flow so the companion manager can't steal it)/
  `launchApp`/`stopApp`/`installPbw` with the typed-tagged dict codec. Ships the find-my-phone
  **watchapp** + companion (the [yoxcu/findphone](https://github.com/yoxcu/findphone) boilerplate,
  vendored as the `examples/extensions/findphone` submodule).
- **Phase 4 — Install & hotplug + config simplification. ✅ done.** `stoandl ext install <archive>`
  (extract → sideload bundled `.pbw` → enable → start) + `list`/`enable`/`disable`/`restart`/`uninstall`,
  all **without a daemon restart**. Dropped the `allow` capability gate and the `confine` knob (no
  sandbox → they bought nothing); default entry `ext/<name>/<name>.py`; optional `manifest.json`.
- **Phase 5 — Further polish (partly done).**
  - ✅ **A real reply extension — Matrix** (`examples/extensions/matrix/`): a Go child using
    [mautrix-go](https://github.com/mautrix/go) + the pure-Go **goolm** crypto backend, built
    `CGO_ENABLED=0 -tags goolm` into a fully static, **musl-clean** binary (no libc/libolm). Logs in as
    a second device, decrypts E2EE rooms via a recovery-key bootstrap (4S → self-sign device → restore
    megolm backup), surfaces messages (filtered by the account's **push rules**) as notifications, and
    sends canned replies back through the account. Receive is a long-poll `/sync` behind a pluggable
    `WakeSource` so a UnifiedPush wake can drop in later. _TBT on hardware — see TESTING.md §5.27._
  - ✅ **Go helper** — `examples/extensions/stoandl_ext.go` (the Go counterpart of `stoandl_ext.py`), with
    `Synthesize` and `SpeakerPump` for the watch speaker.
  - ✅ **Watch speaker receiver** — `examples/extensions/watchlib/stoandl_speaker.{c,h}`: the C half a
    watchapp vendors to play `synthesize` audio ([speaker-stream.md](speaker-stream.md)); used by the
    assistant's watchapp. _TBT on hardware._
  - **TODO:** per-extension rate limit; JSON-Schema + a Rust helper; cross-extension UUID arbitration.
    (Wrist dictation works through the daemon's `voice.*` backend, see "Reply UX" above.)

## Action routing, dismiss, and the firmware reality

stoandl routes watch actions through the **global** `PlatformNotificationActionHandler`
(`WatchActionRouter`) keyed by a shared `itemId → NotifRoute` table — not via libpebble3's per-item
`actionHandlerOverrides` (those are `ConnectionCoroutineScope`-scoped and die on disconnect; they remain
in use only for one-shot notifications like FirmwareControl's Update button). Owners are resolved by id
(`NotifOwnerRegistry`), and the owner-opaque token (desktop: the D-Bus id; extension: its conversation
token) is carried *in* the route, so `onReply`/`onAction`/`onDismiss` need no per-owner map.

**Actions from the history.** The firmware offers a notification's action menu from the watch's
Notifications app only when the phone advertises capability bit 3 (`SupportsTwoWayDismissal`, the
firmware's ExtendedNotificationService; `notification_window.c` `prv_should_show_action_in_action_menu`).
stoandl advertises it (`WatchClaims`), so actions work on old notifications too. Routes are bounded to
7 days and 2000 items and persisted (`~/.cache/stoandl/notification-routes.json`), so after a daemon restart a
history item's actions and replies still reach their owner with the original `extToken`. (Before stoandl advertised the bit, the menu appeared only on the
live popup, which these docs once called a firmware limit.)

A wrist Dismiss uses `markForDeletion`: the watch already removed the item, so it only leaves our sync set.
A dismissal *elsewhere* (the desktop closed it, `closeNotification`) uses `markNotificationRead` instead,
whose STATE_READ flag is the firmware's dismissed status bit; the firmware applies that status-only update
to an item it has and ignores it for one it doesn't, so nothing bounces back. A messaging extension can
pass a stable `replace_id` so re-sends replace the same item.

Mid-session, a `notify()` issued before the BlobDB finishes negotiating is dropped
(`onlyInsertAfter=true`); the `onWatchConnected` settle delay is the mitigation.
