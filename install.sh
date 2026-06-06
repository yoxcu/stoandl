#!/bin/sh
set -e

./gradlew shadowJar

JAR=$(ls build/libs/stoandl-*-all.jar | head -1)

sudo install -Dm644 "$JAR"                  /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
sudo install -Dm755 packaging/stoandl-ctl    /usr/local/bin/stoandl

systemctl --user daemon-reload
systemctl --user enable --now stoandl

echo "Installed $JAR"
echo "Service status:"
systemctl --user status stoandl --no-pager
