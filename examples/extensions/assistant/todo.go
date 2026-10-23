package main

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

// todoDue is a to-do's due as the extension saves it and says it: a moment in local time, or a whole day.
// Parsing it here, once, makes the spoken day the saved one: the daemon gets the same instant with its
// offset (wire), the confirmation reads it out (words).
type todoDue struct {
	t      time.Time
	allDay bool
}

// dueLayouts are the forms the daemon's TodoDue.parse takes besides a bare date: local wall time with or
// without seconds (a fraction of a second parses after the seconds too), and the same with an offset or Z.
var dueLayouts = []string{
	"2006-01-02T15:04:05Z07:00",
	"2006-01-02T15:04Z07:00",
	"2006-01-02T15:04:05",
	"2006-01-02T15:04",
}

// parseDue reads Claude's due in loc. A due with an offset or Z is moved into loc, so a stray Z is read
// out as the time actually saved instead of being refused. False when s is none of the daemon's forms.
func parseDue(s string, loc *time.Location) (todoDue, bool) {
	s = strings.ToUpper(strings.TrimSpace(s))
	if t, err := time.ParseInLocation("2006-01-02", s, loc); err == nil {
		return todoDue{t: t, allDay: true}, true
	}
	for _, l := range dueLayouts {
		if t, err := time.ParseInLocation(l, s, loc); err == nil {
			return todoDue{t: t.In(loc)}, true
		}
	}
	return todoDue{}, false
}

// wire is the due sent to the daemon: the bare date, or RFC 3339 with the offset, which the daemon saves
// as exactly that instant whatever its own zone.
func (d todoDue) wire() string {
	if d.allDay {
		return d.t.Format("2006-01-02")
	}
	return d.t.Format(time.RFC3339)
}

// pastGrace is how long a timed due may lie behind now: "at five" said at 17:00:40 is still meant.
const pastGrace = 5 * time.Minute

// past says the due is gone at now: a day before today, or a time more than pastGrace ago. Claude is told
// to ask instead, but late in the evening its date arithmetic is what slips, so it is checked here.
func (d todoDue) past(now time.Time) bool {
	if d.allDay {
		y, m, dd := now.Date()
		return d.t.Before(time.Date(y, m, dd, 0, 0, 0, 0, d.t.Location()))
	}
	return d.t.Before(now.Add(-pastGrace))
}

// todoLanguage is the language the due is said in: the `language` setting when set (ttsLanguage, the one
// source), else the language Claude wrote the confirmation in, so the default setting gets day names too.
func todoLanguage(setting, claude string) string {
	if strings.TrimSpace(setting) != "" {
		return ttsLanguage(setting)
	}
	return ttsLanguage(claude)
}

var (
	deWeekdays = [...]string{"Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"}
	deMonths   = [...]string{"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
		"September", "Oktober", "November", "Dezember"}
)

// words says the due relative to now in lang ("en" or "de", from todoLanguage): "tomorrow, Thursday 22
// October, 20:00" / "morgen, Donnerstag, 22. Oktober, 20:00", full names so a wrong day is heard; the year
// only when it isn't now's. Any other language gets the numeric "2026-10-22 20:00".
func (d todoDue) words(now time.Time, lang string) string {
	t := d.t.In(now.Location())
	if lang != "en" && lang != "de" {
		if d.allDay {
			return t.Format("2006-01-02")
		}
		return t.Format("2006-01-02 15:04")
	}
	ny, nm, nd := now.Date()
	ty, tm, td := t.Date()
	days := int(time.Date(ty, tm, td, 0, 0, 0, 0, time.UTC).Sub(time.Date(ny, nm, nd, 0, 0, 0, 0, time.UTC)) / (24 * time.Hour))
	year := ""
	if ty != ny {
		year = fmt.Sprintf(" %d", ty)
	}
	var s, rel string
	if lang == "de" {
		s = fmt.Sprintf("%s, %d. %s%s", deWeekdays[t.Weekday()], td, deMonths[tm-1], year)
		rel = map[int]string{0: "heute", 1: "morgen"}[days]
	} else {
		s = fmt.Sprintf("%s %d %s%s", t.Weekday(), td, tm, year)
		rel = map[int]string{0: "today", 1: "tomorrow"}[days]
	}
	if rel != "" {
		s = rel + ", " + s
	}
	if !d.allDay {
		s += ", " + t.Format("15:04")
	}
	return s
}

// duePlaceholder is where the confirmation says the due; an empty one takes the separator before it along.
var duePlaceholder = regexp.MustCompile(`,?\s*\{due\}`)

// todoConfirmation fills Claude's confirmation with the due's words: in place of {due}, or appended
// before the closing punctuation when Claude left the placeholder out. Without a due, {due} goes.
func todoConfirmation(confirmation, due string) string {
	if due == "" {
		return strings.TrimSpace(duePlaceholder.ReplaceAllString(confirmation, ""))
	}
	if strings.Contains(confirmation, "{due}") {
		return strings.ReplaceAll(confirmation, "{due}", due)
	}
	body := strings.TrimRight(confirmation, ".!。")
	return body + ", " + due + confirmation[len(body):]
}
