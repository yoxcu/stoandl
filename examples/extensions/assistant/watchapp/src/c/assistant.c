#include <pebble.h>
#include <string.h>

#include "stoandl_speaker.h"

// "Ask Claude" watchapp. The companion is the stoandl `assistant` extension (no PebbleKit JS); the
// AppMessage contract is section C of ../../PROTOCOL.md, which this file follows key for key. Answers
// are read aloud through stoandl_speaker.c (a copy of examples/extensions/watchlib/, keep it identical).

#define KEY_CMD 0
#define KEY_TEXT 1
#define KEY_SEQ 2
#define KEY_TURN 3
#define KEY_CONV 4
#define KEY_FLAGS 5
#define KEY_INBOX 6
#define KEY_CAPS 7
#define KEY_PROTO 8
#define KEY_CODE 9
#define KEY_INDEX 10
#define KEY_SPK_DATA 11  // HELLO: bytes of speech one message may carry (stoandl_speaker_data_max)
#define KEY_SPK_RING 12  // HELLO: the speech receiver's ring in bytes

#define CMD_HELLO 1
#define CMD_ASK 2
#define CMD_PRESET 3
#define CMD_NEW_CONV 4
#define CMD_CANCEL 5
#define CMD_BYE 6
#define CMD_SPEAK 7
#define CMD_STOP_SPEAK 8

#define CMD_PRESETS 16
#define CMD_STATUS 17
#define CMD_CHUNK 18
#define CMD_DONE 19
#define CMD_ERROR 20

#define PROTO_VERSION 1
#define CAP_MIC 0x01
#define CAP_COLOR 0x02
#define CAP_ROUND 0x04
#define CAP_SPEAKER 0x08
#define CAP_MUTED 0x10
#define FLAG_TRUNCATED 0x01
#define FLAG_REPLAY 0x02

#define STATUS_IDLE 0
#define STATUS_SEARCHING 2

#define ERR_LOCAL 0
#define ERR_CANCELLED 1
#define ERR_BAD_REQUEST 8

#if defined(PBL_SPEAKER)
#define INBOX_WANT STOANDL_SPEAKER_INBOX_MAX  // several speech blocks per message
#else
#define INBOX_WANT 768
#endif
#define OUTBOX_SIZE 640
#define ANSWER_MAX 4096
#define QUESTION_MAX 100
#define ASK_MAX 500
#define DICTATION_BUF 512
#define PRESETS_MAX 512
#define MAX_PRESETS 16
#define ERROR_MAX 100

#define OUTQ_LEN 4
#define SEND_RETRY_MS 500
#define QUIET_DONE_MS 3000
#define LONG_PRESS_MS 500
#define NOTE_MS 2000

typedef enum {
  ST_IDLE,
  ST_THINKING,
  ST_STREAMING,
  ST_ANSWER,
  ST_ERROR,
} UiState;

typedef struct {
  uint8_t cmd;
  uint8_t index;
  uint8_t turn;
  uint32_t conv;
  char text[ASK_MAX + 1];
} OutMsg;

static Window *s_window;
static ScrollLayer *s_scroll;
static TextLayer *s_header;
static TextLayer *s_body;
static Layer *s_ind_up;
static Layer *s_ind_down;

static Window *s_menu_window;
static MenuLayer *s_menu;

#if defined(PBL_MICROPHONE)
static DictationSession *s_dictation;
#endif

static UiState s_state = ST_IDLE;
static uint32_t s_inbox;  // the inbox the firmware gave us
// Active request as the extension named it (STATUS/ERROR). Real conversation ids are never 0.
static uint32_t s_conv;
static uint8_t s_turn;
static uint16_t s_expected_seq;
// An ASK/PRESET is out but its STATUS hasn't arrived: CHUNK/DONE still name the previous request.
static bool s_awaiting_status;
static bool s_cancel_pending;
static uint64_t s_request_start_ms;
// Hold Down asked to hear the answer and no audio has arrived yet.
static bool s_speak_wait;
// A short note in place of the header ("Speaker muted"), cleared by a timer.
static const char *s_note;
static AppTimer *s_note_timer;

static char s_question[QUESTION_MAX + 1];
static char s_answer[ANSWER_MAX];
static size_t s_answer_len;
static bool s_answer_full;
static const char *s_status_text = "";
static char s_error_text[ERROR_MAX + 1];
static uint8_t s_error_code;
static char s_body_text[ERROR_MAX + 64];

static char s_presets_buf[PRESETS_MAX + 1];
static const char *s_presets[MAX_PRESETS];
static uint8_t s_preset_count;

// Conversation context of the dictation or presets menu in progress (0, 0 = let the extension decide).
static uint32_t s_ask_conv;
static uint8_t s_ask_turn;

static OutMsg s_outq[OUTQ_LEN];
static uint8_t s_outq_head;
static uint8_t s_outq_count;
static bool s_out_busy;
static bool s_out_retried;
static AppTimer *s_retry_timer;
static OutMsg s_failed;

// ---------------------------------------------------------------------------------------------
// Text helpers

static uint64_t now_ms(void) {
  time_t s;
  uint16_t ms;
  time_ms(&s, &ms);
  return (uint64_t)s * 1000 + ms;
}

// Largest n <= max that doesn't cut a UTF-8 sequence of text (len bytes).
static size_t utf8_cut(const char *text, size_t len, size_t max) {
  if (len <= max) {
    return len;
  }
  size_t n = max;
  while (n > 0 && ((uint8_t)text[n] & 0xC0) == 0x80) {
    n--;
  }
  return n;
}

static void copy_cut(char *dst, size_t cap, const char *src, size_t src_len) {
  size_t n = utf8_cut(src, src_len, cap - 1);
  memcpy(dst, src, n);
  dst[n] = '\0';
}

// ---------------------------------------------------------------------------------------------
// Tuple readers: integers by their actual width, so a 1/2/4-byte mismatch with the sender can't misread.

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

// Returns the string and its byte length (without the terminator); "" when missing.
static const char *read_cstr(DictionaryIterator *iter, uint32_t key, size_t *len) {
  Tuple *t = dict_find(iter, key);
  if (!t || t->type != TUPLE_CSTRING || t->length == 0) {
    *len = 0;
    return "";
  }
  const char *nul = memchr(t->value->cstring, '\0', t->length);
  *len = nul ? (size_t)(nul - t->value->cstring) : 0;
  return nul ? t->value->cstring : "";
}

// ---------------------------------------------------------------------------------------------
// Layout

static bool big_screen(void) { return PBL_DISPLAY_WIDTH >= 200; }

static GFont body_font(void) {
  switch (preferred_content_size()) {
    case PreferredContentSizeSmall:
      return fonts_get_system_font(FONT_KEY_GOTHIC_18);
    case PreferredContentSizeLarge:
      return fonts_get_system_font(FONT_KEY_GOTHIC_24);
    case PreferredContentSizeExtraLarge:
      return fonts_get_system_font(FONT_KEY_GOTHIC_28);
    default:
      return fonts_get_system_font(big_screen() ? FONT_KEY_GOTHIC_24 : FONT_KEY_GOTHIC_18);
  }
}

static GFont header_font(void) {
  switch (preferred_content_size()) {
    case PreferredContentSizeSmall:
      return fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
    case PreferredContentSizeLarge:
    case PreferredContentSizeExtraLarge:
      return fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
    default:
      return fonts_get_system_font(big_screen() ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD);
  }
}

#define INSET_X PBL_IF_ROUND_ELSE(24, 4)
#define PAD_TOP PBL_IF_ROUND_ELSE(32, 2)
#define PAD_BOTTOM PBL_IF_ROUND_ELSE(34, 10)
#define MEASURE_H 30000

static bool has_mic(void) {
#if defined(PBL_MICROPHONE)
  return true;
#else
  return false;
#endif
}

static const char *body_for_state(void) {
  switch (s_state) {
    case ST_IDLE:
      return has_mic() ? "Select: ask\nHold Select: new chat\nHold Up: presets"
                       : "Select: presets\nHold Select: new chat";
    case ST_THINKING:
      return s_status_text;
    case ST_STREAMING:
    case ST_ANSWER:
      return s_answer;
    case ST_ERROR: {
      bool retry = s_error_code == ERR_LOCAL || (s_conv != 0 && s_error_code != ERR_BAD_REQUEST);
      snprintf(s_body_text, sizeof(s_body_text), "%s\n\n%s", s_error_text,
               retry ? "Select: retry\nHold Select: new chat" : "Select: ask");
      return s_body_text;
    }
  }
  return "";
}

static bool speaking(void) { return s_speak_wait || stoandl_speaker_active(); }

static const char *header_for_state(void) {
  if (s_note) {
    return s_note;
  }
  if (s_state == ST_IDLE) {
    return "Ask Claude";
  }
  if (s_state == ST_ANSWER && speaking()) {
    return "Speaking...";
  }
  return s_question[0] ? s_question : "Claude";
}

// TextLayer's own content size comes from its layout cache, which drawing refreshes with the visible
// part only, so a re-measure of the same text can return a stale, shorter height (the end of a long
// answer then can't be scrolled to). Measure without a cache instead.
static int16_t measure_text(const char *text, GFont font, int16_t w) {
  return graphics_text_layout_get_content_size(text, font, GRect(0, 0, w, MEASURE_H), GTextOverflowModeWordWrap,
                                               GTextAlignmentLeft).h + 4;
}

static void relayout(void) {
  if (!s_scroll) {
    return;
  }
  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));
  int16_t w = bounds.size.w - 2 * INSET_X;

  text_layer_set_text(s_header, header_for_state());
  text_layer_set_font(s_header, header_font());
  int16_t header_h = measure_text(header_for_state(), header_font(), w);
  layer_set_frame(text_layer_get_layer(s_header), GRect(INSET_X, PAD_TOP, w, header_h));

  int16_t body_y = PAD_TOP + header_h;
  text_layer_set_text(s_body, body_for_state());
  text_layer_set_font(s_body, body_font());
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_body, s_state == ST_THINKING ? GColorDarkGray : GColorBlack);
#endif
  int16_t body_h = measure_text(body_for_state(), body_font(), w);
  layer_set_frame(text_layer_get_layer(s_body), GRect(INSET_X, body_y, w, body_h));

  scroll_layer_set_content_size(s_scroll, GSize(bounds.size.w, body_y + body_h + PAD_BOTTOM));
}

static void scroll_to_top(void) {
  if (s_scroll) {
    scroll_layer_set_content_offset(s_scroll, GPointZero, false);
  }
}

static void set_state(UiState state) {
  s_state = state;
  relayout();
}

static void clear_answer(void) {
  s_answer[0] = '\0';
  s_answer_len = 0;
  s_answer_full = false;
  s_expected_seq = 0;
}

// Keeps room for a closing "..." so an overflowing answer always ends with it.
static void append_answer(const char *text, size_t len) {
  static const char ellipsis[] = "...";
  const size_t text_cap = ANSWER_MAX - sizeof(ellipsis);
  if (s_answer_full) {
    return;
  }
  size_t room = text_cap - s_answer_len;
  size_t n = utf8_cut(text, len, room);
  memcpy(s_answer + s_answer_len, text, n);
  s_answer_len += n;
  if (n < len) {
    memcpy(s_answer + s_answer_len, ellipsis, sizeof(ellipsis) - 1);
    s_answer_len += sizeof(ellipsis) - 1;
    s_answer_full = true;
  }
  s_answer[s_answer_len] = '\0';
}

static void append_answer_str(const char *text) { append_answer(text, strlen(text)); }

// ---------------------------------------------------------------------------------------------
// Outgoing messages: one in flight; the next leaves on outbox_sent.

static void show_local_error(const char *text);

static void send_head(void);

// Read fresh for every HELLO, ASK and PRESET: the speaker can be muted, and Quiet Time can begin, while
// the app is open. MUTED keeps the extension from reading answers aloud unasked; Hold Down still plays.
static uint8_t caps(void) {
  uint8_t c = PBL_IF_COLOR_ELSE(CAP_COLOR, 0) | PBL_IF_ROUND_ELSE(CAP_ROUND, 0);
#if defined(PBL_MICROPHONE)
  if (s_dictation) {
    c |= CAP_MIC;
  }
#endif
#if defined(PBL_SPEAKER)
  c |= CAP_SPEAKER;
  if (!stoandl_speaker_usable() || quiet_time_is_active()) {
    c |= CAP_MUTED;
  }
#endif
  return c;
}

static void retry_cb(void *ctx) {
  s_retry_timer = NULL;
  send_head();
}

// C5: retry once after 500 ms, then give up and keep the message for a manual retry.
static void send_failed(void) {
  s_out_busy = false;
  if (s_outq_count == 0) {
    return;
  }
  if (!s_out_retried) {
    s_out_retried = true;
    s_retry_timer = app_timer_register(SEND_RETRY_MS, retry_cb, NULL);
    return;
  }
  s_out_retried = false;
  s_failed = s_outq[s_outq_head];
  s_outq_count = 0;
  show_local_error("Phone not reachable");
}

static void send_head(void) {
  if (s_out_busy || s_outq_count == 0 || s_retry_timer) {
    return;
  }
  OutMsg *m = &s_outq[s_outq_head];
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    send_failed();
    return;
  }
  dict_write_uint8(iter, KEY_CMD, m->cmd);
  switch (m->cmd) {
    case CMD_HELLO:
      dict_write_uint8(iter, KEY_PROTO, PROTO_VERSION);
      dict_write_uint16(iter, KEY_INBOX, s_inbox);
      dict_write_uint8(iter, KEY_CAPS, caps());
      if (stoandl_speaker_ring_bytes()) {
        dict_write_uint16(iter, KEY_SPK_DATA, stoandl_speaker_data_max(s_inbox));
        dict_write_uint16(iter, KEY_SPK_RING, stoandl_speaker_ring_bytes());
      }
      break;
    case CMD_ASK:
      dict_write_cstring(iter, KEY_TEXT, m->text);
      dict_write_uint32(iter, KEY_CONV, m->conv);
      dict_write_uint8(iter, KEY_TURN, m->turn);
      dict_write_uint8(iter, KEY_CAPS, caps());
      break;
    case CMD_PRESET:
      dict_write_uint8(iter, KEY_INDEX, m->index);
      dict_write_uint32(iter, KEY_CONV, m->conv);
      dict_write_uint8(iter, KEY_TURN, m->turn);
      dict_write_uint8(iter, KEY_CAPS, caps());
      break;
    case CMD_CANCEL:
    case CMD_SPEAK:
      dict_write_uint32(iter, KEY_CONV, m->conv);
      dict_write_uint8(iter, KEY_TURN, m->turn);
      break;
    default:
      break;
  }
  if (app_message_outbox_send() == APP_MSG_OK) {
    s_out_busy = true;
  } else {
    send_failed();
  }
}

static OutMsg *enqueue(uint8_t cmd) {
  if (s_outq_count == OUTQ_LEN) {
    return NULL;
  }
  OutMsg *m = &s_outq[(s_outq_head + s_outq_count) % OUTQ_LEN];
  memset(m, 0, sizeof(*m));
  m->cmd = cmd;
  s_outq_count++;
  return m;
}

static void outbox_sent(DictionaryIterator *iter, void *ctx) {
  s_out_busy = false;
  s_out_retried = false;
  if (s_outq_count > 0) {
    s_outq_head = (s_outq_head + 1) % OUTQ_LEN;
    s_outq_count--;
  }
  send_head();
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed: %d", (int)reason);
  send_failed();
}

static void begin_request(const char *question, size_t len) {
  // A new question stops the answer being read aloud; the extension stops sending it on ASK/PRESET.
  s_speak_wait = false;
  stoandl_speaker_stop();
  copy_cut(s_question, sizeof(s_question), question, len);
  clear_answer();
  s_awaiting_status = true;
  s_cancel_pending = false;
  s_status_text = "Thinking...";
  s_request_start_ms = now_ms();
  set_state(ST_THINKING);
  scroll_to_top();
}

static void send_ask(const char *text, uint32_t conv, uint8_t turn) {
  OutMsg *m = enqueue(CMD_ASK);
  if (!m) {
    show_local_error("Busy, try again");
    return;
  }
  size_t len = strlen(text);
  copy_cut(m->text, sizeof(m->text), text, len);
  m->conv = conv;
  m->turn = turn;
  begin_request(text, len);
  send_head();
}

static void send_preset(uint8_t index, uint32_t conv, uint8_t turn) {
  OutMsg *m = enqueue(CMD_PRESET);
  if (!m) {
    show_local_error("Busy, try again");
    return;
  }
  m->index = index;
  m->conv = conv;
  m->turn = turn;
  const char *q = index < s_preset_count ? s_presets[index] : "";
  begin_request(q, strlen(q));
  send_head();
}

static void send_simple(uint8_t cmd, uint32_t conv, uint8_t turn) {
  OutMsg *m = enqueue(cmd);
  if (m) {
    m->conv = conv;
    m->turn = turn;
    send_head();
  }
}

static void send_hello(void) {
  if (enqueue(CMD_HELLO)) {
    send_head();
  }
}

// ---------------------------------------------------------------------------------------------
// Reading aloud (stoandl_speaker.c plays what the extension streams)

// Stops what is being read, or was asked for, so the microphone won't hear it, and tells the extension.
// With an answer on screen the extension may still be synthesizing an unasked reading that hasn't
// reached the watch yet, so it is told even when nothing plays.
static void stop_speech(void) {
#if defined(PBL_SPEAKER)
  if (!speaking() && s_state != ST_ANSWER) {
    return;
  }
  s_speak_wait = false;
  stoandl_speaker_stop();
  send_simple(CMD_STOP_SPEAK, 0, 0);
  relayout();
#endif
}

static void speaker_changed(bool active, void *ctx) {
  s_speak_wait = false;  // the stream took over, or there will be none
  relayout();
}

#if defined(PBL_SPEAKER)
static void note_done(void *ctx) {
  s_note_timer = NULL;
  s_note = NULL;
  relayout();
}

static void show_note(const char *text) {
  s_note = text;
  if (s_note_timer) {
    app_timer_reschedule(s_note_timer, NOTE_MS);
  } else {
    s_note_timer = app_timer_register(NOTE_MS, note_done, NULL);
  }
  relayout();
}
#endif

static void show_local_error(const char *text) {
  s_awaiting_status = false;
  s_error_code = ERR_LOCAL;
  copy_cut(s_error_text, sizeof(s_error_text), text, strlen(text));
  set_state(ST_ERROR);
  scroll_to_top();
}

// ---------------------------------------------------------------------------------------------
// Asking: dictation where there is a microphone, the presets menu otherwise.

static void open_presets(uint32_t conv, uint8_t turn);

#if defined(PBL_MICROPHONE)
static void dictation_cb(DictationSession *session, DictationSessionStatus status, char *text,
                         void *ctx) {
  stoandl_speaker_suppress(false);
  if (status == DictationSessionStatusSuccess && text && text[0]) {
    send_ask(text, s_ask_conv, s_ask_turn);
  } else if (status == DictationSessionStatusFailureDisabled) {
    open_presets(s_ask_conv, s_ask_turn);
  }
}

static bool ensure_dictation(void) {
  if (!s_dictation) {
    s_dictation = dictation_session_create(DICTATION_BUF, dictation_cb, NULL);
    if (s_dictation) {
      dictation_session_enable_confirmation(s_dictation, false);
      dictation_session_enable_error_dialogs(s_dictation, true);
    }
  }
  return s_dictation != NULL;
}
#endif

static void start_ask(uint32_t conv, uint8_t turn) {
  s_ask_conv = conv;
  s_ask_turn = turn;
#if defined(PBL_MICROPHONE)
  // The voice window opens in the app's own window stack, so the app keeps focus: hold the speaker off
  // by hand until the dictation callback, or the microphone hears an answer that arrives meanwhile.
  stoandl_speaker_suppress(true);
  if (ensure_dictation() && dictation_session_start(s_dictation) == DictationSessionStatusSuccess) {
    return;
  }
  stoandl_speaker_suppress(false);
#endif
  open_presets(conv, turn);
}

// Follow-ups name the answer on screen; anything else lets the extension's idle rule pick.
static void context_of_screen(uint32_t *conv, uint8_t *turn) {
  if (s_state == ST_ANSWER) {
    *conv = s_conv;
    *turn = s_turn;
  } else {
    *conv = 0;
    *turn = 0;
  }
}

static void new_conversation(void) {
  if ((s_state == ST_THINKING || s_state == ST_STREAMING) && !s_awaiting_status) {
    send_simple(CMD_CANCEL, s_conv, s_turn);
  }
  send_simple(CMD_NEW_CONV, 0, 0);
  s_conv = 0;
  s_turn = 0;
  s_awaiting_status = false;
  s_question[0] = '\0';
  clear_answer();
  set_state(ST_IDLE);
  scroll_to_top();
}

static void retry_failed_send(void) {
  OutMsg m = s_failed;
  if (m.cmd == CMD_ASK) {
    send_ask(m.text, m.conv, m.turn);
    return;
  }
  if (m.cmd == CMD_PRESET) {
    send_preset(m.index, m.conv, m.turn);
    return;
  }
  set_state(ST_IDLE);
  if (m.cmd == CMD_HELLO) {
    send_hello();
  } else if (m.cmd != 0) {
    send_simple(m.cmd, m.conv, m.turn);
  }
}

// ---------------------------------------------------------------------------------------------
// Presets menu

static const char *preset_text(uint16_t row) {
  return s_preset_count == 0 ? "Loading..." : s_presets[row];
}

static GFont menu_font(void) {
  return fonts_get_system_font(big_screen() ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD);
}

#define MENU_INSET PBL_IF_ROUND_ELSE(14, 6)

static uint16_t menu_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return s_preset_count == 0 ? 1 : s_preset_count;
}

static int16_t menu_cell_height(MenuLayer *menu, MenuIndex *index, void *ctx) {
  GRect b = layer_get_bounds(menu_layer_get_layer(menu));
  GSize s = graphics_text_layout_get_content_size(
      preset_text(index->row), menu_font(), GRect(0, 0, b.size.w - 2 * MENU_INSET, 200),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft);
  int16_t h = s.h + 12;
  int16_t max_h = big_screen() ? 96 : 72;
  return h < 44 ? 44 : (h > max_h ? max_h : h);
}

static void menu_draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *data) {
  GRect b = layer_get_bounds(cell);
  graphics_draw_text(ctx, preset_text(index->row), menu_font(),
                     GRect(MENU_INSET, 2, b.size.w - 2 * MENU_INSET, b.size.h - 4),
                     GTextOverflowModeTrailingEllipsis,
                     PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
}

static void menu_select(MenuLayer *menu, MenuIndex *index, void *ctx) {
  if (s_preset_count == 0) {
    return;
  }
  uint8_t row = index->row;
  window_stack_remove(s_menu_window, true);
  send_preset(row, s_ask_conv, s_ask_turn);
}

static void menu_window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
                                             .get_num_rows = menu_rows,
                                             .get_cell_height = menu_cell_height,
                                             .draw_row = menu_draw_row,
                                             .select_click = menu_select,
                                         });
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(s_menu, GColorOrange, GColorWhite);
#endif
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void menu_window_unload(Window *w) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

static void open_presets(uint32_t conv, uint8_t turn) {
  s_ask_conv = conv;
  s_ask_turn = turn;
  if (!window_stack_contains_window(s_menu_window)) {
    window_stack_push(s_menu_window, true);
  }
}

static void store_presets(const char *text, size_t len) {
  copy_cut(s_presets_buf, sizeof(s_presets_buf), text, len);
  s_preset_count = 0;
  char *p = s_presets_buf;
  while (*p && s_preset_count < MAX_PRESETS) {
    char *nl = strchr(p, '\n');
    if (nl) {
      *nl = '\0';
    }
    if (*p) {
      s_presets[s_preset_count++] = p;
    }
    if (!nl) {
      break;
    }
    p = nl + 1;
  }
  if (s_menu) {
    menu_layer_reload_data(s_menu);
  }
}

// ---------------------------------------------------------------------------------------------
// Incoming messages

static bool is_active(uint32_t conv, uint8_t turn) { return conv == s_conv && turn == s_turn; }

static bool accepts_stream(uint32_t conv, uint8_t turn) {
  if (s_awaiting_status || !is_active(conv, turn)) {
    return false;
  }
  return s_state == ST_IDLE || s_state == ST_THINKING || s_state == ST_STREAMING;
}

static void handle_status(DictionaryIterator *iter) {
  uint8_t code = read_uint(iter, KEY_CODE);
  if (code == STATUS_IDLE && s_awaiting_status) {
    return;  // the HELLO reply racing our ASK; the ASK's own STATUS follows
  }
  bool was_awaiting = s_awaiting_status;
  s_conv = read_uint(iter, KEY_CONV);
  s_turn = read_uint(iter, KEY_TURN);
  s_awaiting_status = false;
  clear_answer();
  size_t len;
  const char *text = read_cstr(iter, KEY_TEXT, &len);
  copy_cut(s_question, sizeof(s_question), text, len);
  scroll_to_top();

  if (code == STATUS_IDLE) {
    set_state(ST_IDLE);
    return;
  }
  if (!was_awaiting) {
    s_request_start_ms = now_ms();  // re-stream after HELLO: count the wait from here
  }
  s_status_text = code == STATUS_SEARCHING ? "Searching..." : "Thinking...";
  if (s_cancel_pending) {
    s_cancel_pending = false;
    s_status_text = "Cancelling...";
    send_simple(CMD_CANCEL, s_conv, s_turn);
  }
  set_state(ST_THINKING);
}

static void handle_chunk(DictionaryIterator *iter) {
  if (!accepts_stream(read_uint(iter, KEY_CONV), read_uint(iter, KEY_TURN))) {
    return;
  }
  uint16_t seq = read_uint(iter, KEY_SEQ);
  if (seq < s_expected_seq) {
    return;  // a retry whose ACK was lost
  }
  if (seq > s_expected_seq) {
    append_answer_str("[...]");
  }
  size_t len;
  const char *text = read_cstr(iter, KEY_TEXT, &len);
  append_answer(text, len);
  s_expected_seq = seq + 1;
  set_state(ST_STREAMING);
}

static void handle_done(DictionaryIterator *iter) {
  if (!accepts_stream(read_uint(iter, KEY_CONV), read_uint(iter, KEY_TURN))) {
    return;
  }
  uint16_t sent = read_uint(iter, KEY_SEQ);
  uint8_t flags = read_uint(iter, KEY_FLAGS);
  if (sent > s_expected_seq) {
    append_answer_str(" [...]");
  }
  if (flags & FLAG_TRUNCATED) {
    append_answer_str("...");
  }
  set_state(ST_ANSWER);
  if (!(flags & FLAG_REPLAY) && now_ms() - s_request_start_ms >= QUIET_DONE_MS) {
    vibes_short_pulse();
  }
}

static const char *error_fallback(uint8_t code) {
  switch (code) {
    case 1: return "Cancelled";
    case 2: return "no API key";
    case 3: return "key rejected";
    case 4: return "busy";
    case 5: return "no answer";
    case 6: return "Claude declined";
    case 7: return "error";
    case 8: return "Couldn't ask";
    default: return "Something went wrong";
  }
}

static void handle_error(DictionaryIterator *iter) {
  uint8_t code = read_uint(iter, KEY_CODE);
  uint32_t conv = read_uint(iter, KEY_CONV);
  uint8_t turn = read_uint(iter, KEY_TURN);
  if (s_awaiting_status) {
    if (code == ERR_CANCELLED) {
      return;  // the request our new question replaced
    }
    s_awaiting_status = false;
    s_cancel_pending = false;
    s_conv = conv;
    s_turn = turn;
  } else if (!accepts_stream(conv, turn)) {
    return;  // includes a late Cancelled for an answer that already finished
  }
  size_t len;
  const char *text = read_cstr(iter, KEY_TEXT, &len);
  if (len == 0) {
    text = error_fallback(code);
    len = strlen(text);
  }
  copy_cut(s_error_text, sizeof(s_error_text), text, len);
  s_error_code = code;
  set_state(ST_ERROR);
  scroll_to_top();
}

static void inbox_received(DictionaryIterator *iter, void *ctx) {
  if (stoandl_speaker_handle(iter)) {
    return;
  }
  switch (read_uint(iter, KEY_CMD)) {
    case CMD_PRESETS: {
      size_t len;
      const char *text = read_cstr(iter, KEY_TEXT, &len);
      store_presets(text, len);
      break;
    }
    case CMD_STATUS:
      handle_status(iter);
      break;
    case CMD_CHUNK:
      handle_chunk(iter);
      break;
    case CMD_DONE:
      handle_done(iter);
      break;
    case CMD_ERROR:
      handle_error(iter);
      break;
    default:
      break;
  }
}

static void inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

// ---------------------------------------------------------------------------------------------
// Main window

static void select_click(ClickRecognizerRef ref, void *ctx) {
  stop_speech();
  switch (s_state) {
    case ST_IDLE:
      start_ask(0, 0);
      break;
    case ST_THINKING:
    case ST_STREAMING:
      if (s_awaiting_status) {
        s_cancel_pending = true;
      } else {
        send_simple(CMD_CANCEL, s_conv, s_turn);
      }
      if (s_state == ST_THINKING) {
        s_status_text = "Cancelling...";
        relayout();
      }
      break;
    case ST_ANSWER:
      start_ask(s_conv, s_turn);
      break;
    case ST_ERROR:
      if (s_error_code == ERR_LOCAL) {
        retry_failed_send();
      } else if (s_conv != 0 && s_error_code != ERR_BAD_REQUEST) {
        send_ask("Retry", s_conv, s_turn);
      } else {
        start_ask(0, 0);
      }
      break;
  }
}

static void select_long(ClickRecognizerRef ref, void *ctx) {
  stop_speech();
  new_conversation();
  start_ask(0, 0);
}

static void up_click(ClickRecognizerRef ref, void *ctx) {
  scroll_layer_scroll_up_click_handler(ref, s_scroll);
}

static void down_click(ClickRecognizerRef ref, void *ctx) {
  scroll_layer_scroll_down_click_handler(ref, s_scroll);
}

#if defined(PBL_SPEAKER)
// Hold Down: read the answer on screen aloud, or stop reading.
static void down_long(ClickRecognizerRef ref, void *ctx) {
  if (speaking()) {
    stop_speech();
    return;
  }
  if (s_state != ST_ANSWER) {
    return;
  }
  if (!stoandl_speaker_usable()) {
    show_note("Speaker muted");
    return;
  }
  s_speak_wait = true;
  send_simple(CMD_SPEAK, s_conv, s_turn);
  relayout();
}
#endif

static void up_long(ClickRecognizerRef ref, void *ctx) {
  uint32_t conv;
  uint8_t turn;
  context_of_screen(&conv, &turn);
  open_presets(conv, turn);
}

static void click_config(void *ctx) {
  // Up (and Down with a speaker) is not repeating: a repeating click and a long click can't share a button.
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_long_click_subscribe(BUTTON_ID_UP, LONG_PRESS_MS, up_long, NULL);
#if defined(PBL_SPEAKER)
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click);
  window_long_click_subscribe(BUTTON_ID_DOWN, LONG_PRESS_MS, down_long, NULL);
#else
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 100, down_click);
#endif
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, LONG_PRESS_MS, select_long, NULL);
}

static void configure_indicator(ContentIndicator *ind, ContentIndicatorDirection dir, Layer *layer) {
  ContentIndicatorConfig cfg = {
      .layer = layer,
      .times_out = false,
      .alignment = GAlignCenter,
      .colors = {.foreground = GColorBlack, .background = GColorWhite},
  };
  content_indicator_configure_direction(ind, dir, &cfg);
}

static void win_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);

  s_scroll = scroll_layer_create(b);
  scroll_layer_set_shadow_hidden(s_scroll, true);
  layer_add_child(root, scroll_layer_get_layer(s_scroll));

  s_header = text_layer_create(GRect(INSET_X, PAD_TOP, b.size.w - 2 * INSET_X, 20));
  text_layer_set_background_color(s_header, GColorClear);
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_header));

  s_body = text_layer_create(GRect(INSET_X, PAD_TOP + 20, b.size.w - 2 * INSET_X, 20));
  text_layer_set_background_color(s_body, GColorClear);
  text_layer_set_overflow_mode(s_body, GTextOverflowModeWordWrap);
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_body));

  const int16_t ind_h = PBL_IF_ROUND_ELSE(18, 14);
  s_ind_up = layer_create(GRect(0, 0, b.size.w, ind_h));
  s_ind_down = layer_create(GRect(0, b.size.h - ind_h, b.size.w, ind_h));
  layer_add_child(root, s_ind_up);
  layer_add_child(root, s_ind_down);
  ContentIndicator *ind = scroll_layer_get_content_indicator(s_scroll);
  if (ind) {
    configure_indicator(ind, ContentIndicatorDirectionUp, s_ind_up);
    configure_indicator(ind, ContentIndicatorDirectionDown, s_ind_down);
  }

  relayout();
}

static void win_unload(Window *w) {
  layer_destroy(s_ind_down);
  layer_destroy(s_ind_up);
  text_layer_destroy(s_body);
  text_layer_destroy(s_header);
  scroll_layer_destroy(s_scroll);
  s_scroll = NULL;
}

static void launch_ask(void *ctx) { start_ask(0, 0); }

static void init(void) {
  // Ask for the faster link for as long as the app is open. Idle, the watch lets its link skip 30 of every
  // 31 connection events (fw 4.41: 45 ms interval, latency 30, so it listens every ~1.4 s), and the switch
  // to the fast link a dictation asks for only lands ~4.5 s later. Until then the firmware's 4-packet PPoG
  // window fills, the watch sleeps through the phone's acks and drops dictation audio (up to 2.5 s of
  // it, op6 + Time 2, 2026-10-20). Held from launch, the link is already faster when a later question
  // starts; the first dictation, 100 ms after launch, mostly still meets the slow one.
  app_comm_set_sniff_interval(SNIFF_INTERVAL_REDUCED);
  stoandl_speaker_init(speaker_changed, NULL);
  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  // The firmware gives at most app_message_inbox_size_maximum() (8 KiB with stoandl, which advertises 8 KiB
  // AppMessages); the extension learns the size in HELLO.
  uint32_t inbox_max = app_message_inbox_size_maximum();
  s_inbox = INBOX_WANT < inbox_max ? INBOX_WANT : inbox_max;
  app_message_open(s_inbox, OUTBOX_SIZE);
  APP_LOG(APP_LOG_LEVEL_INFO, "inbox %u, heap free %u", (unsigned)s_inbox, (unsigned)heap_bytes_free());

#if defined(PBL_MICROPHONE)
  ensure_dictation();
#endif

  s_window = window_create();
  window_set_background_color(s_window, GColorWhite);
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(s_window, (WindowHandlers){.load = win_load, .unload = win_unload});
  window_stack_push(s_window, false);

  s_menu_window = window_create();
  window_set_window_handlers(s_menu_window, (WindowHandlers){
                                                .load = menu_window_load,
                                                .unload = menu_window_unload,
                                            });

  send_hello();
  app_timer_register(100, launch_ask, NULL);
}

static void deinit(void) {
  stoandl_speaker_deinit();  // Back: the speech stops with the app; BYE stops the extension's stream
  if (s_retry_timer) {
    app_timer_cancel(s_retry_timer);
  }
  if (s_note_timer) {
    app_timer_cancel(s_note_timer);
  }
  DictionaryIterator *iter;
  if (!s_out_busy && app_message_outbox_begin(&iter) == APP_MSG_OK) {
    dict_write_uint8(iter, KEY_CMD, CMD_BYE);
    app_message_outbox_send();
  }
#if defined(PBL_MICROPHONE)
  if (s_dictation) {
    dictation_session_destroy(s_dictation);
  }
#endif
  window_destroy(s_menu_window);
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
