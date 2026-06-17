#!/usr/bin/env python3
"""
Find My Phone — a stoandl extension that puts a persistent notification on the watch with a "Ring"
action. Choosing it on the wrist plays a sound on this computer until you choose "Stop". It's the
watch->host mirror of the built-in "find my watch", and needs no watchapp.

Enable it in stoandl.conf:

    extensions.enabled       = findphone
    extension.findphone.cmd  = /usr/bin/python3 %h/.config/stoandl/ext/findphone.py
    # extension.findphone.allow defaults to "notify" — nothing else is needed.
    # Optional: override the sound the host plays.
    # extension.findphone.sound = /usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga

Put stoandl_ext.py next to this file (both under ~/.config/stoandl/ext/ above), then restart stoandl.
"""
import subprocess
import shutil
from stoandl_ext import Extension

ext = Extension()
_player = None  # the running sound process, if any


def sound_path():
    return ext.config.get("sound", "/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga")


def arm():
    # One persistent notification with Ring/Stop actions, re-sent on (re)connect. The stable
    # replace_id means each re-send REPLACES the same watch item (across restarts too) rather than
    # piling up stale, un-actionable copies — so its Ring/Stop route is always current.
    ext.notify(
        app_name="Find My Phone",
        title="Find My Phone",
        body="Choose Ring to make this computer play a sound.",
        actions=[("ring", "Ring phone"), ("stop", "Stop")],
        ext_token="findphone",
        replace_id="findphone-ring",
    )


def ring():
    global _player
    stop()  # don't stack players
    snd = sound_path()
    # paplay loops the file; fall back to whatever audio player is around.
    if shutil.which("paplay"):
        _player = subprocess.Popen(["paplay", "--loop", snd])
    elif shutil.which("ffplay"):
        _player = subprocess.Popen(["ffplay", "-nodisp", "-autoexit", "-loop", "0", snd])
    elif shutil.which("aplay"):
        _player = subprocess.Popen(["aplay", snd])
    else:
        ext.log("no audio player found (install pulseaudio-utils / ffmpeg / alsa-utils)")
        return
    ext.log("ringing with %s" % snd)


def stop():
    global _player
    if _player and _player.poll() is None:
        _player.terminate()
    _player = None


def on_action(item_id, action):
    if action == "ring":
        ring()
    elif action == "stop":
        stop()


ext.on_initialize = lambda: arm() if ext.watch.get("connected") else None
ext.on_watch_connected = arm
ext.on_action = on_action
ext.on_dismiss = lambda item_id: stop()
ext.run("findphone", capabilities=["notify"])
