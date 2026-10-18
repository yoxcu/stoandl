package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

const (
	modelHaiku  = "claude-haiku-4-5-20251001"
	modelSonnet = "claude-sonnet-5"
	modelOpus   = "claude-opus-5-5"
)

var allModels = []string{modelHaiku, modelSonnet, modelOpus}

const systemPrompt = "You are answering on a Pebble smartwatch: the reply is read at a glance on a small screen " +
	"that shows about 30 words at a time, so every word costs the user a scroll. " +
	"Reply in 1 to 3 short sentences, at most 40 words; the first sentence must answer the question on its own. " +
	"Start with the answer: no greeting, no restating the question, no filler or closing offer. " +
	"Plain text only: no markdown, lists, bullets, headings, tables, links or emoji. Write numbers as digits and use short units. " +
	"Go longer (up to about 120 words) only when the user asks for more detail, such as \"Explain more\". " +
	"The question was dictated and transcribed by speech recognition, so expect misheard words: answer the most likely " +
	"meaning. Only if it is truly unintelligible, ask in one short sentence to repeat it. " +
	"Reply in the language the user spoke."

// Overridable in tests.
var (
	firstTextTimeout = 20 * time.Second
	requestTimeout   = 60 * time.Second
)

const maxPauseResends = 2

type family int

const (
	famHaiku family = iota
	famSonnet
	famOpus
)

func modelFamily(model string) family {
	switch {
	case strings.Contains(model, "haiku"):
		return famHaiku
	case strings.Contains(model, "opus"):
		return famOpus
	default:
		return famSonnet
	}
}

// price is USD per million tokens (input, output).
func price(model string) (in, out float64) {
	switch modelFamily(model) {
	case famHaiku:
		return 1, 5
	case famOpus:
		if !strings.HasPrefix(model, modelOpus) { // Opus 5, a fallback target
			return 5, 25
		}
		return 4, 20
	default:
		return 2, 10
	}
}

const webSearchPrice = 0.01

func cost(model string, in, out, searches int64) float64 {
	pi, po := price(model)
	return float64(in)*pi/1e6 + float64(out)*po/1e6 + float64(searches)*webSearchPrice
}

// usageCost prices one response. usage.iterations, when present, lists every attempt (one declined
// mid-stream before a server-side fallback included) at its own model; top-level usage covers only
// the attempt that served the message.
func usageCost(model string, u anthropic.BetaUsage) float64 {
	c := float64(u.ServerToolUse.WebSearchRequests) * webSearchPrice
	var sum float64
	n := 0
	for _, it := range u.Iterations {
		if it.Type != "message" && it.Type != "fallback_message" {
			continue
		}
		m := string(it.Model)
		if m == "" {
			m = model
		}
		sum += cost(m, it.InputTokens, it.OutputTokens, 0)
		n++
	}
	if n == 0 {
		return c + cost(model, u.InputTokens, u.OutputTokens, 0)
	}
	return c + sum
}

// echoable drops what must not be echoed after a mid-output server-side fallback: the thinking,
// redacted_thinking and tool_use blocks before the last fallback block, and server_tool_use blocks
// there without their result. Content without a fallback block is returned as is.
func echoable(blocks []json.RawMessage) []json.RawMessage {
	type head struct {
		Type      string `json:"type"`
		ID        string `json:"id"`
		ToolUseID string `json:"tool_use_id"`
	}
	heads := make([]head, len(blocks))
	last := -1
	for i, b := range blocks {
		_ = json.Unmarshal(b, &heads[i])
		if heads[i].Type == "fallback" {
			last = i
		}
	}
	if last < 0 {
		return blocks
	}
	results := map[string]bool{}
	for _, h := range heads[:last] {
		if h.ToolUseID != "" {
			results[h.ToolUseID] = true
		}
	}
	out := make([]json.RawMessage, 0, len(blocks))
	for i, b := range blocks {
		if i < last {
			switch h := heads[i]; h.Type {
			case "thinking", "redacted_thinking", "tool_use":
				continue
			case "server_tool_use":
				if !results[h.ID] {
					continue
				}
			}
		}
		out = append(out, b)
	}
	return out
}

func newClient(apiKey, baseURL string) anthropic.Client {
	opts := []option.RequestOption{option.WithAPIKey(apiKey), option.WithMaxRetries(1)}
	if baseURL != "" {
		opts = append(opts, option.WithBaseURL(baseURL))
	}
	return anthropic.NewClient(opts...)
}

// askRequest is one question with the turns it is asked on.
type askRequest struct {
	Model       string
	Effort      string
	MaxTokens   int64
	WebSearch   bool
	SystemExtra string
	History     []Turn // the base; failed turns are skipped
	Prompt      string // the user turn: time line + question
	// Searching is the one-off request made after Claude asked for a web lookup: the real web search
	// tool, earlier turns as plain text. Its answer is stored as text only, so the conversation itself
	// keeps one tool set (lookupTool) and stays append-only.
	Searching bool
}

// lookupTool stands in for web search in every regular request. The server web search tool's own
// definition costs thousands of input tokens per question (web_search_20260209 brings dynamic
// filtering); this one costs about a hundred, and the real tool is only sent when Claude calls it.
const lookupTool = "web_lookup"

func lookupToolParam() anthropic.BetaToolUnionParam {
	return anthropic.BetaToolUnionParam{OfTool: &anthropic.BetaToolParam{
		Name: lookupTool,
		Description: anthropic.String("Search the web. Call it, before writing any answer text, only when the answer " +
			"depends on current or fast-changing information (news, prices, weather, schedules, scores, recent " +
			"releases, opening hours) or on a specific fact you do not know. Never call it for general knowledge."),
		InputSchema: anthropic.BetaToolInputSchemaParam{
			Properties: map[string]any{"query": map[string]any{"type": "string", "description": "What to search for"}},
			Required:   []string{"query"},
		},
	}}
}

func systemText(extra string) string {
	if extra = strings.TrimSpace(extra); extra != "" {
		return systemPrompt + "\n\n" + extra
	}
	return systemPrompt
}

// prefixKey fingerprints what precedes the messages besides the model: the system prompt and
// whether the web_search tool is sent.
func prefixKey(systemExtra string, webSearch bool) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%t\x00%s", webSearch, systemText(systemExtra))))
	return hex.EncodeToString(sum[:8])
}

func (r askRequest) params() anthropic.BetaMessageNewParams {
	p := anthropic.BetaMessageNewParams{
		Model:     anthropic.Model(r.Model),
		MaxTokens: r.MaxTokens,
		System:    []anthropic.BetaTextBlockParam{{Text: systemText(r.SystemExtra)}},
		Messages:  append(replay(r.History), anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(r.Prompt))),
	}
	if r.Searching {
		p.Messages = append(replayText(r.History), anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(r.Prompt)))
	}
	fam := modelFamily(r.Model)
	if fam != famHaiku {
		p.OutputConfig = anthropic.BetaOutputConfigParam{Effort: anthropic.BetaOutputConfigEffort(r.Effort)}
	}
	if fam == famOpus {
		p.Betas = []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01}
		p.Fallbacks = anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()}
	}
	switch {
	case r.WebSearch && !r.Searching:
		p.Tools = []anthropic.BetaToolUnionParam{lookupToolParam()}
	case r.Searching:
		if fam == famHaiku {
			p.Tools = []anthropic.BetaToolUnionParam{{OfWebSearchTool20250305: &anthropic.BetaWebSearchTool20250305Param{MaxUses: anthropic.Int(1)}}}
		} else {
			p.Tools = []anthropic.BetaToolUnionParam{{OfWebSearchTool20260209: &anthropic.BetaWebSearchTool20260209Param{MaxUses: anthropic.Int(1)}}}
		}
	}
	return p
}

// replayText is the history as plain question/answer text, for the one-off search request.
func replayText(turns []Turn) []anthropic.BetaMessageParam {
	var msgs []anthropic.BetaMessageParam
	for _, t := range turns {
		if t.Err != 0 || strings.TrimSpace(t.Text) == "" {
			continue
		}
		prompt := t.Prompt
		if prompt == "" {
			prompt = t.Q
		}
		msgs = append(msgs,
			anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(prompt)),
			anthropic.BetaMessageParam{Role: anthropic.BetaMessageParamRoleAssistant,
				Content: []anthropic.BetaContentBlockParamUnion{anthropic.NewBetaTextBlock(t.Text)}},
		)
	}
	return msgs
}

// replay rebuilds the message list from stored turns, skipping failed ones (and their question) so
// user and assistant turns keep alternating.
func replay(turns []Turn) []anthropic.BetaMessageParam {
	var msgs []anthropic.BetaMessageParam
	for _, t := range turns {
		if t.Err != 0 || len(t.Content) == 0 {
			continue
		}
		blocks := make([]anthropic.BetaContentBlockParamUnion, 0, len(t.Content))
		for _, raw := range t.Content {
			var b anthropic.BetaContentBlockUnion
			if err := b.UnmarshalJSON(raw); err != nil {
				continue
			}
			blocks = append(blocks, b.ToParam())
		}
		if len(blocks) == 0 {
			continue
		}
		prompt := t.Prompt
		if prompt == "" {
			prompt = t.Q
		}
		msgs = append(msgs,
			anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(prompt)),
			anthropic.BetaMessageParam{Role: anthropic.BetaMessageParamRoleAssistant, Content: blocks},
		)
	}
	return msgs
}

// B8 error codes.
const (
	errCancelled  = 1
	errNoKey      = 2
	errKeyBad     = 3
	errBusy       = 4
	errTimeout    = 5
	errRefused    = 6
	errServer     = 7
	errBadRequest = 8
)

type askError struct {
	Code   int
	Title  string // card title
	Hint   string // card body
	Status int
}

func (e *askError) Error() string { return fmt.Sprintf("%d %s", e.Code, e.Title) }

// AppText is the ERROR text for the watchapp: the card title without "Claude: ".
func (e *askError) AppText() string {
	return cutUTF8(strings.TrimPrefix(e.Title, "Claude: "), 100)
}

func newAskError(code int, status int, detail string) *askError {
	e := &askError{Code: code, Status: status}
	switch code {
	case errCancelled:
		e.Title = "Cancelled"
	case errNoKey:
		e.Title, e.Hint = "Claude: no API key", "Set it in stoandl: Extensions → assistant."
	case errKeyBad:
		e.Title, e.Hint = "Claude: key rejected", "Check the API key in stoandl."
	case errBusy:
		e.Title, e.Hint = "Claude: busy", "Reply Retry in a moment."
	case errTimeout:
		e.Title, e.Hint = "Claude: no answer", "Network or API timeout. Reply Retry."
	case errRefused:
		e.Title, e.Hint = "Claude declined", "Try asking differently."
	case errServer:
		e.Title = "Claude: error"
		if status > 0 {
			e.Title = fmt.Sprintf("Claude: error %d", status)
		}
		e.Hint = cutUTF8(fontSafe(detail), 100)
	case errBadRequest:
		e.Title = "Couldn't ask"
	}
	return e
}

var (
	errSuperseded  = errors.New("superseded by a newer question")
	errUserCancel  = errors.New("cancelled on the watch")
	errNoFirstText = errors.New("no text in time")
	errTooLong     = errors.New("request time cap")
)

// classify maps a failed request to its B8 error.
func classify(err, cause error) *askError {
	switch {
	case errors.Is(cause, errSuperseded), errors.Is(cause, errUserCancel):
		return newAskError(errCancelled, 0, "")
	case errors.Is(cause, errNoFirstText), errors.Is(cause, errTooLong):
		return newAskError(errTimeout, 0, "")
	}
	var apierr *anthropic.Error
	if errors.As(err, &apierr) {
		status := apierr.StatusCode
		typ := string(apierr.Type())
		switch {
		case status == 401 || status == 403 || typ == "authentication_error" || typ == "permission_error":
			return newAskError(errKeyBad, status, "")
		case status == 429 || status == 529 || typ == "overloaded_error" || typ == "rate_limit_error":
			return newAskError(errBusy, status, "")
		}
		if status < 400 {
			status = 0
		}
		return newAskError(errServer, status, apiErrorMessage(apierr.RawJSON(), typ))
	}
	var nerr net.Error
	if errors.As(err, &nerr) || errors.Is(err, context.DeadlineExceeded) {
		return newAskError(errTimeout, 0, "")
	}
	return newAskError(errTimeout, 0, "")
}

func apiErrorMessage(raw, typ string) string {
	var env struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal([]byte(raw), &env) == nil && env.Error.Message != "" {
		return env.Error.Message
	}
	return typ
}

// askResult is a finished request: an answer, or Err.
type askResult struct {
	Content   []json.RawMessage
	Text      string // raw text blocks joined (before cleanup)
	Stop      string
	Truncated bool
	In, Out   int64
	Searches  int64
	Cost      float64       // USD, every attempt included (cache pricing ignored)
	TTFT      time.Duration // 0 = no text
	Total     time.Duration
	Err       *askError
}

// streamHooks receive the answer while it streams. Both may be nil.
type streamHooks struct {
	Text      func(delta string)
	Searching func()
}

// runAsk streams one answer from Claude. It enforces the 20 s first-text and 60 s total limits,
// re-sends on pause_turn, and checks stop_reason before reading content. ctx is cancelled with a
// cause (errSuperseded, errUserCancel) to abandon the request.
func runAsk(parent context.Context, client anthropic.Client, req askRequest, hooks streamHooks) askResult {
	start := time.Now()
	ctx, cancel := context.WithCancelCause(parent)
	defer cancel(nil)
	capTimer := time.AfterFunc(requestTimeout, func() { cancel(errTooLong) })
	defer capTimer.Stop()
	firstTimer := time.AfterFunc(firstTextTimeout, func() { cancel(errNoFirstText) })
	defer firstTimer.Stop()

	var res askResult
	params := req.params()
	var text strings.Builder
	var content []json.RawMessage
	gap := false // a non-text block came after the last text

	for resend := 0; ; resend++ {
		stream := client.Beta.Messages.NewStreaming(ctx, params)
		msg := anthropic.BetaMessage{}
		complete := false
		for stream.Next() {
			ev := stream.Current()
			if err := msg.Accumulate(ev); err != nil {
				break
			}
			switch ev.Type {
			case "content_block_start":
				switch ev.ContentBlock.Type {
				case "text":
					if gap && text.Len() > 0 {
						text.WriteString("\n\n")
						if hooks.Text != nil {
							hooks.Text("\n\n")
						}
					}
					gap = false
				case "server_tool_use":
					gap = true
					if hooks.Searching != nil {
						hooks.Searching()
					}
				case "thinking", "redacted_thinking":
				case "fallback":
					// A mid-stream fallback model continues the declined partial text: no break.
				default:
					gap = true
				}
			case "content_block_delta":
				if ev.Delta.Type == "text_delta" && ev.Delta.Text != "" {
					if res.TTFT == 0 {
						res.TTFT = time.Since(start)
						firstTimer.Stop()
					}
					text.WriteString(ev.Delta.Text)
					if hooks.Text != nil {
						hooks.Text(ev.Delta.Text)
					}
				}
			case "message_stop":
				complete = true
			}
		}
		err := stream.Err()
		stream.Close()
		res.In += msg.Usage.InputTokens
		res.Out += msg.Usage.OutputTokens
		res.Searches += msg.Usage.ServerToolUse.WebSearchRequests
		res.Cost += usageCost(req.Model, msg.Usage)
		res.Total = time.Since(start)
		res.Text = text.String()

		if err != nil || !complete {
			cause := context.Cause(ctx)
			if errors.Is(cause, errTooLong) && strings.TrimSpace(res.Text) != "" {
				res.Stop, res.Truncated = "timeout", true
				res.Content = textOnly(res.Text)
				return res
			}
			if err == nil {
				err = errors.New("stream ended early")
			}
			res.Err = classify(err, cause)
			return res
		}

		res.Stop = string(msg.StopReason)
		blocks := make([]json.RawMessage, 0, len(msg.Content))
		for _, b := range msg.Content {
			blocks = append(blocks, json.RawMessage(b.RawJSON()))
		}
		switch msg.StopReason {
		case anthropic.BetaStopReasonRefusal:
			res.Err = newAskError(errRefused, 0, "")
			return res
		case anthropic.BetaStopReasonPauseTurn:
			if resend < maxPauseResends {
				content = append(content, blocks...)
				params.Messages = append(params.Messages, msg.ToParam())
				continue
			}
			if strings.TrimSpace(res.Text) == "" {
				res.Err = newAskError(errTimeout, 0, "")
				return res
			}
			res.Truncated = true
			res.Content = textOnly(res.Text)
			return res
		case anthropic.BetaStopReasonMaxTokens, anthropic.BetaStopReasonModelContextWindowExceeded:
			res.Truncated = true
		case anthropic.BetaStopReasonToolUse:
			if !req.Searching && callsLookup(msg) {
				req.Searching = true
				params = req.params()
				content = nil
				gap = text.Len() > 0
				firstTimer.Reset(firstTextTimeout)
				if hooks.Searching != nil {
					hooks.Searching()
				}
				continue
			}
		}
		if req.Searching {
			res.Content = textOnly(res.Text)
			return res
		}
		res.Content = echoable(append(content, blocks...))
		return res
	}
}

func callsLookup(msg anthropic.BetaMessage) bool {
	for _, b := range msg.Content {
		if b.Type == "tool_use" && b.Name == lookupTool {
			return true
		}
	}
	return false
}

// textOnly is the stored content for an answer cut off mid-stream: its text alone, since a partial
// thinking or tool block can't be replayed.
func textOnly(text string) []json.RawMessage {
	b, _ := json.Marshal(map[string]string{"type": "text", "text": text})
	return []json.RawMessage{b}
}

// timeLine is the first line of each user turn; the time stays out of the system prompt so the
// prompt prefix is stable.
func timeLine(now time.Time) string {
	s := "Local time: " + now.Format("Mon 2 Jan 2006 15:04 MST")
	if z := zoneName(); z != "" {
		s += " (" + z + ")"
	}
	return s
}

func zoneName() string {
	if tz := strings.TrimPrefix(os.Getenv("TZ"), ":"); tz != "" && !strings.HasPrefix(tz, "/") {
		return tz
	}
	if p, err := filepath.EvalSymlinks("/etc/localtime"); err == nil {
		if i := strings.Index(p, "zoneinfo/"); i >= 0 {
			return p[i+len("zoneinfo/"):]
		}
	}
	return ""
}
