#pragma once
#include <pebble.h>

// Speech from a stoandl extension on the watch speaker: the receiving half of the speaker stream
// (docs/speaker-stream.md). Vendor stoandl_speaker.{c,h} into your watchapp's src/c/ next to your own
// code, like the extension vendors stoandl_ext.go, and keep the copies identical.
//
// The extension sends self-contained IMA ADPCM blocks (8 kHz mono, at most 2000 bytes each, one or more
// per message) under four reserved AppMessage keys. stoandl_speaker_handle() consumes them; everything
// else is the app's. On platforms without a speaker (no PBL_SPEAKER) the receiver is a stub that only
// consumes the keys, so it costs no RAM there.
//
// Use:
//   init():           stoandl_speaker_init(on_change, ctx);
//                     inbox = MIN(STOANDL_SPEAKER_INBOX_MAX, app_message_inbox_size_maximum());
//                     app_message_open(inbox (at least STOANDL_SPEAKER_INBOX_MIN), …)
//                     tell the extension stoandl_speaker_data_max(inbox) and stoandl_speaker_ring_bytes()
//                     through your own protocol (a sender without them sends one block per message)
//   inbox_received(): if (stoandl_speaker_handle(iter)) return;
//   deinit():         stoandl_speaker_deinit();
//
// The receiver subscribes to the app focus service: playback stops when a modal window (a call, a
// notification) covers the app. An app that needs focus events itself calls stoandl_speaker_stop() from
// its own handler, after stoandl_speaker_init(). Dictation is not a focus change (its window opens in
// the app's own window stack), so an app that dictates holds the receiver off around it:
//   stoandl_speaker_suppress(true); dictation_session_start(…);  …  in the dictation callback:
//   stoandl_speaker_suppress(false);
// A stream with no message for 35 s (the sender vanished without END or ABORT) is dropped.

#define STOANDL_SPK_KEY_ID 0x7FFF0000     // uint32: stream id, new for every stream
#define STOANDL_SPK_KEY_SEQ 0x7FFF0001    // uint16: the (first) block's number within the stream, from 0
#define STOANDL_SPK_KEY_DATA 0x7FFF0002   // bytes: one ADPCM block, or several with PACKED
#define STOANDL_SPK_KEY_FLAGS 0x7FFF0003  // uint8: STOANDL_SPK_FLAG_*

#define STOANDL_SPK_FLAG_START 0x01
#define STOANDL_SPK_FLAG_END 0x02
#define STOANDL_SPK_FLAG_ABORT 0x04
#define STOANDL_SPK_FLAG_PACKED 0x08  // DATA is blocks, each behind its u16 little-endian length
#define STOANDL_SPK_FLAG_HOLD 0x10    // with START: don't start at 0.4 s, the sender sends PLAY
#define STOANDL_SPK_FLAG_PLAY 0x20    // start playing now

// An inbox that holds one full block message (2000 data bytes plus the dictionary overhead).
#define STOANDL_SPEAKER_INBOX_MIN 2048
// The inbox worth opening for PACKED messages: one 8 KiB byte array on emery (4 blocks a message), less
// on flint, whose app has half the RAM. The firmware caps it at app_message_inbox_size_maximum().
#if defined(PBL_PLATFORM_FLINT)
#define STOANDL_SPEAKER_INBOX_MAX 6200
#elif defined(PBL_SPEAKER)
#define STOANDL_SPEAKER_INBOX_MAX 8200
#else
#define STOANDL_SPEAKER_INBOX_MAX STOANDL_SPEAKER_INBOX_MIN
#endif

// Called with true when a stream starts (its first block arrived) and with false when it ends: played
// out, stopped, aborted by the extension, stalled, or refused (also a stream ignored because it began
// while suppressed or unfocused). A false can repeat; treat it as "not speaking".
typedef void (*StoandlSpeakerChanged)(bool active, void *ctx);

void stoandl_speaker_init(StoandlSpeakerChanged on_change, void *ctx);
void stoandl_speaker_deinit(void);

// Returns true when the message was a speaker-stream message (consumed, even by the stub).
bool stoandl_speaker_handle(DictionaryIterator *iter);

// Stops playback now and ignores the rest of the current stream; the next stream id plays again.
void stoandl_speaker_stop(void);

// While on, nothing plays: the current stream stops, and a stream that begins is ignored (on_change
// false). For the time the microphone listens.
void stoandl_speaker_suppress(bool on);

// A stream is buffering or playing.
bool stoandl_speaker_active(void);

// The watch has a speaker and the user hasn't muted it (Settings, or Quiet Time with "mute speaker").
bool stoandl_speaker_usable(void);

// For the sender (docs/speaker-stream.md, "Receiver capabilities"): the bytes of DATA one PACKED message may
// carry in an inbox of inbox_size, and the receiver's ring in bytes. Both 0 without a speaker.
uint16_t stoandl_speaker_data_max(uint32_t inbox_size);
uint16_t stoandl_speaker_ring_bytes(void);
