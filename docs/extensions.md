# Extensions (companion apps) — design

> **Status: approved design, not yet implemented.** Architectural decisions locked 2026-06-17.
> This is the spec the implementation tracks; phases land incrementally (see the bottom).

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

### Lifecycle & handshake

Static, config-driven discovery (matches every other stoandl feature; no plugin-dir auto-scan). In
`stoandl.conf`:

```
extensions.enabled       = matrix,findphone
extension.matrix.cmd     = /usr/bin/python3 %h/.config/stoandl/ext/matrix/main.py
extension.matrix.allow   = notify                       # USER-granted capabilities (authoritative)
extension.matrix.homeserver = https://matrix.example.org
extension.matrix.token_file = %h/.config/stoandl/ext/matrix/token
extension.matrix.confine = false                        # true → wrap in systemd-run --user --scope
extension.matrix.pbw     = %h/.config/stoandl/ext/matrix/app.pbw   # optional
```

At startup `ExtensionManager` (one new `start*()` hook, after `registerControlService()`) spawns each
enabled child and does the **`initialize` handshake first** (host→ext events are buffered/dropped until
it lands — the Android `onListenerConnected` analog):

```jsonc
// host -> ext (request)
{"jsonrpc":"2.0","id":0,"method":"initialize",
 "params":{"protocolVersion":1,
           "config":{"homeserver":"…","token_file":"…"},   // THIS extension's keys only, never cmd
           "granted":["notify"],                            // echoes the user grant
           "watch":{"connected":true,"platform":"emery","model":"…","firmware":"…"}}}
// ext -> host (result = manifest; daemon INTERSECTS it with `granted`, excess is rejected + logged)
{"jsonrpc":"2.0","id":0,
 "result":{"name":"matrix","protocolVersion":1,
           "capabilities":["notify"],
           "ownsUuid":[],
           "egress":["matrix.example.org:443"],            // advisory/audit, surfaced in `stoandl ext status`
           "cannedReplyDefaults":["OK","On my way","Call you later"]}}
```

The manifest is a *request*, not a grant: `extension.<name>.allow` is authoritative and the manifest is
intersected with it (a child can't write its own permission slip).

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
{"id":13,"method":"launchApp","params":{"uuid":"6f1c…"}}        // / stopApp
{"id":14,"method":"installPbw","params":{"path":"/abs/app.pbw"}}        // -> {ok,uuid?}  (wraps sideloadApp)
// host -> ext (REQUEST, expects a timely result; daemon auto-NACKs before the 10s firmware timeout)
{"id":99,"method":"onAppMessage","params":{"uuid":"6f1c…","transactionId":7,
   "data":{"0":{"t":"int","v":12}}}}                            // child returns {"ack":true|false}
```

Dict keys are integers-as-JSON-strings (`AppMessageDictionary = Map<Int,Any>`). **Values are
typed-tagged** `{"t":"uint8|int|uint|cstring|bytes","v":…}` — JSON has one number type but
libpebble3/C watchapps distinguish `uint8` vs `int32` (`dict_read_uint8` vs `dict_read_int`); bare
numbers would silently mismatch.

## Capabilities & config

- **Opt-in, off by default.** Nothing spawns unless `extensions.enabled` lists it *and*
  `extension.<name>.cmd` is set.
- **Capabilities are user-granted** (`extension.<name>.allow = notify,appmessage:<uuid>`), not
  self-declared; the manifest is validated against the grant.
- **UUID ownership is first-writer-wins with collision rejection** — `inboundAppMessages(uuid)`
  competitively consumes a single daemon-wide channel, so a second extension claiming an already-owned
  UUID is refused (else it would silently steal the victim's traffic).
- Per-extension config (tokens, homeserver) is injected only into that one child's `initialize` —
  never shared, so an extension can't read another's secrets via stoandl.

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
  can't touch the daemon heap or the BLE link; worst case is a dead pipe the supervisor reaps.
- **No third-party code on the protocol thread** — every watch→ext callback returns an immediate
  `TimelineActionResult`/auto-NACK and dispatches the real work async. The daemon owns all timeouts.
- **Spam guard** — per-extension `notify()` rate limit/quota (a responsive-but-abusive child won't trip
  the hang/backoff logic).
- **Restart / quarantine** — per-child supervisor: restart with exponential backoff, cap (e.g. 5
  restarts / 5 min) then quarantine + desktop-notify. Stale `itemId→owner` entries pruned on exit.
  Clean shutdown: `shutdown` notification → close stdin → grace → SIGTERM → SIGKILL.
- **Sandbox posture (opt-in)** — the child runs as the daemon UID with full FS/network, like any script
  you run as your user. `extension.<name>.confine = true` launches `cmd` via
  `systemd-run --user --scope -p MemoryMax= -p RuntimeMaxSec=` (additive — the transport is already
  `exec`). `egress[]` is advisory/audit only. Documented bluntly: "an extension is as trusted as any
  script you run as your user; enable `confine` for untrusted ones."
- **CLI** — new `StoandlControl` methods on the existing object: `ExtList`/`ExtStatus`/`ExtRestart`
  (status-prefixed strings, consistent with the existing methods). Config is load-once: enabling a new
  extension needs a daemon restart, like every other feature.

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

- **Phase 0 — Refactor (no behavior change).** Extract `pushToWatch` from
  `DbusNotificationListenerConnection`; introduce the single `itemId→owner` route map generalizing
  `itemIdToDbusId`/`itemIdToMutePkg`. Desktop notifications still work identically.
- **Phase 1 — MVP: notify-only extensions (goals a + b).** `ExtensionManager` supervisor +
  `initialize` handshake + `notify`/`onAction`/`onReply`/`onDismiss`/`closeNotification`, async
  dispatch, per-call timeouts, restart/backoff, stderr→logback, user-granted capabilities. Ship the
  **find-my-phone** built-in example + the `stoandl_ext.py` reference helper. Delivers Matrix/Signal/SMS
  push + reply.
- **Phase 2 — Reconnect hardening + spam guard + CLI.** Re-push live extension notifications on
  `onWatchConnected`; per-extension `notify` rate limit; `ExtList`/`ExtStatus`/`ExtRestart`; the
  `systemd-run` `confine` template.
- **Phase 3 — Watchapp companion (goal c).** `registerApp`/`sendAppMessage`/`onAppMessage`/`launchApp`/
  `stopApp`/`installPbw` with the typed-tagged dict codec, UUID collision rejection, auto-NACK deadline.
  Ship a Matrix extension that also drives a small `.pbw`.
- **Phase 4 — Polish.** JSON-Schema for the wire contract + mirror helpers (Rust/Go); voice
  `TranscriptionProvider` wiring if a backend exists; protocol-version negotiation beyond `1`.

## Reconnect / restart handling (resolved in Phase 2)

stoandl routes watch actions through the **global** `PlatformNotificationActionHandler`
(`WatchActionRouter`) keyed by a shared `itemId → NotifRoute` table — not via libpebble3's per-item
`actionHandlerOverrides` (those are `ConnectionCoroutineScope`-scoped and die on disconnect; they remain
in use only for one-shot notifications like FirmwareControl's Update button). The route table is
**persisted** to `<configDir>/notif-routes.json` and owners are resolved by id
(`NotifOwnerRegistry`, re-registered under the same id each startup), with the owner-opaque token
(desktop: the D-Bus id; extension: its conversation token) carried *in* the route — so a notification's
actions, reply routing, and dismiss-closes-the-original all survive a **daemon restart**, with no fork
change. The table is pruned to the watch's 1-day window on load.

Dismiss uses `markForDeletion` (not `markNotificationRead`, which re-inserts the record with a changed
hash and makes the BlobDB re-sync it — so a dismissal would bounce back); this makes dismissals stick.
A persistent notification (find-my-phone) passes a stable `replace_id` so re-sends replace the same item
instead of accumulating orphaned copies.

**Still open:** notifications sent before route persistence existed have no stored route (dismiss them
once — now sticks). Mid-session, a `notify()` issued before the BlobDB finishes negotiating is dropped
(`onlyInsertAfter=true`); the `onWatchConnected` settle delay is the current mitigation.
