package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var t0 = time.Unix(1760700000, 0)

func okTurn(q string) Turn {
	return Turn{Q: q, Content: []json.RawMessage{json.RawMessage(`{"type":"text","text":"a"}`)}, Text: "a"}
}

func TestPruneExpiryAndCap(t *testing.T) {
	h := newHistory()
	h.Conversations = []*Conversation{
		{ID: "00000001", Updated: t0.Add(-25 * time.Hour).Unix()},
		{ID: "00000002", Updated: t0.Add(-23 * time.Hour).Unix()},
	}
	h.Current = "00000001"
	if !h.prune(t0) {
		t.Fatal("prune reported no change")
	}
	if len(h.Conversations) != 1 || h.Conversations[0].ID != "00000002" || h.Current != "" {
		t.Fatalf("after prune: %+v current=%q", h.Conversations, h.Current)
	}
	for i := 0; i < 60; i++ {
		h.Conversations = append(h.Conversations, &Conversation{ID: convID(uint32(100 + i)), Updated: t0.Unix() - int64(60-i)})
	}
	h.prune(t0)
	if len(h.Conversations) != maxConversations {
		t.Fatalf("cap: %d", len(h.Conversations))
	}
	if h.find("00000002") != nil {
		t.Fatal("oldest conversation survived the cap")
	}
}

func TestIdleBase(t *testing.T) {
	h := newHistory()
	c, _ := h.addTurn(base{}, okTurn("q1"), modelHaiku, "", t0)
	if b := h.idleBase(t0.Add(5*time.Minute), 15*time.Minute, modelHaiku, ""); b.conv != c || b.n != 1 {
		t.Fatalf("within idle: %+v", b)
	}
	if b := h.idleBase(t0.Add(16*time.Minute), 15*time.Minute, modelHaiku, ""); b.conv != nil {
		t.Fatal("idle conversation continued")
	}
	if b := h.idleBase(t0.Add(time.Minute), 15*time.Minute, modelSonnet, ""); b.conv != nil {
		t.Fatal("conversation continued on another model")
	}
	if b := h.idleBase(t0.Add(time.Minute), 15*time.Minute, modelHaiku, prefixKey("", true)); b.conv != nil {
		t.Fatal("conversation continued with another tools/system prefix")
	}
	if nc, _ := h.addTurn(base{conv: c, n: 1}, okTurn("q2"), modelHaiku, prefixKey("be brief", false), t0); nc == c {
		t.Fatal("turn appended under another prefix")
	}
	h.Current = ""
	if b := h.idleBase(t0.Add(time.Minute), 15*time.Minute, modelHaiku, ""); b.conv != nil {
		t.Fatal("NEW_CONV ignored")
	}
}

func TestTurnBaseAndBranching(t *testing.T) {
	h := newHistory()
	c, _ := h.addTurn(base{}, okTurn("q1"), modelHaiku, "", t0)
	h.addTurn(base{conv: c, n: 1}, okTurn("q2"), modelHaiku, "", t0)
	failed := okTurn("q3")
	failed.Err, failed.Content = errBusy, nil
	h.addTurn(base{conv: c, n: 2}, failed, modelHaiku, "", t0)
	if len(c.Turns) != 3 {
		t.Fatalf("appends: %d turns", len(c.Turns))
	}

	// Reply on the last good card: base is turns 1..2.
	if b, q := h.turnBase(c.ID, 2, "why?"); b.n != 2 || q != "why?" {
		t.Fatalf("reply: %+v %q", b, q)
	}
	// Retry on an answer card re-asks its question on turns 1..N-1.
	if b, q := h.turnBase(c.ID, 2, " retry "); b.n != 1 || q != "q2" {
		t.Fatalf("retry: %+v %q", b, q)
	}
	// Reply on a failed turn: base 1..N-1; Retry re-asks, other text is asked as-is.
	if b, q := h.turnBase(c.ID, 3, "Retry"); b.n != 2 || q != "q3" {
		t.Fatalf("retry failed: %+v %q", b, q)
	}
	if b, q := h.turnBase(c.ID, 3, "other"); b.n != 2 || q != "other" {
		t.Fatalf("other on failed: %+v %q", b, q)
	}
	// Unknown conversation: new conversation with the text.
	if b, q := h.turnBase("deadbeef", 1, "hi"); b.conv != nil || q != "hi" {
		t.Fatalf("unknown: %+v %q", b, q)
	}

	// A base shorter than the conversation branches into a copy; the original is untouched.
	b, _ := h.turnBase(c.ID, 1, "branch")
	nc, n := h.addTurn(b, okTurn("branch"), modelHaiku, "", t0)
	if nc == c || n != 2 || nc.Turns[0].Q != "q1" || len(c.Turns) != 3 || h.Current != nc.ID {
		t.Fatalf("branch: id=%s n=%d turns=%d current=%s", nc.ID, n, len(c.Turns), h.Current)
	}

	// A full conversation starts a new one.
	full := &Conversation{ID: "0000abcd", Model: modelHaiku, Updated: t0.Unix()}
	for i := 0; i < maxTurns; i++ {
		full.Turns = append(full.Turns, okTurn("x"))
	}
	h.Conversations = append(h.Conversations, full)
	nc, n = h.addTurn(base{conv: full, n: maxTurns}, okTurn("y"), modelHaiku, "", t0)
	if nc == full || n != 1 {
		t.Fatalf("full conversation appended: n=%d", n)
	}
}

func TestReplaySkipsFailedTurns(t *testing.T) {
	failed := okTurn("bad")
	failed.Err, failed.Content = errTimeout, nil
	good := okTurn("good")
	good.Prompt = "Local time: x\n\ngood"
	msgs := replay([]Turn{failed, good})
	if len(msgs) != 2 || msgs[0].Role != "user" || msgs[1].Role != "assistant" {
		t.Fatalf("replay: %+v", msgs)
	}
	b, _ := json.Marshal(msgs[0])
	if want := `Local time: x\n\ngood`; !json.Valid(b) || !strings.Contains(string(b), want) {
		t.Fatalf("user turn not replayed as sent: %s", b)
	}
}

func TestParseToken(t *testing.T) {
	if c, n, ok := parseToken("1a2b3c4d:3"); !ok || c != "1a2b3c4d" || n != 3 {
		t.Fatalf("got %q %d %v", c, n, ok)
	}
	for _, bad := range []string{"anchor", "", "1a2b3c4d", "1a2b3c4d:0", "00000000:1", "xyz:1", "1a2b3c4d:x", "1a2b3c4d5:1"} {
		if _, _, ok := parseToken(bad); ok {
			t.Errorf("parseToken(%q) accepted", bad)
		}
	}
	if convNum("1a2b3c4d") != 0x1a2b3c4d || convID(0x1a2b3c4d) != "1a2b3c4d" || convID(0) != "" {
		t.Fatal("conv id round trip")
	}
}

func TestHistoryFile(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "stoandl", "assistant")
	f := historyFile{path: filepath.Join(dir, "history.json")}
	h := newHistory()
	h.addTurn(base{}, okTurn("q"), modelHaiku, "", t0)
	if err := f.save(h); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(f.path)
	dst, _ := os.Stat(dir)
	if st.Mode().Perm() != 0o600 || dst.Mode().Perm() != 0o700 {
		t.Fatalf("modes: file %v dir %v", st.Mode().Perm(), dst.Mode().Perm())
	}
	if got := f.load(t0.Add(time.Hour)); len(got.Conversations) != 1 || got.Current == "" {
		t.Fatalf("reload: %+v", got)
	}
	if got := f.load(t0.Add(25 * time.Hour)); len(got.Conversations) != 0 {
		t.Fatal("expired conversation loaded")
	}
	if got := (historyFile{}).load(t0); got == nil || (historyFile{}).save(h) != nil {
		t.Fatal("memory-only history")
	}
}
