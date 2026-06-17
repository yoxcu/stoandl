#include <pebble.h>

// "Find My Phone" watchapp for stoandl's extension system. UP rings the host computer, DOWN stops it.
// Unlike a notification (whose action menu the firmware only offers while it's on-screen), a watchapp
// can be opened any time, so its buttons always work — which is why find-my-phone is a watchapp.
//
// It just sends an AppMessage to the phone: a single uint8 at key 0 (CMD_RING / CMD_STOP). stoandl's
// findphone extension (examples/extensions/findphone.py) registers this app's UUID, ACKs the message,
// and plays/stops a sound on the host. Key 0 is a raw AppMessage key (not a named messageKey), which
// is exactly what the companion reads back: {0: 1} or {0: 2}.

#define KEY_CMD 0
#define CMD_RING 1
#define CMD_STOP 2

static Window *s_window;
static TextLayer *s_label;

static const char *HELP = "UP = Ring phone\nDOWN = Stop";

static void send_cmd(uint8_t cmd) {
  DictionaryIterator *iter;
  AppMessageResult r = app_message_outbox_begin(&iter);
  if (r != APP_MSG_OK) {
    text_layer_set_text(s_label, "Busy — try again");
    return;
  }
  dict_write_uint8(iter, KEY_CMD, cmd);
  app_message_outbox_send();
}

static void up_click(ClickRecognizerRef ref, void *ctx) {
  send_cmd(CMD_RING);
  text_layer_set_text(s_label, "Ringing your phone…\n\nUP = Ring\nDOWN = Stop");
}

static void down_click(ClickRecognizerRef ref, void *ctx) {
  send_cmd(CMD_STOP);
  text_layer_set_text(s_label, "Stopped.\n\nUP = Ring\nDOWN = Stop");
}

static void click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click);
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed: %d", (int)reason);
  text_layer_set_text(s_label, "Send failed\n(phone unreachable?)");
}

static void win_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_label = text_layer_create(GRect(0, 24, b.size.w, b.size.h - 24));
  text_layer_set_text_alignment(s_label, GTextAlignmentCenter);
  text_layer_set_text(s_label, HELP);
  layer_add_child(root, text_layer_get_layer(s_label));
}

static void win_unload(Window *w) { text_layer_destroy(s_label); }

static void init(void) {
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(64, 64);  // small in/out buffers; we only send one tiny tuple
  s_window = window_create();
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(
      s_window, (WindowHandlers){.load = win_load, .unload = win_unload});
  window_stack_push(s_window, true);
}

static void deinit(void) {
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
