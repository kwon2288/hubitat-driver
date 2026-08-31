# Homey Bridge

A custom Hubitat app + drivers that pull Homey Pro devices into Hubitat via
Homey's local REST API.

[한국어 README](./README.md)

## Layout

```
homey-bridge/
├── apps/
│   └── homey-bridge-app.groovy          # parent app
└── drivers/
    ├── homey-switch-driver.groovy           # plain on/off child driver
    ├── homey-dimmer-driver.groovy           # dimming/color child driver
    ├── homey-generic-device-driver.groovy   # sensor/button/shade child driver
    └── homey-onair-radio-driver.groovy      # radio-specific child driver (MusicPlayer)
```

## Key features

- Reads and controls devices via Homey Pro's local REST API
  (`/api/manager/devices/device/...`)
- Auto-creates/removes a Hubitat child device per selected Homey device
  (newly created devices default to the `Homey Generic Device` driver -
  switches/dimmers/radios need their Type changed manually afterward,
  see step 5 under Installation)
- Selectable **Update Mode**:
  - `Polling only` (default, no extra setup required)
  - `Polling + Webhook`
  - `Webhook only`

## Drivers

### Homey Switch

A minimal driver for plain on/off devices - plugs, wall switches with no
dimming or color control.

| Homey capability | Hubitat mapping |
|---|---|
| `onoff` | Switch |

### Homey Dimmer

A driver for dimming and color/color temperature. Dimmable devices need
their own on/off control too, so Switch is included alongside SwitchLevel.

| Homey capability | Hubitat mapping |
|---|---|
| `onoff` | Switch |
| `dim` | SwitchLevel |
| `light_hue` / `light_saturation` | ColorControl |
| `light_temperature` | ColorTemperature (approximate 2200K-6500K mapping) |

### Homey Generic Device

A driver dedicated to sensors and other non-switch devices: temperature,
humidity, illuminance, power, motion, contact, battery, a push button
(PushableButton), and window coverings.

| Homey capability | Hubitat mapping |
|---|---|
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
   → child devices are created automatically (default Type is
   `Homey Generic Device` - change plain on/off devices to `Homey Switch`,
   dimming/color devices to `Homey Dimmer`, and radios to `Homey OnAir Radio`
   in Device Information afterward)

## Finding capability IDs / homeyId

To add a new device or set up a webhook, you need to know which capability
IDs a device actually exposes on Homey, and the internal `homeyId` Hubitat
assigned it. No need to dig through Homey's developer docs - the drivers
already log this for you.

1. Open the child device's page → check `Enable debug logging` under
   `Preferences` → click `Save Preferences`
2. Click the `Refresh` command on the same page (no need to wait for the
   next poll cycle)
3. Check Hubitat's `Logs` (Live Logs) page for a line like:
   ```
   updateFromHomey received: [speaker_playing:[..., id:speaker_playing, getable:true, setable:true, value:false], ...]
   ```
   Each top-level key (`speaker_playing`, `measure_temperature`, etc.) is
   that device's actual capability ID. The `setable`/`getable` flags also
   tell you immediately whether that capability can be written to / read
   from via Hubitat (`setable:false` means Homey's server will reject any
   write regardless of what Hubitat sends).
4. Unmapped capabilities show up separately as
   `Unmapped Homey capability <id> = <value>`. Use that ID to add a new
   `case` to the driver's `updateFromHomey()` switch statement.
5. A child device's `homeyId` (needed for webhook URLs, etc.) is listed per
   device in the parent app's (`Homey Bridge`) settings page, under the
   `Realtime Webhook` section.

## Realtime webhook setup (optional)

Skip this section if `Update Mode` is set to "Polling only". This is only
needed if you want Hubitat to reflect a Homey state change immediately
instead of waiting for the next poll.

### Concept

- **Polling**: Hubitat asks Homey for its current state every few minutes.
  Simple to implement, but introduces up to one poll-interval's worth of lag.
- **Webhook**: Homey notifies Hubitat the moment a state changes, via a
  Homey Flow calling an HTTP endpoint this app exposes. The endpoint itself
  is always active regardless of the `Update Mode` setting.

### 1. Enable OAuth for the Hubitat app (one-time)

1. Open `Apps Code` → `Homey Bridge`
2. Top-right menu → `OAuth` → check `Enable OAuth in Apps` → `Update`
3. Skipping this makes `createAccessToken()` fail, and no webhook URL will
   be generated.

### 2. Find the webhook URL

`Apps` → `Homey Bridge` → bottom `Realtime Webhook` section:

```
http://<hub-ip>/apps/api/<app-id>/webhook/<homeyId>/<capability>?value=[[value]]&access_token=<token>
```

`<hub-ip>`, `<app-id>`, and `<token>` are already filled in on that page.
Get `<homeyId>` from the device list right below it, and `<capability>` from
the ["Finding capability IDs"](#finding-capability-ids--homeyid) section
above.

### 3. Make sure Homey has the Logic app installed

Search **Logic** in the Homey app store (often pre-installed). It provides a
Flow card for making an arbitrary HTTP request. Searching for "webhook" in
the card picker won't find it - Homey has no dedicated webhook card; you
reuse its generic HTTP request card for this purpose.

### 4. Create a Flow (example: playback status)

1. Homey app → the device → `Flow` tab → new Flow
2. **WHEN**: trigger on "when [playing status] changes"
3. **THEN**: add the generic "make a web request" card from Logic
   - **Method**: `GET`
   - **URL**: fill in the format above with `<capability>` fixed to
     `speaker_playing`
   - For `[[value]]`, either insert the changed-value tag from the WHEN
     trigger, or just hardcode `true`/`false` directly
   - Headers/Body can be left empty for a GET request
4. Save

Repeat for other capabilities (`speaker_track`, `speaker_artist`, etc.) to
get those reflected in realtime too. If creating a Flow per capability is
too much, use "Polling + Webhook" mode instead - whatever you did wire up a
Flow for updates instantly, and polling covers the rest.

### 5. A note on value formatting

Homey's Flow tags sometimes come through as words like `playing`/`paused`
instead of `true`/`false`. If that happens, the simplest fix is to hardcode
`true`/`false` directly in the Flow instead of using the tag.

### 6. Testing

1. Trigger a real state change on the device (e.g. Play/Pause)
2. Check Hubitat's `Logs` for a line like:
   ```
   webhookHandler: homey-<id> speaker_playing raw='true' -> true
   ```
3. If nothing shows up: check the Flow's run history in the Homey app to
   confirm it actually fired, double-check the `homeyId`/`access_token` in
   the URL, and confirm Homey and Hubitat are on the same local network
   (check for firewall/VLAN separation).

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

Look up the capability ID using the
["Finding capability IDs"](#finding-capability-ids--homeyid) steps above,
then add a new `case` to the switch statement inside `updateFromHomey()`.
