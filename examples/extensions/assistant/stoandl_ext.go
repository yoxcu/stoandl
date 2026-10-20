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

// PrepareSynthesize tells the host a Synthesize in language is likely soon (the app opened, a question
// is on its way), so it loads the voice now and the first sentence doesn't wait for it. No answer; a host
// without it ignores it.
func (e *Ext) PrepareSynthesize(language string) {
	msg := map[string]any{"jsonrpc": "2.0", "method": "prepareSynthesize"}
	if language != "" {
		msg["params"] = map[string]any{"language": language}
	}
	e.send(msg)
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

	SpkFlagStart  = 0x01
	SpkFlagEnd    = 0x02
	SpkFlagAbort  = 0x04
	SpkFlagPacked = 0x08 // DATA is blocks, each behind its u16 little-endian length
	SpkFlagHold   = 0x10 // with START: the receiver buffers until PLAY instead of starting at 0.4 s
	SpkFlagPlay   = 0x20 // start playing now

	spkRingBytes   = 6144 // the ring of a receiver that reports none
	spkLenBytes    = 2    // the ring stores each block behind its length; PACKED data has the same layout
	spkBlockHeader = 4
	spkBlockMax    = 2000
	spkRate        = 8000
	spkBytesPerSec = spkRate / 2             // 4-bit ADPCM
	spkPrebuffer   = 400 * time.Millisecond  // a stream without HOLD opens with this much buffered
	spkHoldMax     = 3 * time.Second         // a HOLD stream opens this long after its first message at the latest
	spkMargin      = 250 * time.Millisecond  // slack in the pacing estimates
	spkUnknownXfer = 1500 * time.Millisecond // a message's transfer time before any was measured
	spkNever       = time.Duration(1<<63 - 1)
)

// SpeakerWatch is what a receiver reports through the app's own protocol: stoandl_speaker_data_max() for
// the app's inbox and stoandl_speaker_ring_bytes(). The zero value is a receiver that reported nothing:
// one block per message, a 6144-byte ring, playback from 0.4 s buffered.
type SpeakerWatch struct {
	DataMax int // bytes of DATA one PACKED message may carry
	Ring    int // the receiver's ring in bytes
}

// modern: the receiver knows PACKED, HOLD and PLAY (it reported its ring).
func (w SpeakerWatch) modern() bool { return w.Ring > 0 }

// packed: several blocks per message, and at least one full block fits.
func (w SpeakerWatch) packed() bool { return w.modern() && w.DataMax >= spkLenBytes+spkBlockMax }

// SpeakerPump streams speech blocks to a watchapp that vendors stoandl_speaker.c, one stream per pump.
// send must be the extension's own one-in-flight path to that watchapp (it retries NACKs and reports
// whether the watch ACKed), so speech never races the app's other messages.
//
// Told what the watch reported (SetWatch), it packs several blocks per message and decides when the watch
// starts playing (PLAY): once it holds enough that the rest, at the measured rate, arrives before it is
// needed. Expect gives it the whole stream's length as far as the caller knows it.
type SpeakerPump struct {
	send  func(ctx context.Context, data map[int]AppValue) bool
	id    uint32
	seq   int
	watch SpeakerWatch
	clock speakerClock
	now   func() time.Time
	sleep func(ctx context.Context, d time.Duration) bool // false when ctx ended first

	expect   time.Duration // the whole stream's audio, as estimated by the caller
	rateHint float64       // bytes/s to assume before anything was measured
	stats    speakerStats
}

// speakerStats measures one stream, for Report.
type speakerStats struct {
	messages, blocks, bytes int
	audio                   time.Duration
	first, last             time.Time // first send, last ACK
	ackSum, ackMax          time.Duration
}

func NewSpeakerPump(send func(ctx context.Context, data map[int]AppValue) bool) *SpeakerPump {
	id := rand.Uint32()
	for id == 0 {
		id = rand.Uint32()
	}
	return &SpeakerPump{send: send, id: id, now: time.Now, sleep: sleepCtx}
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// SetWatch sets what the receiver reported; call it before the first Write.
func (p *SpeakerPump) SetWatch(w SpeakerWatch) {
	p.watch = w
	p.clock.ring = w.Ring
	p.clock.hold = w.modern()
}

// Expect sets the estimated length of the whole stream (sent and still to come). Call it again when the
// estimate improves; 0 means unknown.
func (p *SpeakerPump) Expect(total time.Duration) { p.expect = total }

// SetRateHint sets the link rate, in bytes/s, to assume before this stream has measured its own (an
// earlier stream's LinkRate).
func (p *SpeakerPump) SetRateHint(bytesPerSec float64) { p.rateHint = bytesPerSec }

// LinkRate is the measured rate of the watch link: data bytes per second of send→ACK time; 0 before the
// first ACK.
func (p *SpeakerPump) LinkRate() float64 {
	if p.stats.ackSum <= 0 {
		return 0
	}
	return float64(p.stats.bytes) / p.stats.ackSum.Seconds()
}

// rate is what arrives per second of wall time so far (the link and any wait for the next sentence), or
// the hint before the first ACK.
func (p *SpeakerPump) rate() float64 {
	if el := p.stats.last.Sub(p.stats.first); p.stats.messages > 0 && el > 0 {
		return float64(p.stats.bytes) / el.Seconds()
	}
	return p.rateHint
}

func (p *SpeakerPump) linkRate() float64 {
	if r := p.LinkRate(); r > 0 {
		return r
	}
	return p.rateHint
}

// Sent reports whether a block went out.
func (p *SpeakerPump) Sent() bool { return p.seq > 0 }

// Write sends blocks in order, as many per message as the watch takes, each message once the watch's ring
// has room for it. It returns false when ctx ended or the watch didn't take a message.
func (p *SpeakerPump) Write(ctx context.Context, blocks [][]byte) bool {
	maxData := spkLenBytes + spkBlockMax
	if p.watch.packed() {
		maxData = p.watch.DataMax
	}
	for len(blocks) > 0 {
		sizes := make([]int, len(blocks))
		for i, b := range blocks {
			sizes[i] = len(b)
		}
		n, wait := p.clock.plan(p.now(), sizes, maxData, p.watch.packed(), p.linkRate())
		if wait > 0 {
			if !p.sleep(ctx, wait) {
				return false
			}
			continue
		}
		if ctx.Err() != nil {
			return false
		}
		part := blocks[:n]
		var audio time.Duration
		for _, b := range part {
			audio += blockDuration(len(b))
		}
		flags := 0
		if p.seq == 0 {
			flags |= SpkFlagStart
			if p.watch.modern() {
				flags |= SpkFlagHold
			}
		}
		play := p.watch.modern() && p.clock.wantPlay(audio, p.expect-p.stats.audio-audio, p.rate())
		if play {
			flags |= SpkFlagPlay
		}
		m := p.msg(0)
		if p.watch.packed() {
			flags |= SpkFlagPacked
			var data []byte
			for _, b := range part {
				data = append(data, byte(len(b)), byte(len(b)>>8))
				data = append(data, b...)
			}
			m[SpkKeyData] = Blob(data)
		} else {
			m[SpkKeyData] = Blob(part[0])
		}
		if flags != 0 {
			m[SpkKeyFlags] = U8(flags)
		}
		start := p.now()
		if !p.send(ctx, m) {
			return false
		}
		acked := p.now()
		p.stats.add(start, acked, part, audio)
		p.seq += n
		p.clock.sent(acked, sizes[:n], play)
		blocks = blocks[n:]
	}
	return true
}

func (s *speakerStats) add(start, acked time.Time, blocks [][]byte, audio time.Duration) {
	if s.messages == 0 {
		s.first = start
	}
	s.messages++
	s.blocks += len(blocks)
	for _, b := range blocks {
		s.bytes += len(b)
	}
	s.audio += audio
	s.last = acked
	d := acked.Sub(start)
	s.ackSum += d
	s.ackMax = max(s.ackMax, d)
}

// End tells the watch the stream is complete; it plays out what it holds. With nothing sent it aborts.
func (p *SpeakerPump) End(ctx context.Context) bool {
	if p.seq == 0 {
		return p.Abort(ctx)
	}
	if !p.clock.started {
		p.clock.start(p.now(), "end")
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

// Report is one line about the stream so far, for the extension's log: what went out, how fast the watch
// took it, when playback began and how long the watch is estimated to have run dry. Never the text.
func (p *SpeakerPump) Report() string {
	s := p.stats
	if s.messages == 0 {
		return "nothing sent"
	}
	wall := s.last.Sub(s.first)
	kbs := 0.0
	if wall > 0 {
		kbs = float64(s.bytes) / 1000 / wall.Seconds()
	}
	start := "not started"
	if c := p.clock; c.started {
		start = fmt.Sprintf("started after %d ms (%s)", max(0, c.startAt.Sub(c.first).Milliseconds()), c.startWhy)
	}
	return fmt.Sprintf("%d messages, %d blocks, %d B, %d ms of audio in %d ms (first send to last ack), "+
		"ack avg %d max %d ms, %.1f KB/s (link %.1f), %s, underrun ~%d ms",
		s.messages, s.blocks, s.bytes, s.audio.Milliseconds(), wall.Milliseconds(),
		(s.ackSum / time.Duration(s.messages)).Milliseconds(), s.ackMax.Milliseconds(), kbs, p.LinkRate()/1000,
		start, p.clock.underrun.Milliseconds())
}

// spkStartNeed is how much audio a watch must hold before it starts playing so that the rest of the stream
// (rest of audio, arriving at rate bytes/s) comes before it is needed. Audio arriving at r seconds per
// second, r < 1, falls behind by rest·(1/r − 1) by the end; at r ≥ 1 the prebuffer is enough. With a 10 %
// margin on the rate. Unknown rate: never (the receiver starts by itself).
func spkStartNeed(rest time.Duration, rate float64) time.Duration {
	if rest <= 0 {
		return spkPrebuffer
	}
	if rate <= 0 {
		return spkNever
	}
	r := rate / spkBytesPerSec / 1.1
	if r >= 1 {
		return spkPrebuffer
	}
	return spkPrebuffer + time.Duration(float64(rest)*(1/r-1))
}

// speakerClock estimates what the watch still holds of a stream, so a message is sent only when the ring
// has room for it, and when the watch starts playing. A block counts as held until it has played, which
// errs on the safe side: the watch frees it once decoded into the firmware's stream buffer.
type speakerClock struct {
	ring     int  // 0: spkRingBytes
	hold     bool // a HOLD stream: the watch waits for PLAY (or a full ring, END, or spkHoldMax)
	started  bool
	first    time.Time // the first ACK; a HOLD receiver opens by first+spkHoldMax
	startAt  time.Time
	startWhy string
	end      time.Time     // when everything sent so far will have played
	pre      time.Duration // audio buffered before playback
	held     []heldBlock   // oldest first
	underrun time.Duration // estimated time the watch had nothing to play
}

type heldBlock struct {
	bytes int
	dur   time.Duration
	until time.Time // zero until playback has started
}

func blockDuration(n int) time.Duration {
	return time.Duration(1+2*(n-spkBlockHeader)) * time.Second / spkRate
}

func (c *speakerClock) ringBytes() int {
	if c.ring > 0 {
		return c.ring
	}
	return spkRingBytes
}

func (c *speakerClock) heldBytes() int {
	n := 0
	for _, b := range c.held {
		n += b.bytes
	}
	return n
}

func (c *speakerClock) start(at time.Time, why string) {
	c.started, c.startAt, c.startWhy, c.end = true, at, why, at
	for i := range c.held {
		c.end = c.end.Add(c.held[i].dur)
		c.held[i].until = c.end
	}
}

func (c *speakerClock) expire(now time.Time) {
	if c.hold && !c.started && !c.first.IsZero() && !now.Before(c.first.Add(spkHoldMax)) {
		c.start(c.first.Add(spkHoldMax), "hold timeout")
	}
	i := 0
	for i < len(c.held) && c.started && !c.held[i].until.After(now) {
		i++
	}
	c.held = c.held[i:]
}

// until is how long until need bytes of the ring are free: 0 now (or never possible), -1 not before
// playback starts.
func (c *speakerClock) until(now time.Time, need int) time.Duration {
	free := c.ringBytes() - c.heldBytes()
	if need <= free || need > c.ringBytes() {
		return 0
	}
	if !c.started {
		return -1
	}
	for _, b := range c.held {
		if free += b.bytes; free >= need {
			return b.until.Sub(now)
		}
	}
	return 0
}

// plan decides the next message: its first n blocks of sizes, or wait first. One message carries up to
// maxData bytes (each block behind its length) when packed, else one block. A message that doesn't fit
// the ring yet waits for room while the watch has enough left to play through the wait and the transfer;
// otherwise the blocks that fit go now.
func (c *speakerClock) plan(now time.Time, sizes []int, maxData int, packed bool, linkRate float64) (n int, wait time.Duration) {
	c.expire(now)
	k, bytes := 1, sizes[0]+spkLenBytes
	for packed && k < len(sizes) && bytes+sizes[k]+spkLenBytes <= maxData {
		bytes += sizes[k] + spkLenBytes
		k++
	}
	free := c.ringBytes() - c.heldBytes()
	fit, fitBytes := 0, 0
	for fit < k && fitBytes+sizes[fit]+spkLenBytes <= free {
		fitBytes += sizes[fit] + spkLenBytes
		fit++
	}
	if fit == k {
		return k, 0
	}
	if !c.started {
		if fit > 0 {
			return fit, 0
		}
		c.start(now, "full") // the watch starts by itself once its ring can't take a full block
	}
	all := c.until(now, bytes)
	if all <= 0 {
		return k, 0
	}
	xfer := spkUnknownXfer
	if linkRate > 0 {
		xfer = time.Duration(float64(bytes) / linkRate * float64(time.Second))
	}
	if !now.Add(all + xfer + spkMargin).After(c.end) {
		return 0, all // the watch has enough to play while room for the whole message frees up
	}
	if fit > 0 {
		return fit, 0
	}
	return 0, max(1, c.until(now, sizes[0]+spkLenBytes))
}

// wantPlay: a HOLD stream that hasn't started should start once a message with add of audio arrives,
// because the watch then holds enough for the rest of the stream (rest of audio, at rate bytes/s).
func (c *speakerClock) wantPlay(add, rest time.Duration, rate float64) bool {
	return c.hold && !c.started && c.pre+add >= spkStartNeed(rest, rate)
}

// sent records a message with blocks of sizes the watch ACKed at now; play: it carried PLAY.
func (c *speakerClock) sent(now time.Time, sizes []int, play bool) {
	c.expire(now)
	if c.first.IsZero() {
		c.first = now
	}
	for _, n := range sizes {
		b := heldBlock{bytes: n + spkLenBytes, dur: blockDuration(n)}
		if c.started {
			if c.end.Before(now) {
				c.underrun += now.Sub(c.end) // the watch ran dry: this block plays at once
				c.end = now
			}
			c.end = c.end.Add(b.dur)
			b.until = c.end
		} else {
			c.pre += b.dur
		}
		c.held = append(c.held, b)
	}
	if c.started {
		return
	}
	switch {
	case play:
		c.start(now, "play")
	case !c.hold && c.pre >= spkPrebuffer:
		c.start(now, "prebuffer")
	case c.ringBytes()-c.heldBytes() < spkLenBytes+spkBlockMax:
		c.start(now, "full")
	}
}
