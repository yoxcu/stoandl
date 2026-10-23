# assistant: ask Claude from your wrist

A stoandl extension that answers questions with Claude (your own Anthropic API key), as notification
cards on any Pebble or streamed into the bundled **Assistant** watchapp. User guide:
[docs/assistant.md](../../../docs/assistant.md). Contract between the daemon, this extension and the
watchapp: [PROTOCOL.md](PROTOCOL.md).

## Build

```sh
./package.sh            # watchapp (Pebble SDK, if present) + static arm64 and amd64 binaries
./package.sh amd64      # one arch
PEBBLE=~/.cache/pebble-tool-venv/bin/pebble ./package.sh   # when another `pebble` comes first on PATH
```

It produces `assistant-<arch>.tar.gz` holding `assistant/{stoandl-assistant, manifest.json,
config.example, assistant.pbw}`. The binary is `CGO_ENABLED=0`, so it runs on glibc and musl alike.

## Install and set the key

```sh
stoandl ext install assistant-arm64.tar.gz
```

Set the API key in the GUI (Apps & Faces → Extensions → assistant); it goes to the keyring. The
extension starts once a key is stored. All other settings are in [config.example](config.example), each
at its default.

## Use

- **Notifications:** open the "Ask Claude" card → Reply → speak or pick a preset. The answer comes back
  as a card; Reply on it to follow up, **More** for the next page.
- **Watchapp:** launch Assistant; dictation starts at once and the answer streams in.
- **To-dos:** say "don't let me forget to …"; Claude saves it in stoandl's to-do list (`calendar.todos`,
  Settings → Calendar → To-do list), with the due time you said.
- **Read aloud** (Pebble Time 2 and 2 Duo): once text to speech is set up in stoandl (Settings → Voice),
  answers in the app are spoken on the watch speaker (`speak = always`, the default; silent in Quiet
  Time). Hold Down reads the answer on screen, or stops; Select stops it before you speak.

## Bench

```sh
ANTHROPIC_API_KEY=sk-ant-… ./stoandl-assistant bench [-models m1,m2] [-n 3] [-effort low] [-web] [-tsv]
```

Six fixed questions per model, run in sequence; prints TTFT and total p50/p90, mean output tokens and the
estimated cost. It reads only `ANTHROPIC_API_KEY` (and `ANTHROPIC_BASE_URL`), never stoandl's config.

## Develop

```sh
go vet ./... && go test -race ./...
```

The tests run offline: an `httptest` fake of the streaming Messages endpoint drives the request code,
the notification path, the watchapp chunk stream and the speech stream. `stoandl_ext.go` is a copy of
[`../stoandl_ext.go`](../stoandl_ext.go), and `watchapp/src/c/stoandl_speaker.{c,h}` are copies of
[`../watchlib/`](../watchlib/); keep them identical (`TestVendoredCopies` checks).

## License

GPLv3, same as stoandl. See [LICENSE](LICENSE).
