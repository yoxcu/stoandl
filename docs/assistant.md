# Ask Claude from the wrist (assistant extension)

`assistant` is an [extension](extensions.md) that sends a question to Claude with your own Anthropic API
key and puts the answer on the watch. It ships in
[`examples/extensions/assistant/`](../examples/extensions/assistant/): a static Go binary (official
Anthropic Go SDK, streaming) plus the **Assistant** watchapp (C, no PKJS). The wire contract between the
three parts (daemon, extension, watchapp) is
[PROTOCOL.md](../examples/extensions/assistant/PROTOCOL.md).

Nothing is sent anywhere until you install it, enable it and set a key. The only egress is
`api.anthropic.com` (or your `base_url`). Web search is a separate opt-in.

## Two ways to ask

**Notifications (works on every watch).** An "Ask Claude" card sits in the notification list. Open it,
Reply, then speak (needs `voice.backend`, see [voice.md](voice.md)) or pick a preset question. The answer
arrives as a new card titled with your question:

- a long answer is split into pages of 500 bytes, and **More (2/N)** posts the next page;
- **Reply** on an answer asks a follow-up in the same conversation (`Explain more`, `Shorter`, `Retry`,
  or your voice). Replying to an older card branches the conversation from that answer;
- a failure arrives as a short card (`Claude: busy`, `Claude: key rejected`, …) whose Reply offers
  `Retry`.

The anchor card is re-sent (silently) when the watch connects and it is older than 20 h, so it stays in
the watch's notification list.

**The Assistant watchapp (watches with a microphone; presets elsewhere).** Launch it (Quick Launch, the
app menu, or **Open app** on the anchor card): dictation starts at once, `Thinking…` shows, and the answer
streams in. Select asks a follow-up, hold Select starts a new chat, hold Up opens the presets. If you leave
the app before the answer is done, it arrives as a notification instead; it is never lost.

## Reading answers aloud

On a watch with a speaker (Pebble Time 2, Pebble 2 Duo) the Assistant app can speak its answers. The
daemon does the speech (its text to speech, **Settings → Voice**, `tts.backend`; off by default, so
nothing is spoken until you set it up), the extension streams the audio to the app, and the app plays it
on the watch speaker. The format and the reusable receiver are described in
[speaker-stream.md](speaker-stream.md).

- With `speak = always` (the default) every answer the app shows is read aloud once it is complete.
  It stays silent while the watch is in Quiet Time or its speaker is muted.
- **Hold Down** on an answer reads it (again), or stops the reading. It plays in Quiet Time too. The
  header shows `Speaking...`.
- Select (a follow-up), hold Select, a preset, Back, an incoming call or notification stop it first, so
  the microphone doesn't hear the speaker. While you dictate, the app also ignores a reading that
  arrives late (one still being synthesised when you pressed Select).
- `language = German` or `English` picks an installed voice of that language; other languages use the
  daemon's default voice.
- If text to speech is off or not working, Hold Down posts a short card saying so (`Text to speech is
  off: Turn it on in stoandl: Settings → Voice.`). `speak = on_request` reads only on Hold Down;
  `speak = off` never. A stoandl too old to synthesise never answers: after one unanswered try (30 s)
  answers are no longer read unasked until a later Hold Down gets an answer.
- Each sentence is synthesised while the previous one plays. The phone stays out of suspend while it
  reads (`keepAwake`, at most 120 s), from the first sentence stoandl answers. If the stream stops
  without an end (the phone left range or slept), the watch drops it after 35 s.

## Setup

```sh
stoandl ext install assistant-arm64.tar.gz   # or -amd64 on a desktop; also installs the watchapp
```

Then set the API key in the GUI (**Apps & Faces → Extensions → assistant**). It is stored in the keyring
(or the daemon's 0600 secrets file), never in the extension's `config` file; the extension starts once
the key is set. Without the GUI, put `api_key = sk-ant-…` into `~/.config/stoandl/ext/assistant/config`:
the daemon moves it into the secret store at the next start and deletes the line.

## Settings

| Key | Default | Meaning |
|---|---|---|
| `api_key` | (none) | Anthropic API key (secret, required). |
| `model` | `claude-haiku-4-5-20251001` | Or `claude-sonnet-5`, `claude-opus-5-5`. Changing it starts a new conversation. |
| `effort` | `low` | `low`, `medium`, `high`; sent to Sonnet 5 and Opus 5.5 only (Haiku 4.5 has no effort or thinking). |
| `max_tokens` | `2048` | Per answer; on Sonnet 5 and Opus 5.5 it includes thinking. A cut answer ends in `...`. |
| `web_search` | `false` | Let Claude search the web when a question needs current facts. Regular questions carry only a small `web_lookup` tool (about 100 tokens); when Claude calls it, the question is re-asked once with Anthropic's web search tool (thousands of tokens of tool definition, plus $0.01 per search) and the watch shows "Searching...". The searched answer is kept as text, so the conversation stays append-only. |
| `presets` | 4 starter questions | `\|`-separated: the anchor card's replies and the watchapp's presets menu. |
| `followups` | `Explain more\|Shorter\|Retry` | Replies on answer cards. |
| `idle_minutes` | `15` | A question with no card attached continues the last conversation only within this time. |
| `keep_history` | `true` | Keep conversations for 24 h in a file, so replies to older cards survive a restart. |
| `base_url` | `https://api.anthropic.com` | For a proxy. |
| `language` | (empty) | Reply language, e.g. `German`. Empty: the language you spoke, which a misheard question can flip (Parakeet guesses the language itself). |
| `speak` | `always` | `always`, `on_request` (Hold Down only) or `off`: read answers aloud on the watch speaker (above). |
| `system_prompt_extra` | (empty) | Appended to the built-in prompt (Pebble screen: answer first, 1–3 sentences, at most 40 words, plain text, longer only when asked). |

## Conversations and history

A conversation is the chain of questions and answers you reply along. Each request replays the earlier
turns exactly as the API returned them (append-only), so models with thinking keep their context. A
conversation lives 24 hours after its last use (at most 50 conversations, 30 turns each).

With `keep_history = true` they are kept in `${XDG_STATE_HOME:-~/.local/state}/stoandl/assistant/history.json`
(mode 0600, directory 0700), outside `~/.config/stoandl`, so `stoandl backup` never includes them. With
`false` they live in memory only, and the file is deleted at startup.

## Latency and cost

The extension logs one line per question (no key, the question cut to 40 characters):

```
"how long to boil an egg" ask conv=1a2b3c4d turn=1 model=claude-haiku-4-5-20251001 ttft=612 total=1480 in=312 out=58 searches=0 cost=$0.00060 stop=end_turn sink=notif
```

To compare the models before choosing one, run the bench by hand (never under the daemon). It reads only
`ANTHROPIC_API_KEY`, asks six fixed short questions per model, and prints TTFT and total time (p50/p90),
mean output tokens and the estimated cost:

```sh
ANTHROPIC_API_KEY=sk-ant-… ~/.config/stoandl/ext/assistant/stoandl-assistant bench -n 3
… bench -models claude-haiku-4-5-20251001 -effort low -web -tsv   # one line per request
```

Prices used (USD per million tokens in/out): Haiku 4.5 1/5, Sonnet 5 2/10, Opus 5.5 4/20, plus $0.01 per
web search; prompt caching is ignored.

## Timeouts and errors

No text within 20 s, or a network error, gives `Claude: no answer`; a request is capped at 60 s (any text
by then is shown, marked cut). HTTP 401/403 → `key rejected`; 429/529/overloaded → `busy`; a refusal →
`Claude declined`; other API errors → `Claude: error <status>` with the API's message. Only one question
runs at a time: a new one cancels the running one. While a question runs the extension holds a short
logind sleep block (`keepAwake`, at most 60 s), so a phone doesn't suspend mid-answer.

_Implemented — to be tested ([TESTING.md §5.50](../TESTING.md))._
