package main

import (
	"strconv"
	"strings"
	"time"
)

type config struct {
	APIKey      string
	Model       string
	Effort      string
	MaxTokens   int64
	WebSearch   bool
	Presets     []string
	Followups   []string
	Idle        time.Duration
	KeepHistory bool
	BaseURL     string
	SystemExtra string
	Language    string
}

const (
	defaultPresets   = "Tell me a fun fact|Give me a quick dinner idea|How do I say thank you in Japanese?|Motivate me"
	defaultFollowups = "Explain more|Shorter|Retry"
)

func parseConfig(m map[string]string) config {
	get := func(k, def string) string {
		if v, ok := m[k]; ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
		return def
	}
	c := config{
		APIKey:      strings.TrimSpace(m["api_key"]),
		Model:       get("model", modelHaiku),
		Effort:      get("effort", "low"),
		MaxTokens:   int64(atoi(get("max_tokens", "2048"), 2048, 256, 64000)),
		WebSearch:   get("web_search", "false") == "true",
		Presets:     splitList(get("presets", defaultPresets), 512),
		Followups:   splitList(get("followups", defaultFollowups), 512),
		Idle:        time.Duration(atoi(get("idle_minutes", "15"), 15, 1, 24*60)) * time.Minute,
		KeepHistory: get("keep_history", "true") != "false",
		BaseURL:     get("base_url", "https://api.anthropic.com"),
		SystemExtra: strings.TrimSpace(m["system_prompt_extra"]),
		Language:    strings.TrimSpace(m["language"]),
	}
	switch c.Effort {
	case "low", "medium", "high":
	default:
		c.Effort = "low"
	}
	return c
}

func atoi(s string, def, min, max int) int {
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	if v < min {
		return min
	}
	if v > max {
		return max
	}
	return v
}

// systemExtra is what follows the fixed system prompt: the user's language, then their own instructions.
func (c config) systemExtra() string {
	var parts []string
	if c.Language != "" {
		parts = append(parts, "The user speaks "+c.Language+": always reply in "+c.Language+".")
	}
	if c.SystemExtra != "" {
		parts = append(parts, c.SystemExtra)
	}
	return strings.Join(parts, "\n\n")
}
