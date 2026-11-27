# Plugins

Plugins are the official Pebble app's newer extension point (app v1.14+, still marked experimental
upstream): phone-side **sources** of data any watchface or watchapp can read (`weather/location`,
`music/track`, `phone/phone_state`, …) and **actions** they can trigger (`set_on`, `next_track`, …), behind a
permission system. stoandl runs them on the computer the watch is paired with. Upstream's preview
documentation describes the API for developers: `test-apps/plugins.md` in
[coredevices/libpebble3](https://github.com/coredevices/libpebble3).

Off by default: `plugins.enabled = false` (`stoandl daemon set plugins.enabled true`, or the GUI's
Settings → Plugins). While it is off no plugin code runs and nothing is fetched for one (upstream removed
this switch in `bf66a5be`; the fork keeps it). Turning it on also turns on **app permissions** for every
watchapp's PKJS: its XHRs (every redirect hop) and `WebSocket`s then need the app's network permission,
and an app's microphone audio stream (refused while plugins are off) its microphone permission.

## What provides plugins

| Kind | What | Where it comes from |
|---|---|---|
| Built-in | **Weather** (`weather/location`, the official app's contract: now, today's high/low/feels-like and condition, plus the hourly and daily series, UV, precipitation, wind, humidity, sunrise/sunset and the place's UTC offset whenever the forecast carries them; reads what stoandl's weather sync fetched, no extra requests. Upstream removed `weather/hour`: the hours are the `hourly_*` series), **Music** (`music/track`, play/pause/skip; the MPRIS bridge), **Calendar** (`calendar/event`; the calendar sync, cached between syncs), **Phone** (`phone/phone_state`: battery, Wi-Fi/cellular, cell generation; UPower, NetworkManager, ModemManager), **Watch Settings** and **Watch State** (launch apps; a Quick Launch action only on a watch that has it), **Find My Phone** (`phone/find_my_phone` rings this device while subscribed, plus `ring`/`stop` actions; the freedesktop alarm sound through `pw-play`/`paplay` and a desktop notification with Stop, at most 60 s) | stoandl / libpebble3 |
| App | the `plugin.js` a `.pbw` ships (or a plugin-only `.pbw`), run in GraalJS with `fetch`, `WebSocket`, `navigator.geolocation`, `localStorage` and `Pebble.secureStorage` | `stoandl apps install x.pbw` |
| Extension | an extension that declares a `plugin` block serves sources and actions over its JSON-RPC pipe ([extensions.md](extensions.md#serving-plugin-sources-and-actions)) | `stoandl ext install` |

## Permissions

Apps declare what they need in their `package.json` (`usesPermissions`); older apps keep what they had.
When an app needs an answer, stoandl asks on the **watch** (a notification with Allow / Deny) and the
**desktop** (a notification with the same buttons) unless `alerts.permissions = false`; the GUI's Apps
page shows the request too, and so does `stoandl perms`. Until answered, the app is refused. When apps
have to ask is `plugins.permission_policy` (`ask_if_exfiltrating` by default: only when the data could
leave this machine).

Only a running app's requests are prompted. A request from an app that has since closed stays waiting:
`stoandl perms` lists it (`[not running]`), and the GUI shows it as a banner and a badge on the app's
Permissions button. **Later** (`perms later`) stops asking about a request without deciding it: no more
prompts or banners, the app stays refused, and the app's permission page still offers Allow / Deny.

`perms list` (and the GUI's per-app Permissions dialog) shows each permission's effective status:
`granted` / `denied` (you decided), `auto` (undecided, but the policy allows it), `waiting` (undecided, the
app has to ask) or `blocked` (refused whatever was decided: Legacy PebbleKit switched off, or global
internet for an app that declares its domains).

```sh
stoandl perms                       # requests waiting for an answer (every app, running or not)
stoandl perms list Stocks           # what an app asked for, with its effective status
stoandl perms allow Stocks Internet/query1.finance.yahoo.com
stoandl perms reset Stocks Location # back to undecided (and asked again)
stoandl perms later Stocks          # stop asking about Stocks' waiting requests for now
```

Plugins' `Pebble.secureStorage` lives in the keyring (else the 0600 `secrets` file next to stoandl.conf),
out of backups, and is deleted with the app.

## Trying them

```sh
stoandl plugins                     # what is installed
stoandl plugins read weather/location
stoandl plugins invoke Music set_playing '{"playing":false}'
stoandl config Stocks               # an app's own settings page (it can talk to its plugin while open)
```

The official app's example plugins (stocks, met-weather, hue, …) can be packed into `.pbw`s without the
Pebble SDK with upstream's `scripts/pack-plugin-pbw.py`.

## OAuth sign-in (bring your own app)

Plugins that sign in with `Pebble.oauth` (the Todoist, TickTick and Notion examples) go through the official
app store's broker there, which stoandl can't use. Instead, register an OAuth app with the provider yourself
(a developer console, a minute's work), with the redirect address `http://127.0.0.1:47811/oauth` (any
free port), and describe it in `~/.config/stoandl/oauth/<slug>.conf`, the slug being the connector name the
plugin declares (`"oauth": {"todoist": {}}`):

```
client_id = …
client_secret = …        # if the provider issues one (keep this file private: chmod 600)
authorize_url = https://todoist.com/oauth/authorize
token_url = https://todoist.com/oauth/access_token
redirect_uri = http://127.0.0.1:47811/oauth
scope = data:read_write
```

When the plugin signs in, stoandl opens the provider's page in the browser (and a desktop notification with
the link), takes the answer on that loopback address (PKCE), keeps the refresh token in the keyring and hands
the plugin the access token, as the broker would. No connector file, no sign-in.

## What doesn't work (yet)

- **Web sign-in** from a settings page (`Pebble.webSignIn`).
- The app store's Plugins section: install `.pbw`s yourself.
