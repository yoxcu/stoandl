"""
stoandl extension helper (Python) — a thin wrapper over the stdio JSON-RPC protocol stoandl speaks to
its extensions. Copy this next to your extension and import it; see findphone.py for a worked example
and ../../docs/extensions.md for the full contract.

An extension is a child process stoandl spawns. It reads requests/notifications on stdin and writes on
stdout (one JSON object per line). NEVER print to stdout yourself — use ext.log() (which writes to
stderr, folded into stoandl's log). stdout is the wire; a stray print corrupts it.
"""
import sys
import json
import uuid
import itertools
import threading

# Namespace for turning a human-friendly replace_id string into a stable UUID.
_NS = uuid.uuid5(uuid.NAMESPACE_URL, "stoandl-ext")


# --- AppMessage value tags (for send_app_message). JSON has one number type but the watchapp's
# --- dict_read_uint8 / dict_read_int distinguish width, so each value carries its Pebble type. ---
def u8(v):    return {"t": "uint8", "v": int(v)}
def u16(v):   return {"t": "uint16", "v": int(v)}
def u32(v):   return {"t": "uint", "v": int(v)}
def i8(v):    return {"t": "int8", "v": int(v)}
def i16(v):   return {"t": "int16", "v": int(v)}
def i32(v):   return {"t": "int", "v": int(v)}
def cstr(v):  return {"t": "cstring", "v": str(v)}
def blob(b):  import base64; return {"t": "bytes", "v": base64.b64encode(bytes(b)).decode()}


class Extension:
    def __init__(self):
        self._ids = itertools.count(1)
        self._out_lock = threading.Lock()
        self._pending = {}            # request id -> callback(result_dict)
        # Populated from the initialize handshake:
        self.config = {}             # your extension.<name>.<key> settings
        self.granted = []            # capabilities the user granted (e.g. ["notify"])
        self.watch = {}              # {"connected": bool, ...}
        # Optional handlers — assign before calling run():
        self.on_initialize = None     # ()                  -> called once, after the handshake
        self.on_watch_connected = None  # ()                -> a watch (re)connected; re-arm here
        self.on_action = None         # (item_id, action)   -> a named action was chosen
        self.on_reply = None          # (item_id, text)     -> a (canned/dictated) reply was sent
        self.on_dismiss = None        # (item_id)           -> the notification was dismissed
        self.on_app_message = None    # (uuid, txn, data)   -> a watchapp sent an AppMessage; data is
                                      #                        {int_key: value} (already untagged)

    # ---- outgoing -------------------------------------------------------------------------------

    def _send(self, obj):
        with self._out_lock:
            sys.stdout.write(json.dumps(obj) + "\n")
            sys.stdout.flush()

    def log(self, message):
        sys.stderr.write(str(message) + "\n")
        sys.stderr.flush()

    def notify(self, title, body="", app_name=None, subtitle=None, actions=None,
               canned_replies=None, allow_voice=False, ext_token=None,
               icon=None, color=None, vibe=None, on_item=None, replace_id=None):
        """Push a notification to the watch.

        actions: list of (action_id, label) tuples — each becomes a menu item; the chosen one comes
                 back via on_action(item_id, action_id).
        canned_replies: list of strings — adds a Reply menu; the chosen/dictated text comes back via
                 on_reply(item_id, text).
        on_item: optional callback(item_id) invoked with the watch item id once the watch accepts it
                 (None if the app was muted or no watch is connected). Use it to correlate replies.
        replace_id: an optional stable string. Re-sending with the same replace_id REPLACES the same
                 watch notification (across daemon restarts too) instead of piling up duplicates — use
                 it for a persistent/standing notification like find-my-phone.
        """
        rid = next(self._ids)
        params = {"title": title, "body": body}
        if replace_id:
            params["itemId"] = str(uuid.uuid5(_NS, replace_id))
        if app_name:
            params["appName"] = app_name
        if subtitle:
            params["subtitle"] = subtitle
        if ext_token:
            params["extToken"] = ext_token
        if icon:
            params["iconCode"] = icon
        if color:
            params["color"] = color
        if vibe:
            params["vibe"] = vibe
        if actions:
            params["actions"] = [{"id": a[0], "label": a[1]} for a in actions]
        if canned_replies:
            params["reply"] = {"cannedReplies": list(canned_replies), "allowVoice": allow_voice}
        if on_item:
            self._pending[rid] = lambda res: on_item(res.get("itemId"))
        self._send({"jsonrpc": "2.0", "id": rid, "method": "notify", "params": params})

    def close(self, item_id):
        """Clear a notification from the watch (e.g. the message was read elsewhere)."""
        self._send({"jsonrpc": "2.0", "method": "closeNotification", "params": {"itemId": item_id}})

    # ---- watchapp companion (needs an `appmessage:<uuid>` grant) ---------------------------------

    def _request(self, method, params, on_result=None):
        rid = next(self._ids)
        if on_result:
            self._pending[rid] = on_result
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})

    def register_app(self, app_uuid):
        """Arm inbound AppMessages from the watchapp `app_uuid` — they arrive via on_app_message."""
        self._request("registerApp", {"uuid": app_uuid})

    def send_app_message(self, app_uuid, data, on_result=None):
        """Send an AppMessage to the watchapp. `data` is {int_key: u8(...)/i32(...)/cstr(...)/...}.
        on_result(str) receives "ack" / "nack" / "timeout" / "error"."""
        self._request("sendAppMessage", {"uuid": app_uuid, "data": {str(k): v for k, v in data.items()}},
                      (lambda res: on_result(res.get("result"))) if on_result else None)

    def launch_app(self, app_uuid):
        self._request("launchApp", {"uuid": app_uuid})

    def stop_app(self, app_uuid):
        self._request("stopApp", {"uuid": app_uuid})

    def install_pbw(self, path, on_result=None):
        """Sideload a .pbw onto the watch. on_result(bool) receives success."""
        self._request("installPbw", {"path": path},
                      (lambda res: on_result(res.get("ok"))) if on_result else None)

    # ---- run loop -------------------------------------------------------------------------------

    def run(self, name, capabilities=("notify",), egress=(), canned_reply_defaults=()):
        """Block reading stdin and dispatching to the on_* handlers until stoandl closes the pipe."""
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                self.log("ignoring non-JSON line: %r" % line[:200])
                continue
            method = msg.get("method")
            if method == "initialize":
                p = msg.get("params", {})
                self.config = p.get("config", {})
                self.granted = p.get("granted", [])
                self.watch = p.get("watch", {})
                self._send({"jsonrpc": "2.0", "id": msg["id"], "result": {
                    "name": name,
                    "protocolVersion": 1,
                    "capabilities": list(capabilities),
                    "egress": list(egress),
                    "cannedReplyDefaults": list(canned_reply_defaults),
                }})
                if self.on_initialize:
                    self.on_initialize()
            elif method == "onWatchConnected":
                if self.on_watch_connected:
                    self.on_watch_connected()
            elif method == "onAction":
                p = msg.get("params", {})
                if self.on_action:
                    self.on_action(p.get("itemId"), p.get("action"))
            elif method == "onReply":
                p = msg.get("params", {})
                if self.on_reply:
                    self.on_reply(p.get("itemId"), p.get("text"))
            elif method == "onDismiss":
                p = msg.get("params", {})
                if self.on_dismiss:
                    self.on_dismiss(p.get("itemId"))
            elif method == "onAppMessage":
                p = msg.get("params", {})
                if self.on_app_message:
                    # Untag the typed dict {key: {"t":..,"v":..}} → {int_key: value}.
                    data = {int(k): tv.get("v") for k, tv in (p.get("data") or {}).items()}
                    self.on_app_message(p.get("uuid"), p.get("transactionId"), data)
            elif method == "shutdown":
                break
            elif method is None and "result" in msg:
                cb = self._pending.pop(msg.get("id"), None)
                if cb:
                    cb(msg["result"])
