package main

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	historyTTL       = 24 * time.Hour
	maxConversations = 50
	maxTurns         = 30
)

// Turn is one question and its answer. Content is the assistant content array exactly as the API
// returned it (thinking blocks and signatures included); it is replayed unchanged.
type Turn struct {
	Q       string            `json:"q"`
	Prompt  string            `json:"prompt,omitempty"` // the user turn as sent (time line + question)
	At      int64             `json:"at"`
	Content []json.RawMessage `json:"content,omitempty"`
	Text    string            `json:"text,omitempty"`
	Stop    string            `json:"stop,omitempty"`
	In      int64             `json:"in,omitempty"`
	Out     int64             `json:"out,omitempty"`
	Err     int               `json:"err"`
}

type Conversation struct {
	ID    string `json:"id"`
	Model string `json:"model"`
	// Prefix fingerprints the system prompt and tools (prefixKey): replayed thinking blocks are bound
	// to them, so a conversation only continues under the same prefix.
	Prefix  string `json:"prefix,omitempty"`
	Created int64  `json:"created"`
	Updated int64  `json:"updated"`
	Turns   []Turn `json:"turns"`
}

// History holds the conversations of the last 24 h. Not safe for concurrent use: the caller locks.
type History struct {
	V             int             `json:"v"`
	Current       string          `json:"current"`
	Conversations []*Conversation `json:"conversations"`
}

func newHistory() *History { return &History{V: 1} }

func (h *History) find(id string) *Conversation {
	if id == "" {
		return nil
	}
	for _, c := range h.Conversations {
		if c.ID == id {
			return c
		}
	}
	return nil
}

// prune drops conversations idle for more than 24 h and the oldest beyond the cap. It reports
// whether anything changed.
func (h *History) prune(now time.Time) bool {
	cutoff := now.Add(-historyTTL).Unix()
	kept := h.Conversations[:0]
	changed := false
	for _, c := range h.Conversations {
		if c.Updated < cutoff {
			changed = true
			continue
		}
		kept = append(kept, c)
	}
	h.Conversations = kept
	if len(h.Conversations) > maxConversations {
		sort.SliceStable(h.Conversations, func(i, j int) bool { return h.Conversations[i].Updated < h.Conversations[j].Updated })
		h.Conversations = h.Conversations[len(h.Conversations)-maxConversations:]
		changed = true
	}
	if h.Current != "" && h.find(h.Current) == nil {
		h.Current = ""
		changed = true
	}
	return changed
}

// base names the earlier turns a new question is asked on: turns[:n] of conv (conv nil = none).
type base struct {
	conv *Conversation
	n    int
}

// idleBase is the base for a question with no card or turn attached: the current conversation if
// it was used within idle and is on the same model and prefix, else a new conversation.
func (h *History) idleBase(now time.Time, idle time.Duration, model, prefix string) base {
	c := h.find(h.Current)
	if c == nil || c.Model != model || c.Prefix != prefix || now.Sub(time.Unix(c.Updated, 0)) >= idle || len(c.Turns) == 0 {
		return base{}
	}
	return base{conv: c, n: len(c.Turns)}
}

// turnBase resolves a reply to turn n of conversation id (B7): it returns the base and the
// question to ask. An unknown conversation or turn starts a new conversation with text.
func (h *History) turnBase(id string, n int, text string) (base, string) {
	c := h.find(id)
	if c == nil || n < 1 || n > len(c.Turns) {
		return base{}, text
	}
	t := c.Turns[n-1]
	if t.Err != 0 {
		if isRetry(text) {
			return base{conv: c, n: n - 1}, t.Q
		}
		return base{conv: c, n: n - 1}, text
	}
	if isRetry(text) {
		return base{conv: c, n: n - 1}, t.Q
	}
	return base{conv: c, n: n}, text
}

// addTurn places a new turn on b: appended when b is all of its conversation, otherwise in a new
// conversation holding a copy of the base (append-only branching). The receiving conversation
// becomes current. It returns the conversation and the turn's 1-based number.
func (h *History) addTurn(b base, t Turn, model, prefix string, now time.Time) (*Conversation, int) {
	c := b.conv
	if c != nil && (c.Model != model || c.Prefix != prefix || b.n >= maxTurns) {
		c, b = nil, base{}
	}
	if c == nil || b.n != len(c.Turns) {
		nc := &Conversation{ID: h.newID(), Model: model, Prefix: prefix, Created: now.Unix()}
		if c != nil {
			nc.Turns = append([]Turn(nil), c.Turns[:b.n]...)
		}
		h.Conversations = append(h.Conversations, nc)
		c = nc
	}
	c.Turns = append(c.Turns, t)
	c.Updated = now.Unix()
	h.Current = c.ID
	h.prune(now)
	return c, len(c.Turns)
}

func (h *History) newID() string {
	for {
		var b [4]byte
		_, _ = rand.Read(b[:])
		v := binary.BigEndian.Uint32(b[:])
		if v == 0 {
			continue
		}
		id := fmt.Sprintf("%08x", v)
		if h.find(id) == nil {
			return id
		}
	}
}

// convNum is the conversation id as the watchapp's uint32 CONV.
func convNum(id string) uint32 {
	v, err := strconv.ParseUint(id, 16, 32)
	if err != nil {
		return 0
	}
	return uint32(v)
}

func convID(n uint32) string {
	if n == 0 {
		return ""
	}
	return fmt.Sprintf("%08x", n)
}

// parseToken splits an answer card's extToken "<conv>:<turn>".
func parseToken(tok string) (conv string, turn int, ok bool) {
	i := strings.IndexByte(tok, ':')
	if i <= 0 {
		return "", 0, false
	}
	conv = tok[:i]
	if len(conv) != 8 || convNum(conv) == 0 {
		return "", 0, false
	}
	turn, err := strconv.Atoi(tok[i+1:])
	if err != nil || turn < 1 {
		return "", 0, false
	}
	return conv, turn, true
}

func token(conv string, turn int) string { return fmt.Sprintf("%s:%d", conv, turn) }

// historyFile persists History atomically with owner-only permissions. A zero path keeps history in
// memory only.
type historyFile struct{ path string }

func stateDir() string {
	dir := os.Getenv("XDG_STATE_HOME")
	if dir == "" {
		home, _ := os.UserHomeDir()
		dir = filepath.Join(home, ".local", "state")
	}
	return filepath.Join(dir, "stoandl", "assistant")
}

func (f historyFile) load(now time.Time) *History {
	h := newHistory()
	if f.path == "" {
		return h
	}
	b, err := os.ReadFile(f.path)
	if err != nil {
		return h
	}
	if json.Unmarshal(b, h) != nil || h.V != 1 {
		return newHistory()
	}
	h.prune(now)
	return h
}

func (f historyFile) save(h *History) error {
	if f.path == "" {
		return nil
	}
	b, err := json.Marshal(h)
	if err != nil {
		return err
	}
	return writeFileAtomic(f.path, b)
}

func writeFileAtomic(path string, b []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	_ = os.Chmod(filepath.Dir(path), 0o700)
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// appState is state.json: when the anchor card was last sent.
type appState struct {
	AnchorSentAt int64 `json:"anchorSentAt,omitempty"`
}

func loadState(path string) appState {
	var s appState
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &s)
	}
	return s
}

func saveState(path string, s appState) error {
	b, _ := json.Marshal(s)
	return writeFileAtomic(path, b)
}
