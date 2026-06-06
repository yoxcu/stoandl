#!/bin/sh
set -e

DEBUG=false
for arg in "$@"; do
    case "$arg" in
        -d) DEBUG=true ;;
    esac
done

./gradlew shadowJar

JAR=$(ls build/libs/stoandl-*-all.jar | head -1)

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
