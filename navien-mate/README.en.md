# Navien Smart 숙면매트 → Hubitat

[한국어 README](README.md)

Bring the Navien Smart sleep mat (숙면매트, step-type / 1.0L models such as the
EME-500) into Hubitat Elevation, with real-time state updates.

Navien's cloud has no REST endpoint that reports current state (heat level,
power) — the only way to get it is subscribing to the AWS IoT Core shadow over
a SigV4-signed WebSocket, and the account allows exactly one login session at
a time (a second sign-in kicks the first out with `code: 404`). Those two
constraints are why there are two install paths.

## Two install paths

| | Bridge | On-hub |
|---|---|---|
| Status | ✅ Verified on real hardware (recommended) | 🧪 Experimental — not yet verified on real hardware |
| Setup | Docker bridge (Python) + Hubitat driver | Hubitat App + driver only (no Docker) |
| Session owner | The bridge | The Hubitat App |
| Live-state path | Bridge subscribes to AWS IoT → republishes to a local MQTT broker → driver subscribes | Driver subscribes to AWS IoT directly over `wss://` |
| Extra infrastructure | Docker host, MQTT broker | None (just the Hubitat hub) |

Hubitat's built-in `interfaces.mqtt` accepting `wss://` is undocumented
behavior. [jlslate/hubitat-navien](https://github.com/jlslate/hubitat-navien)
(for a different Navien product, NaviLink) demonstrated it works on real
hardware first; this project's "on-hub" path ports that technique to our API
(mate). Since it's not an officially supported behavior, **it may not work on
every hub firmware build.** If it doesn't, power/level control still works
over REST — only live state fails to update, so there's not much to lose in
the worst case.

If you want something that's known to work, use the **bridge** path. If
you'd rather not add Docker infrastructure and don't mind experimenting, try
the **on-hub** path. Both live in this repo and can be installed side by
side without conflicting (the driver names differ).

## Architecture

### Bridge path

```
Navien cloud (AWS IoT + REST)
   ▲  SigV4-signed WSS subscribe + REST login/control  (one session — bridge only)
   │
Bridge (Docker, Python)
   ├─ Owns login/session refresh
   ├─ Subscribes to AWS IoT → republishes `reported` state to the local broker
   └─ Exposes a local HTTP API for control requests
   │                                  │
   │ state (MQTT, retained)           │ control (HTTP)
   ▼                                  ▼
Local MQTT broker                 Bridge relays to Navien REST
(Hubitat's built-in broker or
 any external broker)
   │
   ▼
Hubitat driver (navien-smart-mat.groovy)
   ├─ interfaces.mqtt subscribes to the local broker → live state
   └─ on()/off()/setHeatLevel() → bridge's local HTTP (never calls Navien's cloud directly)
```

### On-hub path (experimental)

```
Navien cloud (AWS IoT + REST)
   ▲  SigV4-signed WSS subscribe (driver, directly) + REST login/control (App, directly)
   │                                  ▲
   │ state                            │ control
   │                                  │
Hubitat App (Connector)  ──credentials──▶  Hubitat driver (On-Hub)
   ├─ Owns the login session (sole owner)     ├─ interfaces.mqtt.connect("wss://...")
   ├─ Issues/refreshes AWS temp credentials    │  connects directly to AWS IoT
   └─ Relays REST control via sendControl()    └─ parse() decodes the shadow `reported` payload
```

No Docker containers at all — the App plays the bridge's role, and the
driver does what it always did, including the subscription itself.

## Requirements

### Bridge path

- A Docker host reachable from your Hubitat hub (Proxmox/Portainer, Synology,
  etc.)
- An MQTT broker reachable from both the bridge and the hub. Either:
  - Hubitat's built-in broker: Integrations → Add Built-In Integration →
    add "MQTT Import Integration" (or Export) → enable **"Use built-in MQTT
    service"**. You only need the broker daemon; the device-mapping UI is not
    used (see Limitations).
  - An external broker (e.g. `eclipse-mosquitto`).
- A Navien Smart account with a step-type (1.0L) sleep mat registered.

### On-hub path

- A Navien Smart account with a step-type (1.0L) sleep mat registered.
  Nothing else — no Docker, no separate MQTT broker.

## Install

### Option A — Bridge path

#### 1. Bridge

Run this on a Docker host that can reach your Hubitat hub over the network
(Proxmox VM/LXC, Synology, etc.).

**Prerequisites** — confirm Docker and the Compose plugin are available.

```bash
docker --version
docker compose version
```

Install them first if either command fails (already covered if you're
managing this host through Portainer).

**Get the code**

```bash
git clone https://github.com/kwon2288/hubitat-driver.git
cd hubitat-driver/navien-mate/bridge-mode/bridge
```

To pull only this project instead of the whole multi-project repo, use a
sparse checkout:

```bash
git clone --filter=blob:none --sparse https://github.com/kwon2288/hubitat-driver.git
cd hubitat-driver
git sparse-checkout set navien-mate
cd navien-mate/bridge-mode/bridge
```

**Configure**

```bash
cp .env.example .env
vi .env   # or your editor of choice
```

Minimum required values:

- `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` — your Navien Smart account
- `MQTT_HOST` — the IP of Hubitat's built-in broker or your external broker
- `MQTT_USERNAME` / `MQTT_PASSWORD` — if the broker requires auth

Everything else (`MQTT_PORT`, `MQTT_PREFIX`, `HTTP_PORT`, `LOG_LEVEL`) can be
left at its default — see the "Configuration reference" table below for what
each one does.

**Run**

```bash
docker compose up -d --build
docker logs -f navien-bridge
```

You should see logs in this order:

```
로그인 성공 userSeq=... homeSeq=...
HTTP API 기동: 0.0.0.0:8099
MQTT 구독 시작: <homeSeq>/mate/#
```

**Verify**

```bash
curl http://<bridge-host>:8099/health
curl http://<bridge-host>:8099/devices
```

`/devices` should return your mat's info (`deviceId`, `zones`,
`rangeMin`/`rangeMax`, etc.).

**Updating** (after pulling new code)

```bash
cd hubitat-driver/navien-mate/bridge-mode/bridge
git pull
docker compose up -d --build
```

If `requirements.txt` changed, rebuild without cache to make sure the new
dependencies actually land:

```bash
docker compose build --no-cache
docker compose up -d
```

**Common operational commands**

```bash
docker compose logs -f navien-bridge   # tail logs
docker compose restart navien-bridge   # restart, no code change
docker compose down                    # stop + remove container
docker compose up -d                   # bring it back up
```

#### 2. Hubitat driver

1. **Drivers Code** → **New Driver** → paste
   `bridge-mode/drivers/navien-smart-mat.groovy` → **Save**.
2. **Devices** → **Add Device** → **Virtual** → pick the new driver type.
3. In **Preferences**, enter the bridge host/port and MQTT broker
   host/port/credentials → **Save Preferences**. Saving triggers
   `initialize()`, which pulls device info from the bridge and connects to
   MQTT.

### Option B — On-hub path (experimental)

No Docker at all. Keep in mind this hasn't been verified on real hardware
yet — if it doesn't work, REST control still does.

1. **Drivers Code** → **New Driver** → paste
   `onhub-mode/drivers/navien-smart-mat-onhub.groovy` → **Save**.
2. **Apps Code** → **New App** → paste
   `onhub-mode/apps/navien-mate-connector.groovy` → **Save**.
3. **Apps** → **Add User App** → "Navien Smart 숙면매트 (Connector)".
4. Enter your Navien Smart username/password → tap **"로그인 및 기기 검색"**
   ("Sign in and discover devices").
5. On success a child device is created automatically and immediately tries
   to connect to AWS IoT.

Watch the child device's `connection` attribute go from `connecting` to
`connected`. After 4 consecutive failures a warning is logged and the driver
falls back to REST-only control, retrying the MQTT connection in the
background.

## Configuration reference

### Bridge environment variables (`bridge-mode/bridge/.env`)

| Variable | Default | Description |
|---|---|---|
| `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` | — | Your Navien Smart account (required) |
| `MQTT_HOST` / `MQTT_PORT` | `127.0.0.1` / `1883` | The local broker the bridge publishes to |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | — | Broker credentials, if any |
| `MQTT_PREFIX` | `navien` | Topic prefix — must match the driver's `mqttPrefix` |
| `HTTP_PORT` | `8099` | Local control API port |
| `LOG_LEVEL` | `INFO` | Python log level |

### Bridge-path driver preferences

| Field | Description |
|---|---|
| `bridgeHost` / `bridgePort` | Bridge HTTP API address |
| `mqttHost` / `mqttPort` | Broker address the driver subscribes to |
| `mqttUsername` / `mqttPassword` | Broker credentials, if any |
| `mqttPrefix` | Must match the bridge's `MQTT_PREFIX` |

### On-hub path

The Connector App's login username/password is the whole configuration. The
On-Hub driver has nothing to set besides `logEnable` — it gets all its
credentials from the parent App.

## Usage

Both drivers (`navien-smart-mat.groovy`, `navien-smart-mat-onhub.groovy`)
expose the same capabilities, attributes, and commands.

- `on()` / `off()` — whole-mat power (`operationMode`). On split (left/right)
  mats, both zones share power, matching the physical device.
- `setHeatLevel(zone, level)` — `zone` is `single`/`left`/`right` (whichever
  the mat has), `level` is `0`–`8` (`0` = standby).
- `single_level`/`left_level`/`right_level` and their `*_levelLabel` pairs
  update to the **actual device state** whenever an MQTT message arrives —
  not just the last command sent.
- `refresh()` — re-fetches device registry info.

## Limitations

- Only step-type (1.0L) mats are supported — temperature-type (0.5C) mats and
  four-season cooling are not implemented. The upstream Home Assistant
  integration hasn't verified those on real hardware either, so this project
  matches that scope.
- If the account has more than one mat, only the first one is used.
- The on-hub path is **experimental and not yet verified on real
  hardware**. It relies on undocumented `wss://` behavior in Hubitat, so it
  may simply not work on some hub firmware builds. REST control (power/level)
  is unaffected either way.
- On the bridge path, Hubitat's built-in **MQTT Import Integration**'s
  device-mapping UI is intentionally not used — in testing, attribute mapping
  was unreliable for anything outside a handful of built-in capability
  templates. This project only borrows that app's broker daemon; all topic
  parsing happens in the driver's own `parse()`.

## Troubleshooting

### Bridge path

- **`WebsocketConnectionError: WebSocket handshake error, connection not
  upgraded`** — versions of `paho-mqtt` before 2.0 append the port to the
  WebSocket `Host:` header even for the default port, which breaks AWS IoT
  SigV4 signature validation. Confirm `bridge-mode/bridge/requirements.txt` pins
  `paho-mqtt>=2.1`.
- **Bridge keeps re-logging in, or Hubitat control intermittently fails** —
  the Navien account allows only one session. Check that nothing else is
  authenticating with the same account (a duplicate bridge instance, or the
  on-hub App running at the same time as the bridge) — **running both paths
  against the same account at once will make them fight over the session.**
- **`single_level`/`left_level`/`right_level` never change** — check the
  bridge logs for `상태 수신: <deviceId> heater=...`, and confirm a retained
  message actually exists at `navien/mate/<deviceId>/state` on the broker
  (`mosquitto_sub -t 'navien/#' -v`).

### On-hub path

- **`connection` attribute stays `disconnected`** — check whether
  `signHostWithPort` flips between attempts in the driver log. Whether
  Hubitat's built-in MQTT client appends the port to the `Host:` header on
  the WebSocket upgrade appears to vary by hub firmware build, so the driver
  alternates the signing style on each failure. After 4 failures it falls
  back to REST-only control and keeps retrying every 5 minutes.
- **"로그인 및 기기 검색" fails in the App** — same causes as the login
  entries under the bridge path (session conflict, wrong password, etc.).
  Read the error message shown on the App page directly.
- **Child device exists but no state ever arrives** — if `connection` is
  `connected` but state never updates, check the parent App's log for
  `제어 전송 성공` (confirming the initial-state request went out). If that's
  present, REST is fine and the issue is more likely in the MQTT subscribe
  filter (the `/update/accepted` suffix check).

## Credits

Protocol reverse-engineered from
[ripe-avocado/navien_smart_ha](https://github.com/ripe-avocado/navien_smart_ha)
(MIT License, © 2026 Eui Young Jung) — the REST auth flow, shadow control
payload shape, and the AWS SigV4 WebSocket signing steps in `bridge-mode/bridge/app.py`
are ported from that project's `api.py`/`mqtt.py`.

The on-hub path's (`onhub-mode/apps/navien-mate-connector.groovy`,
`onhub-mode/drivers/navien-smart-mat-onhub.groovy`) direct `wss://` connection and the
Host-header port signing quirk are ported from
[jlslate/hubitat-navien](https://github.com/jlslate/hubitat-navien)
(Unlicense / public domain), which verified the technique on real hardware
first for a different Navien product (NaviLink).

## License

Apache License 2.0 — see [LICENSE](LICENSE). (Omit this file if your repo
already carries a top-level Apache 2.0 LICENSE that covers all projects.)
`bridge-mode/bridge/app.py` incorporates logic ported from the MIT-licensed project
credited above; the MIT notice is preserved here per its terms.
