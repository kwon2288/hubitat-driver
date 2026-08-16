# Navien Smart 숙면매트 → Hubitat

Bring the Navien Smart sleep mat (숙면매트, step-type / 1.0L models such as the
EME-500) into Hubitat Elevation, with real-time state updates.

Navien's cloud has no REST endpoint that returns live device state — the only
way to read current heater level/power is to subscribe to the AWS IoT Core
shadow over a SigV4-signed WebSocket. Hubitat's built-in `interfaces.mqtt`
client does not support WebSocket transport, so this project splits the work
across two pieces:

- **`bridge/`** — a small Python/Docker service that owns the Navien cloud
  session, subscribes to AWS IoT over WebSocket, and republishes device state
  to a plain MQTT broker.
- **`drivers/navien-smart-mat.groovy`** — a Hubitat driver that subscribes to
  that broker for state, and sends commands through the bridge's local HTTP
  API (never talks to Navien's cloud directly).

## Why a bridge instead of a driver-only solution

1. **Hubitat's MQTT client is TCP/TLS only.** There's no `wss://` support, so
   a Groovy driver alone cannot subscribe to AWS IoT Core.
2. **The account allows exactly one login session.** If both the bridge and
   the Hubitat driver logged in independently, they would keep kicking each
   other out (`code: 404` from Navien). The bridge is the single owner of the
   session; the driver never authenticates against Navien directly.

## Architecture

```
Navien Cloud (AWS IoT + REST)
   ▲  SigV4-signed WSS subscribe + REST login/control  (single session — bridge only)
   │
Bridge (Docker, Python)
   ├─ owns login/session refresh
   ├─ subscribes to AWS IoT, republishes `reported` state to a local broker
   └─ exposes a local HTTP API for control
   │                                  │
   │ state (MQTT, retained)           │ control (HTTP)
   ▼                                  ▼
Local MQTT broker                bridge relays to Navien REST
(Hubitat's built-in broker,
 or any external broker)
   │
   ▼
Hubitat driver
   ├─ interfaces.mqtt subscribes to the local broker → live state
   └─ on()/off()/setHeatLevel() → bridge's local HTTP (never Navien directly)
```

## Requirements

- A Docker host reachable from your Hubitat hub (Proxmox/Portainer, Synology,
  etc.)
- An MQTT broker reachable from both the bridge and the hub. Two options:
  - Hubitat's own built-in broker: Integrations → Add Built-In Integration →
    "MQTT Import Integration" (or Export) → enable **"Use built-in MQTT
    service"**. You only need the broker it starts — you do not need to use
    its device-mapping UI (see Limitations).
  - Any external broker (e.g. `eclipse-mosquitto`).
- A Navien Smart account with a registered step-type (1.0L) sleep mat.

## Installation

### 1. Bridge

```bash
cd bridge
cp .env.example .env
# edit .env: NAVIEN_USERNAME/PASSWORD, MQTT_HOST/PORT/USERNAME/PASSWORD
docker compose up -d --build
docker logs -f navien-bridge
```

You should see `로그인 성공 userSeq=... homeSeq=...` followed by
`MQTT 구독 시작: <homeSeq>/mate/#`. Verify device discovery:

```bash
curl http://<bridge-host>:8099/devices
```

### 2. Hubitat driver

1. **Drivers Code** → **New Driver** → paste `drivers/navien-smart-mat.groovy`
   → **Save**.
2. **Devices** → **Add Device** → **Virtual** → select the new driver type.
3. In **Preferences**, set the bridge host/port and the MQTT broker
   host/port/credentials → **Save Preferences**. This triggers
   `initialize()`, which discovers the device from the bridge and connects to
   MQTT.

## Configuration reference

### Bridge environment variables (`bridge/.env`)

| Variable | Default | Description |
|---|---|---|
| `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` | — | Navien Smart account credentials (required) |
| `MQTT_HOST` / `MQTT_PORT` | `127.0.0.1` / `1883` | Local broker the bridge publishes to |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | — | Broker credentials, if any |
| `MQTT_PREFIX` | `navien` | Topic prefix; must match the driver's `mqttPrefix` |
| `HTTP_PORT` | `8099` | Local control API port |
| `LOG_LEVEL` | `INFO` | Python logging level |

### Driver preferences

| Preference | Description |
|---|---|
| `bridgeHost` / `bridgePort` | Bridge's HTTP API address |
| `mqttHost` / `mqttPort` | Broker address the driver subscribes to |
| `mqttUsername` / `mqttPassword` | Broker credentials, if any |
| `mqttPrefix` | Must match the bridge's `MQTT_PREFIX` |

## Usage

- `on()` / `off()` — whole-mat power (`operationMode`). On a two-zone
  (left/right) mat this is shared between zones, matching the physical mat.
- `setHeatLevel(zone, level)` — `zone` is `single`, `left`, or `right`
  (whichever your mat reports); `level` is `0`–`8` (`0` = standby).
- `single_level` / `left_level` / `right_level` and their `*_levelLabel`
  counterparts reflect the **actual** device-reported state once MQTT
  messages arrive — not just the last command sent.
- `refresh()` re-fetches device registry info from the bridge.

## Limitations (v1)

- Step-type (1.0L) mats only — temperature-type (0.5C) mats and four-season
  cooling are not implemented, matching the reference Home Assistant
  integration's own scope (untested on real hardware there too).
- Only the first mat on the account is used if more than one is registered.
- Hubitat's built-in **MQTT Import Integration** device-mapping UI is not
  used — as of testing, its attribute mapping is inconsistent for anything
  beyond a handful of built-in capability templates. This project only uses
  the broker daemon it can optionally provide; all topic parsing happens in
  the driver's own `parse()`.

## Troubleshooting

- **`WebsocketConnectionError: WebSocket handshake error, connection not
  upgraded`** — `paho-mqtt` versions before 2.0 append the port to the
  WebSocket `Host:` header even on the default port, which breaks AWS IoT's
  SigV4 signature check. Make sure `bridge/requirements.txt` pins
  `paho-mqtt>=2.1`.
- **Bridge keeps re-logging in / Hubitat control fails intermittently** — the
  Navien account allows one session at a time. Make sure nothing else (a
  second bridge instance, a stray Hubitat v1-style driver with its own
  login) is authenticating with the same credentials.
- **`single_level`/`left_level`/`right_level` never update** — confirm the
  bridge log shows `상태 수신: <deviceId> heater=...` and that
  `navien/mate/<deviceId>/state` actually has a retained message on your
  broker (`mosquitto_sub -t 'navien/#' -v`).

## Credits

Protocol reverse-engineered from
[ripe-avocado/navien_smart_ha](https://github.com/ripe-avocado/navien_smart_ha)
(MIT License, © 2026 Eui Young Jung) — the REST auth flow, shadow control
payload shape, and the AWS SigV4 WebSocket signing steps in `bridge/app.py`
are ported from that project's `api.py`/`mqtt.py`.

## License

Apache License 2.0 — see [LICENSE](LICENSE). (Omit this file if your repo
already carries a top-level Apache 2.0 LICENSE that covers all projects.)
`bridge/app.py` incorporates logic ported from the MIT-licensed project
credited above; the MIT notice is preserved here per its terms.

