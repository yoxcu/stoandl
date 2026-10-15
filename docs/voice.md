# Voice dictation: speech-to-text engines

stoandl turns the watch's dictation (Voice in a notification's Reply menu, watchapps' dictation API) and
extensions' voice notes into text with the backend in `voice.backend`:

- `command`: a local engine run per dictation. It gets a 16 kHz mono 16-bit WAV as `{wav}` and the language
  as `{lang}` (`voice.language`: a code such as `de`, or `auto`) and prints the transcript on stdout. The
  command line runs through `sh`, so `~` and pipes work. Nothing leaves the machine.
- `http`: an OpenAI-compatible endpoint (`voice.url`, `…/v1/audio/transcriptions`), optionally with
  `voice.model` and an API key (`stoandl daemon set voice.api_key …`, kept in the keyring). The audio
  leaves the machine.

The watch waits 15 s for the result after you stop speaking; stoandl gives the engine 14. Check a setup
without a watch: `stoandl voice` (status) and `stoandl voice test clip.wav`.

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

### Install

```sh
# postmarketOS / Alpine edge (community), Arch (extra: whisper-cpp), Debian sid (whisper.cpp-tools)
apk add whisper.cpp
mkdir -p ~/.local/share/stoandl/stt
wget -O ~/.local/share/stoandl/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin \
  https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q8_0.bin
```

### `voice.command` lines

```
# Parakeet v3 (ignores {lang}: it detects the language). On a big.LITTLE phone keep it on the big cores:
voice.command = taskset -c 4-7 parakeet-cli -m ~/.local/share/stoandl/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin -t 4 --no-prints -f {wav}

# Whisper small, audio context cut to the watch's 15 s, greedy decoding:
voice.command = whisper-cli -m ~/.local/share/stoandl/stt/ggml-small-q8_0.bin -l {lang} -t 4 -ac 768 -bs 1 -bo 1 -nf -mc 0 -nt -np -f {wav}

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
