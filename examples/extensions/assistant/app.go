package main

import (
	"context"
	"strconv"
	"strings"
	"sync"
	"time"
)

// appUUID is the bundled Assistant watchapp.
const appUUID = "5b24fc80-114d-4c28-b007-53e241b95856"

// AppMessage keys (PROTOCOL.md C2).
const (
	keyCmd   = 0
	keyText  = 1
	keySeq   = 2
	keyTurn  = 3
	keyConv  = 4
	keyFlags = 5
	keyInbox = 6
	keyCaps  = 7
	keyProto = 8
	keyCode  = 9
	keyIndex = 10
	// HELLO from a watchapp that packs speech (1.1.1): stoandl_speaker_data_max and _ring_bytes.
	keySpkData = 11
	keySpkRing = 12
)

// Commands, watch to extension.
const (
	cmdHello     = 1
	cmdAsk       = 2
	cmdPreset    = 3
	cmdNewConv   = 4
	cmdCancel    = 5
	cmdBye       = 6
	cmdSpeak     = 7
	cmdStopSpeak = 8
)

// Commands, extension to watch.
const (
	cmdPresets = 16
	cmdStatus  = 17
	cmdChunk   = 18
	cmdDone    = 19
	cmdError   = 20
)

const (
	statusIdle      = 0
	statusThinking  = 1
	statusSearching = 2

	flagTruncated = 0x01
	flagReplay    = 0x02

	capSpeaker = 0x08
	capMuted   = 0x10 // muted, or Quiet Time: nothing is read aloud unasked

	protoVersion = 1
)

func msgPresets(presets []string) map[int]AppValue {
	return map[int]AppValue{keyCmd: U8(cmdPresets), keyProto: U8(protoVersion),
		keyText: CStr(cutUTF8(strings.Join(presets, "\n"), 512))}
}

func msgStatus(conv uint32, turn, code int, q string) map[int]AppValue {
	m := map[int]AppValue{keyCmd: U8(cmdStatus), keyConv: U32(int(conv)), keyTurn: U8(turn), keyCode: U8(code)}
	if code != statusIdle {
		m[keyText] = CStr(cutUTF8(fontSafe(q), 100))
	}
	return m
}

func msgChunk(conv uint32, turn, seq int, text string) map[int]AppValue {
	return map[int]AppValue{keyCmd: U8(cmdChunk), keyConv: U32(int(conv)), keyTurn: U8(turn),
		keySeq: U16(seq), keyText: CStr(text)}
}

func msgDone(conv uint32, turn, chunks, flags int) map[int]AppValue {
	return map[int]AppValue{keyCmd: U8(cmdDone), keyConv: U32(int(conv)), keyTurn: U8(turn),
		keySeq: U16(chunks), keyFlags: U8(flags)}
}

func msgError(conv uint32, turn int, e *askError) map[int]AppValue {
	return map[int]AppValue{keyCmd: U8(cmdError), keyConv: U32(int(conv)), keyTurn: U8(turn),
		keyCode: U8(e.Code), keyText: CStr(e.AppText())}
}

// Overridable in tests.
var (
	appReplyTimeout = 15 * time.Second
	nackRetryBase   = 200 * time.Millisecond
	slowNack        = 5 * time.Second
)

// appSend sends one AppMessage and waits for the watch's answer, one message in flight at a time.
// A NACK is retried after 200, 400 and 800 ms; a slow NACK, a timeout, an error or a fourth failure
// means the app is closed. It reports whether the watch ACKed.
func (a *assistant) appSend(ctx context.Context, data map[int]AppValue) bool {
	a.appMu.Lock()
	defer a.appMu.Unlock()
	for attempt := 0; attempt < 4; attempt++ {
		if ctx != nil && ctx.Err() != nil {
			return false
		}
		start := time.Now()
		r := a.sendWait(data)
		if r == "ack" {
			return true
		}
		if r != "nack" || time.Since(start) >= slowNack || attempt == 3 {
			break
		}
		select {
		case <-time.After(nackRetryBase << attempt):
		case <-ctxDone(ctx):
			return false
		}
	}
	a.appClosed()
	return false
}

func ctxDone(ctx context.Context) <-chan struct{} {
	if ctx == nil {
		return nil
	}
	return ctx.Done()
}

func (a *assistant) sendWait(data map[int]AppValue) string {
	ch := make(chan string, 1)
	a.ext.SendAppMessage(appUUID, data, func(r string) { ch <- r })
	select {
	case r := <-ch:
		return r
	case <-time.After(appReplyTimeout):
		return "timeout"
	}
}

// appClosed marks the watchapp gone: a running app request then ends as notification cards.
func (a *assistant) appClosed() {
	a.mu.Lock()
	a.appOpen = false
	a.stopSpeechLocked()
	var sink *appSink
	if a.job != nil {
		sink = a.job.sink
	}
	if a.replay != nil {
		a.replay.cancel()
		a.replay = nil
	}
	a.mu.Unlock()
	if sink != nil {
		sink.detach()
	}
}

func (a *assistant) onAppMessage(uuid string, data map[string]any) {
	if uuid != appUUID {
		return
	}
	select {
	case a.inbound <- data:
	default:
		a.ext.Log("app: inbound queue full, dropping a message")
	}
}

// appWorker handles watchapp messages in order.
func (a *assistant) appWorker() {
	for m := range a.inbound {
		a.handleApp(m)
	}
}

func appInt(m map[string]any, key int) int {
	switch v := m[strconv.Itoa(key)].(type) {
	case float64:
		return int(v)
	case int:
		return v
	}
	return 0
}

func appStr(m map[string]any, key int) string {
	s, _ := m[strconv.Itoa(key)].(string)
	return s
}

func (a *assistant) handleApp(m map[string]any) {
	conv := uint32(appInt(m, keyConv))
	turn := appInt(m, keyTurn)
	cmd := appInt(m, keyCmd)
	if cmd == cmdHello || cmd == cmdAsk || cmd == cmdPreset {
		a.mu.Lock()
		if _, ok := m[strconv.Itoa(keyCaps)]; ok {
			a.caps = appInt(m, keyCaps)
		}
		// The app is open even if an earlier send to it failed (e.g. while it was dictating).
		a.appOpen = true
		a.prepareSpeechLocked()
		a.mu.Unlock()
	}
	switch cmd {
	case cmdHello:
		a.mu.Lock()
		a.spk = SpeakerWatch{DataMax: appInt(m, keySpkData), Ring: appInt(m, keySpkRing)} // zero: an older app
		a.mu.Unlock()
		a.hello()
	case cmdAsk:
		a.ask(originApp, convID(conv), turn, cutUTF8(appStr(m, keyText), 500))
	case cmdPreset:
		i := appInt(m, keyIndex)
		if i < 0 || i >= len(a.cfg.Presets) {
			a.ext.Logf("app: unknown preset %d", i)
			a.appSend(nil, msgError(conv, turn, newAskError(errBadRequest, 0, "")))
			return
		}
		a.ask(originApp, convID(conv), turn, a.cfg.Presets[i])
	case cmdNewConv:
		a.mu.Lock()
		a.stopSpeechLocked()
		a.hist.Current = ""
		a.saveLocked()
		a.mu.Unlock()
	case cmdCancel:
		a.mu.Lock()
		a.stopSpeechLocked()
		if j := a.job; j != nil && convNum(j.conv) == conv && j.turn == turn {
			j.cancel(errUserCancel)
			a.job = nil
		}
		a.mu.Unlock()
		a.appSend(nil, msgError(conv, turn, newAskError(errCancelled, 0, "")))
	case cmdBye:
		a.appClosed()
	case cmdSpeak:
		a.speakTurn(conv, turn)
	case cmdStopSpeak:
		a.stopSpeech()
	}
}

// hello: the watchapp opened. It gets the presets, then the running request re-streamed, or the
// current conversation's last answer replayed.
func (a *assistant) hello() {
	a.mu.Lock()
	a.appOpen = true
	a.stopSpeechLocked() // a new app instance: the old stream's blocks would start mid-answer
	if a.replay != nil {
		a.replay.cancel()
		a.replay = nil
	}
	j := a.job
	a.mu.Unlock()

	if !a.appSend(nil, msgPresets(a.cfg.Presets)) {
		return
	}
	if j != nil && j.sink != nil {
		j.sink.restream()
		return
	}
	a.mu.Lock()
	var conv uint32
	var turn int
	var last *Turn
	if c := a.hist.find(a.hist.Current); c != nil && len(c.Turns) > 0 {
		conv, turn = convNum(c.ID), len(c.Turns)
		if a.hist.idleBase(a.now(), a.cfg.Idle, a.cfg.Model, a.prefix()).conv == c && c.Turns[turn-1].Err == 0 {
			t := c.Turns[turn-1]
			last = &t
		}
	}
	if last == nil || a.job != nil {
		a.mu.Unlock()
		a.appSend(nil, msgStatus(conv, turn, statusIdle, ""))
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := newAppSink(a, ctx, conv, turn, last.Q, statusIdle, true)
	s.stop = cancel
	s.add(last.Text)
	s.finish0(nil, flagReplay)
	a.replay = s
	a.mu.Unlock()
}

// appSink streams one answer to the watchapp as CHUNKs (C5). It survives the app closing and
// reopening: while detached it only collects text; restream starts again from SEQ 0.
type appSink struct {
	a      *assistant
	ctx    context.Context
	stop   context.CancelFunc
	conv   uint32
	turn   int
	q      string
	wake   chan struct{}
	result chan bool

	mu           sync.Mutex
	status       int
	text         string
	sent         int // bytes of text in CHUNKs the watch ACKed
	seq          int
	pendingSince time.Time
	attached     bool
	restart      bool
	gen          int // bumped by restream, so a failed send doesn't detach a newer attach
	finished     bool
	err          *askError
	flags        int
}

func newAppSink(a *assistant, ctx context.Context, conv uint32, turn int, q string, status int, attached bool) *appSink {
	s := &appSink{a: a, ctx: ctx, conv: conv, turn: turn, q: q, status: status, attached: attached,
		restart: attached, wake: make(chan struct{}, 1), result: make(chan bool, 1)}
	go s.loop()
	return s
}

func (s *appSink) poke() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *appSink) cancel() {
	if s.stop != nil {
		s.stop()
	}
}

func (s *appSink) add(text string) {
	s.mu.Lock()
	if s.sent == len(s.text) {
		s.pendingSince = time.Now()
	}
	s.text += text
	s.mu.Unlock()
	s.poke()
}

func (s *appSink) searching() {
	s.mu.Lock()
	if s.status != statusSearching {
		s.status = statusSearching
		s.restart = s.attached
	}
	s.mu.Unlock()
	s.poke()
}

func (s *appSink) restream() {
	s.mu.Lock()
	s.attached, s.restart = true, true
	s.gen++
	s.mu.Unlock()
	s.poke()
}

func (s *appSink) detach() {
	s.mu.Lock()
	s.attached = false
	s.mu.Unlock()
	s.poke()
}

func (s *appSink) finish0(e *askError, flags int) {
	s.mu.Lock()
	s.finished, s.err, s.flags = true, e, flags
	s.mu.Unlock()
	s.poke()
}

// finish marks the answer complete and waits until DONE (or ERROR) is ACKed: true, or the app is
// gone or the request superseded: false.
func (s *appSink) finish(e *askError, flags int) bool {
	s.finish0(e, flags)
	return <-s.result
}

func (s *appSink) loop() {
	for {
		if s.ctx.Err() != nil {
			s.result <- false
			return
		}
		s.mu.Lock()
		switch {
		case !s.attached:
			done := s.finished
			s.mu.Unlock()
			if done {
				s.result <- false
				return
			}
			s.wait(0)
			continue
		case s.restart:
			s.restart, s.sent, s.seq = false, 0, 0
			s.pendingSince = time.Now()
			msg, gen := msgStatus(s.conv, s.turn, s.status, s.q), s.gen
			s.mu.Unlock()
			s.send(msg, gen)
			continue
		}
		if s.err != nil && s.finished {
			msg, gen := msgError(s.conv, s.turn, s.err), s.gen
			s.mu.Unlock()
			if s.send(msg, gen) {
				s.result <- true
				return
			}
			continue
		}
		buf := s.text[s.sent:]
		age := time.Since(s.pendingSince)
		if chunk := nextChunk(buf, age, s.finished); chunk != "" {
			seq, gen := s.seq, s.gen
			s.mu.Unlock()
			if !s.send(msgChunk(s.conv, s.turn, seq, chunk), gen) {
				continue
			}
			s.mu.Lock()
			if s.gen == gen && !s.restart { // not restarted meanwhile
				s.sent += len(chunk)
				s.seq++
				s.pendingSince = time.Now()
			}
			s.mu.Unlock()
			continue
		}
		if s.finished {
			msg, gen := msgDone(s.conv, s.turn, s.seq, s.flags), s.gen
			s.mu.Unlock()
			if s.send(msg, gen) {
				s.result <- true
				return
			}
			continue
		}
		var timeout time.Duration
		if buf != "" {
			timeout = chunkAge - age
			if timeout <= 0 {
				timeout = time.Millisecond
			}
		}
		s.mu.Unlock()
		s.wait(timeout)
	}
}

// send delivers one message; on failure the sink detaches unless a restream came in meanwhile.
func (s *appSink) send(msg map[int]AppValue, gen int) bool {
	if s.a.appSend(s.ctx, msg) {
		return true
	}
	s.mu.Lock()
	if s.gen == gen {
		s.attached = false
	}
	s.mu.Unlock()
	return false
}

func (s *appSink) wait(timeout time.Duration) {
	var tc <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		tc = t.C
	}
	select {
	case <-s.wake:
	case <-s.ctx.Done():
	case <-tc:
	}
}
