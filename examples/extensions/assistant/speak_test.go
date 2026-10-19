package main

import (
	"context"
	"encoding/base64"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestTtsLanguage(t *testing.T) {
	for in, want := range map[string]string{
		"German": "de", " deutsch ": "de", "de": "de", "de-AT": "de", "English": "en", "en_GB": "en",
		"": "", "French": "", "Dutch": "", "denglisch": "",
	} {
		if got := ttsLanguage(in); got != want {
			t.Errorf("ttsLanguage(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSpeechSentences(t *testing.T) {
	got := speechSentences("Yes. About 384,400 km, which is roughly thirty Earths lined up. Light takes a second.\n- one item\n- two")
	want := []string{
		"Yes. About 384,400 km, which is roughly thirty Earths lined up.",
		"Light takes a second.\none item\ntwo",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("sentences %q", got)
	}
	long := strings.Repeat("word ", 200)
	for _, p := range speechSentences(long) {
		if len(p) > sentenceMax {
			t.Fatalf("piece of %d B", len(p))
		}
	}
	if s := speechSentences("  \n "); len(s) != 0 {
		t.Fatalf("blank text: %q", s)
	}
}

// Full 2000-byte blocks (499 ms each): three fit in the 6 KB ring, the fourth waits for the first to
// have played; after the watch ran dry a block plays at once.
func TestSpeakerClockPacing(t *testing.T) {
	var c speakerClock
	t0 := time.Unix(1000, 0)
	d := blockDuration(2000)
	if d != 499125*time.Microsecond {
		t.Fatalf("block duration %v", d)
	}
	for i := 0; i < 3; i++ {
		if w := c.wait(t0, 2000); w != 0 {
			t.Fatalf("block %d waits %v", i, w)
		}
		c.sent(t0, 2000)
	}
	if w := c.wait(t0, 2000); w != d {
		t.Fatalf("fourth block waits %v, want %v", w, d)
	}
	if w := c.wait(t0.Add(d), 2000); w != 0 {
		t.Fatalf("after one played, waits %v", w)
	}
	late := t0.Add(10 * time.Second)
	c.sent(late, 2000)
	if c.end != late.Add(d) {
		t.Fatalf("after a gap the block ends at %v", c.end.Sub(late))
	}

	// Before 0.4 s has arrived the watch isn't playing yet, and nothing has to wait.
	var short speakerClock
	short.sent(t0, 1000) // 249 ms
	if short.started || short.wait(t0, 2000) != 0 {
		t.Fatalf("prebuffer: %+v", short)
	}
	short.sent(t0.Add(time.Second), 1000)
	if !short.started || short.end != t0.Add(time.Second).Add(2*blockDuration(1000)) {
		t.Fatalf("start: %+v", short)
	}
}

func TestSpeakerPumpMessages(t *testing.T) {
	var sent []map[int]AppValue
	send := func(_ context.Context, m map[int]AppValue) bool { sent = append(sent, m); return true }
	p := NewSpeakerPump(send)
	if !p.Write(context.Background(), [][]byte{make([]byte, 100), make([]byte, 60)}) || !p.End(context.Background()) {
		t.Fatal("pump failed")
	}
	if len(sent) != 3 {
		t.Fatalf("%d messages", len(sent))
	}
	for i, m := range sent {
		if m[SpkKeyID]["v"] != int(p.id) || m[SpkKeySeq]["v"] != i {
			t.Fatalf("message %d: %v", i, m)
		}
	}
	if sent[0][SpkKeyFlags]["v"] != SpkFlagStart || sent[1][SpkKeyFlags] != nil || sent[2][SpkKeyFlags]["v"] != SpkFlagEnd {
		t.Fatalf("flags %v / %v / %v", sent[0][SpkKeyFlags], sent[1][SpkKeyFlags], sent[2][SpkKeyFlags])
	}
	if sent[1][SpkKeyData]["v"] != base64.StdEncoding.EncodeToString(make([]byte, 60)) || sent[2][SpkKeyData] != nil {
		t.Fatal("data")
	}

	sent = nil
	if q := NewSpeakerPump(send); !q.End(context.Background()) || len(sent) != 1 || sent[0][SpkKeyFlags]["v"] != SpkFlagAbort {
		t.Fatalf("end with nothing sent: %v", sent)
	}
}

// speakHost is a host whose watch ACKs every AppMessage and whose text to speech answers synth.
func speakHost(a *assistant, out *syncBuf, synth func(text string) map[string]any) func() {
	return fakeHost(a, out, func(l map[string]any) map[string]any {
		switch l["method"] {
		case "sendAppMessage":
			return map[string]any{"result": "ack"}
		case "synthesize":
			return synth(params(l)["text"].(string))
		case "keepAwake":
			return map[string]any{"token": "tok"}
		}
		return nil
	})
}

func blocksOf(n, size int) map[string]any {
	var b []any
	for i := 0; i < n; i++ {
		b = append(b, base64.StdEncoding.EncodeToString(make([]byte, size)))
	}
	return map[string]any{"blocks": b, "ms": n * 499}
}

func spkFlags(l map[string]any) int {
	if l["method"] != "sendAppMessage" {
		return -1
	}
	v, ok := params(l)["data"].(map[string]any)["2147418115"].(map[string]any)
	if !ok {
		return 0
	}
	return int(v["v"].(float64))
}

func isSpk(l map[string]any) bool {
	if l["method"] != "sendAppMessage" {
		return false
	}
	_, ok := params(l)["data"].(map[string]any)["2147418112"]
	return ok
}

func count(out *syncBuf, pred func(map[string]any) bool) int {
	n := 0
	for _, l := range out.lines() {
		if pred(l) {
			n++
		}
	}
	return n
}

const helloSpeaker = capSpeaker | 0x02

// speak = always: an answer shown in the app is read aloud, sentence by sentence, unless the watch is
// muted or in Quiet Time.
func TestAlwaysSpeaksAfterDone(t *testing.T) {
	for _, tc := range []struct {
		name  string
		cfg   map[string]string
		caps  int
		speak bool
	}{
		{"always", nil, helloSpeaker, true},
		{"muted", nil, helloSpeaker | capMuted, false},
		{"no speaker", nil, 0x02, false},
		{"on_request", map[string]string{"speak": "on_request"}, helloSpeaker, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeAPI(t, textResponse("About 384,400 km from here, give or take. Light takes about a second."))
			a, out := newTestAssistant(t, f, tc.cfg)
			var mu sync.Mutex
			var texts []string
			defer speakHost(a, out, func(text string) map[string]any {
				mu.Lock()
				texts = append(texts, text)
				mu.Unlock()
				return blocksOf(1, 400)
			})()
			a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(tc.caps)})
			a.onAppMessage(appUUID, map[string]any{"0": float64(cmdAsk), "1": "how far is the moon", "7": float64(tc.caps)})
			waitFor(t, out, "DONE", func(l map[string]any) bool { return l["method"] == "sendAppMessage" && appCmd(l) == cmdDone })
			if !tc.speak {
				time.Sleep(100 * time.Millisecond)
				if n := count(out, func(l map[string]any) bool { return l["method"] == "synthesize" || isSpk(l) }); n != 0 {
					t.Fatalf("spoke anyway (%d lines)", n)
				}
				return
			}
			waitFor(t, out, "END", func(l map[string]any) bool { return spkFlags(l) == SpkFlagEnd })
			mu.Lock()
			defer mu.Unlock()
			if len(texts) != 2 || texts[0] != "About 384,400 km from here, give or take." {
				t.Fatalf("synthesized %q", texts)
			}
			if n := count(out, isSpk); n != 3 {
				t.Fatalf("%d speaker messages", n)
			}
			for _, l := range out.lines() {
				if l["method"] == "synthesize" && params(l)["format"] != "pebble-adpcm" {
					t.Fatalf("synthesize %v", params(l))
				}
			}
		})
	}
}

// A new question stops the reading: the pump sends no more blocks, and the watch gets an abort.
func TestAskStopsSpeech(t *testing.T) {
	f := newFakeAPI(t, textResponse("Second answer."))
	a, out := newTestAssistant(t, f, map[string]string{"speak": "on_request"})
	a.mu.Lock()
	c, n := a.hist.addTurn(base{}, Turn{Q: "q", Text: "A long first answer that takes a while to read aloud."}, a.cfg.Model, a.prefix(), a.now())
	a.mu.Unlock()
	defer speakHost(a, out, func(string) map[string]any { return blocksOf(8, 2000) })()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(helloSpeaker)})
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdSpeak), "4": float64(convNum(c.ID)), "3": float64(n)})
	// Three blocks fill the ring; the fourth waits half a second.
	waitFor(t, out, "third block", func(l map[string]any) bool { return count(out, isSpk) >= 3 })
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdAsk), "1": "next question", "7": float64(helloSpeaker)})
	waitFor(t, out, "abort", func(l map[string]any) bool { return spkFlags(l) == SpkFlagAbort })
	waitFor(t, out, "DONE", func(l map[string]any) bool { return l["method"] == "sendAppMessage" && appCmd(l) == cmdDone })
	time.Sleep(700 * time.Millisecond)
	if blocks := count(out, func(l map[string]any) bool { return isSpk(l) && spkFlags(l) != SpkFlagAbort }); blocks != 3 {
		t.Fatalf("%d blocks sent", blocks)
	}
}

// Asked to read aloud while text to speech is off: a card says where to turn it on, and the watch is
// told to stop waiting. Unasked (always), the same answer is silent.
func TestSpeechOffCard(t *testing.T) {
	f := newFakeAPI(t)
	a, out := newTestAssistant(t, f, nil)
	a.mu.Lock()
	c, n := a.hist.addTurn(base{}, Turn{Q: "q", Text: "An answer."}, a.cfg.Model, a.prefix(), a.now())
	a.mu.Unlock()
	defer speakHost(a, out, func(string) map[string]any { return map[string]any{"error": map[string]any{"message": "off"}} })()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(helloSpeaker)})

	a.startSpeech("An answer.", false)
	time.Sleep(100 * time.Millisecond)
	if count(out, isNotify) != 0 || count(out, isSpk) != 0 {
		t.Fatal("unasked reading wasn't silent")
	}

	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdSpeak), "4": float64(convNum(c.ID)), "3": float64(n)})
	card := params(waitFor(t, out, "card", isNotify))
	if card["title"] != "Text to speech is off" || card["body"] != fontSafe(speechOffHint) {
		t.Fatalf("card %v", card)
	}
	waitFor(t, out, "abort", func(l map[string]any) bool { return spkFlags(l) == SpkFlagAbort })
}

// A stoandl without text to speech never answers synthesize. The reading holds off no suspend, and
// once a call went unanswered unasked readings stop; Hold Down still tries and shows why it failed.
func TestSilentDaemon(t *testing.T) {
	defer func(d time.Duration) { synthTimeout = d }(synthTimeout)
	synthTimeout = 100 * time.Millisecond
	f := newFakeAPI(t)
	a, out := newTestAssistant(t, f, nil)
	a.mu.Lock()
	c, n := a.hist.addTurn(base{}, Turn{Q: "q", Text: "An answer."}, a.cfg.Model, a.prefix(), a.now())
	a.mu.Unlock()
	defer speakHost(a, out, func(string) map[string]any { return nil })()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(helloSpeaker)})
	auto := func() bool {
		a.mu.Lock()
		defer a.mu.Unlock()
		return a.autoSpeakLocked()
	}
	waitFor(t, out, "HELLO handled", func(l map[string]any) bool { return l["method"] == "sendAppMessage" })
	if !auto() {
		t.Fatal("always doesn't speak before the daemon was asked")
	}

	a.startSpeech("An answer.", false)
	time.Sleep(300 * time.Millisecond)
	if auto() {
		t.Fatal("still speaks unasked after an unanswered synthesize")
	}
	if count(out, isNotify) != 0 || count(out, isSpk) != 0 {
		t.Fatal("unasked reading wasn't silent")
	}

	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdSpeak), "4": float64(convNum(c.ID)), "3": float64(n)})
	if card := params(waitFor(t, out, "card", isNotify)); card["title"] != "Text to speech unavailable" {
		t.Fatalf("card %v", card)
	}
	waitFor(t, out, "abort", func(l map[string]any) bool { return spkFlags(l) == SpkFlagAbort })
	if n := count(out, func(l map[string]any) bool { return l["method"] == "keepAwake" }); n != 0 {
		t.Fatalf("%d suspend holds for a daemon that never answered", n)
	}
}

// A requested reading stopped before any block went out (here by the extension, as an ASK from a
// notification reply does) still aborts, so the watch stops showing "Speaking...".
func TestStoppedRequestAborts(t *testing.T) {
	f := newFakeAPI(t)
	a, out := newTestAssistant(t, f, nil)
	a.mu.Lock()
	c, n := a.hist.addTurn(base{}, Turn{Q: "q", Text: "An answer."}, a.cfg.Model, a.prefix(), a.now())
	a.mu.Unlock()
	defer speakHost(a, out, func(string) map[string]any { return nil })()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(helloSpeaker)})
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdSpeak), "4": float64(convNum(c.ID)), "3": float64(n)})
	waitFor(t, out, "synthesize", func(l map[string]any) bool { return l["method"] == "synthesize" })
	a.stopSpeech()
	waitFor(t, out, "abort", func(l map[string]any) bool { return spkFlags(l) == SpkFlagAbort })
}

// A hold longer than the host's 60 s cap is renewed before it expires; each replaced token is
// released, and so is the last one.
func TestKeepAwakeRenews(t *testing.T) {
	defer func(m, e time.Duration) { awakeHoldMax, awakeRenewEarly = m, e }(awakeHoldMax, awakeRenewEarly)
	awakeHoldMax, awakeRenewEarly = 100*time.Millisecond, 40*time.Millisecond
	f := newFakeAPI(t)
	a, out := newTestAssistant(t, f, nil)
	var mu sync.Mutex
	next := 0
	defer fakeHost(a, out, func(l map[string]any) map[string]any {
		if l["method"] != "keepAwake" {
			return nil
		}
		mu.Lock()
		defer mu.Unlock()
		next++
		return map[string]any{"token": string(rune('a' + next - 1))}
	})()
	// Holds at 0, 60, 120 and 180 ms; the last covers the 70 ms left.
	release := a.keepAwake("reading", 250*time.Millisecond)
	isHold := func(l map[string]any) bool { return l["method"] == "keepAwake" }
	waitFor(t, out, "fourth hold", func(map[string]any) bool { return count(out, isHold) >= 4 })
	time.Sleep(100 * time.Millisecond)
	if count(out, isHold) != 4 {
		t.Fatalf("renewed past the cap")
	}
	release()
	waitFor(t, out, "last release", func(map[string]any) bool {
		return count(out, func(l map[string]any) bool { return l["method"] == "release" }) >= 4
	})
	var holds []float64
	var released []string
	for _, l := range out.lines() {
		switch l["method"] {
		case "keepAwake":
			holds = append(holds, params(l)["maxMs"].(float64))
		case "release":
			released = append(released, params(l)["token"].(string))
		}
	}
	if len(holds) != 4 || holds[0] != 100 || holds[1] != 100 || holds[3] >= 100 {
		t.Fatalf("holds %v", holds)
	}
	if strings.Join(released, "") != "abcd" {
		t.Fatalf("released %v", released)
	}
}

// The vendored copies must not drift from the shared ones.
func TestVendoredCopies(t *testing.T) {
	for local, shared := range map[string]string{
		"stoandl_ext.go":                   "../stoandl_ext.go",
		"watchapp/src/c/stoandl_speaker.c": "../watchlib/stoandl_speaker.c",
		"watchapp/src/c/stoandl_speaker.h": "../watchlib/stoandl_speaker.h",
	} {
		want, err := os.ReadFile(shared)
		if err != nil {
			t.Skipf("no %s outside the stoandl tree", shared)
		}
		if got, _ := os.ReadFile(local); string(got) != string(want) {
			t.Errorf("%s differs from %s", local, shared)
		}
	}
}
