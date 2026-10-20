package main

import (
	"context"
	"encoding/base64"
	"reflect"
	"strings"
	"testing"
	"time"
)

// fakeLink is a watch link on a fake clock: every message takes rtt plus its data at rate bytes/s.
type fakeLink struct {
	t    time.Time
	rtt  time.Duration
	rate float64
	msgs []map[int]AppValue
}

func (f *fakeLink) now() time.Time { return f.t }

func (f *fakeLink) send(_ context.Context, m map[int]AppValue) bool {
	f.t = f.t.Add(f.rtt + time.Duration(float64(len(spkData(m)))/f.rate*float64(time.Second)))
	f.msgs = append(f.msgs, m)
	return true
}

func (f *fakeLink) sleep(_ context.Context, d time.Duration) bool { f.t = f.t.Add(d); return true }

func newFakePump(f *fakeLink, w SpeakerWatch) *SpeakerPump {
	p := NewSpeakerPump(f.send)
	p.now, p.sleep = f.now, f.sleep
	p.SetWatch(w)
	return p
}

func spkData(m map[int]AppValue) []byte {
	v, ok := m[SpkKeyData]
	if !ok {
		return nil
	}
	b, _ := base64.StdEncoding.DecodeString(v["v"].(string))
	return b
}

func spkFlagsOf(m map[int]AppValue) int {
	if v, ok := m[SpkKeyFlags]; ok {
		return v["v"].(int)
	}
	return 0
}

// unpack splits PACKED data into its block lengths, checking the framing.
func unpack(t *testing.T, data []byte) []int {
	var lens []int
	for len(data) > 0 {
		if len(data) < 2 {
			t.Fatalf("a dangling length byte")
		}
		n := int(data[0]) | int(data[1])<<8
		if n > len(data)-2 {
			t.Fatalf("block of %d bytes, %d left", n, len(data)-2)
		}
		lens = append(lens, n)
		data = data[2+n:]
	}
	return lens
}

func blocks(n, size int) [][]byte {
	b := make([][]byte, n)
	for i := range b {
		b[i] = make([]byte, size)
	}
	return b
}

var emery = SpeakerWatch{DataMax: 8164, Ring: 32768}

// A receiver that reported its inbox gets several blocks per message, each behind its length; SEQ counts
// blocks, so END carries the number after the last block.
func TestSpeakerPumpPacked(t *testing.T) {
	f := &fakeLink{t: time.Unix(1000, 0), rtt: 100 * time.Millisecond, rate: 20000}
	p := newFakePump(f, emery)
	if !p.Write(context.Background(), append(blocks(8, 2000), make([]byte, 700))) || !p.End(context.Background()) {
		t.Fatal("pump failed")
	}
	if len(f.msgs) != 4 {
		t.Fatalf("%d messages", len(f.msgs))
	}
	wantLens := [][]int{{2000, 2000, 2000, 2000}, {2000, 2000, 2000, 2000}, {700}}
	for i, m := range f.msgs[:3] {
		if got := unpack(t, spkData(m)); !reflect.DeepEqual(got, wantLens[i]) {
			t.Fatalf("message %d carries %v", i, got)
		}
		if m[SpkKeySeq]["v"] != 4*i || spkFlagsOf(m)&SpkFlagPacked == 0 {
			t.Fatalf("message %d: seq %v flags %#x", i, m[SpkKeySeq]["v"], spkFlagsOf(m))
		}
	}
	if fl := spkFlagsOf(f.msgs[0]); fl&(SpkFlagStart|SpkFlagHold) != SpkFlagStart|SpkFlagHold {
		t.Fatalf("first message flags %#x", fl)
	}
	if fl := spkFlagsOf(f.msgs[1]); fl&(SpkFlagStart|SpkFlagHold) != 0 {
		t.Fatalf("second message flags %#x", fl)
	}
	if end := f.msgs[3]; spkFlagsOf(end) != SpkFlagEnd || end[SpkKeySeq]["v"] != 9 || spkData(end) != nil {
		t.Fatalf("end %v", end)
	}
	// A watch that reported less than a full block's room gets single blocks, unpacked.
	f.msgs = nil
	q := newFakePump(f, SpeakerWatch{DataMax: 1500, Ring: 32768})
	q.Write(context.Background(), blocks(2, 2000))
	if len(f.msgs) != 2 || len(spkData(f.msgs[0])) != 2000 || spkFlagsOf(f.msgs[0])&SpkFlagPacked != 0 {
		t.Fatalf("small inbox: %d messages", len(f.msgs))
	}
}

func TestSpkStartNeed(t *testing.T) {
	for _, tc := range []struct {
		rest time.Duration
		rate float64
		want time.Duration
	}{
		{0, 0, spkPrebuffer},                             // nothing more to come
		{time.Second, 0, spkNever},                       // unknown rate: the receiver's own start
		{6 * time.Second, 8000, spkPrebuffer},            // faster than playback
		{6 * time.Second, 4400, spkPrebuffer},            // just fast enough with the 10 % margin
		{6 * time.Second, 2200, 6400 * time.Millisecond}, // half speed (with margin): 6 s behind by the end
	} {
		if got := spkStartNeed(tc.rest, tc.rate); got != tc.want {
			t.Errorf("spkStartNeed(%v, %v) = %v, want %v", tc.rest, tc.rate, got, tc.want)
		}
	}
}

// The watch is told to play once it holds enough for the rest to arrive in time: on the second message
// once the first measured the link, on the first with an earlier reading's rate as the hint.
func TestSpeakerPumpPlay(t *testing.T) {
	for _, tc := range []struct {
		name    string
		hint    float64
		playMsg int
	}{
		{"measured", 0, 1},
		{"hinted", 3500, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := &fakeLink{t: time.Unix(1000, 0), rate: 3500}
			p := newFakePump(f, emery)
			p.SetRateHint(tc.hint)
			p.Expect(12 * blockDuration(2000))
			p.Write(context.Background(), blocks(12, 2000))
			for i, m := range f.msgs {
				if got := spkFlagsOf(m)&SpkFlagPlay != 0; got != (i == tc.playMsg) {
					t.Fatalf("message %d PLAY %v", i, got)
				}
			}
			if !p.clock.started || p.clock.startWhy != "play" {
				t.Fatalf("clock %+v", p.clock)
			}
		})
	}
	// Too slow to catch up within the hold: the watch starts by itself 3 s after the first message.
	f := &fakeLink{t: time.Unix(1000, 0), rate: 2000}
	p := newFakePump(f, emery)
	p.Expect(24 * blockDuration(2000)) // the rest still to synthesize
	p.Write(context.Background(), blocks(12, 2000))
	for i, m := range f.msgs {
		if spkFlagsOf(m)&SpkFlagPlay != 0 {
			t.Fatalf("message %d PLAY", i)
		}
	}
	if p.clock.startWhy != "hold timeout" || p.clock.startAt != p.clock.first.Add(spkHoldMax) {
		t.Fatalf("start %s at %v", p.clock.startWhy, p.clock.startAt.Sub(p.clock.first))
	}
}

// A HOLD stream starts by itself on a nearly full ring, or spkHoldMax after its first message.
func TestSpeakerClockHold(t *testing.T) {
	t0 := time.Unix(1000, 0)
	c := speakerClock{ring: 4 * 2002, hold: true}
	c.sent(t0, []int{2000, 2000, 2000}, false)
	if c.started {
		t.Fatal("started with room for another block")
	}
	if c.plan(t0.Add(2*time.Second), []int{2000}, 8164, true, 0); c.started {
		t.Fatal("started before the hold ran out")
	}
	c.plan(t0.Add(4*time.Second), []int{2000}, 8164, true, 0)
	if !c.started || c.startWhy != "hold timeout" || c.end != t0.Add(spkHoldMax+3*blockDuration(2000)) {
		t.Fatalf("after the hold: %+v", c)
	}

	full := speakerClock{ring: 4 * 2002, hold: true}
	full.sent(t0, []int{2000, 2000, 2000, 2000}, false)
	if !full.started || full.startWhy != "full" {
		t.Fatalf("full ring: %+v", full)
	}
}

// Once playing, a message that doesn't fit waits for room while the watch has enough to play through the
// wait and the transfer; when it hasn't, what fits goes now.
func TestSpeakerClockPlan(t *testing.T) {
	t0 := time.Unix(1000, 0)
	d := blockDuration(2000)
	ring := func() *speakerClock {
		c := &speakerClock{ring: 4 * 2002, hold: true}
		c.sent(t0, []int{2000, 2000, 2000, 2000}, true) // plays from t0, until t0+4d
		return c
	}
	next := []int{2000, 2000, 2000}
	if n, w := ring().plan(t0, next, 8164, true, 40000); n != 0 || w != 3*d {
		t.Fatalf("fast link: %d blocks, wait %v", n, w)
	}
	c := ring()
	if n, w := c.plan(t0, next, 8164, true, 2000); n != 0 || w != d {
		t.Fatalf("slow link: %d blocks, wait %v (one block's room)", n, w)
	}
	if n, w := c.plan(t0.Add(d), next, 8164, true, 2000); n != 1 || w != 0 {
		t.Fatalf("slow link, room for one: %d blocks, wait %v", n, w)
	}
	// Unpacked, one block a message.
	if n, w := (&speakerClock{}).plan(t0, next, 2002, false, 0); n != 1 || w != 0 {
		t.Fatalf("unpacked: %d, %v", n, w)
	}
}

// The report measures the stream; a link slower than playback leaves the watch dry between blocks.
func TestSpeakerPumpReport(t *testing.T) {
	f := &fakeLink{t: time.Unix(1000, 0), rate: 2000}
	p := newFakePump(f, SpeakerWatch{})
	if p.Report() != "nothing sent" {
		t.Fatal(p.Report())
	}
	p.Write(context.Background(), blocks(4, 2000))
	p.End(context.Background())
	r := p.Report()
	// Each block takes 1 s and plays 0.5 s: dry for 0.5 s before blocks 2, 3 and 4.
	for _, want := range []string{"4 messages, 4 blocks, 8000 B, 1996 ms of audio in 4000 ms", "ack avg 1000 max 1000 ms",
		"2.0 KB/s (link 2.0)", "started after 0 ms (prebuffer)", "underrun ~1502 ms"} {
		if !strings.Contains(r, want) {
			t.Errorf("report %q lacks %q", r, want)
		}
	}
	if p.LinkRate() != 2000 {
		t.Fatalf("link rate %v", p.LinkRate())
	}
}

func TestSpeechFirstPiece(t *testing.T) {
	got := speechSentences("The Moon is about 384,400 kilometres from Earth on average, which is roughly thirty Earths lined up side by side. Light takes a second to get there.")
	want := []string{
		"The Moon is about 384,400 kilometres from Earth on average,",
		"which is roughly thirty Earths lined up side by side.",
		"Light takes a second to get there.",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("pieces %q", got)
	}
	// Without a clause boundary in reach the sentence stays whole; later sentences are never cut.
	long := "Supercalifragilisticexpialidocious words keep going on and on without any pause for breath at all until the end. Then, " +
		"a second sentence that is also quite long, with commas, and more commas, and yet more words to make it longer than a hundred."
	if p := speechSentences(long); len(p) != 2 || !strings.HasSuffix(p[0], "end.") {
		t.Fatalf("pieces %q", p)
	}
}

func TestSpeechLength(t *testing.T) {
	if got := speechLength(0, 0, 100); got != 6500*time.Millisecond {
		t.Fatalf("before any answer: %v", got)
	}
	if got := speechLength(4*time.Second, 50, 50); got != 8*time.Second {
		t.Fatalf("measured: %v", got)
	}
}

// A watchapp that reports its speech receiver in HELLO gets packed messages that wait for PLAY.
func TestHelloSpeakerCaps(t *testing.T) {
	f := newFakeAPI(t)
	a, out := newTestAssistant(t, f, map[string]string{"speak": "on_request"})
	a.mu.Lock()
	c, n := a.hist.addTurn(base{}, Turn{Q: "q", Text: "An answer that is read aloud."}, a.cfg.Model, a.prefix(), a.now())
	a.mu.Unlock()
	defer speakHost(a, out, func(string) map[string]any { return blocksOf(6, 2000) })()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "7": float64(helloSpeaker), "11": float64(8164), "12": float64(32768)})
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdSpeak), "4": float64(convNum(c.ID)), "3": float64(n)})
	waitFor(t, out, "END", func(l map[string]any) bool { return spkFlags(l) == SpkFlagEnd })
	var flags []int
	for _, l := range out.lines() {
		if isSpk(l) {
			flags = append(flags, spkFlags(l))
		}
	}
	if len(flags) != 3 || flags[0]&(SpkFlagStart|SpkFlagHold|SpkFlagPacked) != SpkFlagStart|SpkFlagHold|SpkFlagPacked ||
		flags[1]&SpkFlagPacked == 0 {
		t.Fatalf("flags %v", flags)
	}
	// The reading records the link's rate for the next one just after END.
	for i := 0; ; i++ {
		a.mu.Lock()
		spk, rate := a.spk, a.spkRate
		a.mu.Unlock()
		if spk == (SpeakerWatch{DataMax: 8164, Ring: 32768}) && rate > 0 {
			break
		}
		if i == 100 {
			t.Fatalf("speaker %+v rate %v", spk, rate)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
