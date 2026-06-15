#!/usr/bin/env python3
"""
Bluetooth Classic (RFCOMM/SPP) spike for a classic-era Pebble (Time / Time Steel).

Purpose: confirm — with NO stoandl/Kotlin code — that the watch speaks the Pebble
Protocol over a plain RFCOMM socket, before investing in the dbus-java Profile1/FD
plumbing. If Pebble Protocol frames flow here, the BT Classic transport is viable
and the rest is mechanical.

The watch's SDP record (seen in btmon) advertises:
    Serial Port (0x1101) -> RFCOMM (0x0003) -> Server Port 14

Run it on the HOST (where BlueZ + the radio + the watch live), NOT in the sandbox:

    # stop the daemon first so it isn't paging the watch every ~10s and fighting us
    systemctl --user stop stoandl
    python3 tools/rfcomm_spike.py                    # defaults: B0:B4:48:B6:1E:81 ch 14
    python3 tools/rfcomm_spike.py AA:BB:CC:DD:EE:FF 14

Pebble Protocol framing (same over Classic as over BLE/PPoG, minus the PPoG wrapper):
    [uint16 length BE][uint16 endpoint BE][payload (length bytes)]
"""
import socket
import struct
import sys
import threading
import time

DEFAULT_MAC = "B0:B4:48:B6:1E:81"
DEFAULT_CHANNEL = 14

ENDPOINTS = {
    11: "TIME",
    16: "WATCH_VERSION",
    17: "PHONE_VERSION",
    18: "SYSTEM_MESSAGE",
    49: "APP_RUN_STATE",
    2001: "APP_FETCH",
    2030: "PUT_BYTES",
    6000: "DATA_LOG",
}

# WatchVersion request: endpoint 16 (0x0010), 1-byte payload 0x00. Elicits a
# WatchVersionResponse (firmware/serial) from any Pebble — a clean liveness probe.
WATCH_VERSION_REQUEST = bytes.fromhex("00 01 00 10 00".replace(" ", ""))


def ep_name(ep):
    return ENDPOINTS.get(ep, f"0x{ep:04x}")


def reader(sock, stop):
    buf = bytearray()
    frames = 0
    while not stop.is_set():
        try:
            chunk = sock.recv(1024)
        except socket.timeout:
            continue
        except OSError as e:
            print(f"[recv] socket error: {e}")
            return
        if not chunk:
            print("[recv] peer closed the RFCOMM connection (EOF)")
            return
        buf += chunk
        # Parse as many complete Pebble Protocol frames as we have.
        while len(buf) >= 4:
            (length, endpoint) = struct.unpack(">HH", buf[:4])
            if len(buf) < 4 + length:
                break  # wait for the rest of the payload
            payload = bytes(buf[4:4 + length])
            del buf[:4 + length]
            frames += 1
            print(f"[RX frame] endpoint={endpoint} ({ep_name(endpoint)}) "
                  f"len={length} payload={payload.hex()}")
        if buf and len(buf) < 4:
            # Trailing bytes that don't yet form a header — show them so non-Pebble
            # garbage is visible too.
            pass
    _ = frames


def discover_spp_channels(mac):
    """Query SDP for Serial Port (0x1101) RFCOMM channels. Returns a list of ints.
    Shells out to `sdptool` (bluez); robust to the channel changing across re-pairs."""
    import re
    import subprocess
    try:
        out = subprocess.run(["sdptool", "browse", "--tree", mac],
                             capture_output=True, text=True, timeout=20).stdout
    except Exception as e:
        print(f"[sdp] sdptool failed ({e}); is `sdptool` installed / watch reachable?")
        return []
    print("---- sdptool browse ----")
    print(out.strip() or "(empty — no SDP records returned; the watch may not be "
          "advertising any Classic service right now)")
    print("------------------------")
    chans = []
    # Find Serial Port records and the RFCOMM channel near them.
    for block in re.split(r"\n\s*\n", out):
        if "0x1101" in block or "Serial Port" in block:
            for m in re.finditer(r"Channel[:/]?\s*(\d+)", block):
                chans.append(int(m.group(1)))
    # de-dup, keep order
    seen = set()
    chans = [c for c in chans if not (c in seen or seen.add(c))]
    if chans:
        print(f"[sdp] Serial Port (SPP) RFCOMM channel(s): {chans}")
    else:
        print("[sdp] no Serial Port / SPP RFCOMM channel found in SDP.")
    return chans


def try_connect(mac, channel):
    print(f"Connecting RFCOMM to {mac} channel {channel} ...")
    try:
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                             socket.BTPROTO_RFCOMM)
    except AttributeError:
        print("This Python build lacks AF_BLUETOOTH/BTPROTO_RFCOMM (need Linux CPython).")
        return None
    # Request an authenticated+encrypted link (BT_SECURITY_MEDIUM) so BlueZ uses the stored
    # BR/EDR LinkKey instead of triggering a fresh SSP (which an insecure socket does → ECONNREFUSED).
    SOL_BLUETOOTH, BT_SECURITY, BT_SECURITY_MEDIUM = 274, 4, 2
    try:
        sock.setsockopt(SOL_BLUETOOTH, BT_SECURITY, BT_SECURITY_MEDIUM)
    except OSError as e:
        print(f"  (couldn't set BT_SECURITY_MEDIUM: {e})")
    sock.settimeout(20)
    try:
        sock.connect((mac, channel))
        return sock
    except OSError as e:
        print(f"  channel {channel}: connect() failed: {e}")
        try:
            sock.close()
        except OSError:
            pass
        return None


def main():
    mac = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MAC
    forced_channel = int(sys.argv[2]) if len(sys.argv) > 2 else None

    # Decide which channel(s) to try: an explicit arg, else SDP-discovered, else the default.
    if forced_channel is not None:
        channels = [forced_channel]
    else:
        channels = discover_spp_channels(mac) or [DEFAULT_CHANNEL]
        print(f"Channels to try: {channels}")

    sock = None
    for ch in channels:
        sock = try_connect(mac, ch)
        if sock is not None:
            channel = ch
            break
    if sock is None:
        print("\nAll RFCOMM connects refused. 'Connection refused' = the watch is "
              "reachable but isn't accepting on that channel. Likely: wrong device "
              "(bond/connect the non-LE 'Pebble Time' entry, not 'Pebble Time LE'), "
              "the SPP server is only open transiently, or the watch wants to be the "
              "one initiating (we'd need to register an SPP Profile1 server instead).")
        return 1
    print(f"RFCOMM connected on channel {channel}. Listening for Pebble Protocol frames ...")
    print("(The watch usually sends a PHONE_VERSION request on connect. We'll also "
          "send a WATCH_VERSION request after 1.5s to elicit a reply.)")

    sock.settimeout(1)  # so the reader loop can poll stop
    stop = threading.Event()
    t = threading.Thread(target=reader, args=(sock, stop), daemon=True)
    t.start()

    time.sleep(1.5)
    try:
        print(f"[TX] WatchVersion request: {WATCH_VERSION_REQUEST.hex()}")
        sock.sendall(WATCH_VERSION_REQUEST)
    except OSError as e:
        print(f"[TX] send failed: {e}")

    # Observe for a while.
    try:
        time.sleep(20)
    except KeyboardInterrupt:
        pass
    stop.set()
    try:
        sock.close()
    except OSError:
        pass
    print("Done. If you saw any [RX frame] lines (esp. PHONE_VERSION or WATCH_VERSION), "
          "the watch speaks Pebble Protocol over RFCOMM — BT Classic is viable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
