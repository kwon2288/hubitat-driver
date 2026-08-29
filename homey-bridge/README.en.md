# Homey Bridge

A custom Hubitat app + drivers that pull Homey Pro devices into Hubitat via
Homey's local REST API.

[한국어 README](./README.md)

## Layout

```
homey-bridge/
├── apps/
│   └── homey-bridge-app.groovy          # parent app
├── drivers/
│   ├── homey-generic-device-driver.groovy   # generic child driver (switches/sensors)
│   └── homey-onair-radio-driver.groovy      # radio-specific child driver (MusicPlayer)
└── docs/
    └── REALTIME-WEBHOOK.md              # realtime webhook setup guide (Korean)
```

## Key features

- Reads and controls devices via Homey Pro's local REST API
  (`/api/manager/devices/device/...`)
- Auto-creates/removes a Hubitat child device per selected Homey device
- Selectable **Update Mode**:
  - `Polling only` (default, no extra setup required)
  - `Polling + Webhook`
  - `Webhook only`
- With webhook mode, Homey Flow pushes state changes to a Hubitat local API
  endpoint for near-instant updates (see
  [docs/REALTIME-WEBHOOK.md](./docs/REALTIME-WEBHOOK.md) for setup - Korean only)

## Drivers

### Homey Generic Device

A generic driver mapping on/off, dimming, color/color temperature, window
coverings, temperature/humidity/illuminance/power/motion/contact/battery
sensors, and a push button (PushableButton).

| Homey capability | Hubitat mapping |
|---|---|
| `onoff` | Switch |
| `dim` | SwitchLevel |
| `light_hue` / `light_saturation` | ColorControl |
| `light_temperature` | ColorTemperature (approximate 2200K-6500K mapping) |
| `measure_temperature` | TemperatureMeasurement |
| `measure_humidity` | RelativeHumidityMeasurement |
| `measure_luminance` | IlluminanceMeasurement |
| `measure_power` | PowerMeter |
| `meter_power` | EnergyMeter |
| `alarm_motion` | MotionSensor |
| `alarm_contact` | ContactSensor |
| `alarm_generic` | PresenceSensor |
| `measure_battery` | Battery |
| `windowcoverings_set` | WindowShade |
| `button` | PushableButton |

### Homey OnAir Radio

A dedicated driver for radio/speaker-type Homey devices that use the media
capabilities (`speaker_playing`, `speaker_next`, `speaker_prev`,
`speaker_track`, `speaker_artist`). Uses the standard `MusicPlayer` capability
so the device types correctly for dashboards, Rule Machine, and voice
assistant bridges.

- **Working**: Play / Pause / Next / Previous
- **Read-only display**: track title, artist, volume (some devices report
  their volume capability as not writable, e.g. an `lge_volume_set` custom
  capability with `setable:false`)
- **No-op**: Mute/Unmute on devices that don't expose a mute capability
- Each command triggers an automatic state refresh 2 seconds later, so you
  don't have to wait for the next poll cycle

## Installation

1. **Register the drivers**
   Hubitat admin UI → `Drivers Code` → `New Driver` → paste in each `.groovy`
   file under `drivers/` and save (one new driver per file)

2. **Register the app**
   `Apps Code` → `New App` → paste in `apps/homey-bridge-app.groovy` and save

3. **Get a Homey Personal Access Token**
   Homey app → Settings → Advanced → API Keys (Homey Pro only - Homey Cloud
   models don't support the local API)

4. **Install the app**
   `Apps` → `Add User App` → select `Homey Bridge`
   - Enter Homey Pro IP address and token
   - Choose Update Mode / Poll Interval
   - A connection test result (✅/❌) is shown immediately after saving

5. **Select devices**
   Open `Select Homey Devices to Import` → check the devices you want → save
   → child devices are created automatically (for radios, change the Type to
   `Homey OnAir Radio` in Device Information afterward)

## Known limitations

- Single Homey hub only (`singleInstance: true`) - remove this from the app
  definition if you need to support more than one
- Color/color temperature mapping is approximate - may need tuning per bulb
- Homey and Hubitat must be on the same local network
- Some devices report certain capabilities as `setable:false` on Homey's API,
  which makes them fundamentally unwritable from Hubitat (if there's no Homey
  Flow action card workaround either, you'll need to control that function
  directly from the physical remote or the Homey app)

## Extending

Add a new `case` to the switch statement inside `updateFromHomey()` to map an
additional capability. If you're not sure what capabilities a device exposes,
enable the `logDebug` preference on the child device, hit Refresh, and check
the `Unmapped Homey capability ...` lines in Hubitat's `Logs`.
