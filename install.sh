#!/bin/sh
set -e

DEBUG=false
for arg in "$@"; do
    case "$arg" in
        -d) DEBUG=true ;;
    esac
done

# clean first so build/libs holds only the freshly built jar. Without it, jars from
# previous builds linger and the selection below can grab a stale one.
./gradlew clean shadowJar

# Newest jar by mtime. A plain `ls | head -1` sorts alphabetically, so an older
# ...-1-gad779b8-... jar sorts before a newer ...-3-gc45a25c-... one and gets installed.
JAR=$(ls -t build/libs/stoandl-*-all.jar | head -1)

sudo install -Dm644 "$JAR"                    /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
sudo install -Dm755 packaging/stoandl-ctl     /usr/local/bin/stoandl

# Install or remove the debug drop-in
DROPIN=/usr/lib/systemd/user/stoandl.service.d/debug.conf
if $DEBUG; then
    sudo mkdir -p "$(dirname "$DROPIN")"
    printf '[Service]\nEnvironment=STOANDL_LOG=DEBUG\n' | sudo tee "$DROPIN" > /dev/null
    echo "Debug logging enabled (STOANDL_LOG=DEBUG)"
else
    sudo rm -f "$DROPIN"
fi

systemctl --user daemon-reload
systemctl --user enable --now stoandl

echo "Installed $JAR"
echo "Service status:"
systemctl --user status stoandl --no-pager
