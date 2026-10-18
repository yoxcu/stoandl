#!/bin/sh
# Build the Assistant watchapp (if watchapp/ and the Pebble SDK are there), then static musl-clean
# companion binaries (CGO_ENABLED=0, pure Go, no libc) and bundle each into an installable archive:
#   assistant-<arch>.tar.gz   (assistant/{stoandl-assistant, manifest.json, config.example, assistant.pbw})
#
# Then, on the machine running stoandl (pick the arch):
#   stoandl ext install assistant-arm64.tar.gz   # phone (Plasma Mobile / postmarketOS)
#   stoandl ext install assistant-amd64.tar.gz   # desktop
#
# Needs the Go toolchain (go >= 1.25) on PATH. Without the Pebble SDK an existing watchapp/build/*.pbw
# is used; with none the archive ships without the watchapp (notifications still work).
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$here"

pbw=""
# PEBBLE overrides the Pebble SDK tool (another `pebble`, e.g. Canonical's, may come first on PATH).
pebble=${PEBBLE:-pebble}
if [ -f watchapp/wscript ] && "$pebble" build --help >/dev/null 2>&1; then
	echo "Building the Assistant watchapp (Pebble SDK)…"
	(cd watchapp && "$pebble" build)
fi
for f in watchapp/build/watchapp.pbw watchapp/build/*.pbw; do
	if [ -f "$f" ]; then
		pbw=$f
		break
	fi
done
if [ -z "$pbw" ]; then
	echo "  WARNING: no watchapp .pbw found; the archive will have notifications only." >&2
fi

build() {
	arch=$1
	echo "Building assistant ($arch, static)…"
	out=$(mktemp -d)
	mkdir "$out/assistant"
	CGO_ENABLED=0 GOOS=linux GOARCH="$arch" \
		go build -trimpath -ldflags="-s -w" -o "$out/assistant/stoandl-assistant" .
	if command -v readelf >/dev/null 2>&1; then
		if readelf -d "$out/assistant/stoandl-assistant" 2>/dev/null | grep -q 'NEEDED'; then
			echo "  WARNING: binary has dynamic dependencies" >&2
		fi
	fi
	cp manifest.json config.example "$out/assistant/"
	if [ -n "$pbw" ]; then
		cp "$pbw" "$out/assistant/assistant.pbw"
	fi
	tar czf "$here/assistant-$arch.tar.gz" -C "$out" assistant
	rm -rf "$out"
	echo "  → assistant-$arch.tar.gz"
}

if [ "$#" -gt 0 ]; then
	for a in "$@"; do build "$a"; done
else
	build arm64
	build amd64
fi

echo
echo "Install it on the stoandl host:  stoandl ext install assistant-<arch>.tar.gz"
echo "Then set the API key in the GUI (Extensions → assistant), or put api_key = … in"
echo "~/.config/stoandl/ext/assistant/config (the daemon moves it into the keyring) and: stoandl ext restart assistant"
