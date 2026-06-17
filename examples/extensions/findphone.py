#!/usr/bin/env python3
"""
Find My Phone — the companion for the find-my-phone *watchapp* (testing/findphone). The watchapp's UP
button rings this computer, DOWN stops it. A watchapp is used (rather than a notification) because the
firmware only offers a notification's action menu while it's on screen, whereas a watchapp's buttons
work whenever you open it.

The watchapp sends an AppMessage with a single uint8 at key 0 (1 = ring, 2 = stop); this companion
registers that app's UUID, receives the message via on_app_message, and plays/stops a sound.

Setup:
  1. Build + install the watchapp once:  cd testing/findphone && pebble build && pebble install --phone <ip>
     (or `stoandl sideload testing/findphone/build/findphone.pbw`).
  2. Copy stoandl_ext.py + findphone.py to ~/.config/stoandl/ext/ and add to stoandl.conf:
        extensions.enabled       = findphone
        extension.findphone.cmd  = /usr/bin/python3 %h/.config/stoandl/ext/findphone.py
        extension.findphone.allow = appmessage:de72f1d0-1111-4a17-9a6b-0123456789ab
        # optional: extension.findphone.sound = /path/to/alarm.oga
  3. Restart stoandl, open "Find My Phone" on the watch, press UP.
"""
import os
import shlex
import signal
import subprocess
import shutil
from stoandl_ext import Extension

# Must match testing/findphone/package.json "uuid" and the extension.findphone.allow grant.
APP_UUID = "de72f1d0-1111-4a17-9a6b-0123456789ab"
KEY_CMD = 0
CMD_RING, CMD_STOP = 1, 2

ext = Extension()
_player = None  # the running sound process group, if any


def sound_path():
    return ext.config.get("sound", "/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga")


def _player_argv(snd):
    if shutil.which("paplay"):
        return ["paplay", snd]
    if shutil.which("pw-play"):
        return ["pw-play", snd]
    if shutil.which("ffplay"):
        return ["ffplay", "-nodisp", "-autoexit", snd]
    if shutil.which("aplay"):
        return ["aplay", snd]
    return None


def ring():
    global _player
    stop()
    argv = _player_argv(sound_path())
    if argv is None:
        ext.log("no audio player found (install pulseaudio-utils / pipewire / ffmpeg / alsa-utils)")
        return
    cmd = "while true; do %s; done" % " ".join(shlex.quote(a) for a in argv)
    _player = subprocess.Popen(["sh", "-c", cmd], start_new_session=True)
    ext.log("ringing with %s" % " ".join(argv))


def stop():
    global _player
    if _player and _player.poll() is None:
        try:
            os.killpg(os.getpgid(_player.pid), signal.SIGTERM)
        except ProcessLookupError:
            pass
    _player = None


def on_app_message(app_uuid, txn, data):
    cmd = data.get(KEY_CMD)
    if cmd == CMD_RING:
        ring()
    elif cmd == CMD_STOP:
        stop()
    else:
        ext.log("unknown command in AppMessage: %r" % data)


ext.on_app_message = on_app_message
ext.on_initialize = lambda: ext.register_app(APP_UUID)
ext.on_watch_connected = lambda: ext.register_app(APP_UUID)  # idempotent; re-arm after a reconnect
ext.run("findphone", capabilities=["appmessage"])
