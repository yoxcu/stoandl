package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"math"
	"os"
	"sort"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
)

var benchQuestions = []string{
	"What's the capital of Australia?",
	"How long do I soft-boil an egg?",
	"Convert 72 Fahrenheit to Celsius.",
	"Give me a synonym for happy.",
	"What does HTTP 418 mean?",
	"Explain a leap year in one sentence.",
}

// benchRun is one measured request.
type benchRun struct {
	Model    string
	Q, Run   int
	TTFT     time.Duration
	Total    time.Duration
	In, Out  int64
	Bytes    int
	Stop     string
	Cost     float64
	Err      string
	Searches int64
}

type benchSummary struct {
	Model             string
	Runs, Errors      int
	TTFT50, TTFT90    time.Duration
	Total50, Total90  time.Duration
	MeanOut           float64
	MeanCost, SumCost float64
}

// percentile interpolates linearly between the closest ranks of sorted durations.
func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	pos := p * float64(len(sorted)-1)
	lo := int(math.Floor(pos))
	hi := int(math.Ceil(pos))
	frac := pos - float64(lo)
	return sorted[lo] + time.Duration(math.Round(frac*float64(sorted[hi]-sorted[lo])))
}

// summarize aggregates one model's runs; failed runs only count as errors.
func summarize(model string, runs []benchRun) benchSummary {
	s := benchSummary{Model: model}
	var ttft, total []time.Duration
	var out int64
	for _, r := range runs {
		s.Runs++
		s.SumCost += r.Cost
		if r.Err != "" {
			s.Errors++
			continue
		}
		if r.TTFT > 0 {
			ttft = append(ttft, r.TTFT)
		}
		total = append(total, r.Total)
		out += r.Out
	}
	sort.Slice(ttft, func(i, j int) bool { return ttft[i] < ttft[j] })
	sort.Slice(total, func(i, j int) bool { return total[i] < total[j] })
	s.TTFT50, s.TTFT90 = percentile(ttft, 0.5), percentile(ttft, 0.9)
	s.Total50, s.Total90 = percentile(total, 0.5), percentile(total, 0.9)
	if ok := s.Runs - s.Errors; ok > 0 {
		s.MeanOut = float64(out) / float64(ok)
	}
	if s.Runs > 0 {
		s.MeanCost = s.SumCost / float64(s.Runs)
	}
	return s
}

// benchClient builds the bench's client: ANTHROPIC_API_KEY only, ANTHROPIC_BASE_URL through the SDK.
var benchClient = func(key string) anthropic.Client {
	return anthropic.NewClient(option.WithAPIKey(key), option.WithMaxRetries(1))
}

func benchMain(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("bench", flag.ContinueOnError)
	fs.SetOutput(stderr)
	models := fs.String("models", strings.Join(allModels, ","), "comma-separated model ids")
	n := fs.Int("n", 3, "runs per model and question")
	fs.IntVar(n, "runs", 3, "same as -n")
	effort := fs.String("effort", "low", "effort for Sonnet 5 and Opus 5.5 (low, medium, high)")
	web := fs.Bool("web", false, "enable web search (billed per search)")
	tsv := fs.Bool("tsv", false, "one tab-separated line per request instead of the table")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	key := os.Getenv("ANTHROPIC_API_KEY")
	if key == "" {
		fmt.Fprintln(stderr, "bench: set ANTHROPIC_API_KEY (the bench never reads stoandl's config or keyring)")
		return 2
	}
	if *n < 1 {
		fmt.Fprintln(stderr, "bench: -n must be at least 1")
		return 2
	}
	client := benchClient(key)
	var list []string
	for _, m := range strings.Split(*models, ",") {
		if m = strings.TrimSpace(m); m != "" {
			list = append(list, m)
		}
	}
	if *tsv {
		fmt.Fprintln(stdout, "model\tq#\trun\tttft_ms\ttotal_ms\tin\tout\tbytes\tstop\tcost")
	}
	var sums []benchSummary
	for _, model := range list {
		var runs []benchRun
		for qi, q := range benchQuestions {
			for run := 1; run <= *n; run++ {
				r := benchOne(client, model, *effort, *web, q)
				r.Q, r.Run = qi+1, run
				runs = append(runs, r)
				if *tsv {
					stop := r.Stop
					if r.Err != "" {
						stop = "error:" + r.Err
					}
					fmt.Fprintf(stdout, "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%.6f\n", model, r.Q, r.Run,
						r.TTFT.Milliseconds(), r.Total.Milliseconds(), r.In, r.Out, r.Bytes, stop, r.Cost)
				} else {
					fmt.Fprint(stderr, ".")
				}
			}
		}
		sums = append(sums, summarize(model, runs))
	}
	if !*tsv {
		fmt.Fprintln(stderr)
		printSummaries(stdout, sums, *n)
	}
	return 0
}

func benchOne(client anthropic.Client, model, effort string, web bool, q string) benchRun {
	req := askRequest{Model: model, Effort: effort, MaxTokens: 2048, WebSearch: web,
		Prompt: timeLine(time.Now()) + "\n\n" + q}
	res := runAsk(context.Background(), client, req, streamHooks{})
	r := benchRun{Model: model, TTFT: res.TTFT, Total: res.Total, In: res.In, Out: res.Out,
		Stop: res.Stop, Searches: res.Searches, Cost: res.Cost}
	if res.Err != nil {
		r.Err = res.Err.Title
	} else {
		r.Bytes = len(cleanText(res.Text))
	}
	return r
}

func printSummaries(w io.Writer, sums []benchSummary, n int) {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', tabwriter.AlignRight)
	fmt.Fprintln(tw, "model\truns\terrors\tTTFT p50\tTTFT p90\ttotal p50\ttotal p90\tout tok\t$/question\t$ total\t")
	var all float64
	for _, s := range sums {
		fmt.Fprintf(tw, "%s\t%d\t%d\t%s\t%s\t%s\t%s\t%.0f\t%.5f\t%.4f\t\n", s.Model, s.Runs, s.Errors,
			ms(s.TTFT50), ms(s.TTFT90), ms(s.Total50), ms(s.Total90), s.MeanOut, s.MeanCost, s.SumCost)
		all += s.SumCost
	}
	tw.Flush()
	fmt.Fprintf(w, "\n%d questions x %d runs per model; estimated cost of this run: $%.4f (cache pricing ignored)\n",
		len(benchQuestions), n, all)
}

func ms(d time.Duration) string {
	if d == 0 {
		return "-"
	}
	return fmt.Sprintf("%d ms", d.Milliseconds())
}
