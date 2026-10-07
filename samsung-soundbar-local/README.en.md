# Samsung Soundbar Local - Hubitat Driver

Local (LAN-only) control for 2024+ Samsung Wi-Fi soundbars on **Hubitat Elevation**.
No cloud round-trip, no SmartThings API key — the driver talks directly to the
soundbar's local JSON-RPC API over TCP **1516**, the same protocol the SmartThings
app itself uses on the LAN.

Protocol reverse-engineered by [ZtF](https://github.com/ZtF) for Home Assistant
([hass-samsung-soundbar-local](https://github.com/ZtF/hass-samsung-soundbar-local));
this repo ports that protocol to a native Hubitat driver.

[한국어 README →](README.ko.md)

---

## Supported models

Per the upstream project and community reports:

`HW-Q990D` · `HW-Q930D` · `HW-Q800D` · `HW-QS730D` · `HW-S800D` · `HW-S801D` ·
`HW-S700D` · `HW-S60D` · `HW-S61D` · `HW-LS60D`

Some later F-series bars (e.g. `HW-Q990F`) have also been reported working.
**2023-and-older / C-series bars do not implement this local API and will not work.**

## Features

- Standard `Switch` capability — power on/off
- Standard `AudioVolume` capability — volume, volume up/down, mute/unmute
- Standard `MediaInputSource` capability — dropdown selection:
  `HDMI_IN1`, `HDMI_IN2`, `E_ARC`, `ARC`, `D_IN`, `BT`, `WIFI_IDLE`
- Sound mode dropdown: `STANDARD`, `SURROUND`, `GAME`, `MOVIE`, `MUSIC`,
  `CLEARVOICE`, `DTS_VIRTUAL_X`, `ADAPTIVE`
- Subwoofer `+` / `-` step commands
- Configurable auto-refresh polling
- Automatic AccessToken acquisition, caching, and recovery on auth failure

## Requirements

- Soundbar registered in the **Samsung SmartThings app**, connected to Wi-Fi
- **"IP control" enabled** on the soundbar (SmartThings app → device settings) —
  this is what allows the driver to obtain an AccessToken
- The soundbar and the Hubitat hub must be on the same LAN/VLAN — the API is
  local-only, not routable through the cloud

## Installation

1. Hubitat → **Drivers Code** → **New Driver**
2. Paste in [`samsung_soundbar_local.groovy`](samsung-soundbar-local/samsung_soundbar_local.groovy),
   or use **Import** with:
   ```
   https://raw.githubusercontent.com/kwon2288/hubitat-driver/main/samsung-soundbar-local/samsung_soundbar_local.groovy
   ```
3. **Save**
4. **Devices** → **Add Device** → **Virtual** (or select an existing device) →
   set **Type** to `Samsung Soundbar Local`
5. In device **Preferences**, enter the soundbar's IP address → **Save Preferences**
6. Hit **Refresh** — the driver will request its AccessToken on the first call

## Configuration

| Setting | Description | Default |
|---|---|---|
| IP address | Soundbar's local IP (set a DHCP reservation) | *required* |
| Auto-refresh interval | Disabled / 1 / 5 / 10 / 30 min | 5 min |
| Debug logging | Verbose RPC logging, auto-disables after 30 min | on |
| Descriptive text logging | Info-level status logging | on |

## Commands

| Command | Capability | Notes |
|---|---|---|
| `on()` / `off()` | Switch | |
| `setVolume(level)` | AudioVolume | No absolute-set API exists on the bar — steps VOL_UP/DOWN to the target |
| `volumeUp()` / `volumeDown()` | AudioVolume | |
| `mute()` / `unmute()` | AudioVolume | |
| `setInputSource(source)` | MediaInputSource | Dropdown-constrained |
| `setSoundMode(mode)` | custom | Dropdown-constrained |
| `subwooferUp()` / `subwooferDown()` | custom | |
| `refresh()` | Refresh | Polls power/volume/mute/input/sound mode/codec/identifier |
| `resetAccessToken()` | custom | Clears the cached token, forces re-pairing on next call |

## Known limitations

- **No absolute volume-set endpoint.** The soundbar only exposes `VOL_UP` /
  `VOL_DOWN` remote-key steps, so `setVolume()` reads the current level and
  steps toward the target with a short delay between presses. Large jumps
  (e.g. 10 → 80) take a few seconds — this mirrors how the upstream HA
  integration handles it.
- Only 2024-and-newer Wi-Fi soundbars expose this local API; older bars will
  fail at the AccessToken step.
- Input-selection behavior can vary by SKU/firmware — a few users on the
  upstream project report certain HDMI inputs not switching on some models.

## Credits

- Local API protocol reverse-engineered by [ZtF](https://github.com/ZtF) —
  [hass-samsung-soundbar-local](https://github.com/ZtF/hass-samsung-soundbar-local) (MIT)

## License

MIT
