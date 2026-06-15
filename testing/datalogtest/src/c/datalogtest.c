#include <pebble.h>

// Minimal PebbleKit DataLogging test app for exercising `stoandl` datalog
// capture (TESTING.md §5.8). Logs an incrementing 4-byte little-endian uint
// every few seconds to tag 42, shows a live counter, and forces a flush to the
// phone on SELECT (data logging otherwise spools and syncs lazily).

#define LOG_TAG 42
#define INTERVAL_MS 3000

static Window *s_window;
static TextLayer *s_label;
static DataLoggingSessionRef s_session;
static AppTimer *s_timer;
static uint32_t s_value = 0;
static char s_buf[96];

static void tick(void *ctx) {
  uint32_t v = ++s_value;
  DataLoggingResult r =
      data_logging_log(s_session, &v, 1); // 1 item of sizeof(uint32_t) bytes
  snprintf(s_buf, sizeof(s_buf), "logged: %lu\nresult: %d\n\nSELECT = flush",
           (unsigned long)v, (int)r);
  text_layer_set_text(s_label, s_buf);
  APP_LOG(APP_LOG_LEVEL_INFO, "logged %lu (result=%d)", (unsigned long)v,
          (int)r);
  s_timer = app_timer_register(INTERVAL_MS, tick, NULL);
}

static void select_click(ClickRecognizerRef ref, void *ctx) {
  // Close the session to flush its buffered items to the phone now, then open a
  // fresh one.
  data_logging_finish(s_session);
  s_session =
      data_logging_create(LOG_TAG, DATA_LOGGING_UINT, sizeof(uint32_t), false);
  text_layer_set_text(s_label, "flushed — run\nstoandl datalog list");
  APP_LOG(APP_LOG_LEVEL_INFO, "flushed datalog session");
}

static void click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
}

static void win_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_label = text_layer_create(GRect(0, 30, b.size.w, b.size.h - 30));
  text_layer_set_text_alignment(s_label, GTextAlignmentCenter);
  text_layer_set_text(s_label, "starting...");
  layer_add_child(root, text_layer_get_layer(s_label));
}

static void win_unload(Window *w) { text_layer_destroy(s_label); }

static void init(void) {
  s_session =
      data_logging_create(LOG_TAG, DATA_LOGGING_UINT, sizeof(uint32_t), false);
  s_window = window_create();
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(
      s_window, (WindowHandlers){.load = win_load, .unload = win_unload});
  window_stack_push(s_window, true);
  s_timer = app_timer_register(INTERVAL_MS, tick, NULL);
}

static void deinit(void) {
  if (s_timer)
    app_timer_cancel(s_timer);
  data_logging_finish(s_session); // backing out of the app also flushes
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
