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

# Service (re)start, also shared by both paths. `restart`, not `enable --now`: --now only starts a
# stopped unit, so a running daemon kept the old JAR. reset-failed first: the unit stops restarting
# after 5 starts in 5 minutes (StartLimitBurst), and installing a fix is when that should be cleared.
START_SERVICE='systemctl --user daemon-reload
systemctl --user reset-failed stoandl 2>/dev/null || true
systemctl --user enable stoandl
systemctl --user restart stoandl'

# Drop old fat jars first so build/libs holds only the one built now: the selection below would
# otherwise grab a stale one. Not `clean`, which throws away every incremental compile.
#
# Gradle 8.14 CANNOT run on JDK 25 (its launcher hard-fails on class major 69), so pin its JVM to
# JDK 21. The Kotlin jvmToolchain(25) still auto-detects the JDK 25 under /usr/lib/jvm to compile
# libpebble3's java.lang.foreign (FFM) code to JDK 25 bytecode — so BOTH JDKs must be installed.
# Override BUILD_JAVA_HOME if your JDK 21 lives elsewhere.
#   Arch/Manjaro:  sudo pacman -S jdk21-openjdk jdk-openjdk
#   Debian/Ubuntu: sudo apt install openjdk-21-jdk openjdk-25-jdk
: "${BUILD_JAVA_HOME:=/usr/lib/jvm/java-21-openjdk}"
rm -f build/libs/stoandl-*-all.jar
JAVA_HOME="$BUILD_JAVA_HOME" ./gradlew shadowJar

# Newest jar by mtime. A plain `ls | head -1` sorts alphabetically, so an older
# ...-1-gad779b8-... jar sorts before a newer ...-3-gc45a25c-... one and gets installed.
JAR=$(ls -t build/libs/stoandl-*-all.jar | head -1)

# NOTE: the daemon must RUN on a JDK 25 runtime (libpebble3's BT Classic transport uses
# java.lang.foreign). The shipped unit + wrapper call /usr/bin/java and `java`, so make sure those
# resolve to JDK 25 on the target (e.g. Arch: archlinux-java set java-25-openjdk). See README.

if [ -n "$REMOTE" ]; then
    # The script run from the staging directory installs through sudo, so it must be one no other user
    # can pre-create and fill. With rsync on both ends: a directory in the remote user's cache that stays,
    # so the next install sends only the JAR's changed blocks (the build is reproducible), compressed.
    # Else a fresh directory mktemp creates (mode 0700, unpredictable name), removed afterwards.
    if command -v rsync > /dev/null && ssh "$REMOTE" 'command -v rsync' > /dev/null; then
        TMP=$(ssh "$REMOTE" 'd="${XDG_CACHE_HOME:-$HOME/.cache}/stoandl/install" && mkdir -p "$d" && chmod 700 "$d" && echo "$d"')
        COPY="rsync -z --partial"
        CLEANUP="rm -f $TMP/stoandl-remote.*"
    else
        TMP=$(ssh "$REMOTE" 'mktemp -d /tmp/stoandl-install.XXXXXX')
        COPY=scp
        CLEANUP="rm -rf $TMP"
    fi
    [ -n "$TMP" ] || { echo "Could not create a staging directory on $REMOTE" >&2; exit 1; }

    # Build the remote install script locally ($TMP is baked in by the unquoted heredoc; DROPIN and
    # systemctl references stay literal for the remote shell). The local file is mktemp's too.
    SCRIPT=$(mktemp /tmp/stoandl-remote.XXXXXX)
    cat > "$SCRIPT" << EOF
#!/bin/sh
set -e
DROPIN=$DROPIN
sudo install -Dm644 $TMP/stoandl.jar /usr/lib/stoandl/stoandl.jar
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
$START_SERVICE
$CLEANUP
EOF

    # Fixed names on the remote: rsync diffs against the file of the same name from the last install.
    $COPY "$JAR" "$REMOTE:$TMP/stoandl.jar"
    $COPY packaging/stoandl.service packaging/stoandl-ctl "$SCRIPT" "$REMOTE:$TMP/"
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

    eval "$START_SERVICE"

    echo "Installed $JAR"
    echo "Service status:"
    systemctl --user status stoandl --no-pager
fi
