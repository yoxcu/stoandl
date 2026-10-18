package main

import (
	"fmt"
	"strconv"
	"strings"
)

const (
	cardApp     = "Claude"
	anchorToken = "anchor"
	anchorItem  = "assistant:anchor"
	actionOpen  = "app"
	actionMore  = "more:"
	titleBytes  = 64
	vibeSilent  = "None"
)

// anchorCard is the standing "Ask Claude" card; silent is used for re-sends.
func anchorCard(presets []string, withApp, silent bool) Notification {
	n := Notification{
		Title:         "Ask Claude",
		Body:          "Reply to ask by voice or pick a question.",
		AppName:       cardApp,
		ReplyLabel:    "Ask",
		CannedReplies: presets,
		AllowVoice:    true,
		ExtToken:      anchorToken,
		ReplaceID:     anchorItem,
	}
	if withApp {
		n.Actions = [][2]string{{actionOpen, "Open app"}}
	}
	if silent {
		n.Vibe = vibeSilent
	}
	return n
}

// answerCard is page k (1-based) of an answer.
func answerCard(conv string, turn int, question string, pages []string, k int, followups []string) Notification {
	n := Notification{
		Title:         ellipsize(fontSafe(question), titleBytes),
		Body:          pages[k-1],
		AppName:       cardApp,
		ReplyLabel:    "Reply",
		CannedReplies: followups,
		AllowVoice:    true,
		ExtToken:      token(conv, turn),
		ReplaceID:     fmt.Sprintf("assistant:%s:%d:%d", conv, turn, k),
		Urgent:        true,
	}
	if k < len(pages) {
		n.Actions = [][2]string{{actionMore + strconv.Itoa(k+1), fmt.Sprintf("More (%d/%d)", k+1, len(pages))}}
	}
	if k > 1 {
		n.Vibe = vibeSilent
	}
	return n
}

func errorCard(conv string, turn int, e *askError) Notification {
	return Notification{
		Title:         fontSafe(e.Title),
		Body:          fontSafe(e.Hint),
		AppName:       cardApp,
		ReplyLabel:    "Reply",
		CannedReplies: []string{"Retry"},
		AllowVoice:    true,
		ExtToken:      token(conv, turn),
		ReplaceID:     fmt.Sprintf("assistant:%s:%d:err", conv, turn),
		Urgent:        true,
	}
}

// parseMore reads the page number from a "more:<k>" action.
func parseMore(action string) (int, bool) {
	if !strings.HasPrefix(action, actionMore) {
		return 0, false
	}
	k, err := strconv.Atoi(action[len(actionMore):])
	return k, err == nil && k >= 2
}
