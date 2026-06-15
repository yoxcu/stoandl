#!/usr/bin/env python3
"""
Bluetooth Classic SPP spike, take 2 — via BlueZ org.bluez.Profile1.

The raw-socket spike (rfcomm_spike.py) opens an *insecure* socket to a hardcoded
channel and fails (ECONNREFUSED + re-pair loop): with no profile holding the SPP
UUID, BlueZ tears the ACL down after ~2s, and an insecure socket triggers SSP the
watch refuses without a stored bond. This spike does it the right way: register a
CLIENT-role SPP Profile1 and call Device1.ConnectProfile — BlueZ resolves the SDP
channel, authenticates against the STORED BR/EDR bond, and hands us the socket FD
via Profile1.NewConnection. Direction confirmed (host=client, watch=SPP server):
Android createRfcommSocketToServiceRecord+connect, Gadgetbridge, libpebble2.

Prereq: a PERSISTENT BR/EDR bond (a [LinkKey] in /var/lib/bluetooth/<adapter>/<mac>/info,
not just a CTKD [LongTermKey]) — see the chat recipe (btmgmt pair -t bredr).

Run on the HOST (needs python-dbus + PyGObject):

    systemctl --user stop stoandl
    python3 tools/rfcomm_profile_spike.py                    # default MAC B0:B4:48:B6:1E:81
    python3 tools/rfcomm_profile_spike.py AA:BB:CC:DD:EE:FF
"""
import os
import struct
import sys
import threading

SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
PROFILE_PATH = "/io/stoandl/sppspike"
DEFAULT_MAC = "B0:B4:48:B6:1E:81"

ENDPOINTS = {11: "TIME", 16: "WATCH_VERSION", 17: "PHONE_VERSION", 18: "SYSTEM_MESSAGE"}


def ep_name(ep):
    return ENDPOINTS.get(ep, f"0x{ep:04x}")


def read_frames(fd):
    buf = bytearray()
    while True:
        try:
            chunk = os.read(fd, 1024)
        except OSError as e:
            print(f"[fd {fd}] read error: {e}")
            return
        if not chunk:
            print(f"[fd {fd}] EOF (peer closed)")
            return
        buf += chunk
        while len(buf) >= 4:
            length, endpoint = struct.unpack(">HH", buf[:4])
            if len(buf) < 4 + length:
                break
            payload = bytes(buf[4:4 + length])
            del buf[:4 + length]
            print(f"[RX frame] endpoint={endpoint} ({ep_name(endpoint)}) "
                  f"len={length} payload={payload.hex()}")


def main():
    try:
        import dbus
        import dbus.service
        import dbus.mainloop.glib
        from gi.repository import GLib
    except ImportError as e:
        print(f"Missing dependency: {e}. Install python-dbus + python-gobject (PyGObject).")
        return 2

    mac = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MAC

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            print("[Profile1] Release")

        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, props):
            realfd = fd.take()
            print(f"[Profile1] NewConnection from {device} fd={realfd} props={dict(props)}")
            print("  -> Pebble Protocol over Classic is flowing if you see [RX frame] below.")
            threading.Thread(target=read_frames, args=(realfd,), daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            print(f"[Profile1] RequestDisconnection {device}")

    Profile(bus, PROFILE_PATH)

    mgr = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"),
                         "org.bluez.ProfileManager1")
    # CLIENT role: the host dials the watch's SPP server (confirmed direction — Android
    # createRfcommSocketToServiceRecord + connect, Gadgetbridge PebbleIoThread, libpebble2).
    opts = {
        "Name": "stoandl SPP spike",
        "Role": "client",
        "RequireAuthentication": dbus.Boolean(True),
        "RequireAuthorization": dbus.Boolean(False),
    }
    try:
        mgr.RegisterProfile(PROFILE_PATH, SPP_UUID, opts)
        print(f"Registered SPP Profile1 (client) at {PROFILE_PATH}.")
    except dbus.DBusException as e:
        print(f"RegisterProfile failed: {e}")
        return 1

    # ConnectProfile does the SDP channel lookup + auth against the STORED BR/EDR bond, holds the
    # UUID (defeating BlueZ's ~2s no-profile ACL teardown), and hands us the fd via NewConnection.
    dev_path = "/org/bluez/hci0/dev_" + mac.replace(":", "_")
    try:
        dev = dbus.Interface(bus.get_object("org.bluez", dev_path), "org.bluez.Device1")
        print(f"ConnectProfile({SPP_UUID}) on {mac} — BlueZ does SDP + auth using the stored bond ...")
        dev.ConnectProfile(SPP_UUID)
        print("ConnectProfile returned OK — expect a NewConnection + [RX frame] lines below.")
    except dbus.DBusException as e:
        print(f"ConnectProfile failed: {e}")
        print("  'Does Not Exist'      -> no device object; run `bluetoothctl scan on` / page it first.")
        print("  'NotAvailable'/'..'   -> watch isn't exposing SPP right now (open its Bluetooth screen, retry).")
        print("  auth / 'Refused'      -> bond isn't a persistent BR/EDR LinkKey "
              "(check /var/lib/bluetooth/<adapter>/" + mac + "/info for a [LinkKey] section).")

    print("In mainloop; NewConnection fires if/when the SPP profile connects. Ctrl-C to stop.")
    loop = GLib.MainLoop()
    try:
        loop.run()
    except KeyboardInterrupt:
        print("\nbye")
    try:
        mgr.UnregisterProfile(PROFILE_PATH)
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
