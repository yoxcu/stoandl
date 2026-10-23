package main

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
	_ "time/tzdata" // Europe/Berlin without the system's zoneinfo
)

func berlin(t *testing.T) *time.Location {
	t.Helper()
	loc, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	return loc
}

func TestDaysLine(t *testing.T) {
	loc := berlin(t)
	cases := []struct {
		name string
		now  time.Time
		want string
	}{
		{"late evening", time.Date(2026, 10, 21, 23, 2, 0, 0, loc),
			"Days: today Wed 2026-10-21, tomorrow Thu 2026-10-22, Fri 2026-10-23, Sat 2026-10-24, " +
				"Sun 2026-10-25, Mon 2026-10-26, Tue 2026-10-27, Wed 2026-10-28"},
		// Summer time ends at 03:00 on Sun 25 Oct: the 25-hour day is listed once.
		{"DST end ahead", time.Date(2026, 10, 24, 23, 59, 0, 0, loc),
			"Days: today Sat 2026-10-24, tomorrow Sun 2026-10-25, Mon 2026-10-26, Tue 2026-10-27, " +
				"Wed 2026-10-28, Thu 2026-10-29, Fri 2026-10-30, Sat 2026-10-31"},
		{"DST end night", time.Date(2026, 10, 25, 2, 30, 0, 0, loc),
			"Days: today Sun 2026-10-25, tomorrow Mon 2026-10-26, Tue 2026-10-27, Wed 2026-10-28, " +
				"Thu 2026-10-29, Fri 2026-10-30, Sat 2026-10-31, Sun 2026-11-01"},
		{"year end", time.Date(2026, 12, 31, 22, 0, 0, 0, loc),
			"Days: today Thu 2026-12-31, tomorrow Fri 2027-01-01, Sat 2027-01-02, Sun 2027-01-03, " +
				"Mon 2027-01-04, Tue 2027-01-05, Wed 2027-01-06, Thu 2027-01-07"},
	}
	for _, c := range cases {
		if got := daysLine(c.now); got != c.want {
			t.Errorf("%s:\n got %s\nwant %s", c.name, got, c.want)
		}
	}
	now := cases[0].now
	if lines := strings.Split(timeLine(now), "\n"); len(lines) != 2 || lines[1] != daysLine(now) {
		t.Errorf("timeLine: %q", timeLine(now))
	}
}

// Every form the daemon's TodoDue.parse takes is read, in local time, and sent as the instant with its
// offset (or the bare date); anything else is refused.
func TestParseDue(t *testing.T) {
	loc := berlin(t)
	ok := map[string]string{
		"2026-10-22":                  "2026-10-22",
		"2026-10-22T20:00":            "2026-10-22T20:00:00+02:00",
		" 2026-10-22T20:00:00 ":       "2026-10-22T20:00:00+02:00",
		"2026-10-22T20:00:00.000":     "2026-10-22T20:00:00+02:00",
		"2026-10-22t20:00":            "2026-10-22T20:00:00+02:00",
		"2026-10-22T20:00+02:00":      "2026-10-22T20:00:00+02:00",
		"2026-10-22T20:00:00+02:00":   "2026-10-22T20:00:00+02:00",
		"2026-10-22T18:00Z":           "2026-10-22T20:00:00+02:00", // a stray Z is the time it saves
		"2026-10-22T18:00:00Z":        "2026-10-22T20:00:00+02:00",
		"2026-10-22T12:00:00-04:00":   "2026-10-22T18:00:00+02:00",
		"2026-10-26T09:00":            "2026-10-26T09:00:00+01:00", // after the DST change
		"2026-10-22T20:00:00.5+02:00": "2026-10-22T20:00:00+02:00",
	}
	for in, want := range ok {
		d, parsed := parseDue(in, loc)
		if !parsed || d.wire() != want {
			t.Errorf("parseDue(%q) = %q, %v; want %q", in, d.wire(), parsed, want)
		}
	}
	for _, in := range []string{"tomorrow", "2026-10-22 20:00", "22.10.2026", "2026-10-22T25:00",
		"2026-13-01", "2026-10-22T20", "8 pm"} {
		if d, parsed := parseDue(in, loc); parsed {
			t.Errorf("parseDue(%q) = %q, want refused", in, d.wire())
		}
	}
}

func TestDueWords(t *testing.T) {
	loc := berlin(t)
	now := time.Date(2026, 10, 21, 23, 2, 0, 0, loc)
	cases := []struct{ due, lang, want string }{
		{"2026-10-21T23:30", "en", "today, Wednesday 21 October, 23:30"},
		{"2026-10-22T20:00", "en", "tomorrow, Thursday 22 October, 20:00"},
		{"2026-10-23T08:05", "en", "Friday 23 October, 08:05"},
		{"2026-10-22", "en", "tomorrow, Thursday 22 October"},
		{"2027-01-04", "en", "Monday 4 January 2027"},
		{"2026-10-21T23:30", "de", "heute, Mittwoch, 21. Oktober, 23:30"},
		{"2026-10-22T20:00", "de", "morgen, Donnerstag, 22. Oktober, 20:00"},
		{"2026-10-23T08:05", "de", "Freitag, 23. Oktober, 08:05"},
		{"2026-10-22", "de", "morgen, Donnerstag, 22. Oktober"},
		{"2027-03-01T10:00", "de", "Montag, 1. März 2027, 10:00"},
		{"2026-10-22T18:00Z", "en", "tomorrow, Thursday 22 October, 20:00"},
		{"2026-10-22T20:00", "", "2026-10-22 20:00"},
		{"2026-10-22", "", "2026-10-22"},
	}
	for _, c := range cases {
		d, ok := parseDue(c.due, loc)
		if !ok {
			t.Fatalf("parseDue(%q) refused", c.due)
		}
		if got := d.words(now, c.lang); got != c.want {
			t.Errorf("words(%q, %q) = %q, want %q", c.due, c.lang, got, c.want)
		}
	}
	// Just after midnight "tomorrow" is the next date, as the Days line says.
	d, _ := parseDue("2026-10-22T09:00", loc)
	if got := d.words(time.Date(2026, 10, 22, 0, 30, 0, 0, loc), "en"); got != "today, Thursday 22 October, 09:00" {
		t.Errorf("after midnight: %q", got)
	}
}

func TestTodoConfirmation(t *testing.T) {
	due := "tomorrow, Thursday 22 October, 20:00"
	cases := []struct{ conf, due, want string }{
		{"Noted: call the garage, {due}.", due, "Noted: call the garage, tomorrow, Thursday 22 October, 20:00."},
		{"Notiert: {due} Garage anrufen.", "morgen", "Notiert: morgen Garage anrufen."},
		{"Noted: call the garage.", due, "Noted: call the garage, tomorrow, Thursday 22 October, 20:00."},
		{"Noted", due, "Noted, tomorrow, Thursday 22 October, 20:00"},
		{"Noted: call the garage, {due}.", "", "Noted: call the garage."},
		{"Notiert: {due} Garage anrufen.", "", "Notiert: Garage anrufen."},
		{"Noted: buy milk.", "", "Noted: buy milk."},
	}
	for _, c := range cases {
		if got := todoConfirmation(c.conf, c.due); got != c.want {
			t.Errorf("todoConfirmation(%q, %q) = %q, want %q", c.conf, c.due, got, c.want)
		}
	}
}

// A due the extension can't read, or one already gone, is never sent: the answer says why.
func TestTodoRefusedDueIsNotSent(t *testing.T) {
	now := time.Date(2026, 10, 21, 23, 2, 0, 0, berlin(t))
	cases := []struct{ name, due, want string }{
		{"unclear", "tomorrow at 5", "Couldn't save the to-do: unclear date"},
		{"midnight just past", "2026-10-21T00:00", "Couldn't save the to-do: that time has passed"},
		{"yesterday", "2026-10-20", "Couldn't save the to-do: that time has passed"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			in := todoInput{Title: "Call the garage", Due: c.due, Confirmation: "Noted: call the garage, {due}."}
			f := newFakeAPI(t, fakeResponse{events: sseMessage("tool_use", 300, 40, todoBlock("toolu_1", in))})
			a, out := newTestAssistant(t, f, nil)
			a.mu.Lock()
			a.now = func() time.Time { return now }
			a.mu.Unlock()
			stop := fakeHost(a, out, func(l map[string]any) map[string]any {
				switch l["method"] {
				case "addTodo":
					return map[string]any{"status": "saved"}
				case "keepAwake":
					return map[string]any{"token": "t"}
				}
				return nil
			})
			defer stop()
			a.onReply("item1", anchorToken, "don't let me forget to call the garage")
			n := params(waitFor(t, out, "answer card", isNotify))
			if n["body"] != c.want {
				t.Fatalf("body %q, want %q", n["body"], c.want)
			}
			for _, l := range out.lines() {
				if l["method"] == "addTodo" {
					t.Fatalf("a refused due was sent: %v", l)
				}
			}
		})
	}
}

func TestDuePast(t *testing.T) {
	loc := berlin(t)
	now := time.Date(2026, 10, 21, 23, 2, 0, 0, loc)
	cases := map[string]bool{
		"2026-10-21T00:00":          true, // midnight just past
		"2026-10-21T22:56":          true,
		"2026-10-21T22:58":          false, // within the grace: "at eleven" said at 23:02
		"2026-10-22T00:00":          false,
		"2026-10-21T20:00:00Z":      true,  // 22:00 here
		"2026-10-21":                false, // today, all day
		"2026-10-20":                true,
		"2026-10-22":                false,
		"2026-10-21T23:30:00+02:00": false,
	}
	for in, want := range cases {
		d, ok := parseDue(in, loc)
		if !ok {
			t.Fatalf("parseDue(%q) refused", in)
		}
		if got := d.past(now); got != want {
			t.Errorf("past(%q) = %v, want %v", in, got, want)
		}
	}
}

// The `language` setting decides alone when set; only an empty one takes the language Claude names.
func TestTodoLanguage(t *testing.T) {
	cases := []struct{ setting, claude, want string }{
		{"German", "en", "de"},
		{"English", "de", "en"},
		{"French", "de", ""},
		{"", "de", "de"},
		{"", "EN", "en"},
		{"", "fr", ""},
		{"", "", ""},
		{"  ", "de", "de"},
	}
	for _, c := range cases {
		if got := todoLanguage(c.setting, c.claude); got != c.want {
			t.Errorf("todoLanguage(%q, %q) = %q, want %q", c.setting, c.claude, got, c.want)
		}
	}
}

// zoneData is a zone's TZif file from Go's own zoneinfo.zip, so the test needs no system zoneinfo.
func zoneData(t *testing.T, name string) []byte {
	t.Helper()
	z, err := zip.OpenReader(filepath.Join(runtime.GOROOT(), "lib", "time", "zoneinfo.zip"))
	if err != nil {
		t.Skip("no zoneinfo.zip:", err)
	}
	defer z.Close()
	f, err := z.Open(name)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	b, err := io.ReadAll(f)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// The assistant's clock follows the host's zone between two asks, as the daemon does, so a to-do's due
// is saved in the zone the phone has now, not the one the extension started in.
func TestLocalZoneFollowsHost(t *testing.T) {
	t.Setenv("TZ", "")
	os.Unsetenv("TZ")
	dir := t.TempDir()
	for _, z := range []string{"Europe/Berlin", "America/New_York"} {
		p := filepath.Join(dir, "zoneinfo", z)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, zoneData(t, z), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	link := filepath.Join(dir, "localtime")
	old := localtimePath
	localtimePath = link
	defer func() { localtimePath = old }()
	a := newAssistant(nil)
	setZone := func(z string) {
		os.Remove(link)
		if err := os.Symlink(filepath.Join(dir, "zoneinfo", z), link); err != nil {
			t.Fatal(err)
		}
	}
	for _, c := range []struct{ zone, wire string }{
		{"Europe/Berlin", "2026-10-22T17:00:00+02:00"},
		{"America/New_York", "2026-10-22T17:00:00-04:00"},
	} {
		setZone(c.zone)
		now := a.now()
		if got := now.Location().String(); got != c.zone {
			t.Errorf("zone %q, want %q", got, c.zone)
		}
		if !strings.Contains(timeLine(now), "("+c.zone+")") {
			t.Errorf("time line %q doesn't name %s", timeLine(now), c.zone)
		}
		if d, _ := parseDue("2026-10-22T17:00", now.Location()); d.wire() != c.wire {
			t.Errorf("in %s: due %q, want %q", c.zone, d.wire(), c.wire)
		}
	}
	// A copied file (no zoneinfo/ in the path) still gives the zone's offsets.
	os.Remove(link)
	if err := os.WriteFile(link, zoneData(t, "Europe/Berlin"), 0o644); err != nil {
		t.Fatal(err)
	}
	if d, _ := parseDue("2026-10-22T17:00", a.now().Location()); d.wire() != "2026-10-22T17:00:00+02:00" {
		t.Errorf("copied file: due %q", d.wire())
	}
	if z := zoneName(a.now().Location()); z != "" {
		t.Errorf("copied file named %q", z)
	}
}
