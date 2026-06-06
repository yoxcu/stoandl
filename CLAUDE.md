# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**stoandl** is a headless Pebble smartwatch companion daemon for Linux. It bridges the D-Bus session bus (desktop notifications) to a Pebble watch over BLE via libpebble3/BlueZ. No UI. Runs as a systemd user service.

## Commands

```sh
./gradlew run          # run locally (includes required --add-opens JVM flags)
./gradlew jar          # build fat JAR → build/libs/stoandl-<version>.jar
```

To run the fat JAR manually, the `--add-opens` flags are required:
```sh
java \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.base=ALL-UNNAMED \
  --add-opens=org.freedesktop.dbus/org.freedesktop.dbus.connections.transports=ALL-UNNAMED \
  -jar build/libs/stoandl-*.jar
```

Install and control (requires systemd service running):
```sh
./install.sh              # build jar, install service, restart
stoandl sideload app.pbw  # sideload a .pbw onto the connected watch
```

There are no automated tests in this project.

## Architecture

```
Desktop apps
    │  Notify() on org.freedesktop.Notifications (D-Bus session bus)
    ▼
DbusNotificationMonitor   ← passive BecomeMonitor copy (does NOT intercept)
    │  Flow<IncomingNotification>  (MutableSharedFlow, buffer 64)
    ▼
PebbleIntegration / DbusNotificationListenerConnection
    │  buildTimelineNotification → libPebble.sendNotification()
    ▼
libpebble3 (composite build submodule: libs/libpebble3, micropebble branch)
    ▼
BlueZ GATT server (reversedPPoG=false: phone acts as BLE peripheral)
    ▼
Pebble watch over BLE/PPoG
```

**Key design points:**

- `monitorNotifications()` (`DbusNotificationMonitor.kt`) uses `DBusMonitoring.BecomeMonitor` — the notification is a *passive copy*; the original still reaches the system notification daemon (dunst, mako, etc.). After `BecomeMonitor` succeeds, the writer on `TransportConnection` is replaced with a no-op via reflection to prevent dbus-java's auto-reply from closing the monitor connection.

- `PebbleIntegration.kt` initializes Koin (libpebble3's DI), then overrides two bindings: `NotificationListenerConnection` (swapped for `DbusNotificationListenerConnection` which bridges the D-Bus `Flow`) and `BleConfigFlow` (pinned to `reversedPPoG=false` so no persisted Java Preferences can override it).

- `KermitSlf4jWriter` bridges libpebble3's Kermit logger into SLF4J/Logback. Tag names are cleaned: strips `/{...}` and `-{...}` device-path suffixes, and also plain app-name suffixes (`RhinoJsRunner-Hooky` → `RhinoJsRunner`) so logback entries match without knowing the app name.

- Logs go to `/tmp/stoandl.log` (rolling, 5 MB × 3) and stdout. Log levels are tuned in `logback.xml` — libpebble3 internals are suppressed to INFO/WARN to reduce noise.

## libpebble3 submodule

The dependency is a patched fork (`yoxcu/libpebble3`, branch `stoandl`) included as a git submodule at `libs/libpebble3`. It is wired via Gradle composite build in `settings.gradle.kts` — no Maven publish needed. After cloning, run `git submodule update --init --recursive`.

The fork adds: BlueZ GATT server, PPoG handshake fixes for Linux BLE, BlueZ pairing/bonding, PKJS/Rhino JS runtime for watchapp companion JS, and strips Android/iOS targets to speed up JVM builds.

## PKJS (PebbleKit JS)

Watchapps can ship a `pkjs/index.js` companion script. libpebble3 runs it in **Mozilla Rhino 1.7.15** via `RhinoJsRunner`. The JS bridge initialises when the watch connects and the app is launched; look for `Pebble JS Bridge initialized.` in the log.

**Rhino 1.7.15 supported ES6 subset** — these work: arrow functions (no rest/default params), template literals, destructuring, `const`/`let`, `Map`/`Set`, `Array.from`, shorthand properties, rest params in regular `function` declarations.

**Rhino 1.7.15 does NOT support** (fix pattern in parentheses):
- `class` declarations — rewrite as constructor + `prototype` methods
- Rest params in arrow functions: `(...args) =>` — use `function() { const args = Array.prototype.slice.call(arguments); }`
- Default parameter values: `(x, y = {}) =>` — use explicit `if (y === undefined) y = {};`
- `for...of` loops — use `.forEach()` or index-based `for`
- Computed property keys: `{[k]: v}` — use `obj[k] = v` after construction
- `const` in separate `if`-blocks in the same function scope triggers redeclaration — use unique names or `let`

**Rhino test harness** — verify JS syntax without deploying. Requires Rhino JAR from the Gradle cache:
```sh
RHINO=/home/vscode/.gradle/caches/modules-2/files-2.1/org.mozilla/rhino/1.7.15/39e53f8e769ea9a7e799f266aa47c85edd99a29e/rhino-1.7.15.jar
# Write TestPkjs.java (compile-only, no runtime stubs needed):
cat > /tmp/TestPkjs.java << 'EOF'
import org.mozilla.javascript.*;
import java.io.*;
import java.nio.file.*;
public class TestPkjs {
    public static void main(String[] args) throws Exception {
        boolean ok = true;
        String dir = args[0];
        for (String f : new String[]{"JSTimeout.js","XMLHTTPRequest.js","startup.js"}) {
            String src = new String(Files.readAllBytes(Paths.get(dir + "/" + f)));
            Context cx = Context.enter();
            try {
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setOptimizationLevel(-1);
                cx.compileString(src, f, 1, null);
                System.out.println("OK  " + f);
            } catch(Exception e) { System.out.println("ERR " + f + ": " + e.getMessage()); ok=false; }
            finally { Context.exit(); }
        }
        System.exit(ok ? 0 : 1);
    }
}
EOF
javac -cp $RHINO /tmp/TestPkjs.java -d /tmp
java -cp /tmp:$RHINO TestPkjs libs/libpebble3/libpebble3/src/jvmMain/resources/pkjs
```

## Deployment (postmarketOS / systemd user service)

```sh
sudo install -Dm644 build/libs/stoandl-*.jar /usr/lib/stoandl/stoandl.jar
sudo install -Dm644 packaging/stoandl.service /usr/lib/systemd/user/stoandl.service
systemctl --user daemon-reload
systemctl --user enable --now stoandl
```

The service unit sets `DBUS_SESSION_BUS_ADDRESS=unix:path=%t/bus` so it finds the user session bus without a graphical login.
