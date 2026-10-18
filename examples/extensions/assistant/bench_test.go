package main

import (
	"bytes"
	"math"
	"strings"
	"testing"
	"time"
)

func TestPercentile(t *testing.T) {
	ms := func(v ...int) []time.Duration {
		var d []time.Duration
		for _, x := range v {
			d = append(d, time.Duration(x)*time.Millisecond)
		}
		return d
	}
	if p := percentile(nil, 0.5); p != 0 {
		t.Fatal("empty")
	}
	if p := percentile(ms(100, 200, 300), 0.5); p != 200*time.Millisecond {
		t.Fatalf("median odd: %v", p)
	}
	if p := percentile(ms(100, 200, 300, 400), 0.5); p != 250*time.Millisecond {
		t.Fatalf("median even: %v", p)
	}
	if p := percentile(ms(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0.9); p != 9100*time.Microsecond {
		t.Fatalf("p90: %v", p)
	}
}

func TestSummarizeAndCost(t *testing.T) {
	if c := cost(modelHaiku, 1_000_000, 1_000_000, 0); c != 6 {
		t.Fatalf("haiku cost %v", c)
	}
	if c := cost(modelSonnet, 500_000, 100_000, 0); math.Abs(c-2) > 1e-9 {
		t.Fatalf("sonnet cost %v", c)
	}
	if c := cost(modelOpus, 1000, 1000, 2); math.Abs(c-(0.004+0.02+0.02)) > 1e-9 {
		t.Fatalf("opus cost %v", c)
	}
	runs := []benchRun{
		{TTFT: 100 * time.Millisecond, Total: 1 * time.Second, Out: 40, Cost: 0.001},
		{TTFT: 300 * time.Millisecond, Total: 3 * time.Second, Out: 60, Cost: 0.003},
		{Err: "Claude: busy", Cost: 0},
	}
	s := summarize(modelHaiku, runs)
	if s.Runs != 3 || s.Errors != 1 || s.TTFT50 != 200*time.Millisecond || s.Total50 != 2*time.Second {
		t.Fatalf("summary %+v", s)
	}
	if s.MeanOut != 50 || math.Abs(s.SumCost-0.004) > 1e-12 || math.Abs(s.MeanCost-0.004/3) > 1e-12 {
		t.Fatalf("means %+v", s)
	}
}

func TestBenchNeedsKey(t *testing.T) {
	t.Setenv("ANTHROPIC_API_KEY", "")
	var out, errb bytes.Buffer
	if code := benchMain(nil, &out, &errb); code != 2 || !strings.Contains(errb.String(), "ANTHROPIC_API_KEY") {
		t.Fatalf("code %d, stderr %q", code, errb.String())
	}
}

func TestBenchAgainstFake(t *testing.T) {
	f := newFakeAPI(t)
	t.Setenv("ANTHROPIC_API_KEY", "sk-test")
	t.Setenv("ANTHROPIC_BASE_URL", f.srv.URL)
	var out, errb bytes.Buffer
	if code := benchMain([]string{"-models", modelHaiku + "," + modelOpus, "-n", "1"}, &out, &errb); code != 0 {
		t.Fatalf("code %d: %s", code, errb.String())
	}
	table := out.String()
	if !strings.Contains(table, modelHaiku) || !strings.Contains(table, modelOpus) || !strings.Contains(table, "TTFT p50") {
		t.Fatalf("table:\n%s", table)
	}
	if n := len(f.requests()); n != 2*len(benchQuestions) {
		t.Fatalf("%d requests", n)
	}
	out.Reset()
	if code := benchMain([]string{"-models", modelHaiku, "-runs", "1", "-tsv"}, &out, &errb); code != 0 {
		t.Fatal(code)
	}
	if lines := strings.Split(strings.TrimSpace(out.String()), "\n"); len(lines) != 1+len(benchQuestions) || strings.Count(lines[1], "\t") != 9 {
		t.Fatalf("tsv:\n%s", out.String())
	}
}
