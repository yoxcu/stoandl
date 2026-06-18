#!/bin/sh
# Build the find-my-phone watchapp and bundle it with the companion into an installable archive:
#   findphone.tar.gz  (findphone/findphone.py + findphone/stoandl_ext.py + findphone/findphone.pbw)
#
# Then install it in one shot:  stoandl ext install findphone.tar.gz
# (extracts to ~/.config/stoandl/ext/findphone/, sideloads the .pbw, enables + starts it — no restart).
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../.." && pwd)

echo "Building the watchapp…"
( cd "$repo/testing/findphone" && pebble build )

tmp=$(mktemp -d)
mkdir "$tmp/findphone"
cp "$here/findphone.py" "$here/stoandl_ext.py" "$tmp/findphone/"
cp "$repo/testing/findphone/build/findphone.pbw" "$tmp/findphone/"
tar czf "$here/findphone.tar.gz" -C "$tmp" findphone
rm -rf "$tmp"

echo "Created $here/findphone.tar.gz"
echo "Install it:  stoandl ext install $here/findphone.tar.gz"
