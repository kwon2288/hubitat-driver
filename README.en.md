# hubitat-driver

[한국어 README](README.md)

Custom Hubitat Elevation drivers, organized one folder per project. Each
project folder is self-contained with its own README, driver code, and
(where relevant) companion scripts.

## Drivers

| Project | Description |
|---|---|
| [`awair-omni-local/`](awair-omni-local/README.md) | Polls an Awair Omni air quality monitor's local API for temperature, humidity, CO2, VOC, PM2.5, lux, noise, and a locally-calculated EPA AQI. Canonical repo/HPM package remains `Hubitat-AwAir`; this copy is kept here for browsing convenience only. |
| [`homey-bridge/`](homey-bridge/README.md) | Bridges Homey Pro devices into Hubitat via Homey's local REST API (`/api/manager/devices/device/...`). Plain on/off devices get a dedicated driver (`homey-switch-driver`), dimming/color devices get a dedicated driver (`homey-dimmer-driver`), radio/speaker-type devices get a dedicated driver on the standard MusicPlayer capability (`homey-onair-radio-driver`), and remaining sensors/buttons/window coverings use a generic driver (`homey-generic-device-driver`). Update mode is configurable: polling / polling+webhook (Homey Flow) / webhook only. |
| [`lg-thinq/`](lg-thinq/README.md) | Integration built on LG's official ThinQ Connect API (PAT authentication). Beyond the framework's stock support (washer, dryer, dishwasher, etc.), this includes added/modified drivers for air purifier, air conditioner (system-type and a dedicated wall-mount variant), styler, dehumidifier, mini washer, and water purifier (monitoring-only). Built on the [jonozzz/hubitat-thinqconnect](https://github.com/jonozzz/hubitat-thinqconnect) framework. |
| [`lotto645/`](lotto645/README.md) | Fetches the Korean Lotto 6/45 winning numbers automatically right after each Saturday draw. Calls the query endpoint behind Dongheung Lottery's redesigned results page (the legacy `common.do` API now just redirects and no longer works), and checks your registered numbers against the draw to report 1st–5th place or no win. |
| [`navien-mate/`](navien-mate/README.md) | Brings the Navien Smart sleep mat (숙면매트, step-type/1.0L, e.g. EME-500) into Hubitat with real-time state. Split into per-mode subfolders: `bridge-mode/` (Docker bridge) and `onhub-mode/` (App+driver only, no Docker) — both verified on real hardware. |
| [`samsung-soundbar-local/`](samsung-soundbar-local/README.md) | Local (LAN-only) control of 2024+ Samsung Wi-Fi soundbars over the local JSON-RPC API on TCP 1516 — power, volume, mute, input source, sound mode, subwoofer. Protocol reverse-engineered by ZtF for Home Assistant; ported here as a native Hubitat driver. |
| [`tuya-bed-presence/`](tuya-bed-presence/README.en.md) | Zigbee driver for the Tuya TS0601 pressure-strap bed occupancy sensor (`_TZE200_seq9cm6u`). Reports whether it is pressed as `presence` (optionally mirrored to `motion`) along with battery and work state, and exposes sensitivity, sampling interval and presence/absence report delays as device preferences. Tuya DP definitions ported from Zigbee2MQTT (zigbee-herdsman-converters). |
| [`wan-failover-monitor/`](wan-failover-monitor/README.md) | Detects a UniFi 5G/LTE WAN failover via public IP polling, auto-updates Cloudflare DDNS, and restarts affected Docker containers (via Portainer) / Proxmox LXCs (via Proxmox VE API) |

More drivers will be added here over time — see each project's own
`README.md` for installation and configuration details specific to that
driver.

## Repository structure

```
hubitat-driver/
├── README.md                  Korean (default)
├── README.en.md                (this file)
├── LICENSE                    (Apache 2.0, applies repo-wide)
└── <project-name>/
    ├── README.md               project-specific docs
    ├── README.en.md            (optional) English project docs
    ├── drivers/                 .groovy driver files for that project
    │   └── *.groovy
    └── scripts/                 optional companion scripts (non-Hubitat)
        └── *.sh
```

Adding a new driver means adding a new top-level `<project-name>/` folder
following this same layout.

## Author

kwon2288 — see also `Hubitat-AwAir` for other published drivers.

## License

Apache License 2.0 applies repo-wide by default — see [LICENSE](LICENSE).
Individual project folders may specify a different license in their own
`LICENSE` file (e.g. `awair-omni-local/` uses CC0); that project-level
license takes precedence for that folder's contents.
