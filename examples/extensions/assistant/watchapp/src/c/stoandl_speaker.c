#include "stoandl_speaker.h"

// Receiver for the stoandl speaker stream; the format is docs/speaker-stream.md. Keep this file and
// every vendored copy identical.

#if defined(PBL_SPEAKER)

#include <string.h>

#define RING_BYTES 6144         // ADPCM ring; each block is stored behind a 2-byte length
#define LEN_BYTES 2
#define BLOCK_HEADER 4          // s16 LE first sample, u8 step index, u8 reserved
#define BLOCK_MAX 2000
#define PREBUFFER_SAMPLES 3200  // 0.4 s at 8 kHz before the stream opens
#define TICK_MS 40
#define FRAME_SAMPLES 256
#define VOLUME 100              // the watch scales it by the user's volume cap
#define STALL_MS 35000          // a stream with no message for this long is dropped (senders: 30 s at most)

typedef enum {
  SPK_IDLE,
  SPK_BUFFERING,  // receiving, not yet 0.4 s in the ring
  SPK_PLAYING,    // stream open, the tick drains the ring into it
  SPK_CLOSING,    // closed after END; the finish callback marks it idle
} SpkState;

static const int16_t s_steps[89] = {
    7,     8,     9,     10,    11,    12,    13,    14,    16,    17,    19,    21,    23,
    25,    28,    31,    34,    37,    41,    45,    50,    55,    60,    66,    73,    80,
    88,    97,    107,   118,   130,   143,   157,   173,   190,   209,   230,   253,   279,
    307,   337,   371,   408,   449,   494,   544,   598,   658,   724,   796,   876,   963,
    1060,  1166,  1282,  1411,  1552,  1707,  1878,  2066,  2272,  2499,  2749,  3024,  3327,
    3660,  4026,  4428,  4871,  5358,  5894,  6484,  7132,  7845,  8630,  9493,  10442, 11487,
    12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
};
static const int8_t s_index_adjust[8] = {-1, -1, -1, -1, 2, 4, 6, 8};

static StoandlSpeakerChanged s_on_change;
static void *s_on_change_ctx;

static SpkState s_state;
static uint32_t s_id;
static uint16_t s_next_seq;
static bool s_ended;
// A stream stopped on the watch (or refused) whose late messages are ignored until a new id arrives.
static uint32_t s_dead_id;
static bool s_has_dead;
// Nothing starts playing while a modal window (a call, a notification) covers the app, or while the app
// holds the receiver off (dictation).
static bool s_unfocused;
static bool s_suppressed;

static uint8_t s_ring[RING_BYTES];
static uint16_t s_ring_head;
static uint16_t s_ring_used;
static uint32_t s_buffered_samples;

// Decoder position in the block at the ring head (s_blk_len 0: none open).
static uint16_t s_blk_len;
static uint16_t s_blk_pos;
static bool s_blk_high;
static int32_t s_pred;
static int32_t s_index;

static int16_t s_frame[FRAME_SAMPLES];
static uint16_t s_frame_len;  // bytes
static uint16_t s_frame_off;  // bytes already written to the stream

static AppTimer *s_timer;
static AppTimer *s_stall_timer;  // runs while a stream is buffering or playing; every message restarts it

static void notify(bool active) {
  if (s_on_change) {
    s_on_change(active, s_on_change_ctx);
  }
}

static uint32_t read_uint(DictionaryIterator *iter, uint32_t key) {
  Tuple *t = dict_find(iter, key);
  if (!t || (t->type != TUPLE_UINT && t->type != TUPLE_INT)) {
    return 0;
  }
  switch (t->length) {
    case 1:
      return t->value->uint8;
    case 2:
      return t->value->uint16;
    case 4:
      return t->value->uint32;
    default:
      return 0;
  }
}

static uint8_t ring_at(uint16_t offset) { return s_ring[(s_ring_head + offset) % RING_BYTES]; }

static void ring_clear(void) {
  s_ring_head = 0;
  s_ring_used = 0;
  s_buffered_samples = 0;
  s_blk_len = 0;
  s_frame_len = 0;
  s_frame_off = 0;
}

static bool ring_push(const uint8_t *block, uint16_t len) {
  if (RING_BYTES - s_ring_used < len + LEN_BYTES) {
    return false;
  }
  uint16_t at = (s_ring_head + s_ring_used) % RING_BYTES;
  const uint8_t prefix[LEN_BYTES] = {len & 0xFF, len >> 8};
  for (uint16_t i = 0; i < LEN_BYTES + len; i++) {
    s_ring[at] = i < LEN_BYTES ? prefix[i] : block[i - LEN_BYTES];
    at = (at + 1) % RING_BYTES;
  }
  s_ring_used += len + LEN_BYTES;
  s_buffered_samples += 1 + 2 * (uint32_t)(len - BLOCK_HEADER);
  return true;
}

static int16_t decode_nibble(uint8_t code) {
  int32_t step = s_steps[s_index];
  int32_t diff = step >> 3;
  if (code & 1) {
    diff += step >> 2;
  }
  if (code & 2) {
    diff += step >> 1;
  }
  if (code & 4) {
    diff += step;
  }
  s_pred += (code & 8) ? -diff : diff;
  if (s_pred > 32767) {
    s_pred = 32767;
  } else if (s_pred < -32768) {
    s_pred = -32768;
  }
  s_index += s_index_adjust[code & 7];
  if (s_index < 0) {
    s_index = 0;
  } else if (s_index > 88) {
    s_index = 88;
  }
  return (int16_t)s_pred;
}

// Decodes up to one frame from the ring; a block leaves the ring once it is fully decoded.
static bool fill_frame(void) {
  uint16_t n = 0;
  while (n < FRAME_SAMPLES) {
    if (s_blk_len == 0) {
      if (s_ring_used == 0) {
        break;
      }
      s_blk_len = ring_at(0) | (ring_at(1) << 8);
      s_pred = (int16_t)(ring_at(LEN_BYTES) | (ring_at(LEN_BYTES + 1) << 8));
      s_index = ring_at(LEN_BYTES + 2);
      if (s_index > 88) {
        s_index = 88;
      }
      s_blk_pos = BLOCK_HEADER;
      s_blk_high = false;
      s_frame[n++] = (int16_t)s_pred;
      continue;
    }
    if (s_blk_pos >= s_blk_len) {
      s_ring_head = (s_ring_head + LEN_BYTES + s_blk_len) % RING_BYTES;
      s_ring_used -= LEN_BYTES + s_blk_len;
      s_blk_len = 0;
      continue;
    }
    uint8_t byte = ring_at(LEN_BYTES + s_blk_pos);
    s_frame[n++] = decode_nibble(s_blk_high ? byte >> 4 : byte & 0x0F);
    if (s_blk_high) {
      s_blk_pos++;
    }
    s_blk_high = !s_blk_high;
  }
  s_buffered_samples = s_buffered_samples > n ? s_buffered_samples - n : 0;
  s_frame_len = n * sizeof(int16_t);
  s_frame_off = 0;
  return n > 0;
}

static void go_idle(void) {
  if (s_timer) {
    app_timer_cancel(s_timer);
    s_timer = NULL;
  }
  if (s_stall_timer) {
    app_timer_cancel(s_stall_timer);
    s_stall_timer = NULL;
  }
  if (s_state != SPK_IDLE) {
    app_comm_set_sniff_interval(SNIFF_INTERVAL_NORMAL);
  }
  s_state = SPK_IDLE;
  ring_clear();
}

// Back to idle now: stops the speaker if the stream is open, empties the ring.
static void reset(void) {
  if (s_state == SPK_PLAYING || s_state == SPK_CLOSING) {
    speaker_stop();  // an open stream fed silence would keep the speaker running
  }
  go_idle();
}

static void kill_stream(void) {
  s_dead_id = s_id;
  s_has_dead = true;
  reset();
  notify(false);
}

// The sender went quiet without END or ABORT (its app link failed, it restarted, the phone slept): an
// open stream would play silence, and hold the reduced sniff interval, until the app closes.
static void stalled(void *ctx) {
  s_stall_timer = NULL;
  if (s_state == SPK_BUFFERING || s_state == SPK_PLAYING) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "speaker: stream stalled, dropped");
    kill_stream();
  }
}

static void restart_stall_timer(void) {
  if (!s_stall_timer || !app_timer_reschedule(s_stall_timer, STALL_MS)) {
    s_stall_timer = app_timer_register(STALL_MS, stalled, NULL);
  }
}

// Writes decoded frames until the stream takes only part of one (its buffer is full); the rest of
// that frame goes first on the next tick.
static void tick(void *ctx) {
  s_timer = NULL;
  if (s_state != SPK_PLAYING) {
    return;
  }
  for (;;) {
    if (s_frame_off >= s_frame_len && !fill_frame()) {
      break;
    }
    uint32_t want = s_frame_len - s_frame_off;
    uint32_t wrote = speaker_stream_write((const uint8_t *)s_frame + s_frame_off, want);
    s_frame_off += wrote;
    if (wrote < want) {
      break;
    }
  }
  if (s_ended && s_ring_used == 0 && s_frame_off >= s_frame_len) {
    speaker_stream_close();  // plays out what the firmware holds, then the finish callback
    s_state = SPK_CLOSING;
    return;
  }
  s_timer = app_timer_register(TICK_MS, tick, NULL);
}

static void start_playing(void) {
  if (!speaker_stream_open(SpeakerPcmFormat_8kHz_16bit, VOLUME)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "speaker: stream refused");
    kill_stream();
    return;
  }
  s_state = SPK_PLAYING;
  tick(NULL);
}

static void finished(SpeakerFinishReason reason, void *ctx) {
  if (reason == SpeakerFinishReasonStopped) {
    return;  // our own speaker_stop(), already handled
  }
  if (reason == SpeakerFinishReasonDone) {
    if (s_state == SPK_CLOSING) {
      go_idle();
      notify(false);
    }
    return;
  }
  if (s_state == SPK_PLAYING || s_state == SPK_CLOSING) {
    kill_stream();  // preempted (a call, an alarm) or failed: drop the rest of this stream
  }
}

static void will_focus(bool in_focus) {
  s_unfocused = !in_focus;
  if (!in_focus) {
    stoandl_speaker_stop();
  }
}

bool stoandl_speaker_handle(DictionaryIterator *iter) {
  if (!dict_find(iter, STOANDL_SPK_KEY_ID)) {
    return false;
  }
  uint32_t id = read_uint(iter, STOANDL_SPK_KEY_ID);
  uint8_t flags = read_uint(iter, STOANDL_SPK_KEY_FLAGS);
  uint16_t seq = read_uint(iter, STOANDL_SPK_KEY_SEQ);
  if (s_has_dead && id == s_dead_id) {
    return true;
  }
  if (flags & STOANDL_SPK_FLAG_ABORT) {
    if (s_state == SPK_IDLE || id == s_id) {
      s_id = id;
      kill_stream();
    }
    return true;
  }
  if (s_state != SPK_IDLE && id != s_id) {
    reset();  // a new stream replaces the one playing
  }
  if (s_state == SPK_IDLE && (s_unfocused || s_suppressed)) {
    s_dead_id = id;  // e.g. an answer read aloud unasked arriving during dictation: the mic would hear it
    s_has_dead = true;
    notify(false);   // clears an app's "waiting for speech"; once per id, later blocks are dead above
    return true;
  }
  if (s_state == SPK_IDLE) {
    s_id = id;
    s_next_seq = 0;
    s_ended = false;
    s_state = SPK_BUFFERING;
    app_comm_set_sniff_interval(SNIFF_INTERVAL_REDUCED);
    notify(true);
  }
  if (seq < s_next_seq) {
    return true;  // a retry whose ACK was lost
  }
  s_next_seq = seq + 1;
  restart_stall_timer();

  Tuple *data = dict_find(iter, STOANDL_SPK_KEY_DATA);
  if (data && data->type == TUPLE_BYTE_ARRAY && data->length > BLOCK_HEADER && data->length <= BLOCK_MAX &&
      !ring_push(data->value->data, data->length)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "speaker: ring full, block %u dropped", seq);
  }
  if (flags & STOANDL_SPK_FLAG_END) {
    s_ended = true;
  }
  if (s_state == SPK_BUFFERING) {
    if (s_ended && s_ring_used == 0) {
      reset();
      notify(false);
    } else if (s_ended || s_buffered_samples >= PREBUFFER_SAMPLES) {
      start_playing();
    }
  }
  return true;
}

void stoandl_speaker_stop(void) {
  if (s_state != SPK_IDLE) {
    kill_stream();
  }
}

void stoandl_speaker_suppress(bool on) {
  s_suppressed = on;
  if (on) {
    stoandl_speaker_stop();
  }
}

bool stoandl_speaker_active(void) { return s_state != SPK_IDLE; }

bool stoandl_speaker_usable(void) { return !speaker_is_muted(); }

void stoandl_speaker_init(StoandlSpeakerChanged on_change, void *ctx) {
  s_on_change = on_change;
  s_on_change_ctx = ctx;
  speaker_set_finish_callback(finished, NULL);
  app_focus_service_subscribe_handlers((AppFocusHandlers){.will_focus = will_focus});
}

void stoandl_speaker_deinit(void) {
  s_on_change = NULL;
  reset();
  speaker_set_finish_callback(NULL, NULL);
}

#else  // no speaker: consume the keys, nothing else

bool stoandl_speaker_handle(DictionaryIterator *iter) { return dict_find(iter, STOANDL_SPK_KEY_ID) != NULL; }

void stoandl_speaker_stop(void) {}

void stoandl_speaker_suppress(bool on) {}

bool stoandl_speaker_active(void) { return false; }

bool stoandl_speaker_usable(void) { return false; }

void stoandl_speaker_init(StoandlSpeakerChanged on_change, void *ctx) {}

void stoandl_speaker_deinit(void) {}

#endif
