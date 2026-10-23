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

## To-dos

Say what you mustn't forget, either way of asking: "don't let me forget to call the garage tomorrow at
five", "remind me to buy milk", "note: the parking spot is C4". Claude saves it as a to-do in your list and
answers with a short confirmation ("Noted: call the garage, tomorrow, Thursday 22 October, 17:00."), read
aloud like any answer. Claude takes the due day from a list of the coming week's dates sent with each
question, and asks back when the day or AM/PM is unclear or the time has passed; a due that has passed
anyway (more than 5 minutes ago, or a day before today) is not saved ("Couldn't save the to-do: that time
has passed"). The dates follow the phone's time zone when it changes. The day and time in the answer are
not Claude's words: the extension says the due it saved, relative to now (today, tomorrow, or the
weekday), with English or German names: those of `language` when it is set (any other language gets
`2026-10-22 17:00`), else those of the language you spoke. A to-do with a time gets a reminder in your to-do app, one with only a day a reminder at 09:00.
Several things in one sentence become several to-dos.

The list is stoandl's `calendar.todos` (Settings → Calendar → To-do list): a CalDAV list of one of your
calendar accounts, or a local calendar file ([configuration.md → To-dos](configuration.md#to-dos)). The
extension needs no sign-in of its own. When the server can't be reached, the to-do is kept on the phone and
sent after the next calendar sync; the answer then ends with "(Saved offline, syncs later.)". Asking needs
the network, though: in a dead zone Claude can't be reached at all.

Every question carries the small `add_todo` tool definition (a few hundred input tokens). A to-do costs one
request: Claude writes the confirmation into the tool call, and the turn is kept as that text, the due
filled in. The dates of the coming week add about a hundred input tokens to each question.

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
- Each sentence is synthesised while the previous one plays; a long first sentence is split at a comma
  (or colon, dash) so the first sound comes sooner. With Piper's `piper_exe` the daemon keeps the voice
  loaded between sentences (and for 60 s after), which saves about 3 s per sentence on a phone.
- The app (1.1.1) takes several half-second blocks per message (4 on a Time 2, 3 on a 2 Duo), since
  every message costs a round trip. The watch waits with the first sound until it holds enough that the
  rest of the answer, at the rate the link has shown, arrives before it is needed, but at most 3 s, so a
  slow link means a later start instead of pauses.
- The phone stays out of suspend while it reads (`keepAwake`, at most 120 s), from the first sentence
  stoandl answers. If the stream stops without an end (the phone left range or slept), the watch drops it
  after 35 s.

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

Each reading aloud logs one line too (never the text):

```
speak: 5 messages, 18 blocks, 36000 B, 8984 ms of audio in 6120 ms (first send to last ack), ack avg 1210 max 1530 ms, 5.9 KB/s (link 5.9), started after 1840 ms (play), underrun ~0 ms
```

The rate is what the watch link carried; below 4.0 KB/s speech can't keep up without a delayed start, and
the underrun estimates how long the watch had nothing to play (the watch log says `speaker: ring ran dry`).

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

A to-do that can't be saved shows why instead of the confirmation: "No to-do list chosen: …" (set
`calendar.todos`), "Couldn't save the to-do: unclear date" (a due that isn't a date or time; nothing is
saved), "Couldn't save the to-do: that time has passed" (a due before now; nothing is saved), "Couldn't save the to-do: …" (the daemon's reason, or no answer within 30 s), "this stoandl is too
old for to-dos" (a daemon without `addTodo`).

No text within 20 s, or a network error, gives `Claude: no answer`; a request is capped at 60 s (any text
by then is shown, marked cut). HTTP 401/403 → `key rejected`; 429/529/overloaded → `busy`; a refusal →
`Claude declined`; other API errors → `Claude: error <status>` with the API's message. Only one question
runs at a time: a new one cancels the running one. While a question runs the extension holds a short
logind sleep block (`keepAwake`, at most 60 s), so a phone doesn't suspend mid-answer.

_Implemented — to be tested ([TESTING.md §5.50](../TESTING.md); to-dos §5.76)._
