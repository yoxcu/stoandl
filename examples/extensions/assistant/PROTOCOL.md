# assistant: implementation contract

The single contract for the three parts of the "Ask Claude" feature. Each part is coded against this
file, not against the other parts. Design background: `next-work/ai-companion/RECOMMENDATION.md`. Wire
format basics: [docs/extensions.md](../../../docs/extensions.md).

| Part | Owner | Files it may touch |
|---|---|---|
| **A** daemon: extension secrets + `keepAwake` | Kotlin | `src/main/kotlin/de/yoxcu/stoandl/ext/*`, `pebble/PebbleIntegration.kt` (wiring only), `Main.kt` (uninstall secret cleanup only), `src/test/...`, `docs/extensions.md`, `docs/dbus-interface.md` |
| **B** extension `assistant` (Go) | Go | `examples/extensions/assistant/` except `watchapp/`, `examples/extensions/stoandl_ext.go`, `docs/assistant.md` (new), `docs/features.md`, `TESTING.md` |
| **C** watchapp | C | `examples/extensions/assistant/watchapp/` only |

Stage 1 (notifications) needs A and B. Stage 2 (watchapp) adds C. Reading answers aloud (C9) adds the
daemon's text to speech (`synthesize`) and the shared speaker stream
([docs/speaker-stream.md](../../../docs/speaker-stream.md)). No fork change, no PKJS, no plugin runtime.

---

## A. Daemon additions

### A1. Secret config fields

A `configSchema` field with `"secret": true` (only valid on `"type": "string"`) is no longer a plain
masked string. A new optional field flag `"required": true` is also added.

| Rule | Behaviour |
|---|---|
| Storage | `SecretStore` (the daemon's existing `LayeredSecretStore`: keyring, else the 0600 `<configDir>/secrets` file) under ref **`ext:<name>:<key>`**. Never written to `ext/<name>/config`, `stoandl.conf` or a log line. Log only `Secret ext:<name>:<key> stored (keyring\|file)` / `removed`. |
| Injection | At spawn (`startProcess`), each stored secret is put into **that child's** `ExtensionDef.config`, so it arrives in `initialize.params.config.<key>` and nowhere else. |
| Legacy plaintext | A secret key found in `ext/<name>/config` is moved: `SecretStore.put`, then its line is deleted from the file (add a `ConfFile` remove helper if needed), logged once. Done in `startProcess` and `setConfig`. A secret key in `stoandl.conf` (`extension.<name>.<key>`) is used only when no stored secret exists, with a one-time warning; stoandl.conf is not rewritten. Manifest `config` must not carry secret defaults (ignored if it does). |
| `ExtGetConfig` | Returns the **effective** values: manifest `config` defaults overlaid by the `config` file (so a default-`true` bool renders checked in the GUI form). Every secret key is reported as `""`. One reserved key `"_secrets"` lists the secret keys that are stored, comma-separated (`"api_key"` or `""`). |
| `ExtSetConfig` | Per secret key: `""` = leave unchanged (the GUI form posts every field), JSON `null` = remove from `SecretStore`, non-empty = store. `_secrets` in a payload is ignored. Non-secret keys behave as today. Restart-if-running as today. |
| `required` | When the schema has any `required` field, `requiresConfig` is satisfied iff every required field has a non-empty effective value (a secret counts when stored). Otherwise the existing `userConfigured` rule applies unchanged. |
| Backup | Nothing to add: the `secrets` file is already excluded and the keyring is never backed up. The rule is that `ext/<name>/config` never holds a secret. |
| Support bundle | Today it does not include extension configs. If it ever does, secret keys are dropped. |
| Uninstall | `--delete-config` also removes `ext:<name>:<key>` for every secret key in that extension's schema (read the manifest before deleting the dir). `--keep-config` keeps them. |

Plugs into:
- `ExtensionManager(...)` gets a new constructor parameter `secrets: SecretStore`. `PebbleIntegration` passes its existing `secretStore`; tests pass an in-memory fake.
- Secret and required keys come from `readExtManifestMeta(dir).configSchemaJson` (`ExtensionDef.kt`). Parse it once in a small pure helper, `schemaFlags(json) -> (secretKeys, requiredKeys)`, and unit-test it.
- Code paths: `ExtensionManager.startProcess`, `getConfig`, `setConfig`, `uninstall`. `ExtensionDef` stays a data class; secrets are merged into a copy of `def.config` before `ExtensionProcess` is built.
- `docs/dbus-interface.md`: update the `ExtGetConfig`/`ExtSetConfig`/`ExtConfigSchema` rows (`_secrets`, `null`, `required`). `docs/extensions.md`: update the `configSchema` paragraph, and correct its outdated "no embedded HTTP server" and voice "no-op" lines while there.

### A2. `keepAwake` / `release` (ext to host requests)

```jsonc
{"jsonrpc":"2.0","id":31,"method":"keepAwake","params":{"why":"answering a question","maxMs":60000}}
{"jsonrpc":"2.0","id":31,"result":{"token":"3f0c9a2e-…"}}
{"jsonrpc":"2.0","id":32,"method":"release","params":{"token":"3f0c9a2e-…"}}
{"jsonrpc":"2.0","id":32,"result":{}}
```

| Rule | Behaviour |
|---|---|
| Backing | `SleepGuard.holdAwake("<ext>: <why>")`, a logind sleep *block*. It is allowed here only because it is short and bounded. ExtensionManager gets `holdAwake: (String) -> AutoCloseable = { AutoCloseable {} }`; PebbleIntegration passes `{ why -> sleepGuard?.holdAwake(why) ?: AutoCloseable {} }`, like `StoandlTranscriptionProvider`. |
| `maxMs` | Integer, required. `> 60000` is clamped to 60000. `≤ 0` or missing gives a JSON-RPC error `keepAwake: maxMs must be 1..60000`. `why` is trimmed and cut to 80 chars; empty becomes `"extension work"`. |
| Token | Opaque string (a random UUID), unique per hold. |
| Expiry | Each hold closes itself after `maxMs`, through a coroutine in the `ExtensionProcess` scope. To hold longer, call `keepAwake` again for a new token before the old one expires. |
| `release` | Closes the hold. Unknown or already-expired token: still `{}` (idempotent). Missing `token`: error. |
| Child exit | Every hold of that child is closed when `runOnce` returns (crash or exit) and in `shutdown()`. |
| Cap | At most 4 open holds per extension. A fifth gets the error `keepAwake: too many holds`. |
| Never | No hold survives the child. Holds are not persisted. |

Plugs into `ExtensionProcess.handleLine`: new branches `"keepAwake"` and `"release"`, a
`ConcurrentHashMap<String, AutoCloseable>` of holds plus each hold's expiry `Job`, and cleanup at the end
of `runOnce` and in `shutdown()`. Unit-test the clamp, expiry, idempotent release and close-on-exit
against a fake `holdAwake`.

### A3. Notes for B (existing daemon behaviour, unchanged)

- `sendAppMessage` runs **inline** in the child's line processor. The next line the extension sends is not handled until the watch has ACKed or NACKed. libpebble3 turns a 10 s silence into a NACK, so a `"nack"` that took ≥ 5 s means "app not there". `"timeout"` means no watch is connected.
- `notify` with `itemId` replaces that item. The `vibe` name `None` exists, but whether a silent re-send stays silent on the watch is unverified.
- Reply routes persist for 7 days (f30fb22), so `onReply` keeps working on cards from before a daemon restart and carries the original `extToken`.

---

## B. Extension `assistant` (Go)

### B1. Layout and build

```
examples/extensions/assistant/
  PROTOCOL.md      this file
  README.md        user-facing: install, set key, use
  manifest.json    see B2
  config.example   every key at its default, api_key empty
  go.mod / go.sum  module stoandl-assistant, go >= 1.25, github.com/anthropics/anthropic-sdk-go
  main.go …        extension; `bench` subcommand (B9)
  stoandl_ext.go   copy of ../stoandl_ext.go after the B10 additions
  package.sh       builds watchapp/ (pebble build) → assistant.pbw, then CGO_ENABLED=0 static binaries
                   → assistant-arm64.tar.gz / assistant-amd64.tar.gz
                   each holding assistant/{stoandl-assistant, manifest.json, config.example, assistant.pbw}
  watchapp/        part C (package.json, wscript, src/c/assistant.c); build output build/watchapp.pbw
```

Pure logic gets Go tests: paging, chunk flushing, markdown to plain text, history pruning, reply and token routing, bench cost math.

### B2. manifest.json

```json
{
  "name": "assistant",
  "cmd": "./stoandl-assistant",
  "author": "stoandl",
  "version": "1.1.2",
  "description": "Ask Claude from your wrist: voice or presets, answers as notifications or in the Assistant app.",
  "requiresConfig": true,
  "watchapps": ["assistant.pbw"],
  "config": {
    "model": "claude-haiku-4-5-20251001", "effort": "low", "max_tokens": "2048", "web_search": "false",
    "presets": "Tell me a fun fact|Give me a quick dinner idea|How do I say thank you in Japanese?|Motivate me",
    "followups": "Explain more|Shorter|Retry",
    "idle_minutes": "15", "keep_history": "true",
    "base_url": "https://api.anthropic.com", "system_prompt_extra": "", "language": "", "speak": "always"
  },
  "configSchema": [ …the rows of B3, in that order… ]
}
```

### B3. Config keys

The GUI renders the schema (an enum shows its first option when the value is unknown, so the default is listed first).

| Key | Schema | Default | Meaning |
|---|---|---|---|
| `api_key` | string, `secret`, `required` | none | Anthropic API key. Keyring via A1. The extension won't start until it is set. |
| `model` | enum `claude-haiku-4-5-20251001`, `claude-sonnet-5`, `claude-opus-5-5` | `claude-haiku-4-5-20251001` | Changing it ends the current conversation (the next question starts a new one). |
| `effort` | enum `low`, `medium`, `high` | `low` | `output_config.effort`, sent to Sonnet 5 and Opus 5.5 only. |
| `max_tokens` | int | 2048 | Per answer. On Sonnet 5 and Opus 5.5 this includes thinking tokens. |
| `web_search` | bool | false | Ad-hoc web search: regular requests carry a client tool `web_lookup` (`{query}`); when Claude calls it, the turn is re-asked once with the server web search tool (`max_uses: 1`), the history as plain text, and the answer stored as text only. Billed per search. Off by default (external egress rule). |
| `presets` | string, `\|`-separated, ≤ 512 B | see B2 | Starter questions: the anchor card's canned replies and the watchapp presets menu. |
| `followups` | string, `\|`-separated, ≤ 512 B | `Explain more\|Shorter\|Retry` | Canned replies on answer and error cards. |
| `idle_minutes` | int | 15 | Idle rule (B7). |
| `keep_history` | bool | true | Persist conversations to the 24 h file (B6); otherwise memory only. |
| `base_url` | string | `https://api.anthropic.com` | For a proxy (`option.WithBaseURL`). |
| `system_prompt_extra` | string | `""` | Appended to the fixed system prompt. It can't contain `#`, because the config parser cuts there. |
| `language` | string | `""` | Reply language, a free-form name (`German`). Also picks the voice for reading aloud: German and English map to `de` / `en` (C9); anything else gets the daemon's default voice. |
| `speak` | enum `always`, `on_request`, `off` | `always` | Read answers aloud on the watch speaker (C9). Inert until the daemon's `tts.backend` is set, which is off by default. |

`config.example` lists every key uncommented at its default, with `api_key =` empty and a comment to set it in the GUI (keyring).

### B4. Claude requests

| Item | Rule |
|---|---|
| Client | `anthropic.NewClient(option.WithAPIKey(cfg.api_key), option.WithBaseURL(cfg.base_url))`. Always streaming: `client.Messages.NewStreaming` (beta `client.Beta.Messages.NewStreaming` where a beta is needed) with `message.Accumulate`. |
| System prompt | Fixed text (`systemPrompt` in claude.go: Pebble screen, 1–3 sentences / at most 40 words, answer first, plain text, longer only on request, expect speech-recognition errors), then `"\n\n"` + a reply-language line when `language` is set, then `"\n\n" + system_prompt_extra` if set. The whole text is part of the conversation fingerprint, so changing it starts a new conversation. |
| User turn | `"Local time: Fri 17 Oct 2026 14:05 CEST (Europe/Berlin)\n\n" + question`. The time stays out of the system prompt so the system prompt is a stable prefix. |
| Haiku 4.5 | No `thinking`, no `effort`. Web search tool `web_search_20250305`. |
| Sonnet 5 | `output_config.effort = cfg.effort`, `thinking` omitted (runs adaptive). Web search `web_search_20260209`. |
| Opus 5.5 | `output_config.effort = cfg.effort` (always sent: its default is `medium`), `thinking` omitted (it can't be disabled). Beta `server-side-fallback-2026-07-01` with `fallbacks: "default"`. Web search `web_search_20260209`. |
| History replay | Append-only. Each turn stores the assistant `content` array **verbatim** (thinking blocks and signatures included) and replays it unchanged. Earlier turns are never edited: Retry and replies to older cards branch (B7). |
| Reading | Check `stop_reason` first. `end_turn`: done. `max_tokens`: done, add `…` and set TRUNCATED. `refusal`: error REFUSED. `pause_turn` (web search): re-send with the partial content appended, at most 2 times. The answer text is **all** `text` blocks joined. |
| Timeouts | No text after 20 s: TIMEOUT. Whole request capped at 60 s: the partial text becomes the answer with TRUNCATED (no text at all: TIMEOUT). SDK retries: `option.WithMaxRetries(1)`. |
| Concurrency | One request in flight, globally. A new question from any entry point cancels the running one; its partial answer is discarded and never posted. |
| keepAwake | `keepAwake{why:"answering a question", maxMs:60000}` before the request, `release` when it finishes, fails or is cancelled. |
| Log (stderr) | One line per request: `ask conv=<id> turn=<n> model=<m> ttft=<ms> total=<ms> in=<tok> out=<tok> cost=$<x> stop=<reason> sink=<app\|notif>`. Never log the key, and log question text only at the start of the line, cut to 40 chars. |

**Text cleanup** (before paging or chunking; in streaming mode up to 32 B that could still be a marker are held back):
- Strip `**`, `__`, `` ` ``, and `#` at line start.
- `[t](u)` becomes `t`.
- A `- `, `* ` or `• ` bullet at line start becomes `- `.
- Collapse 3 or more newlines to 2.
- Map characters the watch fonts can't draw: `“ ”` to `"`, `‘ ’` to `'`, `– —` to `-`, `…` to `...`, NBSP to space.
- Drop emoji and anything outside the BMP.
- Keep other scripts as they are (language packs).

Prices for cost estimates (USD per MTok in/out): Haiku 4.5 1/5, Sonnet 5 2/10, Opus 5.5 4/20, plus $0.01 per web search. Cost = in×p_in + out×p_out; cache pricing is ignored.

### B5. Notifications (Stage 1, and the fallback for Stage 2)

All cards use `appName: "Claude"`.

| Card | itemId (`ReplaceID` → uuid5) | title | body | actions | reply | extToken |
|---|---|---|---|---|---|---|
| **Anchor** "Ask Claude" | `assistant:anchor` (stable) | `Ask Claude` | `Reply to ask by voice or pick a question.` | `app` "Open app" (only if the watchapp is installed, i.e. always with the bundled .pbw) | label `Ask`, canned = `presets` | `anchor` |
| **Answer**, page 1 | `assistant:<conv>:<turn>:1` | question cut to ≤ 64 B UTF-8 (`…` when cut) | page 1 | `more:2` "More (2/N)" when N > 1 | label `Reply`, canned = `followups` | `<conv>:<turn>` |
| **Answer**, page k | `assistant:<conv>:<turn>:<k>` | same | page k | `more:<k+1>` "More (k+1/N)" when k < N | same | same |
| **Error** | `assistant:<conv>:<turn>:err` | `Claude: <short>` (B8) | one-line hint (B8) | none | label `Reply`, canned = `["Retry"]` | `<conv>:<turn>` |

Rules:
- **Paging.** A page's body is ≤ 500 B UTF-8, split at a paragraph break, else a sentence end, else a space, else a UTF-8 boundary. At most 8 pages; if the answer is longer, page 8 ends with `... (open the Assistant app for the rest)`.
- **More.** `onAction(action="more:<k>")` posts page k as its own card. The stable itemIds mean pressing More twice doesn't duplicate.
- **Vibration.** Page 1 and error cards use the default vibe. More pages and anchor re-sends use `vibe: "None"`.
- **Anchor upkeep.** Send it on `OnInitialize` when the watch is connected, and on `onWatchConnected` when `state.json`'s `anchorSentAt` is missing or older than 20 h. Write `anchorSentAt` after the send. `Open app` calls `launchApp(APP_UUID)`.
- **Origin.** A question from a notification (`onReply`) always answers as notification cards, even if the app is open.

### B6. History and state files

| File | Path | Mode | Content |
|---|---|---|---|
| history | `${XDG_STATE_HOME:-~/.local/state}/stoandl/assistant/history.json` | dir 0700, file 0600, written atomically (tmp + rename) | below |
| state | same dir, `state.json` | 0600 | `{"anchorSentAt": <unix s>}` (always persisted, holds nothing sensitive) |

These files are outside `~/.config/stoandl`, so `stoandl backup` never includes them.

```jsonc
{"v":1,
 "current":"1a2b3c4d",                 // conversation the idle rule continues; "" after NEW_CONV
 "conversations":[{
   "id":"1a2b3c4d",                     // 8 hex = uint32, never 0
   "model":"claude-haiku-4-5-20251001",
   "created":1760700000, "updated":1760700300,      // unix s
   "turns":[{
     "q":"how long to boil an egg", "at":1760700000,
     "content":[ /* the assistant content array exactly as returned */ ],
     "text":"Soft: 6 minutes. …",         // cleaned answer as shown
     "stop":"end_turn", "in":312, "out":58,
     "err":0                              // non-zero (B8 code) = failed turn with no content
   }]}]}
```

Lifetime and limits:
- A conversation is dropped when `updated` is more than 24 h old: on load, on every write, and in an hourly sweep.
- At most 50 conversations (oldest dropped) and 30 turns each. A question for a full conversation starts a new one.
- `keep_history=false`: the same structure lives in memory only (still with the 24 h lifetime), and `history.json` is deleted at startup.
- An unknown or expired `<conv>` in a token or `CONV` is treated as a new conversation, with the incoming text as its first question.

### B7. Conversation rules

The base of a question is the list of earlier turns sent with it. A card or `CONV`+`TURN` pair names turn N of conversation C.

| Input | Base | Question |
|---|---|---|
| Reply on the anchor; watchapp `ASK`/`PRESET` with `CONV=0` | `current` conversation if its last turn is less than `idle_minutes` old and its model equals `model`; otherwise a new conversation | the text |
| Reply `X` on answer card C:N; watchapp `ASK` with `CONV=C, TURN=N` | turns 1..N of C | `X` |
| Reply on error card C:N; watchapp `ASK` with `CONV=C, TURN=N` where turn N failed | turns 1..N-1 of C | `Retry` re-asks turn N's `q`; other text is asked as-is |
| `Retry` (trimmed, any case, whether or not it is in `followups`) as a reply on answer card C:N or as watchapp `ASK` TEXT with `CONV=C, TURN=N` | turns 1..N-1 of C | turn N's `q` |
| watchapp `NEW_CONV` | sets `current = ""` | none |

If the base is all of C's turns, the new turn is appended to C. Otherwise the conversation **branches**: a new id is created holding a copy of the base plus the new turn. This stays append-only for preserved thinking. The conversation that receives the turn becomes `current`. A failed turn (any B8 code except BAD_REQUEST, CANCELLED included) is stored with `err` and no `content`, so that Retry can find its `q`. Its question and answer are both skipped on replay, which keeps user and assistant turns alternating.

### B8. Errors

| Code | Cause | Card title / app text | Hint (card body) |
|---|---|---|---|
| 1 CANCELLED | `CANCEL` or a newer question | (app only) `Cancelled` | none |
| 2 NO_KEY | `api_key` empty at request time | `Claude: no API key` | `Set it in stoandl: Extensions → assistant.` |
| 3 KEY_REJECTED | HTTP 401/403 | `Claude: key rejected` | `Check the API key in stoandl.` |
| 4 BUSY | HTTP 429, 529, `overloaded_error` | `Claude: busy` | `Reply Retry in a moment.` |
| 5 TIMEOUT | network error, 20 s no first text | `Claude: no answer` | `Network or API timeout. Reply Retry.` |
| 6 REFUSED | `stop_reason=refusal` | `Claude declined` | `Try asking differently.` |
| 7 SERVER | other HTTP 5xx or 4xx | `Claude: error <status>` | first 100 B of the API error message |
| 8 BAD_REQUEST | empty question, unknown preset index | (app only) `Couldn't ask` | none |

Every error is logged. In the app it is shown as an `ERROR` message (C4). Otherwise it becomes an error card,
except CANCELLED and BAD_REQUEST, which are only logged.

### B9. `bench` subcommand

`./stoandl-assistant bench [-models m1,m2,…] [-n 3] [-effort low] [-web] [-tsv]`, run by hand, never under
the daemon (the daemon starts the binary with no arguments).

| Item | Rule |
|---|---|
| Key | `ANTHROPIC_API_KEY` only. Never reads stoandl config or the keyring. Exits 2 with a message if it is unset. `ANTHROPIC_BASE_URL` is honoured through the SDK default. |
| Models | Default: all three B3 models. Request shape exactly as B4 (same system prompt, user-turn time line, `max_tokens` 2048, `-effort` default `low`, web search only with `-web`). |
| Questions (fixed) | `What's the capital of Australia?` · `How long do I soft-boil an egg?` · `Convert 72 Fahrenheit to Celsius.` · `Give me a synonym for happy.` · `What does HTTP 418 mean?` · `Explain a leap year in one sentence.` |
| Runs | Sequential, each a fresh one-turn conversation, `-n` repetitions per model × question. |
| Measures | TTFT (request start to first text delta), total (to message stop), `usage.input_tokens`, `usage.output_tokens`, answer bytes after cleanup, `stop_reason`, cost per B4 prices. |
| Output | Per model: median and p90 TTFT and total, mean output tokens, mean cost per question, and total cost of the run. `-tsv`: one line per request instead (`model q# run ttft_ms total_ms in out bytes stop cost`). Errors are counted and shown, not fatal. |

### B10. Additions to `examples/extensions/stoandl_ext.go` (backward compatible)

| Addition | Shape |
|---|---|
| `Notification.ReplyLabel string` | sent as `reply.label` when non-empty |
| `Ext.OnActionToken func(itemID, extToken, action string)` / `Ext.OnReplyToken func(itemID, extToken, text string)` | called instead of `OnAction`/`OnReply` when set (the current handlers drop `extToken`) |
| `Ext.KeepAwake(why string, maxMs int, cb func(token string))` | `cb("")` on an error reply |
| `Ext.Release(token string)` | fire and forget |
| error replies | a pending callback that registered for errors receives them (needed for `KeepAwake`); the others are still only logged |
| `Ext.Synthesize(text, language string, cb func(blocks [][]byte, ms int, err string))` | ext `synthesize{text, language?, format:"pebble-adpcm"}`; `err` is the error message (`off`, `unavailable: …`, `busy`). A daemon without it never answers, so callers time out (the assistant after 30 s). |
| `SpeakerPump` (`NewSpeakerPump(send)`, `SetWatch`, `SetRateHint`, `Expect`, `Write`, `End`, `Abort`, `Sent`, `LinkRate`, `Report`), `SpeakerWatch` and the `Spk*` keys | one speaker stream, packed and paced to the watch's ring, its start decided by the sender, sent through the caller's one-in-flight path ([docs/speaker-stream.md](../../../docs/speaker-stream.md)) |

The assistant keeps an identical copy next to its `main.go`.

---

## C. Watchapp ⇄ extension AppMessage protocol

### C1. Identity and build

| Item | Value |
|---|---|
| UUID | **`5b24fc80-114d-4c28-b007-53e241b95856`** (Go constant `APP_UUID`; `package.json` `pebble.uuid`) |
| displayName | `Assistant` |
| package `name` / version | `assistant` / `1.1.2` (bump on every change, or the locker won't re-push it) |
| sdkVersion | `3`, `enableMultiJS: false`, **no `src/pkjs`** |
| targetPlatforms | `basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro` (no aplite) |
| messageKeys | `[]`: the C code uses the raw numeric keys below via `#define`, like findphone and matrix-preview |
| Mic | Capability-driven, not hardcoded per platform: `#if defined(PBL_MICROPHONE)` at build time, and `dictation_session_create` returning NULL at runtime, both mean presets only. diorite is expected to be presets-only. |
| Round | `PBL_IF_ROUND_ELSE` insets for chalk and gabbro; no other round-specific layout |
| Extension side | `registerApp(APP_UUID)` in `OnInitialize`. The daemon keeps the .pbw installed via manifest `watchapps`. |

### C2. Message keys

| Key | Name | Type (Go helper / C read) | Used in |
|---|---|---|---|
| 0 | CMD | uint8 (`U8`) | every message |
| 1 | TEXT | cstring (`CStr`) | ASK, STATUS, CHUNK, ERROR, PRESETS |
| 2 | SEQ | uint16 (`U16`) | CHUNK, DONE |
| 3 | TURN | uint8 (`U8`) | ASK, PRESET, STATUS, CHUNK, DONE, ERROR |
| 4 | CONV | uint32 (`U32`) | ASK, PRESET, STATUS, CHUNK, DONE, ERROR |
| 5 | FLAGS | uint8 | DONE |
| 6 | INBOX | uint16 | HELLO |
| 7 | CAPS | uint8 | HELLO, ASK, PRESET (read fresh for each) |
| 8 | PROTO | uint8, = 1 | HELLO, PRESETS |
| 9 | CODE | uint8 | STATUS (state), ERROR (B8 code) |
| 10 | INDEX | uint8 | PRESET |
| 11 | SPK_DATA | uint16 | HELLO on speaker watches (1.1.1): `stoandl_speaker_data_max(inbox)` |
| 12 | SPK_RING | uint16 | HELLO on speaker watches (1.1.1): `stoandl_speaker_ring_bytes()` |

C reads every integer through one helper that switches on `tuple->length` (1/2/4 bytes), so a width mismatch can't misread. Missing keys read as 0 / `""`.

The speaker stream uses four more keys, `0x7FFF0000`–`0x7FFF0003` (SPK_ID, SPK_SEQ, SPK_DATA, SPK_FLAGS),
defined once in [docs/speaker-stream.md](../../../docs/speaker-stream.md). A message with SPK_ID carries no
CMD; `stoandl_speaker_handle()` takes it before the CMD switch.

### C3. Watch to extension

| CMD | Name | Keys | Extension reaction |
|---|---|---|---|
| 1 | HELLO | PROTO, INBOX (the inbox the firmware gave), CAPS (`0x01` mic, `0x02` color, `0x04` round, `0x08` speaker, `0x10` muted: the speaker is muted or Quiet Time is on), and on speaker watches SPK_DATA and SPK_RING (absent from a 1.1.0 app: one block per speech message) | Mark app open. Send `PRESETS`, then `STATUS{CODE=0, CONV=current or 0, TURN=its last turn}`. If a request is running, send `STATUS{CODE=1\|2}` and re-stream from SEQ 0 (C5). Otherwise, if `current` is idle-valid (B7), replay its last turn (CHUNKs + `DONE` with REPLAY). |
| 2 | ASK | TEXT (question ≤ 500 B), CONV, TURN, CAPS | B7 with origin app. Stops reading aloud. Answer: `STATUS{CODE=1}`, `CHUNK`s, `DONE`, or `ERROR`. |
| 3 | PRESET | INDEX, CONV, TURN, CAPS | As ASK with `presets[INDEX]`. Index out of range: `ERROR{CODE=8}`. |
| 4 | NEW_CONV | none | `current = ""`. Stops reading aloud. No reply message. |
| 5 | CANCEL | CONV, TURN | Stop reading aloud. Cancel the running request if it matches. Reply `ERROR{CODE=1}`. |
| 6 | BYE | none | Sent from `deinit`, best effort. Mark app closed (C6), which stops reading aloud. |
| 7 | SPEAK | CONV, TURN | Read that answer aloud (C9), whatever `speak` = `always` would decide. |
| 8 | STOP_SPEAK | none | Stop reading aloud. No reply message. |

### C4. Extension to watch

| CMD | Name | Keys | Watch reaction |
|---|---|---|---|
| 16 | PRESETS | PROTO, TEXT = presets joined by `\n` (≤ 512 B) | Store for the menu |
| 17 | STATUS | CONV, TURN, CODE (`0` idle, `1` thinking, `2` searching the web), TEXT = question cut to 100 B (on 1/2) | Adopt CONV/TURN as the active request. Show `Thinking…` / `Searching…` under the question. |
| 18 | CHUNK | CONV, TURN, SEQ, TEXT (≤ 120 B) | Append if CONV/TURN match the active request and SEQ is the next one expected (C5) |
| 19 | DONE | CONV, TURN, SEQ = number of chunks sent, FLAGS (`0x01` TRUNCATED, `0x02` REPLAY) | Finish. TRUNCATED appends `…`. One short vibe unless REPLAY or the wait was under 3 s. |
| 20 | ERROR | CONV, TURN, CODE (B8), TEXT (≤ 100 B, the B8 card title without `Claude: `) | Show the text. Select retries: `ASK{TEXT="Retry", CONV, TURN}` (B7). |
| none | speaker stream | SPK_* | Played by `stoandl_speaker.c` (C9). |

### C5. Flow control

| Rule | Value |
|---|---|
| Chunk size | TEXT ≤ 120 B UTF-8, never splitting a UTF-8 sequence |
| Flush | When the buffer reaches 120 B, or 400 ms after its first unflushed byte, or at a sentence end (`. `, `! `, `? `, `\n`) once it holds ≥ 24 B. The text is cleaned up (B4) before chunking. |
| In flight | **One** `sendAppMessage` at a time, in each direction, speech blocks included: they go through the same path as CHUNKs. The next message is sent only after `ack`. |
| NACK | Retry the same message after 200, 400 and 800 ms. A `nack` that took ≥ 5 s, `timeout`, `error`, or a 4th failure means the app is closed (C6). A retried message keeps its SEQ. |
| Watch dedupe | A CHUNK with SEQ below the expected one is ignored (a retry whose ACK was lost). A SEQ above the expected one appends `[…]` and resynchronises. Messages for another CONV/TURN are ignored, except STATUS, which sets them. |
| Re-stream | After a HELLO during a running request, or a replay, the extension restarts from SEQ 0 with all text so far. The watch clears its buffer on STATUS. |
| Watch send | Wait for `outbox_sent` before the next send. On `outbox_failed`, retry once after 500 ms, then show `Phone not reachable`. |
| Buffers | `app_message_open(inbox 768, outbox 640)`; on watches with a speaker (`PBL_SPEAKER`) the inbox is `MIN(STOANDL_SPEAKER_INBOX_MAX, app_message_inbox_size_maximum())`, 8200 on emery and 6200 on flint, for several 2000-byte speech blocks per message. Answer buffer on the watch: 4096 B; overflow ends it with `…`. Question: dictation buffer 512 B, sent truncated to 500 B. |
| Ordering | Before streaming, the extension sends STATUS for every request, so the watch always knows the active CONV/TURN. |

### C6. App closed

- The extension tracks `appOpen`. It is set by HELLO and cleared by BYE, a C5 failure, or `onWatchConnected` (a reconnect means the app is gone).
- Request from the app, app still open at DONE: no notification.
- Request from the app, app closed before DONE: the stream keeps going without AppMessages, and the finished answer, or its error, is posted as notification cards (B5), with extToken `<conv>:<turn>`. It is never lost.
- App reopens while the request is running: HELLO re-streams to the app and cancels the pending notification fallback, unless the notification was already posted.
- Requests from notifications never touch the app.

### C7. Watch UX

| State | Screen | Buttons |
|---|---|---|
| Launch | Any launch reason (Quick Launch, menu, the anchor's "Open app") with a mic starts dictation at once, with confirmation off (`dictation_session_enable_confirmation(false)`, error dialogs on). Without a mic, the presets menu opens. HELLO is sent first. | |
| Listening | system dictation UI | Back cancels and goes to Idle, or to the replayed answer |
| Idle | `Ask Claude` + hint (`Select: ask · Hold Select: new chat · Hold Up: presets`) | Select: dictation or presets · Hold Up: presets |
| Thinking | question (bold, top), `Thinking…` / `Searching…` | Select: CANCEL |
| Streaming | question header + answer in a `ScrollLayer`; the view stays at the top while text grows | Up/Down scroll · Select: CANCEL |
| Answer | same, complete; the header shows `Speaking...` while it is read aloud | Select: follow-up (dictation or presets) with `CONV, TURN` of the answer shown · **Hold Select: NEW_CONV**, then dictation or presets · Hold Up: presets · Up/Down scroll · **Hold Down** (speaker watches): read aloud, or stop |
| Error | message + `Select: retry` | Select: retry (C4 ERROR) · Hold Select: new chat |
| Presets menu | `MenuLayer` of PRESETS; until PRESETS arrives it shows `Loading…` | Select sends PRESET{INDEX, CONV, TURN of the shown answer, or 0, 0} |

Select, Hold Select and a new question stop reading aloud first, so the microphone doesn't hear the
speaker. They send STOP_SPEAK whenever something is read or an answer is on screen, since an unasked
reading may still be synthesising, and the app holds the receiver off (`stoandl_speaker_suppress`)
from `dictation_session_start` to the dictation callback: the voice window opens in the app's own
window stack, so dictation is no focus change. On speaker watches Down is not repeating, like Up, because it has a
long press. Back always exits, sending BYE first; the speech stops with the app. A running answer then arrives as a notification (C6). No glance, no background worker, no persistent storage in v1.

### C8. Sequence (Stage 2, happy path)

```
watch: launch → HELLO ─────────────────────▶ ext: appOpen=true → PRESETS, STATUS{0,current}
watch: dictation "how far is the moon" → ASK{TEXT,CONV=0,TURN=0} ─▶ ext: B7 → keepAwake → STATUS{1,C,N,q}
ext ◀── Claude stream ──  CHUNK{C,N,0,"About 384,400 km."} ─▶ ack ─▶ CHUNK{C,N,1,…} … ─▶ DONE{C,N,k} → release
watch: Select → dictation "in miles?" → ASK{TEXT,CONV=C,TURN=N} ─▶ ext: appends turn N+1 to C
```

### C9. Reading aloud

The daemon synthesizes, the extension streams, the watchapp plays: [docs/speaker-stream.md](../../../docs/speaker-stream.md)
is the shared format, sender and receiver contract.

| Rule | Value |
|---|---|
| When | `speak=always`: after DONE of an answer the app showed (never for a replay or a notification answer), if the latest CAPS has speaker and not muted. `on_request` and `always`: on SPEAK (Hold Down), muted or not. `off`: SPEAK posts the card `Reading aloud is off` and an abort. |
| Quiet Time | The watch sets CAPS `0x10` while `quiet_time_is_active()`, even though the firmware mutes the speaker in Quiet Time only when the user's "mute speaker" preference is on. So `always` stays silent at night; Hold Down still plays. A really muted speaker: Hold Down shows `Speaker muted` and sends nothing. |
| Text | The answer as shown (cleaned, B4), bullets without their `- `, split into sentences: one shorter than 40 B joins the next, one longer than 300 B is cut at a space. A first sentence longer than 100 B is cut at its last clause boundary (`, `, `; `, `: `, a dash) from 30 B on, so the first sound comes sooner. |
| Synthesis | `synthesize{text, language, format:"pebble-adpcm"}` per sentence, one call at a time; sentence N+1 is asked for while N is sent. `language` is `de` or `en` from `language` (German, Deutsch, de, de-AT … / English, en, en-GB …), else omitted. No answer in 30 s counts as `unavailable`, and `always` then reads nothing unasked until a later `synthesize` is answered (a stoandl without text to speech never answers). |
| Stream | One `SpeakerPump` per reading, set to the SPK_DATA and SPK_RING of the latest HELLO: several blocks per message, paced to the watch's ring, END after the last sentence (a 1.1.0 app: one block per message, a 6 KB ring). The pump is told the reading's expected length (the audio of the sentences synthesised so far, plus the characters still to come at their measured ms per character, 65 before the first) and sets PLAY once the watch holds enough for the rest to arrive in time; the previous reading's link rate is its first guess. A synthesis error mid-way ends the stream with what was sent. One log line per reading: `speak: <Report>`. |
| Errors | On SPEAK with nothing sent yet: `off` → card `Text to speech is off` / `Turn it on in stoandl: Settings → Voice.`; `busy` → `Text to speech is busy`; `unavailable: <why>` → `Text to speech unavailable` / `<why>`; then an ABORT, which clears the watch's `Speaking...`. Card itemId `assistant:speech` (`ReplaceID`), urgent. An unasked (`always`) reading fails silently, since text to speech is off by default. |
| Stop | ASK, PRESET, CANCEL, NEW_CONV, STOP_SPEAK, HELLO (a new app instance), BYE and a failed send stop the pump. If the app is still open, an ABORT follows when blocks went out, or when the reading was asked for (SPEAK) and no other reading replaced it, so the watch's `Speaking...` clears. |
| Sleep | `keepAwake{why:"reading an answer aloud"}` to the end of the stream, renewed before each 60 s hold expires, 120 s at most. It starts with the first `synthesize` once the daemon has answered one before, otherwise with the first answered sentence, so a daemon that never answers holds off no suspend. |
| Watch | Hold Down sets `Speaking...` at once; the receiver's on-change callback clears it when the stream ends, is aborted, stalls (35 s without a message) or is ignored (it began during dictation or under a modal window). |

---

## Not verified (hardware rows go in TESTING.md, marked ⚠️ UNVERIFIED)

- Watchapp dictation (§5.46j); whether confirmation off still adds a press.
- Whether `vibe: "None"` re-sends stay silent.
- Whether the 768 B inbox is enough on every platform, and the 10 s NACK behaviour when the app is closed versus busy in dictation.
- Whether `PBL_MICROPHONE` is undefined on diorite and defined on flint.
- Latency figures (`bench`, and the `ask … ttft=` log line).
- Reading aloud (C9) on a Time 2 and a 2 Duo: time from DONE to the first sound, gaps between sentences,
  ring overflows (`speaker: ring full` in the watch log), focus loss (call, notification), a late
  reading during dictation, the 35 s stall drop (`speaker: stream stalled`),
  Quiet Time and a muted speaker, and throughput at the reduced sniff interval over Linux PPoG.
- Packed speech (1.1.1): the inbox the firmware really gives (the app logs `inbox N, heap free M`), the
  link rate with 4 blocks a message (the extension's `speak:` line), whether PLAY timing leaves no
  `speaker: ring ran dry`, and the 2 Duo's heap with dictation open.
