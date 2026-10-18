package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
)

// fakeAPI is an httptest stand-in for POST /v1/messages with stream=true. Each request pops the next
// scripted response.
type fakeAPI struct {
	srv *httptest.Server

	mu        sync.Mutex
	responses []fakeResponse
	bodies    []map[string]any
	headers   []http.Header
}

type fakeResponse struct {
	status int    // non-200: JSON error body
	errTyp string // error type for non-200
	events []string
	delay  time.Duration // before the first event
}

func newFakeAPI(t *testing.T, responses ...fakeResponse) *fakeAPI {
	f := &fakeAPI{responses: responses}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeAPI) handle(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var m map[string]any
	_ = json.Unmarshal(body, &m)
	f.mu.Lock()
	f.bodies = append(f.bodies, m)
	f.headers = append(f.headers, r.Header.Clone())
	var resp fakeResponse
	if len(f.responses) > 0 {
		resp, f.responses = f.responses[0], f.responses[1:]
	} else {
		resp = textResponse("default")
	}
	f.mu.Unlock()
	if resp.status != 0 && resp.status != 200 {
		w.Header().Set("content-type", "application/json")
		w.WriteHeader(resp.status)
		fmt.Fprintf(w, `{"type":"error","error":{"type":%q,"message":"something went wrong here"}}`, resp.errTyp)
		return
	}
	w.Header().Set("content-type", "text/event-stream")
	w.WriteHeader(200)
	fl := w.(http.Flusher)
	select {
	case <-time.After(resp.delay):
	case <-r.Context().Done():
		return
	}
	for _, ev := range resp.events {
		var typ struct {
			Type string `json:"type"`
		}
		_ = json.Unmarshal([]byte(ev), &typ)
		fmt.Fprintf(w, "event: %s\ndata: %s\n\n", typ.Type, ev)
		fl.Flush()
	}
}

func (f *fakeAPI) requests() []map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]map[string]any(nil), f.bodies...)
}

func sseMessage(stop string, in, out int, blocks ...[]string) []string {
	evs := []string{fmt.Sprintf(`{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":%d,"output_tokens":1}}}`, in)}
	for i, b := range blocks {
		evs = append(evs, strings.ReplaceAll(b[0], "IDX", fmt.Sprint(i)))
		for _, d := range b[1:] {
			evs = append(evs, strings.ReplaceAll(d, "IDX", fmt.Sprint(i)))
		}
		evs = append(evs, fmt.Sprintf(`{"type":"content_block_stop","index":%d}`, i))
	}
	return append(evs,
		fmt.Sprintf(`{"type":"message_delta","delta":{"stop_reason":%q,"stop_sequence":null},"usage":{"output_tokens":%d}}`, stop, out),
		`{"type":"message_stop"}`)
}

func textBlock(parts ...string) []string {
	b := []string{`{"type":"content_block_start","index":IDX,"content_block":{"type":"text","text":""}}`}
	for _, p := range parts {
		js, _ := json.Marshal(p)
		b = append(b, fmt.Sprintf(`{"type":"content_block_delta","index":IDX,"delta":{"type":"text_delta","text":%s}}`, js))
	}
	return b
}

func thinkingBlock() []string {
	return []string{
		`{"type":"content_block_start","index":IDX,"content_block":{"type":"thinking","thinking":"","signature":""}}`,
		`{"type":"content_block_delta","index":IDX,"delta":{"type":"thinking_delta","thinking":"hmm"}}`,
		`{"type":"content_block_delta","index":IDX,"delta":{"type":"signature_delta","signature":"SIG123"}}`,
	}
}

func searchBlock() []string {
	return []string{
		`{"type":"content_block_start","index":IDX,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}`,
		`{"type":"content_block_delta","index":IDX,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"x\"}"}}`,
	}
}

func textResponse(parts ...string) fakeResponse {
	return fakeResponse{events: sseMessage("end_turn", 20, 10, textBlock(parts...))}
}

func testClient(f *fakeAPI) askRequest {
	return askRequest{Model: modelHaiku, Effort: "low", MaxTokens: 2048, Prompt: "Local time: x\n\nhow far is the moon"}
}

func TestRunAskStreams(t *testing.T) {
	f := newFakeAPI(t, fakeResponse{events: sseMessage("end_turn", 30, 12, thinkingBlock(), textBlock("About **384,400", " km**."))})
	var got strings.Builder
	res := runAsk(context.Background(), newClient("sk-test", f.srv.URL), testClient(f), streamHooks{Text: func(d string) { got.WriteString(d) }})
	if res.Err != nil {
		t.Fatalf("error: %+v", res.Err)
	}
	if res.Text != "About **384,400 km**." || got.String() != res.Text || cleanText(res.Text) != "About 384,400 km." {
		t.Fatalf("text %q streamed %q", res.Text, got.String())
	}
	if res.Stop != "end_turn" || res.In != 30 || res.Out != 12 || res.TTFT <= 0 || res.Truncated {
		t.Fatalf("result: %+v", res)
	}
	if len(res.Content) != 2 || !strings.Contains(string(res.Content[0]), "SIG123") {
		t.Fatalf("content not kept verbatim: %s", res.Content)
	}
	req := f.requests()[0]
	if req["stream"] != true || req["model"] != modelHaiku || req["output_config"] != nil || req["thinking"] != nil {
		t.Fatalf("haiku request: %v", req)
	}
	if sys := fmt.Sprint(req["system"]); !strings.Contains(sys, "smartwatch") {
		t.Fatalf("system prompt missing: %s", sys)
	}
}

func TestParamsPerModel(t *testing.T) {
	marshal := func(r askRequest) map[string]any {
		b, err := json.Marshal(r.params())
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		return m
	}
	r := askRequest{Model: modelSonnet, Effort: "medium", MaxTokens: 100, WebSearch: true, Prompt: "q", SystemExtra: "Be kind."}
	m := marshal(r)
	if fmt.Sprint(m["output_config"]) != "map[effort:medium]" || m["thinking"] != nil || m["fallbacks"] != nil {
		t.Fatalf("sonnet: %v", m)
	}
	if tools := fmt.Sprint(m["tools"]); !strings.Contains(tools, "name:web_lookup") || strings.Contains(tools, "web_search") {
		t.Fatalf("sonnet regular request carries only the lookup tool: %s", tools)
	}
	r.Searching = true
	if tools := fmt.Sprint(marshal(r)["tools"]); !strings.Contains(tools, "web_search_20260209") || !strings.Contains(tools, "max_uses:1") || strings.Contains(tools, "web_lookup") {
		t.Fatalf("sonnet search request: %s", tools)
	}
	if !strings.Contains(fmt.Sprint(m["system"]), "\n\nBe kind.") {
		t.Fatalf("system_prompt_extra: %v", m["system"])
	}
	r.Searching = false
	r.Model = modelOpus
	p := r.params()
	m = marshal(r)
	if m["fallbacks"] != "default" || len(p.Betas) != 1 || p.Betas[0] != "server-side-fallback-2026-07-01" || m["thinking"] != nil {
		t.Fatalf("opus: %v betas %v", m, p.Betas)
	}
	r.Model, r.Searching = modelHaiku, true
	m = marshal(r)
	if m["output_config"] != nil || !strings.Contains(fmt.Sprint(m["tools"]), "web_search_20250305") {
		t.Fatalf("haiku: %v", m)
	}
}

func TestRunAskStopReasons(t *testing.T) {
	cases := []struct {
		name  string
		resp  fakeResponse
		code  int
		trunc bool
	}{
		{"refusal", fakeResponse{events: sseMessage("refusal", 5, 2, textBlock("I can"))}, errRefused, false},
		{"max_tokens", fakeResponse{events: sseMessage("max_tokens", 5, 2, textBlock("Long answer"))}, 0, true},
		{"401", fakeResponse{status: 401, errTyp: "authentication_error"}, errKeyBad, false},
		{"529", fakeResponse{status: 529, errTyp: "overloaded_error"}, errBusy, false},
		{"400", fakeResponse{status: 400, errTyp: "invalid_request_error"}, errServer, false},
	}
	for _, c := range cases {
		f := newFakeAPI(t, c.resp, c.resp, c.resp)
		res := runAsk(context.Background(), newClient("k", f.srv.URL), testClient(f), streamHooks{})
		code := 0
		if res.Err != nil {
			code = res.Err.Code
		}
		if code != c.code || res.Truncated != c.trunc {
			t.Errorf("%s: code %d truncated %v (%+v)", c.name, code, res.Truncated, res.Err)
		}
		if c.code == errServer && (res.Err.Title != "Claude: error 400" || res.Err.Hint != "something went wrong here") {
			t.Errorf("server error card: %+v", res.Err)
		}
	}
}

func TestRunAskPauseTurnResends(t *testing.T) {
	f := newFakeAPI(t,
		fakeResponse{events: sseMessage("pause_turn", 10, 3, textBlock("Let me check."), searchBlock())},
		fakeResponse{events: sseMessage("end_turn", 40, 8, textBlock("It is sunny."))},
	)
	searched := false
	r := testClient(f)
	r.WebSearch = true
	res := runAsk(context.Background(), newClient("k", f.srv.URL), r, streamHooks{Searching: func() { searched = true }})
	if res.Err != nil || !searched {
		t.Fatalf("err %+v searched %v", res.Err, searched)
	}
	if res.Text != "Let me check.\n\nIt is sunny." || res.In != 50 || res.Out != 11 || len(res.Content) != 3 {
		t.Fatalf("result %q in=%d out=%d content=%d", res.Text, res.In, res.Out, len(res.Content))
	}
	reqs := f.requests()
	if len(reqs) != 2 {
		t.Fatalf("%d requests", len(reqs))
	}
	msgs := reqs[1]["messages"].([]any)
	if last := msgs[len(msgs)-1].(map[string]any); last["role"] != "assistant" {
		t.Fatalf("resend doesn't end with the partial assistant turn: %v", last)
	}
}

func TestRunAskTimeouts(t *testing.T) {
	defer func(a, b time.Duration) { firstTextTimeout, requestTimeout = a, b }(firstTextTimeout, requestTimeout)
	firstTextTimeout, requestTimeout = 100*time.Millisecond, 5*time.Second
	f := newFakeAPI(t, fakeResponse{delay: 2 * time.Second, events: sseMessage("end_turn", 1, 1, textBlock("late"))})
	res := runAsk(context.Background(), newClient("k", f.srv.URL), testClient(f), streamHooks{})
	if res.Err == nil || res.Err.Code != errTimeout {
		t.Fatalf("first-text timeout: %+v", res.Err)
	}

	// User cancel.
	f = newFakeAPI(t, fakeResponse{delay: 2 * time.Second, events: sseMessage("end_turn", 1, 1, textBlock("late"))})
	ctx, cancel := context.WithCancelCause(context.Background())
	time.AfterFunc(50*time.Millisecond, func() { cancel(errUserCancel) })
	res = runAsk(ctx, newClient("k", f.srv.URL), testClient(f), streamHooks{})
	if res.Err == nil || res.Err.Code != errCancelled {
		t.Fatalf("cancel: %+v", res.Err)
	}
}

// --- end to end through the extension ---

// syncBuf collects the extension's stdout lines.
type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuf) lines() []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	sc := bufio.NewScanner(bytes.NewReader(s.b.Bytes()))
	for sc.Scan() {
		var m map[string]any
		if json.Unmarshal(sc.Bytes(), &m) == nil {
			out = append(out, m)
		}
	}
	return out
}

func newTestAssistant(t *testing.T, f *fakeAPI, cfg map[string]string) (*assistant, *syncBuf) {
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	out := &syncBuf{}
	e := NewExt()
	e.out = bufio.NewWriter(out)
	e.Config = map[string]string{"api_key": "sk-test", "base_url": f.srv.URL}
	for k, v := range cfg {
		e.Config[k] = v
	}
	a := newAssistant(e)
	a.init()
	return a, out
}

func waitFor(t *testing.T, out *syncBuf, what string, pred func(map[string]any) bool) map[string]any {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, l := range out.lines() {
			if pred(l) {
				return l
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; got %v", what, out.lines())
	return nil
}

func isNotify(l map[string]any) bool { return l["method"] == "notify" }

func params(l map[string]any) map[string]any { p, _ := l["params"].(map[string]any); return p }

func TestReplyAnswersAsNotification(t *testing.T) {
	f := newFakeAPI(t,
		textResponse("**Soft:** 6 minutes.", " Hard: 10."),
		textResponse("In Fahrenheit, too."),
	)
	a, out := newTestAssistant(t, f, nil)
	a.onReply("item1", anchorToken, "how long to boil an egg")
	n := params(waitFor(t, out, "answer card", isNotify))
	if n["title"] != "how long to boil an egg" || n["body"] != "Soft: 6 minutes. Hard: 10." || n["appName"] != "Claude" {
		t.Fatalf("card: %v", n)
	}
	tok, _ := n["extToken"].(string)
	conv, turn, ok := parseToken(tok)
	if !ok || turn != 1 {
		t.Fatalf("extToken %q", tok)
	}
	reply := n["reply"].(map[string]any)
	if reply["label"] != "Reply" || fmt.Sprint(reply["cannedReplies"]) != "[Explain more Shorter Retry]" {
		t.Fatalf("reply: %v", reply)
	}
	keep := waitFor(t, out, "keepAwake", func(l map[string]any) bool { return l["method"] == "keepAwake" })
	if p := params(keep); p["maxMs"] != float64(60000) {
		t.Fatalf("keepAwake: %v", p)
	}

	// A follow-up on that card replays turn 1 verbatim and appends turn 2.
	a.onReply("item2", tok, "and in Fahrenheit?")
	waitFor(t, out, "second card", func(l map[string]any) bool {
		return isNotify(l) && params(l)["extToken"] == token(conv, 2)
	})
	reqs := f.requests()
	msgs := reqs[1]["messages"].([]any)
	if len(msgs) != 3 {
		t.Fatalf("follow-up sent %d messages", len(msgs))
	}
	first, _ := json.Marshal(reqs[0]["messages"].([]any)[0])
	again, _ := json.Marshal(msgs[0])
	if string(first) != string(again) {
		t.Fatalf("history edited:\n%s\n%s", first, again)
	}
	a.mu.Lock()
	c := a.hist.find(conv)
	a.mu.Unlock()
	if c == nil || len(c.Turns) != 2 || c.Turns[0].Err != 0 || c.Turns[0].Text != "Soft: 6 minutes. Hard: 10." {
		t.Fatalf("history: %+v", c)
	}
}

func TestErrorCard(t *testing.T) {
	f := newFakeAPI(t, fakeResponse{status: 401, errTyp: "authentication_error"})
	a, out := newTestAssistant(t, f, nil)
	a.onReply("i", anchorToken, "hello")
	n := params(waitFor(t, out, "error card", isNotify))
	if n["title"] != "Claude: key rejected" || n["body"] != "Check the API key in stoandl." {
		t.Fatalf("error card: %v", n)
	}
	if fmt.Sprint(n["reply"].(map[string]any)["cannedReplies"]) != "[Retry]" {
		t.Fatalf("error card reply: %v", n["reply"])
	}
}

// The watchapp path: HELLO, ASK, then STATUS, CHUNKs and DONE, each sent after the previous ACK.
func TestAppStreamsChunks(t *testing.T) {
	answer := "About 384,400 km. That is roughly thirty Earths lined up. Light takes a little over a second to cross it."
	f := newFakeAPI(t, textResponse(answer[:40], answer[40:]))
	a, out := newTestAssistant(t, f, nil)

	stop := make(chan struct{})
	defer close(stop)
	go func() { // the watch: ACK every AppMessage, one at a time
		acked := map[float64]bool{}
		for {
			select {
			case <-stop:
				return
			case <-time.After(5 * time.Millisecond):
			}
			for _, l := range out.lines() {
				id, _ := l["id"].(float64)
				if l["method"] != "sendAppMessage" || acked[id] {
					continue
				}
				acked[id] = true
				a.ext.pendMu.Lock()
				cb := a.ext.pending[int(id)]
				delete(a.ext.pending, int(id))
				a.ext.pendMu.Unlock()
				if cb != nil {
					cb(map[string]any{"result": "ack"})
				}
			}
		}
	}()

	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello), "8": float64(1), "6": float64(768), "7": float64(1)})
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdAsk), "1": "how far is the moon", "4": float64(0), "3": float64(0)})
	waitFor(t, out, "DONE", func(l map[string]any) bool {
		return l["method"] == "sendAppMessage" && appCmd(l) == cmdDone
	})
	var cmds []int
	var text strings.Builder
	var done map[string]any
	for _, l := range out.lines() {
		if l["method"] != "sendAppMessage" {
			continue
		}
		cmds = append(cmds, appCmd(l))
		d := params(l)["data"].(map[string]any)
		switch appCmd(l) {
		case cmdChunk:
			c := d["1"].(map[string]any)["v"].(string)
			if len(c) > chunkBytes {
				t.Fatalf("chunk of %d B", len(c))
			}
			text.WriteString(c)
		case cmdDone:
			done = d
		}
	}
	if cmds[0] != cmdPresets || cmds[1] != cmdStatus || cmds[2] != cmdStatus || cmds[len(cmds)-1] != cmdDone {
		t.Fatalf("message order %v", cmds)
	}
	if text.String() != answer {
		t.Fatalf("streamed %q", text.String())
	}
	if done["5"].(map[string]any)["v"] != float64(0) {
		t.Fatalf("DONE flags %v", done)
	}
	// Answered in the app: no notification card.
	time.Sleep(50 * time.Millisecond)
	for _, l := range out.lines() {
		if isNotify(l) {
			t.Fatalf("app answer also posted a card: %v", l)
		}
	}
}

func appCmd(l map[string]any) int {
	d := params(l)["data"].(map[string]any)
	v, _ := d["0"].(map[string]any)["v"].(float64)
	return int(v)
}

// The app closes mid-answer (a CHUNK times out): the finished answer arrives as a notification card.
func TestAppClosedFallsBackToCard(t *testing.T) {
	f := newFakeAPI(t, textResponse("About 384,400 km. That is far away from here."))
	a, out := newTestAssistant(t, f, nil)
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		answered := map[float64]bool{}
		for {
			select {
			case <-stop:
				return
			case <-time.After(5 * time.Millisecond):
			}
			for _, l := range out.lines() {
				id, _ := l["id"].(float64)
				if l["method"] != "sendAppMessage" || answered[id] {
					continue
				}
				answered[id] = true
				r := "ack"
				if appCmd(l) == cmdChunk {
					r = "timeout"
				}
				a.ext.pendMu.Lock()
				cb := a.ext.pending[int(id)]
				delete(a.ext.pending, int(id))
				a.ext.pendMu.Unlock()
				if cb != nil {
					cb(map[string]any{"result": r})
				}
			}
		}
	}()
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdHello)})
	a.onAppMessage(appUUID, map[string]any{"0": float64(cmdAsk), "1": "how far is the moon"})
	n := params(waitFor(t, out, "fallback card", isNotify))
	if n["body"] != "About 384,400 km. That is far away from here." || !strings.HasSuffix(n["extToken"].(string), ":1") {
		t.Fatalf("card %v", n)
	}
	a.mu.Lock()
	open := a.appOpen
	a.mu.Unlock()
	if open {
		t.Fatal("app still marked open")
	}
}

func TestCards(t *testing.T) {
	n := anchorCard([]string{"Tell me a fun fact"}, true, true)
	if n.ExtToken != anchorToken || n.ReplyLabel != "Ask" || n.Vibe != vibeSilent || len(n.Actions) != 1 || n.Actions[0][0] != actionOpen {
		t.Fatalf("anchor %+v", n)
	}
	pages := []string{"one", "two", "three"}
	p1 := answerCard("1a2b3c4d", 2, "a question that is definitely much longer than sixty-four bytes in total", pages, 1, nil)
	if len(p1.Title) > titleBytes || p1.Vibe != "" || p1.Actions[0] != [2]string{"more:2", "More (2/3)"} || p1.ReplaceID != "assistant:1a2b3c4d:2:1" {
		t.Fatalf("page 1 %+v", p1)
	}
	p3 := answerCard("1a2b3c4d", 2, "q", pages, 3, nil)
	if len(p3.Actions) != 0 || p3.Vibe != vibeSilent || p3.Body != "three" {
		t.Fatalf("page 3 %+v", p3)
	}
	if k, ok := parseMore("more:3"); !ok || k != 3 {
		t.Fatal("parseMore")
	}
	if _, ok := parseMore("more:1"); ok {
		t.Fatal("parseMore accepted page 1")
	}
}

func TestEchoableAfterFallback(t *testing.T) {
	raw := func(s ...string) []json.RawMessage {
		out := make([]json.RawMessage, len(s))
		for i, b := range s {
			out[i] = json.RawMessage(b)
		}
		return out
	}
	plain := raw(`{"type":"thinking","thinking":"","signature":"s"}`, `{"type":"text","text":"hi"}`)
	if got := echoable(plain); len(got) != 2 {
		t.Fatalf("no fallback: %d blocks", len(got))
	}
	got := echoable(raw(
		`{"type":"thinking","thinking":"","signature":"s"}`,
		`{"type":"server_tool_use","id":"a"}`,
		`{"type":"web_search_tool_result","tool_use_id":"a"}`,
		`{"type":"server_tool_use","id":"b"}`,
		`{"type":"text","text":"partial sen"}`,
		`{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-5"}}`,
		`{"type":"thinking","thinking":"","signature":"t"}`,
		`{"type":"text","text":"tence"}`,
	))
	var types []string
	for _, b := range got {
		var h struct{ Type, ID string }
		_ = json.Unmarshal(b, &h)
		types = append(types, h.Type+h.ID)
	}
	want := "server_tool_usea web_search_tool_result text fallback thinking text"
	if strings.Join(types, " ") != want {
		t.Fatalf("echoed %v, want %s", types, want)
	}
}

func TestUsageCostCountsDeclinedAttempts(t *testing.T) {
	var u anthropic.BetaUsage
	if err := json.Unmarshal([]byte(`{"input_tokens":1000,"output_tokens":1000,
		"iterations":[{"type":"message","model":"claude-opus-5-5","input_tokens":1000,"output_tokens":500},
		{"type":"fallback_message","model":"claude-opus-5","input_tokens":1000,"output_tokens":1000}]}`), &u); err != nil {
		t.Fatal(err)
	}
	want := cost(modelOpus, 1000, 500, 0) + cost("claude-opus-5", 1000, 1000, 0)
	if c := usageCost(modelOpus, u); math.Abs(c-want) > 1e-12 {
		t.Fatalf("cost %v, want %v", c, want)
	}
	if want <= cost(modelOpus, 1000, 1000, 0) {
		t.Fatal("opus 5 priced like opus 5.5")
	}
	var top anthropic.BetaUsage
	_ = json.Unmarshal([]byte(`{"input_tokens":1000,"output_tokens":1000}`), &top)
	if c := usageCost(modelHaiku, top); math.Abs(c-cost(modelHaiku, 1000, 1000, 0)) > 1e-12 {
		t.Fatalf("no iterations: %v", c)
	}
}

func TestSystemExtraCarriesLanguage(t *testing.T) {
	if got := parseConfig(map[string]string{}).systemExtra(); got != "" {
		t.Fatalf("no language, no extra: %q", got)
	}
	c := parseConfig(map[string]string{"language": "German", "system_prompt_extra": "Be kind."})
	got := c.systemExtra()
	if !strings.HasPrefix(got, "The user speaks German: always reply in German") || !strings.HasSuffix(got, "\n\nBe kind.") {
		t.Fatalf("extra: %q", got)
	}
	if prefixKey(c.systemExtra(), false) == prefixKey("", false) {
		t.Fatal("a language change must start a new conversation")
	}
	if !strings.Contains(systemPrompt, "40 words") {
		t.Fatal("watch rules missing from the system prompt")
	}
}

func lookupBlock() []string {
	return []string{
		`{"type":"content_block_start","index":IDX,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_lookup","input":{}}}`,
		`{"type":"content_block_delta","index":IDX,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"bitcoin price\"}"}}`,
	}
}

func TestLookupEscalatesToOneOffSearch(t *testing.T) {
	f := newFakeAPI(t,
		fakeResponse{events: sseMessage("tool_use", 300, 15, lookupBlock())},
		fakeResponse{events: sseMessage("end_turn", 6000, 20, searchBlock(), textBlock("About $97,000 right now."))},
	)
	req := testClient(f)
	req.Model, req.WebSearch = modelSonnet, true
	req.History = []Turn{{Q: "hi", Prompt: "Local time: x\n\nhi", Text: "Hello!", Content: thinkingAndText("Hello!")}}
	searching := 0
	res := runAsk(context.Background(), newClient("sk-test", f.srv.URL), req, streamHooks{Searching: func() { searching++ }})
	if res.Err != nil || res.Text != "About $97,000 right now." || res.In != 6300 || searching < 1 {
		t.Fatalf("result %+v searching=%d", res, searching)
	}
	if len(res.Content) != 1 || strings.Contains(string(res.Content[0]), "server_tool_use") {
		t.Fatalf("a searched answer is stored as text only: %s", res.Content)
	}
	reqs := f.requests()
	if len(reqs) != 2 {
		t.Fatalf("requests: %d", len(reqs))
	}
	first, second := fmt.Sprint(reqs[0]["tools"]), fmt.Sprint(reqs[1]["tools"])
	if !strings.Contains(first, "web_lookup") || strings.Contains(first, "web_search_2") {
		t.Fatalf("first request tools: %s", first)
	}
	if !strings.Contains(second, "web_search_20260209") || strings.Contains(second, "web_lookup") {
		t.Fatalf("search request tools: %s", second)
	}
	if hist := fmt.Sprint(reqs[1]["messages"]); strings.Contains(hist, "thinking") || !strings.Contains(hist, "Hello!") {
		t.Fatalf("search request replays history as plain text: %s", hist)
	}
}

func thinkingAndText(text string) []json.RawMessage {
	t, _ := json.Marshal(map[string]string{"type": "text", "text": text})
	return []json.RawMessage{json.RawMessage(`{"type":"thinking","thinking":"","signature":"SIG9"}`), t}
}
