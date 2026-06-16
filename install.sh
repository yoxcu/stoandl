#!/bin/sh
set -e

REMOTE=""
DEBUG=false
while [ $# -gt 0 ]; do
    case "$1" in
        -d) DEBUG=true ;;
        --remote) REMOTE="$2"; shift ;;
        --remote=*) REMOTE="${1#--remote=}" ;;
    esac
    shift
done

# Debug drop-in: path + payload shared by the local and remote install paths (single source).
DROPIN=/usr/lib/systemd/user/stoandl.service.d/debug.conf
DEBUG_DROPIN='[Service]\nEnvironment=STOANDL_LOG=DEBUG\n'

# clean first so build/libs holds only the freshly built jar. Without it, jars from
# previous builds linger and the selection below can grab a stale one.
./gradlew clean shadowJar

# Newest jar by mtime. A plain `ls | head -1` sorts alphabetically, so an older
# ...-1-gad779b8-... jar sorts before a newer ...-3-gc45a25c-... one and gets installed.
JAR=$(ls -t build/libs/stoandl-*-all.jar | head -1)

if [ -n "$REMOTE" ]; then
    JARNAME=$(basename "$JAR")
    TMP=/tmp/stoandl-install-$$

    # Build the remote install script locally ($TMP and $JARNAME are baked in by the
    # unquoted heredoc; DROPIN and systemctl references stay literal for the remote shell).
    SCRIPT=/tmp/stoandl-remote-$$.sh
    cat > "$SCRIPT" << EOF
#!/bin/sh
set -e
DROPIN=$DROPIN
sudo install -Dm644 $TMP/$JARNAME /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 $TMP/stoandl.service /usr/lib/systemd/user/stoandl.service
sudo install -Dm755 $TMP/stoandl-ctl /usr/local/bin/stoandl
EOF
    if $DEBUG; then
        cat >> "$SCRIPT" << EOF
sudo mkdir -p "\$(dirname "\$DROPIN")"
printf '$DEBUG_DROPIN' | sudo tee "\$DROPIN" > /dev/null
EOF
    else
        cat >> "$SCRIPT" << 'EOF'
sudo rm -f "$DROPIN"
EOF
    fi
    cat >> "$SCRIPT" << EOF
systemctl --user daemon-reload
systemctl --user enable --now stoandl
rm -rf $TMP
EOF

    ssh "$REMOTE" "mkdir -p $TMP"
    scp "$JAR" packaging/stoandl.service packaging/stoandl-ctl "$SCRIPT" "$REMOTE:$TMP/"
    rm "$SCRIPT"
    # -t allocates a PTY on the remote so sudo can prompt for a password
    ssh -t "$REMOTE" "sh $TMP/$(basename "$SCRIPT")"

    echo "Installed $JAR on $REMOTE"
    echo "Service status:"
    ssh "$REMOTE" "systemctl --user status stoandl --no-pager"
else
    sudo install -Dm644 "$JAR"                    /usr/lib/stoandl/stoandl.jar
    sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
    sudo install -Dm755 packaging/stoandl-ctl     /usr/local/bin/stoandl

    # Install or remove the debug drop-in
    if $DEBUG; then
        sudo mkdir -p "$(dirname "$DROPIN")"
        printf "$DEBUG_DROPIN" | sudo tee "$DROPIN" > /dev/null
        echo "Debug logging enabled (STOANDL_LOG=DEBUG)"
    else
        sudo rm -f "$DROPIN"
    fi

    systemctl --user daemon-reload
    systemctl --user enable --now stoandl

    echo "Installed $JAR"
    echo "Service status:"
    systemctl --user status stoandl --no-pager
fi
