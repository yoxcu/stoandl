// Package-local stoandl extension helper (Go) — a thin wrapper over the newline-delimited JSON-RPC
// protocol stoandl speaks to its extensions (see ../../docs/extensions.md). This is the Go counterpart
// of stoandl_ext.py: copy it next to your extension (keep it in `package main`) and drive it from your
// own service loop.
//
// An extension is a child process stoandl spawns. It reads requests/notifications on stdin and writes on
// stdout (one JSON object per line). NEVER write to stdout yourself — use Ext.Log() (which writes to
// stderr, folded into stoandl's log). stdout is the wire; a stray write corrupts the frame.
package main

import (
	"bufio"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"os"
	"strings"
	"sync"
	"time"
)

// Namespace for turning a human-friendly ReplaceID string into a stable UUID (so re-sends replace the
// same watch notification across daemon restarts). Arbitrary but fixed.
var replaceIDNamespace = [16]byte{0x9e, 0x4f, 0x21, 0xb7, 0x1c, 0x3a, 0x4d, 0x8e, 0xa6, 0x10, 0x77, 0x2b, 0xc3, 0x55, 0xd9, 0x01}

// Notification is one watch notification to push via Ext.Notify.
type Notification struct {
	Title    string
	Body     string
	AppName  string // THE policy key (per-app mute/style). Default = the extension name.
	Subtitle string

	// Actions become menu items; the chosen one comes back via OnAction(itemID, actionID).
	Actions [][2]string // each entry is {id, label}
	// CannedReplies adds a Reply menu; the chosen/dictated text comes back via OnReply(itemID, text).
	CannedReplies []string
	AllowVoice    bool
	// ReplyLabel is the Reply action's title on the watch (default "Reply"). Setting it also offers a
	// Reply with no canned replies, which then come from the host's notification.canned_replies.
	ReplyLabel string

	ExtToken string // your own correlation tag, echoed back verbatim in callbacks
	Icon     string // iconCode (optional; per-app override wins)
	Color    string // optional
	Vibe     string // optional
	// Urgent: sent even while the host's notification.only_when_locked holds notifications back (e.g. an
	// answer to something the user just asked from the wrist).
	Urgent bool

	// ReplaceID: a stable string. Re-sending with the same ReplaceID REPLACES the same watch
	// notification instead of piling up duplicates — use it for a per-conversation notification.
	ReplaceID string

	// OnItem, if set, is called with the daemon-issued watch item id once the watch accepts the
	// notification ("" if the app was muted or no watch is connected). Use it to correlate replies.
	OnItem func(itemID string)
}

// AppValue is a typed AppMessage dict value (JSON has one number type but watchapps distinguish widths).
type AppValue map[string]any

func U8(v int) AppValue      { return AppValue{"t": "uint8", "v": v} }
func U16(v int) AppValue     { return AppValue{"t": "uint16", "v": v} }
func U32(v int) AppValue     { return AppValue{"t": "uint", "v": v} }
func I8(v int) AppValue      { return AppValue{"t": "int8", "v": v} }
func I16(v int) AppValue     { return AppValue{"t": "int16", "v": v} }
func I32(v int) AppValue     { return AppValue{"t": "int", "v": v} }
func CStr(v string) AppValue { return AppValue{"t": "cstring", "v": v} }
func Blob(b []byte) AppValue {
	return AppValue{"t": "bytes", "v": base64.StdEncoding.EncodeToString(b)}
}

// Ext is a running extension: framing + dispatch to the On* handlers.
type Ext struct {
	out *bufio.Writer

	writeMu sync.Mutex
	idMu    sync.Mutex
	nextID  int

	pendMu  sync.Mutex
	pending map[int]func(map[string]any)
	wantErr map[int]bool // pending callbacks that receive {"error": …} instead of only a log line

	// Populated from the initialize handshake.
	Config map[string]string // your extension.<name>.<key> settings
	Watch  map[string]any    // {"connected": bool, ...}

	// Optional handlers — assign before calling Run().
	OnInitialize     func()
	OnWatchConnected func()
	OnAction         func(itemID, action string)
	OnReply          func(itemID, text string)
	// OnActionToken / OnReplyToken also get the notification's ExtToken; when set they are called
	// instead of OnAction / OnReply.
	OnActionToken func(itemID, extToken, action string)
	OnReplyToken  func(itemID, extToken, text string)
	OnDismiss     func(itemID string)
	OnAppMessage  func(uuid string, txn int, data map[string]any)
}

func NewExt() *Ext {
	return &Ext{
		out:     bufio.NewWriter(os.Stdout),
		pending: map[int]func(map[string]any){},
		wantErr: map[int]bool{},
		Config:  map[string]string{},
		Watch:   map[string]any{},
	}
}

func (e *Ext) send(obj map[string]any) {
	b, err := json.Marshal(obj)
	if err != nil {
		e.Log("marshal error: ", err)
		return
	}
	e.writeMu.Lock()
	defer e.writeMu.Unlock()
	e.out.Write(b)
	e.out.WriteByte('\n')
	e.out.Flush()
}

// Log writes to stderr (folded into stoandl's log under this extension's tag). NEVER use fmt.Print* —
// stdout is the protocol wire.
func (e *Ext) Log(args ...any) {
	fmt.Fprintln(os.Stderr, args...)
}

// Logf is the formatted variant of Log.
func (e *Ext) Logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
}

func (e *Ext) reqID() int {
	e.idMu.Lock()
	defer e.idMu.Unlock()
	e.nextID++
	return e.nextID
}

// Notify pushes a notification to the watch.
func (e *Ext) Notify(n Notification) {
	rid := e.reqID()
	params := map[string]any{"title": n.Title, "body": n.Body}
	if n.ReplaceID != "" {
		params["itemId"] = uuid5(replaceIDNamespace, n.ReplaceID)
	}
	if n.AppName != "" {
		params["appName"] = n.AppName
	}
	if n.Subtitle != "" {
		params["subtitle"] = n.Subtitle
	}
	if n.ExtToken != "" {
		params["extToken"] = n.ExtToken
	}
	if n.Icon != "" {
		params["iconCode"] = n.Icon
	}
	if n.Color != "" {
		params["color"] = n.Color
	}
	if n.Vibe != "" {
		params["vibe"] = n.Vibe
	}
	if n.Urgent {
		params["urgent"] = true
	}
	if len(n.Actions) > 0 {
		acts := make([]map[string]any, 0, len(n.Actions))
		for _, a := range n.Actions {
			acts = append(acts, map[string]any{"id": a[0], "label": a[1]})
		}
		params["actions"] = acts
	}
	if len(n.CannedReplies) > 0 || n.ReplyLabel != "" {
		reply := map[string]any{"cannedReplies": n.CannedReplies, "allowVoice": n.AllowVoice}
		if n.CannedReplies == nil {
			reply["cannedReplies"] = []string{}
		}
		if n.ReplyLabel != "" {
			reply["label"] = n.ReplyLabel
		}
		params["reply"] = reply
	}
	if n.OnItem != nil {
		onItem := n.OnItem
		e.pendMu.Lock()
		e.pending[rid] = func(res map[string]any) {
			itemID, _ := res["itemId"].(string)
			onItem(itemID)
		}
		e.pendMu.Unlock()
	}
	e.send(map[string]any{"jsonrpc": "2.0", "id": rid, "method": "notify", "params": params})
}

// Close clears a notification from the watch (e.g. the message was read elsewhere).
func (e *Ext) Close(itemID string) {
	e.send(map[string]any{"jsonrpc": "2.0", "method": "closeNotification", "params": map[string]any{"itemId": itemID}})
}

func (e *Ext) request(method string, params map[string]any, onResult func(map[string]any)) {
	e.requestE(method, params, onResult, false)
}

// requestE is request whose callback, with wantErr, also receives an error reply as {"error": {...}}.
func (e *Ext) requestE(method string, params map[string]any, onResult func(map[string]any), wantErr bool) {
	rid := e.reqID()
	if onResult != nil {
		e.pendMu.Lock()
		e.pending[rid] = onResult
		if wantErr {
			e.wantErr[rid] = true
		}
		e.pendMu.Unlock()
	}
	e.send(map[string]any{"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
}

// KeepAwake asks the host to keep the machine out of suspend for up to maxMs (at most 60000) while
// this extension works. cb gets the hold's token, or "" when the host refused or doesn't support it.
// Release the token when done; to hold longer, take a new token before the old one expires.
func (e *Ext) KeepAwake(why string, maxMs int, cb func(token string)) {
	e.requestE("keepAwake", map[string]any{"why": why, "maxMs": maxMs}, func(res map[string]any) {
		token, _ := res["token"].(string)
		if _, failed := res["error"]; failed {
			e.Logf("keepAwake refused: %v", res["error"])
			token = ""
		}
		if cb != nil {
			cb(token)
		}
	}, true)
}

// Release ends a KeepAwake hold (unknown or expired tokens are fine).
func (e *Ext) Release(token string) {
	if token == "" {
		return
	}
	e.request("release", map[string]any{"token": token}, nil)
}

// Synthesize asks the host's text to speech (tts.*) for speech the watch speaker can play: cb gets the
// self-contained ADPCM blocks (docs/speaker-stream.md) and their length in ms, or err: "off",
// "unavailable: …", "busy" or what else went wrong. Feed the blocks to a SpeakerPump. A host without
// text to speech never answers, so wait with a timeout.
func (e *Ext) Synthesize(text, language string, cb func(blocks [][]byte, ms int, err string)) {
	params := map[string]any{"text": text, "format": "pebble-adpcm"}
	if language != "" {
		params["language"] = language
	}
	e.requestE("synthesize", params, func(res map[string]any) {
		if failure, failed := res["error"]; failed {
			msg, _ := failure.(map[string]any)["message"].(string)
			if msg == "" {
				msg = "synthesize failed"
			}
			cb(nil, 0, msg)
			return
		}
		raw, _ := res["blocks"].([]any)
		blocks := make([][]byte, 0, len(raw))
		for _, r := range raw {
			s, _ := r.(string)
			b, err := base64.StdEncoding.DecodeString(s)
			if err != nil || len(b) <= spkBlockHeader {
				cb(nil, 0, "synthesize: bad block")
				return
			}
			blocks = append(blocks, b)
		}
		ms, _ := res["ms"].(float64)
		cb(blocks, int(ms), "")
	}, true)
}

// CancelSynthesize drops this extension's Synthesize still in flight (its cb gets "cancelled"), so the
// host's engine stops on a sentence nobody will hear. A host with none in flight, or one older than
// it, ignores it.
func (e *Ext) CancelSynthesize() {
	e.send(map[string]any{"jsonrpc": "2.0", "method": "cancelSynthesize"})
}

// RegisterApp arms inbound AppMessages from a watchapp UUID — they arrive via OnAppMessage.
func (e *Ext) RegisterApp(uuid string) {
	e.request("registerApp", map[string]any{"uuid": uuid}, nil)
}

// SendAppMessage sends an AppMessage; onResult (if set) receives "ack"/"nack"/"timeout"/"error".
func (e *Ext) SendAppMessage(uuid string, data map[int]AppValue, onResult func(string)) {
	d := map[string]any{}
	for k, v := range data {
		d[fmt.Sprintf("%d", k)] = v
	}
	var cb func(map[string]any)
	if onResult != nil {
		cb = func(res map[string]any) { s, _ := res["result"].(string); onResult(s) }
	}
	e.request("sendAppMessage", map[string]any{"uuid": uuid, "data": d}, cb)
}

func (e *Ext) LaunchApp(uuid string)  { e.request("launchApp", map[string]any{"uuid": uuid}, nil) }
func (e *Ext) StopApp(uuid string)    { e.request("stopApp", map[string]any{"uuid": uuid}, nil) }
func (e *Ext) InstallPbw(path string) { e.request("installPbw", map[string]any{"path": path}, nil) }

// Run blocks reading stdin and dispatching to the On* handlers until stoandl closes the pipe. name/
// capabilities/egress/cannedReplyDefaults are returned in the initialize handshake (informational).
func (e *Ext) Run(name string, capabilities, egress, cannedReplyDefaults []string) {
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024) // allow long frames
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			e.Logf("ignoring non-JSON line: %.200s", line)
			continue
		}
		method, _ := msg["method"].(string)
		params, _ := msg["params"].(map[string]any)
		switch {
		case method == "initialize":
			if p, ok := params["config"].(map[string]any); ok {
				for k, v := range p {
					e.Config[k] = fmt.Sprintf("%v", v)
				}
			}
			if w, ok := params["watch"].(map[string]any); ok {
				e.Watch = w
			}
			e.send(map[string]any{"jsonrpc": "2.0", "id": msg["id"], "result": map[string]any{
				"name":                name,
				"protocolVersion":     1,
				"capabilities":        capabilities,
				"egress":              egress,
				"cannedReplyDefaults": cannedReplyDefaults,
			}})
			if e.OnInitialize != nil {
				e.OnInitialize()
			}
		case method == "onWatchConnected":
			if e.OnWatchConnected != nil {
				e.OnWatchConnected()
			}
		case method == "onAction":
			if e.OnActionToken != nil {
				e.OnActionToken(str(params, "itemId"), str(params, "extToken"), str(params, "action"))
			} else if e.OnAction != nil {
				e.OnAction(str(params, "itemId"), str(params, "action"))
			}
		case method == "onReply":
			if e.OnReplyToken != nil {
				e.OnReplyToken(str(params, "itemId"), str(params, "extToken"), str(params, "text"))
			} else if e.OnReply != nil {
				e.OnReply(str(params, "itemId"), str(params, "text"))
			}
		case method == "onDismiss":
			if e.OnDismiss != nil {
				e.OnDismiss(str(params, "itemId"))
			}
		case method == "onAppMessage":
			if e.OnAppMessage != nil {
				data := map[string]any{}
				if d, ok := params["data"].(map[string]any); ok {
					for k, tv := range d {
						if m, ok := tv.(map[string]any); ok {
							data[k] = m["v"]
						}
					}
				}
				txn := 0
				if f, ok := params["transactionId"].(float64); ok {
					txn = int(f)
				}
				e.OnAppMessage(str(params, "uuid"), txn, data)
			}
		case method == "shutdown":
			return
		case method == "" && msg["result"] != nil:
			id := 0
			if f, ok := msg["id"].(float64); ok {
				id = int(f)
			}
			e.pendMu.Lock()
			cb := e.pending[id]
			delete(e.pending, id)
			delete(e.wantErr, id)
			e.pendMu.Unlock()
			if cb != nil {
				if res, ok := msg["result"].(map[string]any); ok {
					cb(res)
				} else {
					cb(map[string]any{})
				}
			}
		case method == "" && msg["error"] != nil:
			// An error reply to one of our requests — drop the pending callback so it doesn't leak.
			id := 0
			if f, ok := msg["id"].(float64); ok {
				id = int(f)
			}
			e.pendMu.Lock()
			cb := e.pending[id]
			wantErr := e.wantErr[id]
			delete(e.pending, id)
			delete(e.wantErr, id)
			e.pendMu.Unlock()
			if cb != nil && wantErr {
				cb(map[string]any{"error": msg["error"]})
			} else {
				e.Logf("request %d failed: %v", id, msg["error"])
			}
		}
	}
}

func str(m map[string]any, k string) string {
	if m == nil {
		return ""
	}
	s, _ := m[k].(string)
	return s
}

// uuid5 derives a stable v5 UUID string from (namespace, name).
func uuid5(ns [16]byte, name string) string {
	h := sha1.New()
	h.Write(ns[:])
	h.Write([]byte(name))
	s := h.Sum(nil)
	var u [16]byte
	copy(u[:], s[:16])
	u[6] = (u[6] & 0x0f) | 0x50 // version 5
	u[8] = (u[8] & 0x3f) | 0x80 // RFC 4122 variant
	return fmt.Sprintf("%x-%x-%x-%x-%x", u[0:4], u[4:6], u[6:8], u[8:10], u[10:16])
}

// Speaker stream (docs/speaker-stream.md): the reserved AppMessage keys a watchapp that vendors
// watchlib/stoandl_speaker.c consumes, and the receiver ring SpeakerPump paces against.
const (
	SpkKeyID    = 0x7FFF0000
	SpkKeySeq   = 0x7FFF0001
	SpkKeyData  = 0x7FFF0002
	SpkKeyFlags = 0x7FFF0003

	SpkFlagStart = 0x01
	SpkFlagEnd   = 0x02
	SpkFlagAbort = 0x04

	spkRingBytes   = 6144 // the watch's ADPCM ring
	spkLenBytes    = 2    // the ring stores each block behind its length
	spkBlockHeader = 4
	spkRate        = 8000
	spkPrebuffer   = 400 * time.Millisecond // the watch opens its stream with this much buffered
)

// SpeakerPump streams speech blocks to a watchapp that vendors stoandl_speaker.c, one stream per pump.
// send must be the extension's own one-in-flight path to that watchapp (it retries NACKs and reports
// whether the watch ACKed), so speech never races the app's other messages.
type SpeakerPump struct {
	send  func(ctx context.Context, data map[int]AppValue) bool
	id    uint32
	seq   int
	clock speakerClock
	now   func() time.Time
}

func NewSpeakerPump(send func(ctx context.Context, data map[int]AppValue) bool) *SpeakerPump {
	id := rand.Uint32()
	for id == 0 {
		id = rand.Uint32()
	}
	return &SpeakerPump{send: send, id: id, now: time.Now}
}

// Sent reports whether a block went out.
func (p *SpeakerPump) Sent() bool { return p.seq > 0 }

// Write sends blocks in order, each once the watch's ring has room for it. It returns false when ctx
// ended or the watch didn't take a block.
func (p *SpeakerPump) Write(ctx context.Context, blocks [][]byte) bool {
	for _, b := range blocks {
		if w := p.clock.wait(p.now(), len(b)); w > 0 {
			t := time.NewTimer(w)
			select {
			case <-t.C:
			case <-ctx.Done():
				t.Stop()
				return false
			}
		}
		if ctx.Err() != nil {
			return false
		}
		m := p.msg(0)
		m[SpkKeyData] = Blob(b)
		if p.seq == 0 {
			m[SpkKeyFlags] = U8(SpkFlagStart)
		}
		if !p.send(ctx, m) {
			return false
		}
		p.seq++
		p.clock.sent(p.now(), len(b))
	}
	return true
}

// End tells the watch the stream is complete; it plays out what it holds. With nothing sent it aborts.
func (p *SpeakerPump) End(ctx context.Context) bool {
	if p.seq == 0 {
		return p.Abort(ctx)
	}
	return p.send(ctx, p.msg(SpkFlagEnd))
}

// Abort stops the watch playing this stream, and clears a watch waiting for one that never came.
func (p *SpeakerPump) Abort(ctx context.Context) bool { return p.send(ctx, p.msg(SpkFlagAbort)) }

func (p *SpeakerPump) msg(flags int) map[int]AppValue {
	m := map[int]AppValue{SpkKeyID: U32(int(p.id)), SpkKeySeq: U16(p.seq)}
	if flags != 0 {
		m[SpkKeyFlags] = U8(flags)
	}
	return m
}

// speakerClock estimates what the watch still holds of a stream, so a block is sent only when the
// ring has room for it. A block counts as held until it has played, which errs on the safe side: the
// watch frees it once decoded into the firmware's stream buffer.
type speakerClock struct {
	started bool
	end     time.Time // when everything sent so far will have played
	pre     time.Duration
	held    []heldBlock // oldest first
}

type heldBlock struct {
	bytes int
	dur   time.Duration
	until time.Time // zero until playback has started
}

func blockDuration(n int) time.Duration {
	return time.Duration(1+2*(n-spkBlockHeader)) * time.Second / spkRate
}

func (c *speakerClock) expire(now time.Time) {
	i := 0
	for i < len(c.held) && c.started && !c.held[i].until.After(now) {
		i++
	}
	c.held = c.held[i:]
}

// wait is how long until a block of n bytes fits in the ring.
func (c *speakerClock) wait(now time.Time, n int) time.Duration {
	c.expire(now)
	over := n + spkLenBytes - spkRingBytes
	for _, b := range c.held {
		over += b.bytes
	}
	if over <= 0 || !c.started {
		return 0 // before playback the ring holds less than a block's worth: it can't be full
	}
	for _, b := range c.held {
		if over -= b.bytes; over <= 0 {
			return b.until.Sub(now)
		}
	}
	return 0
}

// sent records a block of n bytes the watch ACKed at now.
func (c *speakerClock) sent(now time.Time, n int) {
	c.expire(now)
	b := heldBlock{bytes: n + spkLenBytes, dur: blockDuration(n)}
	if c.started {
		if c.end.Before(now) {
			c.end = now // the watch ran dry: this block plays at once
		}
		c.end = c.end.Add(b.dur)
		b.until = c.end
		c.held = append(c.held, b)
		return
	}
	c.held = append(c.held, b)
	c.pre += b.dur
	if c.pre >= spkPrebuffer {
		c.started, c.end = true, now
		for i := range c.held {
			c.end = c.end.Add(c.held[i].dur)
			c.held[i].until = c.end
		}
	}
}
