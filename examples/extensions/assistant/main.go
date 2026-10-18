// stoandl-assistant: ask Claude from the wrist. A stoandl extension (see PROTOCOL.md): questions come
// in as notification replies or from the bundled Assistant watchapp, answers go back as notification
// cards or AppMessage chunks. `stoandl-assistant bench` measures latency and cost per model by hand.
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) > 1 {
		if os.Args[1] == "bench" {
			os.Exit(benchMain(os.Args[2:], os.Stdout, os.Stderr))
		}
		fmt.Fprintln(os.Stderr, "usage: stoandl-assistant            (run by stoandl as an extension)")
		fmt.Fprintln(os.Stderr, "       stoandl-assistant bench [-models m1,m2] [-n 3] [-effort low] [-web] [-tsv]")
		os.Exit(2)
	}
	ext := NewExt()
	a := newAssistant(ext)
	ext.OnInitialize = a.onInitialize
	ext.OnWatchConnected = a.onWatchConnected
	ext.OnActionToken = func(itemID, tok, action string) { go a.onAction(itemID, tok, action) }
	ext.OnReplyToken = func(itemID, tok, text string) { go a.onReply(itemID, tok, text) }
	ext.OnAppMessage = func(uuid string, _ int, data map[string]any) { a.onAppMessage(uuid, data) }
	ext.Run("assistant", []string{"notify", "appmessage"}, []string{"api.anthropic.com:443"}, nil)
}
