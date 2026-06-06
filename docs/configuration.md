# Configuration

stoandl reads an optional config file at:

```
$XDG_CONFIG_HOME/stoandl/stoandl.conf      # default: ~/.config/stoandl/stoandl.conf
```

A missing or unreadable file is fine — the daemon falls back to defaults. **Edits require a
restart** (`systemctl --user restart stoandl`); the file is read once at startup.

Syntax is `key = value`, `#` starts a comment, and list values are comma-separated. A starter file
is shipped at [`packaging/stoandl.conf.example`](../packaging/stoandl.conf.example).

## Keys

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `notification.blocklist` | list | _(empty)_ | App-name substrings (case-insensitive) whose notifications are never forwarded to the watch. |
| `call.dialer_apps` | list | `spacebar, calls` | Telephony/dialer app-name substrings. Their notifications are suppressed from the watch (the native call screen replaces them) and their title is used as a fallback caller name. |
| `contacts.vcard_paths` | list | _(empty)_ | vCard (`.vcf`) files or directories scanned for caller-ID resolution. `~` expands to `$HOME`. |

## Caller-ID resolution

There is no contacts D-Bus API shared across GNOME (evolution-data-server) and Plasma/KDE
(Akonadi/KPeople), so stoandl resolves names from **vCard files** — the DE-agnostic common
denominator. Two convenient sources:

- **Plasma Mobile** stores contacts as `.vcf` via the `kpeoplevcard` KPeople backend, typically in
  `~/.local/share/kpeoplevcard/` — point `contacts.vcard_paths` straight at it.
- **GNOME Contacts** / any CardDAV setup (`vdirsyncer`, `khard`) can export/sync a `.vcf` directory.

Numbers are matched digits-only by suffix, so a stored `0151 2345678` resolves an incoming
`+49151 2345678` and vice versa. Files are re-read automatically when they change.

If a number isn't in the vCard files, stoandl falls back to the title of the dialer's own
incoming-call notification (see `call.dialer_apps`) — best-effort, since that depends on the
notification arriving at or before the call rings.
