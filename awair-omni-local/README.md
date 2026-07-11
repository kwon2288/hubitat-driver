# Awair Omni Local

Hubitat driver for the [Awair Omni](https://www.getawair.com/products/omni)
air quality monitor, polling its **local** API (no cloud dependency)
for temperature, humidity, CO2, VOC, PM2.5, lux, and noise readings,
plus a locally-calculated EPA AQI.

> **Note on canonical location:** this driver's `importUrl` (used by
> Hubitat's built-in driver updater) points at the original
> [`kwon2288/Hubitat-AwAir`](https://github.com/kwon2288/Hubitat-AwAir)
> repository, which is also what's registered with [Hubitat Package
> Manager](https://hubitatpackagemanager.hubitatcommunity.com/). That
> repo remains the source of truth for installs/updates. This copy
> lives here purely to keep all of my Hubitat drivers browsable in one
> place — if you're installing via HPM or the `importUrl`, you're
> already getting it from the right place.

## Capabilities

- `Polling`, `Configuration`, `Initialize`, `Sensor`
- `TemperatureMeasurement`, `CarbonDioxideMeasurement`, `RelativeHumidityMeasurement`, `AirQuality`

## Attributes

| Attribute | Description |
|---|---|
| `temperature`, `humidity`, `carbonDioxide`, `voc`, `pm25`, `lux`, `noise` | Raw sensor readings |
| `airQuality` | Awair's own 0-100 score |
| `airQualityIndex` | Locally-calculated EPA AQI from a rolling PM2.5 average |
| `aiq_desc`, `pm25_desc`, `co2_desc`, `voc_desc` | Human-readable severity buckets (`good` / `fair` / `poor` / `bad` / `hazardous`) |

## Configuration

| Setting | Description |
|---|---|
| IP Address | Local IP of the Awair Omni device |
| API Path | Defaults to `/air-data/latest` |
| Polling interval | Seconds between checks (default 300) |

## Installation

Preferred: install via [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/)
using the `Hubitat-AwAir` package.

Manual: Hubitat admin UI → **Drivers Code** → **New Driver** → paste
[`drivers/Awair_Onmi_Local.groovy`](drivers/Awair_Onmi_Local.groovy) → Save.

## License

[CC0 1.0 Universal](LICENSE.txt) — public domain dedication.
