# Speaker stream: speech from an extension to the watch speaker

There is no host-to-watch audio endpoint in the Pebble protocol, and only a foreground watchapp can
drive the speaker. So speech reaches the speaker through the app's own AppMessages: an
[extension](extensions.md) asks the daemon to `synthesize` text, gets the audio back as small
self-contained blocks, and sends them to its watchapp, which decodes and plays them. Only watches with a
speaker can play it (`PBL_SPEAKER`: Pebble Time 2 = emery, Pebble 2 Duo = flint).

| Part | Where | Role |
|---|---|---|
| Encoder | the daemon's text to speech (`tts.*`, [voice.md](voice.md)) | WAV → low-pass, 8 kHz → blocks below |
| `synthesize` | ext JSON-RPC ([extensions.md](extensions.md)) | `{text, language?, format:"pebble-adpcm"}` → `{blocks:[base64…], ms}` |
| `Ext.Synthesize`, `SpeakerPump` | [`examples/extensions/stoandl_ext.go`](../examples/extensions/stoandl_ext.go) | the RPC call; sends blocks through the extension's one-in-flight AppMessage path, paced to the watch |
| `stoandl_speaker.{c,h}` | [`examples/extensions/watchlib/`](../examples/extensions/watchlib/) | the watch receiver, vendored into each watchapp |

The [assistant](assistant.md) is the first user: it reads Claude's answers aloud.

## Block format

IMA ADPCM, mono, 8 kHz. Every block decodes on its own (no state carries over), so a lost or skipped
block costs half a second of audio, never the rest of the stream.

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | first sample, s16 little-endian; it is also the decoder's predictor |
| 2 | 1 | step index, 0–88 |
| 3 | 1 | reserved, 0 |
| 4 | ≤ 1996 | 4-bit codes, two per byte, **low nibble first** (Microsoft IMA / WAV order) |

- A full block is 2000 bytes: 1 + 2 × 1996 = 3993 samples, about 499 ms.
- The last block of a stream may be shorter. A block of `n` bytes always decodes to `1 + 2 × (n − 4)`
  samples, so the encoder pads an odd sample count with one more code.
- Decoding uses the standard IMA tables (89 steps from 7 to 32767; index adjust −1, −1, −1, −1, 2, 4, 6, 8
  by the low three bits). Per code: `diff = step>>3`, plus `step>>2` for bit 0, `step>>1` for bit 1 and
  `step` for bit 2; bit 3 subtracts. The predictor is clamped to s16 and the index to 0–88.
- 4 bits per sample at 8 kHz is about 4 KB/s, half of 8-bit PCM.
- The daemon's encoder (`voice/tts/WatchSpeakerEncoding.kt`) never emits a header-only block: the
  shortest is 5 bytes (3 samples). Known vector: 16 samples of an 8000-amplitude sine at 1 kHz
  (`8000·sin(2π·i/8)`) encode to the single block `00003f0017c99b4114c98b81`, which both
  `stoandl_speaker.c`'s decoder and a separate Python IMA encoder reproduce (`WatchSpeakerEncodingTest`).
- Before encoding, the daemon resamples the engine's audio to 8 kHz through a Blackman-windowed sinc
  low-pass at 3.6 kHz, then peak-normalises to 95 % of full scale with a 2:1 compressor above half the peak
  and at most 16× gain, which keeps quiet voices audible on the small speaker.

## AppMessage keys

The four keys sit at the top of the 31-bit key space, so they can't collide with an app's own keys. A
speaker message carries only these keys; `stoandl_speaker_handle()` returns true for any message with
`SPK_ID`, and the app returns from its inbox handler.

| Key | Name | Type | Meaning |
|---|---|---|---|
| `0x7FFF0000` | SPK_ID | uint32 | stream id, new for every stream, never 0 |
| `0x7FFF0001` | SPK_SEQ | uint16 | block number in the stream, from 0; END carries the next number |
| `0x7FFF0002` | SPK_DATA | bytes | one block (absent on END-only and ABORT messages) |
| `0x7FFF0003` | SPK_FLAGS | uint8 | `0x01` START (on block 0), `0x02` END, `0x04` ABORT |

One block per message, so a message is at most about 2040 bytes: the watchapp's inbox must be at least
`STOANDL_SPEAKER_INBOX_MIN` (2048). The assistant opens 2304 on speaker watches.

## Sender rules (`SpeakerPump`)

- **One message in flight.** Speech blocks go through the same single-sender path as the extension's
  other messages to that watchapp: the next leaves only after the ACK. A NACK is retried after 200, 400
  and 800 ms; a slow NACK, a timeout or a fourth failure means the app is gone, and the stream stops.
- **Pacing.** The receiver keeps blocks in a 6144-byte ring, each behind a 2-byte length. Before each
  block the pump waits until the bytes the watch still holds, plus this block and its length, fit in
  6144. A block counts as held until its estimated play time has passed. Playback is estimated to begin
  when 0.4 s of audio has been ACKed, and to resume at once after the watch ran dry. The estimate is
  conservative: the watch frees a block when it has decoded it into the firmware's 8 KB stream buffer,
  about half a second before it is heard.
- **End.** After the last block, END (`SPK_FLAGS=0x02`, no data). The watch plays out what it holds.
- **Abort.** ABORT stops the watch at once. It also clears a watch that showed "Speaking…" for a
  request that produced no audio; that abort may carry a fresh id.
- **Pipelining.** The sender asks for the next sentence's blocks while the current one plays, one
  `synthesize` call at a time.
- **Gaps.** At most 30 s between two messages of a stream (the assistant's `synthesize` timeout); the
  receiver drops a stream after 35 s without one. A sender that gives up waits no longer and sends END
  or ABORT.

## Receiver rules (`stoandl_speaker.c`)

- **Dedupe.** A SEQ below the next expected one is a retry whose ACK was lost: ignored. A SEQ above it
  (a lost block) plays on.
- **Ids.** A new SPK_ID replaces the stream that is playing. A stream the watch stopped (its user, focus
  loss, a preempting call) is remembered, and its late blocks are ignored until another id arrives. A
  stream that begins while a modal window covers the app (a call, a notification) or while the app
  suppresses the receiver (dictation) is ignored the same way, and `on_change(false)` reports it.
- **Start.** The stream opens (`SpeakerPcmFormat_8kHz_16bit`, volume 100, which the watch scales by the
  user's cap) once 0.4 s is buffered, or at END for a shorter clip.
- **Drain.** A 40 ms timer decodes 256-sample frames and writes them until `speaker_stream_write`
  takes only part of one; the rest of that frame goes first on the next tick. On underrun the firmware
  plays silence and keeps the stream open.
- **Link.** `SNIFF_INTERVAL_REDUCED` from the first block until the stream is idle again.
- **Stall.** A buffering or playing stream with no message for 35 s is dropped like an ABORT
  (`speaker: stream stalled` in the watch log): the sender vanished without END or ABORT (its app link
  failed, it restarted, the phone left range or slept), and an open stream would play silence, keep the
  speaker on and hold the reduced sniff interval until the app closes.
- **Stop.** END closes the stream after the ring is empty (`speaker_stream_close()`; the finish
  callback marks it idle). ABORT, a stall, `stoandl_speaker_stop()`, `stoandl_speaker_suppress(true)`,
  focus loss (a call, a notification) and `stoandl_speaker_deinit()` call `speaker_stop()` at once,
  since an open stream fed silence keeps the speaker running.
- **Dictation.** The voice window opens in the app's own window stack, so dictation is no focus change
  and the focus service can't keep speech out of the microphone. An app that dictates calls
  `stoandl_speaker_suppress(true)` before `dictation_session_start()` and `(false)` in the dictation
  callback (or when the start fails).
- **No speaker.** Without `PBL_SPEAKER` the file compiles to a stub that only consumes the keys
  (no ring, no RAM cost on basalt, chalk, diorite or gabbro).

### Watchapp API

```c
void stoandl_speaker_init(StoandlSpeakerChanged on_change, void *ctx);  // on_change(active, ctx)
void stoandl_speaker_deinit(void);
bool stoandl_speaker_handle(DictionaryIterator *iter);  // first thing in inbox_received
void stoandl_speaker_stop(void);
void stoandl_speaker_suppress(bool on);  // while on, nothing plays (dictation)
bool stoandl_speaker_active(void);   // buffering or playing
bool stoandl_speaker_usable(void);   // a speaker, and not muted (Settings, or Quiet Time with "mute speaker")
```

`on_change(true)` fires when a stream's first message arrives, and `on_change(false)` when it ends for any
reason, or when a stream is ignored because it began while suppressed or unfocused (it may repeat). The receiver subscribes to the app focus service; an app that needs focus events
itself calls `stoandl_speaker_stop()` from its own handler.

Quiet Time mutes the speaker only when the watch's "mute speaker in Quiet Time" preference is on, so an
app that speaks unasked should also stay quiet while `quiet_time_is_active()`.

## Extension API (Go)

```go
ext.Synthesize(text, language, func(blocks [][]byte, ms int, err string) { … })  // err: "off", "unavailable: …", "busy", …
pump := NewSpeakerPump(send)        // send: your one-in-flight AppMessage path to the watchapp
pump.Write(ctx, blocks)             // paced; false when ctx ended or the watch didn't take a block
pump.End(ctx)                       // or pump.Abort(ctx)
```
