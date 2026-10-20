package main

import (
	"context"
	"strings"
	"sync"
	"time"
)

// Reading answers aloud on the watch speaker (PROTOCOL.md C9): the daemon synthesizes each sentence
// (ext `synthesize`), a SpeakerPump streams it to the watchapp's stoandl_speaker receiver.

// Config `speak`.
const (
	speakAlways    = "always"
	speakOnRequest = "on_request"
	speakOff       = "off"
)

const (
	speechHold    = 120 * time.Second // keepAwake cap while speaking
	sentenceMin   = 40                // a shorter sentence is joined to the next
	sentenceMax   = 300               // a longer one is cut at a space
	speechCardID  = "assistant:speech"
	speechOffHint = "Turn it on in stoandl: Settings → Voice."
)

// ttsState is what the daemon did with the last `synthesize`. A stoandl without text to speech never
// answers it, so an unanswered call keeps unasked readings off until a later call is answered.
type ttsState int

const (
	ttsUnknown ttsState = iota // nothing asked yet
	ttsAnswers                 // answered (with blocks or an error such as "off")
	ttsSilent                  // the last call got no answer within synthTimeout
)

// speech is the one answer being read aloud.
type speech struct {
	ctx    context.Context
	cancel context.CancelFunc
}

// ttsLanguage maps the free-form `language` setting to the code `synthesize` takes. Only German and
// English have voices; anything else gets no code, and the daemon speaks with its default voice.
func ttsLanguage(name string) string {
	l := strings.ToLower(strings.TrimSpace(name))
	switch {
	case l == "de" || l == "german" || l == "deutsch" || strings.HasPrefix(l, "de-") || strings.HasPrefix(l, "de_"):
		return "de"
	case l == "en" || l == "english" || l == "englisch" || strings.HasPrefix(l, "en-") || strings.HasPrefix(l, "en_"):
		return "en"
	}
	return ""
}

// speechSentences splits an answer into the pieces synthesized one by one: sentences, a short one
// joined to the next, a long one cut at a space. A short first piece makes the first sound come soon.
func speechSentences(text string) []string {
	var lines []string
	for _, ln := range strings.Split(text, "\n") {
		lines = append(lines, strings.TrimPrefix(strings.TrimSpace(ln), "- "))
	}
	rest := strings.TrimSpace(strings.Join(lines, "\n"))
	var out []string
	for rest != "" {
		cut := firstSentenceEnd(rest, sentenceMin)
		if cut < 0 || cut > sentenceMax {
			cut = breakPoint(rest, sentenceMax)
		}
		if p := strings.TrimSpace(rest[:cut]); p != "" {
			out = append(out, p)
		}
		rest = strings.TrimSpace(rest[cut:])
	}
	return out
}

// firstSentenceEnd returns the index just after the first sentence end at or after from, or -1.
func firstSentenceEnd(s string, from int) int {
	if from > len(s) {
		return -1
	}
	best := -1
	for _, m := range []string{". ", "! ", "? ", "\n"} {
		if i := strings.Index(s[from:], m); i >= 0 {
			if at := from + i + len(m); best < 0 || at < best {
				best = at
			}
		}
	}
	return best
}

// autoSpeakLocked: an answer just shown in the app is read aloud unasked.
func (a *assistant) autoSpeakLocked() bool {
	return a.cfg.Speak == speakAlways && a.appOpen && a.caps&capSpeaker != 0 && a.caps&capMuted == 0 &&
		a.tts != ttsSilent
}

func (a *assistant) stopSpeechLocked() {
	if a.speech != nil {
		a.speech.cancel()
		a.speech = nil
		if a.synthAnswered < a.synthSent {
			a.ext.CancelSynthesize() // the next sentence, which nobody will hear
		}
	}
}

func (a *assistant) stopSpeech() {
	a.mu.Lock()
	a.stopSpeechLocked()
	a.mu.Unlock()
}

// speakTurn answers the watchapp's SPEAK: read turn of conv aloud, whatever `always` would decide.
func (a *assistant) speakTurn(conv uint32, turn int) {
	a.mu.Lock()
	var text string
	if c := a.hist.find(convID(conv)); c != nil && turn >= 1 && turn <= len(c.Turns) && c.Turns[turn-1].Err == 0 {
		text = c.Turns[turn-1].Text
	}
	a.mu.Unlock()
	switch {
	case a.cfg.Speak == speakOff:
		a.ext.Notify(speechCard("Reading aloud is off", "Turn it on in stoandl: Extensions → assistant."))
	case strings.TrimSpace(text) == "":
		a.ext.Logf("speak: %s:%d is no longer in the history", convID(conv), turn)
	default:
		a.startSpeech(text, true)
		return
	}
	NewSpeakerPump(a.appSend).Abort(context.Background()) // the watch shows "Speaking..." until told
}

// startSpeech reads text aloud, replacing what is being read. explicit: the user asked (Hold Down), so
// a failure is shown as a card; an unasked reading fails silently (text to speech is off by default).
func (a *assistant) startSpeech(text string, explicit bool) {
	ctx, cancel := context.WithCancel(context.Background())
	s := &speech{ctx: ctx, cancel: cancel}
	a.mu.Lock()
	a.stopSpeechLocked()
	a.speech = s
	a.mu.Unlock()
	go a.speak(s, text, explicit)
}

type synthResult struct {
	blocks [][]byte
	err    string
}

// synthesize asks the daemon for one sentence; the channel gets exactly one result. It also records
// whether the daemon answers (a.tts), late answers included.
func (a *assistant) synthesize(text, lang string) <-chan synthResult {
	ch := make(chan synthResult, 1)
	deliver := func(r synthResult) bool {
		select {
		case ch <- r:
			return true
		default: // the timeout came first, or the answer did
			return false
		}
	}
	a.mu.Lock()
	a.synthSent++
	seq := a.synthSent
	a.mu.Unlock()
	t := time.AfterFunc(synthTimeout, func() {
		if deliver(synthResult{err: "unavailable: no answer from stoandl"}) {
			a.mu.Lock()
			if a.synthAnswered < seq { // a later call's answer outranks this one's silence
				a.tts = ttsSilent
			}
			a.mu.Unlock()
		}
	})
	a.ext.Synthesize(text, lang, func(blocks [][]byte, _ int, err string) {
		t.Stop()
		a.mu.Lock()
		a.tts = ttsAnswers
		a.synthAnswered = max(a.synthAnswered, seq)
		a.mu.Unlock()
		deliver(synthResult{blocks: blocks, err: err})
	})
	return ch
}

func (a *assistant) speak(s *speech, text string, explicit bool) {
	// The hold starts with the first synthesize only once the daemon is known to answer it: a daemon
	// without text to speech would otherwise block suspend for synthTimeout after every answer.
	var release func()
	hold := func() {
		if release == nil {
			release = a.keepAwake("reading an answer aloud", speechHold)
		}
	}
	defer func() {
		if release != nil {
			release()
		}
	}()
	a.mu.Lock()
	known := a.tts == ttsAnswers
	a.mu.Unlock()
	if known {
		hold()
	}
	pump := NewSpeakerPump(a.appSend)
	lang := ttsLanguage(a.cfg.Language)
	sentences := speechSentences(text)
	var next <-chan synthResult
	if len(sentences) > 0 {
		next = a.synthesize(sentences[0], lang)
	}
	for i := range sentences {
		var r synthResult
		select {
		case r = <-next:
		case <-s.ctx.Done():
		}
		if s.ctx.Err() != nil {
			break
		}
		if r.err != "" {
			a.ext.Logf("speak: %s", r.err)
			if explicit && !pump.Sent() {
				a.ext.Notify(speechErrorCard(r.err))
			}
			break
		}
		hold()
		if i+1 < len(sentences) {
			next = a.synthesize(sentences[i+1], lang) // while this one plays
		}
		if !pump.Write(s.ctx, r.blocks) {
			break
		}
	}

	a.mu.Lock()
	open := a.appOpen
	replaced := a.speech != s && a.speech != nil // the new reading clears the watch's wait itself
	if a.speech == s {
		a.speech = nil
	}
	a.mu.Unlock()
	switch {
	case !open:
	case s.ctx.Err() != nil:
		// Also when nothing was sent but the user asked: the stop may not have come from the watch (an
		// ASK from a notification reply), and the watch shows "Speaking..." until told.
		if pump.Sent() || explicit && !replaced {
			pump.Abort(context.Background())
		}
	case pump.Sent() || explicit:
		// With nothing sent, End is an abort, which clears the watch's "Speaking...". A stop while it
		// waits drops the END; an abort then still closes the stream (a late END would cut off a newer
		// reading's stream, an abort for this id can't).
		if !pump.End(s.ctx) && s.ctx.Err() != nil {
			pump.Abort(context.Background())
		}
	}
	s.cancel()
}

func speechCard(title, body string) Notification {
	return Notification{
		Title:     fontSafe(title),
		Body:      fontSafe(body),
		AppName:   cardApp,
		ReplaceID: speechCardID,
		Urgent:    true,
	}
}

// speechErrorCard explains why an answer the user asked to hear can't be read aloud.
func speechErrorCard(err string) Notification {
	switch {
	case err == "off":
		return speechCard("Text to speech is off", speechOffHint)
	case err == "busy":
		return speechCard("Text to speech is busy", "Hold Down to try again.")
	case strings.HasPrefix(err, "unavailable: "):
		return speechCard("Text to speech unavailable", cutUTF8(strings.TrimPrefix(err, "unavailable: "), 100))
	}
	return speechCard("Couldn't read aloud", cutUTF8(err, 100))
}

// keepAwake holds off suspend for up to total, renewing the host's holds (at most 60 s each) before
// they expire. The returned func releases it, also when a token arrives after the work ended.
func (a *assistant) keepAwake(why string, total time.Duration) func() {
	var mu sync.Mutex
	var tok string
	var timer *time.Timer
	released := false
	deadline := time.Now().Add(total)
	holdMax, renewEarly := awakeHoldMax, awakeRenewEarly
	var take func()
	take = func() {
		left := time.Until(deadline)
		ms := int((min(left, holdMax) + time.Millisecond - 1) / time.Millisecond) // whole ms, rounded up
		a.ext.KeepAwake(why, ms, func(t string) {
			mu.Lock()
			defer mu.Unlock()
			switch {
			case released:
				a.ext.Release(t)
			case t != "":
				a.ext.Release(tok) // the hold this one replaces
				tok = t
			}
		})
		if left > holdMax {
			mu.Lock()
			if !released {
				timer = time.AfterFunc(holdMax-renewEarly, take)
			}
			mu.Unlock()
		}
	}
	take()
	return func() {
		mu.Lock()
		released = true
		t := tok
		tok = ""
		if timer != nil {
			timer.Stop()
		}
		mu.Unlock()
		a.ext.Release(t)
	}
}

// Overridable in tests.
var (
	awakeHoldMax    = 60 * time.Second // the host's cap per hold
	awakeRenewEarly = 5 * time.Second
	// A daemon without text to speech never answers `synthesize`. Below the watch receiver's 35 s
	// stall limit (docs/speaker-stream.md), so a stream that waits this long still ends with END.
	synthTimeout = 30 * time.Second
)
