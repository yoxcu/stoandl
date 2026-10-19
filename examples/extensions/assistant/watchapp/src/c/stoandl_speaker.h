#pragma once
#include <pebble.h>

// Speech from a stoandl extension on the watch speaker: the receiving half of the speaker stream
// (docs/speaker-stream.md). Vendor stoandl_speaker.{c,h} into your watchapp's src/c/ next to your own
// code, like the extension vendors stoandl_ext.go, and keep the copies identical.
//
// The extension sends self-contained IMA ADPCM blocks (8 kHz mono, at most 2000 bytes each) under four
// reserved AppMessage keys. stoandl_speaker_handle() consumes them; everything else is the app's. On
// platforms without a speaker (no PBL_SPEAKER) the receiver is a stub that only consumes the keys, so
// it costs no RAM there.
//
// Use:
//   init():           stoandl_speaker_init(on_change, ctx);  app_message_open(STOANDL_SPEAKER_INBOX_MIN or more, …)
//   inbox_received(): if (stoandl_speaker_handle(iter)) return;
//   deinit():         stoandl_speaker_deinit();
//
// The receiver subscribes to the app focus service: playback stops when a modal window (a call, a
// notification, dictation) covers the app. An app that needs focus events itself calls
// stoandl_speaker_stop() from its own handler, after stoandl_speaker_init().

#define STOANDL_SPK_KEY_ID 0x7FFF0000     // uint32: stream id, new for every stream
#define STOANDL_SPK_KEY_SEQ 0x7FFF0001    // uint16: block number within the stream, from 0
#define STOANDL_SPK_KEY_DATA 0x7FFF0002   // bytes: one ADPCM block
#define STOANDL_SPK_KEY_FLAGS 0x7FFF0003  // uint8: STOANDL_SPK_FLAG_*

#define STOANDL_SPK_FLAG_START 0x01
#define STOANDL_SPK_FLAG_END 0x02
#define STOANDL_SPK_FLAG_ABORT 0x04

// An inbox that holds one full block message (2000 data bytes plus the dictionary overhead).
#define STOANDL_SPEAKER_INBOX_MIN 2048

// Called with true when a stream starts (its first block arrived) and with false when it ends: played
// out, stopped, aborted by the extension, or refused. A false can repeat; treat it as "not speaking".
typedef void (*StoandlSpeakerChanged)(bool active, void *ctx);

void stoandl_speaker_init(StoandlSpeakerChanged on_change, void *ctx);
void stoandl_speaker_deinit(void);

// Returns true when the message was a speaker-stream message (consumed, even by the stub).
bool stoandl_speaker_handle(DictionaryIterator *iter);

// Stops playback now and ignores the rest of the current stream; the next stream id plays again.
void stoandl_speaker_stop(void);

// A stream is buffering or playing.
bool stoandl_speaker_active(void);

// The watch has a speaker and the user hasn't muted it (Settings, or Quiet Time with "mute speaker").
bool stoandl_speaker_usable(void);
