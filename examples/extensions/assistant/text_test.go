package main

import (
	"math/rand"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func TestCleanText(t *testing.T) {
	cases := []struct{ in, want string }{
		{"**Soft:** 6 minutes.", "Soft: 6 minutes."},
		{"## Heading\nBody `code` and __under__.", "Heading\nBody code and under."},
		{"See [the docs](https://example.com/x) now.", "See the docs now."},
		{"* one\n- two\n• three", "- one\n- two\n- three"},
		{"a\n\n\n\nb", "a\n\nb"},
		{"“Quote” – it’s… fine", `"Quote" - it's... fine`},
		{"Hot 🔥 day ☀️!", "Hot  day !"},
		{"\n\n  Answer  \n\n", "Answer"},
		{"Grüße, 東京, Привет", "Grüße, 東京, Привет"},
		{"x\u00a0y", "x y"},
		{"[1] footnote and [link](u)", "[1] footnote and link"},
		{"2 * 3 = 6", "2 * 3 = 6"},
	}
	for _, c := range cases {
		if got := cleanText(c.in); got != c.want {
			t.Errorf("cleanText(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// Streaming in arbitrary pieces gives the same text as cleaning it whole, and never takes text back.
func TestStreamCleanerMatchesWhole(t *testing.T) {
	inputs := []string{
		"**Soft-boiled:** 6 minutes.\n\n## Steps\n- Boil water\n- Add the egg\n* Cool it\n\n\n\nSee [guide](https://example.com/eggs) for “more”…",
		"Plain answer with no markdown at all. Two sentences!",
		"# Title\n\n1. First\n2. Second\n\n`code` **bold** __under__ — dash",
		"Emoji 🎉 and Ünïcödé text ☕ end.",
		"See [the docs](https://example.com/a/very/long/path/to/page/that/goes/on/and/on?with=query&and=more) for more.",
	}
	rng := rand.New(rand.NewSource(1))
	for _, in := range inputs {
		want := cleanText(in)
		for trial := 0; trial < 50; trial++ {
			var c streamCleaner
			var got strings.Builder
			rest := in
			for rest != "" {
				n := 1 + rng.Intn(7)
				if n > len(rest) {
					n = len(rest)
				}
				for n < len(rest) && !utf8.RuneStart(rest[n]) {
					n++
				}
				got.WriteString(c.Push(rest[:n]))
				rest = rest[n:]
				if got.String() != c.Text() {
					t.Fatalf("emitted text and Text() differ")
				}
			}
			got.WriteString(c.Flush())
			if got.String() != want {
				t.Fatalf("stream of %q:\n got %q\nwant %q", in, got.String(), want)
			}
		}
	}
}

func TestStreamCleanerHoldsMarkers(t *testing.T) {
	var c streamCleaner
	if out := c.Push("Hello **"); out != "Hello" {
		t.Fatalf("got %q", out)
	}
	if out := c.Push("bold** and [x"); strings.Contains(out, "[") || strings.Contains(out, "*") {
		t.Fatalf("marker leaked: %q", out)
	}
	c.Push("](http://u) done")
	c.Flush()
	if c.Text() != "Hello bold and x done" {
		t.Fatalf("got %q", c.Text())
	}
}

func TestPaginate(t *testing.T) {
	if p := paginate("short"); len(p) != 1 || p[0] != "short" {
		t.Fatalf("got %q", p)
	}
	para := strings.Repeat("This is a sentence. ", 15) // 300 B
	text := para + "\n\n" + para + "\n\n" + para
	pages := paginate(text)
	if len(pages) != 3 {
		t.Fatalf("want 3 pages at paragraph breaks, got %d", len(pages))
	}
	for _, p := range pages {
		if len(p) > pageBytes || strings.HasPrefix(p, " ") || strings.HasSuffix(p, " ") {
			t.Fatalf("bad page %q", p)
		}
	}
	// Sentence break, then multibyte text with no spaces at all.
	pages = paginate(strings.Repeat("Ü", 600))
	for _, p := range pages {
		if len(p) > pageBytes || !utf8.ValidString(p) {
			t.Fatalf("invalid page len %d", len(p))
		}
	}
	// Longer than eight pages: page 8 points to the app.
	long := strings.Repeat("word ", 2000)
	pages = paginate(long)
	if len(pages) != maxPages {
		t.Fatalf("want %d pages, got %d", maxPages, len(pages))
	}
	last := pages[maxPages-1]
	if !strings.HasSuffix(last, moreInApp) || len(last) > pageBytes {
		t.Fatalf("bad last page (%d B): %q", len(last), last)
	}
}

func TestNextChunk(t *testing.T) {
	if c := nextChunk("", time.Hour, true); c != "" {
		t.Fatal("empty buffer gave a chunk")
	}
	if c := nextChunk("Short", 0, false); c != "" {
		t.Fatalf("flushed early: %q", c)
	}
	if c := nextChunk("Short", chunkAge, false); c != "Short" {
		t.Fatalf("age flush: %q", c)
	}
	if c := nextChunk("Short", 0, true); c != "Short" {
		t.Fatalf("final flush: %q", c)
	}
	s := "About 384,400 km. The moon is far"
	if c := nextChunk(s, 0, false); c != "About 384,400 km. " {
		t.Fatalf("sentence flush: %q", c)
	}
	if c := nextChunk("Hi. x", 0, false); c != "" {
		t.Fatalf("sentence flush under 24 B: %q", c)
	}
	big := strings.Repeat("é", 100) // 200 B
	c := nextChunk(big, 0, false)
	if len(c) > chunkBytes || !utf8.ValidString(c) || len(c) < chunkBytes-1 {
		t.Fatalf("size flush: %d B", len(c))
	}
}

func TestSplitListAndEllipsize(t *testing.T) {
	got := splitList(" a | b ||c ", 512)
	if strings.Join(got, ",") != "a,b,c" {
		t.Fatalf("got %q", got)
	}
	got = splitList(strings.Repeat("x", 300)+"|"+strings.Repeat("y", 300), 512)
	if len(got) != 1 {
		t.Fatalf("cap: got %d items", len(got))
	}
	if e := ellipsize(strings.Repeat("ä", 40), 64); len(e) > 64 || !strings.HasSuffix(e, "...") || !utf8.ValidString(e) {
		t.Fatalf("ellipsize: %q", e)
	}
	if e := ellipsize("short", 64); e != "short" {
		t.Fatalf("ellipsize short: %q", e)
	}
}
