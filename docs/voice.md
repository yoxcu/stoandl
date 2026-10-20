# Voice: speech to text and text to speech

stoandl turns the watch's dictation (Voice in a notification's Reply menu, watchapps' dictation API) and
extensions' voice notes into text with the backend in `voice.backend`:

- `local` (recommended): a downloaded model run by whisper.cpp on this machine. Nothing leaves it. Set up
  below; the GUI's Settings → Voice does the same with buttons.
- `command`: your own engine run per dictation. It gets a 16 kHz mono 16-bit WAV as `{wav}` and the language
  as `{lang}` (`voice.language`: a code such as `de`, or `auto`) and prints the transcript on stdout. The
  command line runs through `sh`, so `~` and pipes work. Nothing leaves the machine.
- `http`: an OpenAI-compatible endpoint (`voice.url`, `…/v1/audio/transcriptions`), optionally with
  `voice.http_model` and an API key (`stoandl daemon set voice.api_key …`, kept in the keyring). The audio
  leaves the machine.

The watch waits 15 s for the result after you stop speaking; stoandl gives the engine 14. Check a setup
without a watch: `stoandl voice` (status) and `stoandl voice test clip.wav`, or in the GUI Settings → Voice
→ Speech to text → *Try a recording…*, which transcribes a WAV or Ogg file you pick with the configured
backend (with the HTTP service the file leaves the machine).

The other direction, reading text aloud, is [Text to speech](#text-to-speech) below.

## Fallback

`voice.fallback_backend` (`off` by default) names a second engine, with the same per-engine settings
(`voice.local_model`, `voice.command`, `voice.url`, …), so a local model and a `whisper-server` on the LAN can
back each other up. It runs after the first engine, on the same audio, when that one isn't ready (no model
downloaded, no URL), fails, is unreachable, hears nothing, or doesn't answer within its share of the time.
Like the official app's speech router, a dictation's first engine gets the 14 s minus 7 s kept for the
fallback, which also gets whatever the first didn't use; a file transcription keeps 7 s of its minute. When
both fail, the watch shows the fallback's error ("No internet connection" when it was unreachable); when the
first heard nothing and the fallback fails, it says "Missed that". With a fallback set, `stoandl voice`
shows both (`ready — local Parakeet v3 then http http://…`), or the fallback alone when the first isn't
ready.

The watch's audio is decoded with libspeex (Alpine `speex`, Debian `libspeex1`, Arch `speex`), voice notes
for a local engine with libopus (`opus`). The postmarketOS/Alpine package depends on both.

## Recommended local engines (open models that run on older hardware)

From a survey in October 2026 ([next-work notes](../next-work/official-app-parity/stt-models-oldish-hw.md)
in the repo checkout). Latencies are estimates for a 10 s German utterance until measured
([TESTING §5.46c](../TESTING.md)).

| Use | Engine and model | Size | German WER (FLEURS) | ~10 s utterance |
|---|---|---|---|---|
| **Phone (postmarketOS, e.g. OnePlus 6) and old desktops** | NVIDIA Parakeet TDT 0.6B v3 (25 European languages, detects the language itself), `parakeet-cli` from whisper.cpp ≥ 1.9 | 669 MB (q8_0) | 5.0 % | phone 2–5 s, 2015 quad-core 1–2 s |
| Lighter fallback | Whisper small (q8_0), `whisper-cli` | 264 MB | 10.2 % | phone 5–8 s |
| Best accuracy on a fast box | Whisper large-v3-turbo (q8_0), `whisper-cli`, or as a `whisper-server` reached over `http` | 874 MB | ~4.5 % | fast 8-core 2–5 s |

Not recommended: Whisper tiny/base for German (28 % / 18 % WER), Vosk small (fast but weak), anything
needing a GPU. Moonshine (German model since 2026) and Canary-180M are promising but have no runtime
on musl/postmarketOS yet.

### Set up (local model)

```sh
# 1. The engine. postmarketOS / Alpine edge (community), Arch (extra: whisper-cpp), Debian sid (whisper.cpp-tools)
sudo apk add whisper.cpp
# 2. A model: the daemon downloads it into ~/.local/share/stoandl/stt and, when voice was off, switches to it
stoandl voice models
stoandl voice download parakeet-v3
stoandl voice                        # Voice: ready — local Parakeet v3
stoandl voice test clip.wav          # optional: any WAV or Ogg/Opus file
```

In the GUI, Settings → Voice has two cards: speech models under *Speech to text*, voices under *Text to
speech*. The models card offers the recommended model while none is installed, shows the download's
progress, and the Model dropdown lists the downloaded models. The download from huggingface.co is the only
network access; it happens only when you start it. `stoandl voice use <id>` switches between downloaded
models, `stoandl voice remove <id>` deletes one.

The local backend runs on the CPU (`-ng`), on the big cores of a big.LITTLE phone (`taskset`, Alpine
`util-linux-misc`). Distro whisper.cpp builds enable Vulkan, and on GPUs without 16-bit storage (the OnePlus
6's Adreno 630) it aborts loading the model; a short dictation gains little from a GPU anyway. To use one,
run the engine yourself with the custom command backend.

Measured on the OnePlus 6 (postmarketOS, whisper.cpp 1.9.4, Parakeet v3 q8_0): a 4 s English clip in 4.5 s,
model load included.

### `voice.command` lines (custom command)

The local backend runs these for you; write your own only for another engine or other flags.

```
# Parakeet v3 (ignores {lang}: it detects the language). On a big.LITTLE phone keep it on the big cores:
voice.command = taskset -c 4-7 parakeet-cli -m ~/.local/share/stoandl/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin -t 4 -ng --no-prints -f {wav}

# Whisper small, audio context cut to the watch's 15 s, greedy decoding:
voice.command = whisper-cli -m ~/.local/share/stoandl/stt/ggml-small-q8_0.bin -l {lang} -t 4 -ng -ac 768 -bs 1 -bo 1 -nf -mc 0 -nt -np -f {wav}

# Whisper large-v3-turbo on a fast machine:
voice.command = whisper-cli -m ~/.local/share/stoandl/stt/ggml-large-v3-turbo-q8_0.bin -l {lang} -t 8 -mc 0 -nt -np -f {wav}
```

Models: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{small-q8_0,large-v3-turbo-q8_0}.bin`;
smaller Parakeet files (`q4_k` 416 MB, `q4_0` 356 MB) are in the same repository as q8_0 and may be faster on
ARM. `taskset` is in `util-linux-misc` on Alpine.

### Pitfalls

- whisper.cpp 1.9.4's `parakeet-cli` can exit 0 on failure; stoandl treats empty output with an error on
  stderr as a failure.
- Whisper pads to 30 s: `-ac 768` (≈15 s) cuts that cost without hurting short dictation.
- Whisper invents "Untertitel der Amara.org-Community"-style lines on silence; stoandl drops the known ones.
- The first dictation after a while reloads the model from disk (1–2 s more). For whisper models a resident
  `whisper-server` on loopback with `voice.backend = http` avoids it.
- Licences: Parakeet weights CC-BY-4.0 (NVIDIA), Whisper MIT.

## Text to speech

stoandl can also read text aloud, with the engine in `tts.backend` (off by default, so nothing speaks out
of the box):

- The [assistant extension](assistant.md) reads its answers on the **watch speaker** (Pebble Time 2,
  Pebble 2 Duo): its `speak` setting is `always` (default), `on_request` (Hold Down) or `off`, inert until
  `tts.backend` is set. Any extension can do the same through `synthesize`
  ([extensions.md](extensions.md#synthesize-text-to-speech-for-the-extensions-watchapp)); the audio goes to the watch
  as described in [speaker-stream.md](speaker-stream.md).
- `stoandl voice say <text>` and the GUI's *Test voice* play on **this device**, through the first of
  `pw-play` (PipeWire, `pipewire-tools`), `paplay` (`pulseaudio-utils`) or `aplay` (`alsa-utils`) installed.
  A new `say` stops the one before; `stoandl voice stop` stops it. The command returns at once ("Reading
  aloud — stoandl voice stop to stop"); a failure after that is in the daemon log.

### Engines (`tts.backend`)

- `local` (recommended): **Piper** with a downloaded voice, or **espeak-ng**. Piper is found as `piper_exe`
  (libpiper's native binary, preferred: it starts faster), then `piper-tts`, then a bare `piper` only if
  its `--help` names the length scale — on Arch and Alpine `piper` is also libratbag's mouse configurator,
  so install `piper-tts`. Piper runs on the big cores of a big.LITTLE phone (`taskset`), and `tts.speed`
  maps to its length scale (100/speed). Pick `espeak-ng` as `tts.local_voice` for no download and every
  language, at the cost of a robotic voice (`-s 175 × speed/100`). Nothing leaves the machine.
- `command` (`tts.command`): your own engine (Kokoro, sherpa-onnx, RHVoice, …). It gets the text on stdin
  and writes a WAV to stdout; `{lang}` is replaced by the text's language (`de`, `en`; the primary subtag of
  `de-DE`), or for `voice say`, which has none, the system locale's language, else `en`. Runs through `sh`.
- `http` (`tts.url`): an OpenAI-compatible `/v1/audio/speech` endpoint (speaches, LocalAI, openedai-speech,
  or a hosted service). stoandl sends `{model, voice, input, speed, response_format: "wav"}` with
  `tts.http_model`/`tts.http_voice` and `tts.api_key` as a Bearer token. That key is separate from
  `voice.api_key` and never inherited from it, so a key only reaches the server it was meant for. **The
  text leaves the machine.** A WAV streamed with unfilled sizes (`0xFFFFFFFF`) is read fine.

`stoandl voice` (and `stoandl status`) shows both directions: `Text to speech: ready — local Thorsten
(German)`, or why it isn't ready. The text is never logged, only its length (`Text to speech (local): 42
characters → 2900 ms of audio in 1800 ms`). Engine WAVs live in `$XDG_RUNTIME_DIR/stoandl/tts` and are
deleted after use (an extension's `wav` result after 10 minutes). The system is kept awake (a logind
block) while a synthesis runs and while `voice say` plays; one synthesis runs at a time.

### Voices

`stoandl voice models` lists them under *Text to speech (tts.local_voice)*; `stoandl voice download
<id>` fetches one from huggingface.co/rhasspy/piper-voices (the `.onnx` and its `.onnx.json`, into
`~/.local/share/stoandl/tts/`; speech models stay in `…/stt/`). A finished download becomes
`tts.local_voice` and turns `tts.backend` to `local` if it was `off`. `stoandl voice use <id>` switches,
`stoandl voice remove <id>` deletes both files.

| Id | Voice | Size | Rate | Licence | Use |
|---|---|---|---|---|---|
| `piper-de-thorsten-low` | German, male (Thorsten-Voice) | 63 MB | 16 kHz | CC0 | **recommended for the watch** |
| `piper-en-kathleen-low` | US English, female (Kathleen dataset) | 63 MB | 16 kHz | CC0 | **recommended for the watch** |
| `piper-de-eva_k-x_low` | German, female (M-AILABS) | 21 MB | 16 kHz | M-AILABS licence (BSD-style) | smallest and fastest, rougher |
| `piper-de-thorsten-medium` | German, male (Thorsten-Voice) | 63 MB | 22 kHz | CC0 | host playback |
| `piper-en-ljspeech-medium` | US English, female (LJ Speech) | 64 MB | 22 kHz | public domain | host playback |

The watch speaker plays 8 kHz audio, so a "low" voice is enough there and the fastest to make. A medium
voice sounds better on this device's speakers but is slower to make, and like any downloaded voice it
becomes `tts.local_voice`, so it is also the watch's voice for its language: a phone's reading on the watch
then waits longer. lessac is not offered (its Blizzard licence is unclear), nor ryan (CC BY-NC-SA).

**Languages.** The assistant passes `de` or `en` (its `language` setting, German or English). stoandl then
speaks with `tts.local_voice` when that voice speaks the language (espeak-ng speaks all), else with the
first installed voice in that language, else `tts.local_voice` anyway. So installing one German and one
English voice makes a bilingual assistant work with no further setting.

In the GUI, the voices card under Settings → Voice → *Text to speech* shows the status (`TtsStatus`), a
download list like the models card, an install hint for Piper or espeak-ng (quiet while text to speech is
already ready, e.g. with espeak-ng), and *Test voice* / *Stop*, which play "This is stoandl speaking." on
this computer.

### Set up (local)

```sh
# 1. An engine. postmarketOS / Alpine: espeak-ng is in community, Piper is piper-tts in edge's testing
#    repository. Elsewhere: the distro's espeak-ng, and Piper TTS (piper-tts) from the distro or upstream.
sudo apk add espeak-ng          # and/or: sudo apk add piper-tts  (edge testing)
# 2. A voice (or use espeak-ng: stoandl voice use espeak-ng)
stoandl voice download piper-de-thorsten-low
stoandl voice download piper-en-kathleen-low   # optional: English too
stoandl voice                                  # Text to speech: ready — local Thorsten (German)
stoandl voice say "Guten Tag"                   # plays on this device
```

Voice licences: Thorsten-Voice and Kathleen CC0, LJ Speech public domain, M-AILABS BSD-style (see the table).
