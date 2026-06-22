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
self-contained archive: `{ "name":…, "cmd":…, "config":{…}, "requiresConfig":true }`) **<** the `config`
file (the user's place) **<** an optional `extension.<name>.<key>` in `stoandl.conf` (a back-compat
override). The launch command (`cmd`, for non-Python extensions) may come from any of those layers too.
On install, the status line points at where to configure it (the path of the extension's `config`, and
the `config.example` to copy).

**GUI-facing manifest fields (optional).** A `"description"` (a one-line summary) shows in a front-end's
plugin list, and a `"configSchema"` lets a GUI render a **native settings form** for the extension
instead of pointing the user at the `config` file. It's a JSON array of typed fields:

```jsonc
"configSchema": [
  {"key":"homeserver", "type":"string", "label":"Homeserver URL"},
  {"key":"password",   "type":"string", "label":"Password",  "secret":true},
  {"key":"notify",     "type":"enum",   "label":"Notify on",  "options":["pushrules","mentions","all"]}
]
```

Field `type` is `string` (+`"secret":true` to mask it), `bool`, `int`, or `enum` (+`options`). A GUI
reads the current values (the `config` file), shows the form, and writes changes back — **merged**, so
unsent secrets aren't clobbered, then restarts the extension. Without a `configSchema` a front-end just
reports "no settings" (the alternative `url` backend — a hosted config page — isn't implemented in
stoandl, which has no embedded HTTP server). These fields are purely for front-ends; the daemon and the
stdio protocol ignore them.

**`requiresConfig`** (manifest): an extension that can't run until configured (e.g. Matrix needs a
homeserver) declares `"requiresConfig": true`. If it's started without any user settings (no `config`
file and no `extension.<name>.*`), the daemon **does not spawn it** — it logs a warning and posts a
"needs setup" *desktop* notification (which stoandl's passive monitor bridges to the watch — so it's not
pushed directly to the watch), and `install`/`enable`/`restart`
return a clear "not started — needs configuration" status instead of a fake failure. Configure it, then
`stoandl ext restart <name>`. (Extensions whose settings are all optional, like find-my-phone's `sound`,
omit the flag and start normally.) The usual way to add one:

```
stoandl ext install findphone.tar.gz   # extract to ext/findphone/, sideload a bundled .pbw,
                                        # enable (append to extensions.enabled) + hotplug-start
stoandl ext list | enable <n> | disable <n> | restart <n> | uninstall <n>
# uninstall also removes a bundled watchapp from the watch (reads the .pbw's UUID → removeApp), and if
# the extension has a `config`, prompts to keep it (default yes; reinstall restores it) — or pass
# --keep-config / --delete-config to skip the prompt.
```

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
  "reply":{"cannedReplies":["OK","On my way","Later"],"allowVoice":true}}}
// host -> ext (result)
{"jsonrpc":"2.0","id":7,"result":{"itemId":"e3b0c442-…"}}   // daemon-issued correlation id
```

Callbacks (host→ext notifications, dispatched **async** — never blocking the read loop):

```jsonc
{"method":"onAction", "params":{"extToken":"room!abc:42","itemId":"e3b0…","action":"archive"}}
{"method":"onReply",  "params":{"extToken":"room!abc:42","itemId":"e3b0…","text":"On my way"}}
{"method":"onDismiss","params":{"extToken":"room!abc:42","itemId":"e3b0…"}}
```

`closeNotification {itemId}` lets a child proactively clear a notif (message read elsewhere).

### AppMessage / watchapp companion

```jsonc
// ext -> host
{"id":11,"method":"registerApp","params":{"uuid":"6f1c…"}}      // arms inbound; UUID must be in grant
{"id":12,"method":"sendAppMessage","params":{"uuid":"6f1c…",
   "data":{"0":{"t":"cstring","v":"Berlin"},"1":{"t":"uint8","v":21}}}}  // -> {"result":"ack"|"nack"|"timeout"}
{"id":13,"method":"launchApp","params":{"uuid":"6f1c…"}}        // / stopApp   -> {"ok":true}
{"id":14,"method":"installPbw","params":{"path":"/abs/app.pbw"}}        // -> {"ok":true}  (wraps sideloadApp)
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

> **Voice caveat:** stoandl's `TranscriptionProvider` is currently a NoOp, so dictation yields nothing
> until a real provider is injected via Koin — a separate, additive gap. Canned replies work today with
> no dependency.

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
        "reply":{"cannedReplies":["OK","On my way","Later"],"allowVoice":True}}})
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
  failures it quarantines (until `stoandl ext restart <name>`).
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

**Two optional, named hooks** only if reality bites: (1) make per-item action overrides survive
reconnect without re-push (only if the §"deferred" re-push proves lossy on hardware); (2) a real
`TranscriptionProvider` for voice (an independent gap — canned replies need nothing).

## Phased plan (each phase shippable)

- **Phase 0 — Refactor (no behavior change). ✅ done.** `WatchNotifier` choke point + `WatchActionRouter`
  + shared `NotifRouteTable`, collapsing `itemIdToDbusId`/`itemIdToMutePkg`. Desktop notifications
  unchanged.
- **Phase 1 — MVP: notify-only extensions (goals a + b). ✅ done.** `ExtensionManager` supervisor +
  `initialize` handshake + `notify`/`onAction`/`onReply`/`onDismiss`/`onWatchConnected`/
  `closeNotification`, async dispatch, restart/backoff + quarantine, stderr→logback. Reference
  `stoandl_ext.py` helper.
- **Phase 2 — Dismiss hygiene + in-memory routes. ✅ done.** Dismiss `markForDeletion`s (no resync);
  owner registry + token-in-route. (Route *persistence* was prototyped then dropped — pointless given
  the firmware only actions live notifications.) `onWatchConnected` settle delay.
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
  - ✅ **Go helper** — `examples/extensions/stoandl_ext.go` (the Go counterpart of `stoandl_ext.py`).
  - **TODO:** per-extension rate limit; JSON-Schema + a Rust helper; voice `TranscriptionProvider`
    (canned replies need nothing, but wrist dictation is a no-op until one is wired);
    cross-extension UUID arbitration.

## Action routing, dismiss, and the firmware reality

stoandl routes watch actions through the **global** `PlatformNotificationActionHandler`
(`WatchActionRouter`) keyed by a shared `itemId → NotifRoute` table — not via libpebble3's per-item
`actionHandlerOverrides` (those are `ConnectionCoroutineScope`-scoped and die on disconnect; they remain
in use only for one-shot notifications like FirmwareControl's Update button). Owners are resolved by id
(`NotifOwnerRegistry`), and the owner-opaque token (desktop: the D-Bus id; extension: its conversation
token) is carried *in* the route, so `onReply`/`onAction`/`onDismiss` need no per-owner map.

**Firmware reality (confirmed on PebbleOS 4.x):** the watch presents a notification's action menu
**only while it is the live/incoming notification**. From the notification history/list, Select is a
no-op and no `InvokeAction` is emitted. So notification actions are inherently "while it's on screen,"
and the route table is **in-memory** — a route never needs to outlive the daemon (you can't act on an
old notification regardless). This is also why an always-available trigger like find-my-phone is a
**watchapp**, not a notification.

Dismiss uses `markForDeletion` (not `markNotificationRead`, which re-inserts the record with a changed
hash and makes the BlobDB re-sync it — so a dismissal would bounce back); this makes dismissals stick.
(A watch-local "hold-select" clear that doesn't round-trip can still resync — "clear all" is the
reliable clear.) A messaging extension can pass a stable `replace_id` so re-sends replace the same item.

Mid-session, a `notify()` issued before the BlobDB finishes negotiating is dropped
(`onlyInsertAfter=true`); the `onWatchConnected` settle delay is the mitigation.
