package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
)

type origin int

const (
	originNotif origin = iota
	originApp
)

func (o origin) String() string {
	if o == originApp {
		return "app"
	}
	return "notif"
}

const anchorMaxAge = 20 * time.Hour

// job is the one request in flight.
type job struct {
	origin origin
	conv   string
	turn   int
	q      string
	ctx    context.Context
	cancel context.CancelCauseFunc
	sink   *appSink // app origin only
}

type assistant struct {
	ext       *Ext
	cfg       config
	client    anthropic.Client
	withApp   bool
	file      historyFile
	statePath string
	now       func() time.Time

	mu      sync.Mutex
	hist    *History
	job     *job
	replay  *appSink
	speech  *speech
	tts     ttsState
	appOpen bool
	caps    int          // the watchapp's CAPS, from its latest HELLO, ASK or PRESET
	spk     SpeakerWatch // the watchapp's speech receiver, from its latest HELLO
	spkRate float64      // the watch link's rate in the last reading, bytes/s
	state   appState
	// The latest synthesize sent, and the latest one answered (under mu): a timeout counts only when
	// no later call was answered, and a stop cancels a call still in flight.
	synthSent, synthAnswered uint64

	appMu   sync.Mutex // one AppMessage in flight
	inbound chan map[string]any
}

func newAssistant(ext *Ext) *assistant {
	return &assistant{ext: ext, now: localNow, hist: newHistory(), inbound: make(chan map[string]any, 32)}
}

// init reads the config and state. Called from OnInitialize, before any other event.
func (a *assistant) init() {
	a.cfg = parseConfig(a.ext.Config)
	if a.cfg.APIKey != "" {
		a.client = newClient(a.cfg.APIKey, a.cfg.BaseURL)
	}
	dir := stateDir()
	a.statePath = filepath.Join(dir, "state.json")
	histPath := filepath.Join(dir, "history.json")
	if a.cfg.KeepHistory {
		a.file = historyFile{path: histPath}
	} else {
		_ = os.Remove(histPath)
	}
	a.mu.Lock()
	a.hist = a.file.load(a.now())
	a.state = loadState(a.statePath)
	a.mu.Unlock()
	a.withApp = bundledPbw() != ""
	go a.appWorker()
	go a.sweep()
}

func bundledPbw() string {
	var dirs []string
	if exe, err := os.Executable(); err == nil {
		dirs = append(dirs, filepath.Dir(exe))
	}
	if wd, err := os.Getwd(); err == nil {
		dirs = append(dirs, wd)
	}
	for _, d := range dirs {
		p := filepath.Join(d, "assistant.pbw")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func (a *assistant) sweep() {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for range t.C {
		a.mu.Lock()
		if a.hist.prune(a.now()) {
			a.saveLocked()
		}
		a.mu.Unlock()
	}
}

func (a *assistant) saveLocked() {
	if err := a.file.save(a.hist); err != nil {
		a.ext.Logf("history: save failed: %v", err)
	}
}

// sendAnchor posts the "Ask Claude" card. A re-send is silent.
func (a *assistant) sendAnchor() {
	a.mu.Lock()
	silent := a.state.AnchorSentAt != 0
	a.state.AnchorSentAt = a.now().Unix()
	st := a.state
	a.mu.Unlock()
	a.ext.Notify(anchorCard(a.cfg.Presets, a.withApp, silent))
	if err := saveState(a.statePath, st); err != nil {
		a.ext.Logf("state: save failed: %v", err)
	}
}

func (a *assistant) onInitialize() {
	a.init()
	a.ext.RegisterApp(appUUID)
	if connected, _ := a.ext.Watch["connected"].(bool); connected {
		a.sendAnchor()
	}
}

func (a *assistant) onWatchConnected() {
	a.appClosed()
	a.ext.RegisterApp(appUUID)
	a.mu.Lock()
	stale := a.state.AnchorSentAt == 0 || a.now().Sub(time.Unix(a.state.AnchorSentAt, 0)) > anchorMaxAge
	a.mu.Unlock()
	if stale {
		a.sendAnchor()
	}
}

func (a *assistant) onAction(itemID, tok, action string) {
	if action == actionOpen {
		a.ext.LaunchApp(appUUID)
		return
	}
	k, ok := parseMore(action)
	conv, turn, tokOK := parseToken(tok)
	if !ok || !tokOK {
		return
	}
	a.mu.Lock()
	var t *Turn
	if c := a.hist.find(conv); c != nil && turn <= len(c.Turns) {
		tt := c.Turns[turn-1]
		t = &tt
	}
	a.mu.Unlock()
	if t == nil || t.Err != 0 {
		a.ext.Logf("more: %s is no longer in the history", tok)
		return
	}
	pages := paginate(t.Text)
	if k > len(pages) {
		return
	}
	a.ext.Notify(answerCard(conv, turn, t.Q, pages, k, a.cfg.Followups))
}

func (a *assistant) onReply(itemID, tok, text string) {
	conv, turn, ok := parseToken(tok)
	if !ok {
		conv, turn = "", 0
	}
	a.ask(originNotif, conv, turn, text)
}

// ask starts answering text. conv "" asks on the idle rule; otherwise it is a reply to turn of conv.
func (a *assistant) ask(o origin, conv string, turn int, text string) {
	text = strings.TrimSpace(text)
	if text == "" {
		a.ext.Logf("ask: empty question (%s)", o)
		if o == originApp {
			go a.appSend(nil, msgError(convNum(conv), turn, newAskError(errBadRequest, 0, "")))
		}
		return
	}
	now := a.now()
	a.mu.Lock()
	a.cancelLocked(errSuperseded)
	var b base
	q := text
	if conv == "" {
		b = a.hist.idleBase(now, a.cfg.Idle, a.cfg.Model, a.prefix())
	} else {
		b, q = a.hist.turnBase(conv, turn, text)
	}
	t := Turn{Q: q, Prompt: timeLine(now) + "\n\n" + q, At: now.Unix(), Err: errCancelled}
	c, n := a.hist.addTurn(b, t, a.cfg.Model, a.prefix(), now)
	req := askRequest{
		Model:       a.cfg.Model,
		Effort:      a.cfg.Effort,
		MaxTokens:   a.cfg.MaxTokens,
		WebSearch:   a.cfg.WebSearch,
		SystemExtra: a.cfg.systemExtra(),
		History:     append([]Turn(nil), c.Turns[:n-1]...),
		Prompt:      t.Prompt,
	}
	ctx, cancel := context.WithCancelCause(context.Background())
	j := &job{origin: o, conv: c.ID, turn: n, q: q, ctx: ctx, cancel: cancel}
	if o == originApp {
		j.sink = newAppSink(a, ctx, convNum(c.ID), n, q, statusThinking, a.appOpen)
	}
	a.job = j
	a.saveLocked()
	a.mu.Unlock()
	go a.run(j, req)
}

// cancelLocked abandons the running request, any replay to the watchapp and any reading aloud.
func (a *assistant) cancelLocked(cause error) {
	a.stopSpeechLocked()
	if a.job != nil {
		a.job.cancel(cause)
		a.job = nil
	}
	if a.replay != nil {
		a.replay.cancel()
		a.replay = nil
	}
}

func (a *assistant) prefix() string { return prefixKey(a.cfg.systemExtra(), a.cfg.WebSearch) }

func (a *assistant) run(j *job, req askRequest) {
	release := a.keepAwake("answering a question", awakeHoldMax)
	defer release()

	var res askResult
	var cl streamCleaner
	if a.cfg.APIKey == "" {
		res.Err = newAskError(errNoKey, 0, "")
	} else {
		res = runAsk(j.ctx, a.client, req, streamHooks{
			Text: func(d string) {
				if out := cl.Push(d); out != "" && j.sink != nil {
					j.sink.add(out)
				}
			},
			Searching: func() {
				if j.sink != nil {
					j.sink.searching()
				}
			},
			Todo: a.addTodo,
		})
	}
	if res.Err == nil {
		if out := cl.Flush(); out != "" && j.sink != nil {
			j.sink.add(out)
		}
	}
	text := cl.Text()
	cancelled := context.Cause(j.ctx) != nil
	if res.Err == nil && strings.TrimSpace(text) == "" && !cancelled {
		if res.Truncated {
			// Thinking used the whole max_tokens budget before any text.
			res.Err = newAskError(errServer, 0, "answer cut off: raise max_tokens")
		} else {
			res.Err = newAskError(errServer, 0, "empty answer")
		}
	}
	if res.Err == nil && res.Truncated {
		text = strings.TrimRight(text, " \n") + truncatedMark
	}

	a.mu.Lock()
	if c := a.hist.find(j.conv); c != nil && j.turn <= len(c.Turns) {
		t := &c.Turns[j.turn-1]
		switch {
		case cancelled:
			t.Err = errCancelled
		case res.Err != nil:
			t.Err = res.Err.Code
		default:
			t.Err, t.Content, t.Text, t.Stop, t.In, t.Out = 0, res.Content, text, res.Stop, res.In, res.Out
		}
		c.Updated = a.now().Unix()
		a.saveLocked()
	}
	a.mu.Unlock()

	a.logAsk(j, req.Model, res, cancelled)
	if cancelled {
		return
	}
	delivered := false
	if j.sink != nil {
		flags := 0
		if res.Truncated {
			flags |= flagTruncated
		}
		delivered = j.sink.finish(res.Err, flags)
	}
	a.mu.Lock()
	if a.job == j {
		a.job = nil
	}
	speak := delivered && res.Err == nil && a.job == nil && a.autoSpeakLocked()
	a.mu.Unlock()
	if speak {
		a.startSpeech(text, false)
	}
	if delivered || context.Cause(j.ctx) != nil {
		return
	}
	a.postResult(j, res.Err, text)
}

// todoWait is how long a to-do may take to save: the daemon's CalDAV request gives up within 45 s, and an
// unreachable server is usually known much sooner.
var todoWait = 30 * time.Second

// addTodo saves one to-do Claude asked for through the daemon and returns what the watch shows: Claude's
// confirmation with the saved due in words, a note when the to-do waits on this device, or why it wasn't
// saved.
func (a *assistant) addTodo(ctx context.Context, in todoInput) string {
	now := a.now()
	var wire, words string
	if s := strings.TrimSpace(in.Due); s != "" {
		due, ok := parseDue(s, now.Location())
		if !ok {
			a.ext.Logf("todo: unclear due %q", s)
			return "Couldn't save the to-do: unclear date"
		}
		if due.past(now) {
			a.ext.Logf("todo: past due %s", due.wire())
			return "Couldn't save the to-do: that time has passed"
		}
		wire, words = due.wire(), due.words(now, todoLanguage(a.cfg.Language, in.Lang))
	}
	type outcome struct{ status, problem, err string }
	done := make(chan outcome, 1)
	a.ext.AddTodo(strings.TrimSpace(in.Title), wire, strings.TrimSpace(in.Note),
		func(status, problem, err string) { done <- outcome{status, problem, err} })
	var o outcome
	select {
	case o = <-done:
	case <-time.After(todoWait):
		o.err = "no answer from stoandl"
	case <-ctx.Done():
		o.err = "cancelled"
	}
	confirmation := strings.TrimSpace(in.Confirmation)
	if confirmation == "" {
		confirmation = "Noted: " + strings.TrimSpace(in.Title) + "."
	}
	confirmation = todoConfirmation(confirmation, words)
	logged := wire
	if logged == "" {
		logged = "none"
	}
	a.ext.Logf("todo: status=%s problem=%q err=%q due=%s", o.status, o.problem, o.err, logged)
	switch {
	case o.err == "off":
		return "No to-do list chosen: pick one in stoandl, Settings → Calendar → To-do list."
	case strings.HasPrefix(o.err, "method not found"):
		return "Couldn't save the to-do: this stoandl is too old for to-dos."
	case o.err != "":
		return "Couldn't save the to-do: " + o.err
	case o.status == "queued" && o.problem == "":
		return confirmation + " (Saved offline, syncs later.)"
	case o.status == "queued":
		return confirmation + " (Not synced yet: " + o.problem + ".)"
	}
	return confirmation
}

func (a *assistant) postResult(j *job, e *askError, text string) {
	if e != nil {
		if e.Code != errCancelled && e.Code != errBadRequest {
			a.ext.Notify(errorCard(j.conv, j.turn, e))
		}
		return
	}
	pages := paginate(text)
	a.ext.Notify(answerCard(j.conv, j.turn, j.q, pages, 1, a.cfg.Followups))
}

func (a *assistant) logAsk(j *job, model string, res askResult, cancelled bool) {
	stop := res.Stop
	if cancelled {
		stop = "cancelled"
	} else if res.Err != nil {
		stop = "error:" + res.Err.Title
	}
	q := []rune(j.q)
	if len(q) > 40 {
		q = q[:40]
	}
	a.ext.Logf("%q ask conv=%s turn=%d model=%s ttft=%d total=%d in=%d out=%d searches=%d cost=$%.5f stop=%s sink=%s",
		string(q), j.conv, j.turn, model, res.TTFT.Milliseconds(), res.Total.Milliseconds(),
		res.In, res.Out, res.Searches, res.Cost, stop, j.origin)
}
