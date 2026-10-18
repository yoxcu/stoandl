package main

import (
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// fontMap replaces characters the watch fonts can't draw with ones they can.
var fontMap = strings.NewReplacer(
	"“", `"`, "”", `"`, "„", `"`,
	"‘", "'", "’", "'", "‚", "'",
	"–", "-", "—", "-", "−", "-",
	"…", "...", "→", "->",
	"\u00a0", " ", "\u202f", " ", "\u2009", " ",
)

// fontSafe maps unsupported punctuation and drops emoji and everything outside the BMP. Other scripts
// stay (language packs draw them).
func fontSafe(s string) string {
	s = fontMap.Replace(s)
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if dropRune(r) {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

func dropRune(r rune) bool {
	switch {
	case r > 0xFFFF, r == utf8.RuneError:
		return true
	case r >= 0x2600 && r <= 0x27BF: // misc symbols, dingbats
		return true
	case r >= 0xFE00 && r <= 0xFE0F, r == 0x200D, r == 0x20E3: // variation selectors, ZWJ, keycap
		return true
	case r == '\r':
		return true
	}
	return false
}

// cleanText turns Claude's answer (possibly markdown) into plain text for the watch. It runs the same
// code as the streaming path, so a streamed answer and its stored text are identical.
func cleanText(s string) string {
	var c streamCleaner
	c.Push(s)
	c.Flush()
	return c.Text()
}

// cleanSegment cleans one piece of the answer; its first line is a line start only if atLineStart.
func cleanSegment(seg string, atLineStart bool) string {
	lines := strings.Split(seg, "\n")
	for i, ln := range lines {
		if i > 0 || atLineStart {
			lines[i] = cleanLine(ln)
		}
	}
	seg = strings.Join(lines, "\n")
	seg = stripLinks(seg)
	seg = strings.NewReplacer("**", "", "__", "", "`", "").Replace(seg)
	return fontSafe(seg)
}

func cleanLine(ln string) string {
	t := strings.TrimLeft(ln, " \t")
	if strings.HasPrefix(t, "#") {
		return strings.TrimLeft(strings.TrimLeft(t, "#"), " \t")
	}
	if strings.HasPrefix(t, "**") {
		return ln
	}
	for _, b := range []string{"- ", "* ", "• ", "•"} {
		if strings.HasPrefix(t, b) {
			return "- " + strings.TrimLeft(t[len(b):], " \t")
		}
	}
	return ln
}

// stripLinks turns [text](url) into text.
func stripLinks(s string) string {
	var b strings.Builder
	for {
		mid := strings.Index(s, "](")
		if mid < 0 {
			break
		}
		open := strings.LastIndex(s[:mid], "[")
		end := strings.Index(s[mid:], ")")
		if open < 0 || end < 0 || strings.Contains(s[open:mid+end], "\n") {
			b.WriteString(s[:mid+2])
			s = s[mid+2:]
			continue
		}
		b.WriteString(s[:open])
		b.WriteString(s[open+1 : mid])
		s = s[mid+end+1:]
	}
	b.WriteString(s)
	return b.String()
}

// holdBack is the most trailing bytes the stream cleaner keeps back because they may still turn into
// a marker (`**`, a heading or bullet at line start, a newline run). An open link on the current line
// is held up to linkHold, so a long URL is still stripped.
const (
	holdBack = 32
	linkHold = 256
)

// streamCleaner cleans a streamed answer incrementally. Raw text is cleaned in segments cut where no
// later text can change them, and emitted text is never taken back.
type streamCleaner struct {
	pending     string // raw text not cleaned yet
	atLineStart bool   // pending starts a line
	started     bool
	out         strings.Builder
	nlRun       int // newlines at the end of out
}

// Push adds a raw delta and returns the newly final cleaned text.
func (c *streamCleaner) Push(delta string) string {
	if !c.started {
		c.started, c.atLineStart = true, true
	}
	c.pending += delta
	cut := safeCut(c.pending, c.atLineStart)
	return c.take(cut, false)
}

// Flush returns the rest once the answer is complete.
func (c *streamCleaner) Flush() string {
	return c.take(len(c.pending), true)
}

// Text is everything emitted so far.
func (c *streamCleaner) Text() string { return c.out.String() }

func (c *streamCleaner) take(cut int, final bool) string {
	if cut <= 0 {
		return ""
	}
	seg := cleanSegment(c.pending[:cut], c.atLineStart)
	c.atLineStart = strings.HasSuffix(c.pending[:cut], "\n")
	c.pending = c.pending[cut:]
	if final {
		seg = strings.TrimRight(seg, " \t\n")
	}
	var b strings.Builder
	for _, r := range seg {
		switch {
		case r == '\n':
			if c.out.Len() == 0 && b.Len() == 0 {
				continue
			}
			c.nlRun++
			if c.nlRun > 2 {
				continue
			}
		case c.out.Len() == 0 && b.Len() == 0 && unicode.IsSpace(r):
			continue
		default:
			c.nlRun = 0
		}
		b.WriteRune(r)
	}
	c.out.WriteString(b.String())
	return b.String()
}

// safeCut returns how much of the raw tail can be cleaned now without a later delta changing it.
func safeCut(raw string, atLineStart bool) int {
	cut := len(raw)
	// The incomplete last line may still become a heading or bullet.
	nl := strings.LastIndex(raw, "\n")
	if nl >= 0 || atLineStart {
		if last := raw[nl+1:]; len(last) <= 8 && strings.Trim(last, "#*-_ \t•`[") == "" {
			cut = nl + 1
		}
	}
	// Trailing newlines, spaces and marker characters.
	cut = len(strings.TrimRight(raw[:cut], "\n*_` \t"))
	// An open link.
	linkOpen := false
	if open := strings.LastIndex(raw[:cut], "["); open >= 0 && len(raw)-open <= linkHold {
		rest := raw[open:]
		if mid := strings.Index(rest, "]("); (mid < 0 || !strings.Contains(rest[mid:], ")")) && !strings.Contains(rest, "\n") {
			cut, linkOpen = open, true
		}
	}
	if !linkOpen && len(raw)-cut > holdBack {
		cut = len(raw) - holdBack
	}
	for cut > 0 && cut < len(raw) && !utf8.RuneStart(raw[cut]) {
		cut--
	}
	return cut
}

// cutUTF8 returns the longest prefix of s that is at most n bytes and ends on a rune boundary.
func cutUTF8(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

// ellipsize cuts s to at most n bytes, ending in "..." when it was cut.
func ellipsize(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return strings.TrimRight(cutUTF8(s, n-3), " ") + "..."
}

const (
	pageBytes   = 500
	maxPages    = 8
	moreInApp   = "... (open the Assistant app for the rest)"
	chunkBytes  = 120
	chunkMinEnd = 24
	chunkAge    = 400 * time.Millisecond
)

// paginate splits an answer into notification pages of at most pageBytes, preferring a paragraph
// break, then a sentence end, then a space. At most maxPages; the last then points to the app.
func paginate(text string) []string {
	text = strings.TrimSpace(text)
	if text == "" {
		return []string{""}
	}
	var pages []string
	for text != "" {
		if len(pages) == maxPages-1 && len(text) > pageBytes {
			last := strings.TrimRight(cutUTF8(text, pageBytes-len(moreInApp)), " \n")
			if b := breakPoint(last, len(last)); b > 0 && b < len(last) {
				last = strings.TrimRight(last[:b], " \n")
			}
			return append(pages, last+moreInApp)
		}
		if len(text) <= pageBytes {
			pages = append(pages, text)
			break
		}
		b := breakPoint(text, pageBytes)
		pages = append(pages, strings.TrimRight(text[:b], " \n"))
		text = strings.TrimLeft(text[b:], " \n")
	}
	return pages
}

// breakPoint returns where to cut text so the first part is at most limit bytes.
func breakPoint(text string, limit int) int {
	if len(text) <= limit {
		return len(text)
	}
	win := cutUTF8(text, limit)
	min := len(win) / 2
	if i := strings.LastIndex(win, "\n\n"); i >= min {
		return i + 2
	}
	if i := lastSentenceEnd(win); i >= min {
		return i
	}
	if i := strings.LastIndexAny(win, " \n"); i >= min {
		return i + 1
	}
	return len(win)
}

// lastSentenceEnd returns the index just after the last ". ", "! ", "? " or newline in s, or -1.
func lastSentenceEnd(s string) int {
	best := -1
	for _, m := range []string{". ", "! ", "? ", "\n"} {
		if i := strings.LastIndex(s, m); i >= 0 && i+len(m) > best {
			best = i + len(m)
		}
	}
	return best
}

// nextChunk decides whether the unsent buffer should go out as an AppMessage chunk now: when it holds
// chunkBytes, when its oldest byte is chunkAge old, when the answer is final, or at a sentence end
// once it holds chunkMinEnd bytes. It returns the chunk (at most chunkBytes, whole runes) or "".
func nextChunk(buf string, age time.Duration, final bool) string {
	if buf == "" {
		return ""
	}
	if len(buf) >= chunkBytes || final || age >= chunkAge {
		return cutUTF8(buf, chunkBytes)
	}
	if len(buf) >= chunkMinEnd {
		if i := lastSentenceEnd(buf); i > 0 {
			return buf[:i]
		}
	}
	return ""
}

// truncatedMark is appended to a notification answer cut off by max_tokens or the time cap.
const truncatedMark = "..."

func isRetry(text string) bool {
	return strings.EqualFold(strings.TrimSpace(text), "retry")
}

// splitList parses a "|"-separated config list, keeping whole items within maxBytes.
func splitList(s string, maxBytes int) []string {
	var out []string
	total := 0
	for _, it := range strings.Split(s, "|") {
		it = strings.TrimSpace(it)
		if it == "" {
			continue
		}
		if total+len(it) > maxBytes {
			break
		}
		total += len(it) + 1
		out = append(out, it)
	}
	return out
}

func isSpaceOnly(s string) bool {
	return strings.IndexFunc(s, func(r rune) bool { return !unicode.IsSpace(r) }) < 0
}
