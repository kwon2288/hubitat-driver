# Tuya Zigbee Bed Presence (Pressure Strap) Sensor

[한국어](README.md)

A single Hubitat Elevation driver for two Tuya TS0601 pressure-strap occupancy sensors: `_TZE200_seq9cm6u` and the HOBEIAN ZG-210Z (`_TZE200_ihizzpzn`). Place it under a pillow or mattress and it reports whether it is being pressed as a presence state. The model is detected automatically from the device's manufacturer/model data.

## Supported devices

| Model | Manufacturer | Notes | Where to buy |
|---|---|---|---|
| TS0601 | `_TZE200_seq9cm6u` | Z2M model `TS0601_bed_presence_sensor` | [AliExpress](https://www.aliexpress.com/item/1005012085353795.html) |
| ZG-210Z (HOBEIAN) | `_TZE200_ihizzpzn` | Pressure strap with temperature/humidity | [AliExpress](https://www.aliexpress.com/item/1005013229135312.html) |

Fingerprints:

```
profileId:"0104", endpointId:"01", inClusters:"0000,EF00", outClusters:"000A,0019", model:"TS0601", manufacturer:"_TZE200_seq9cm6u"
profileId:"0104", endpointId:"01", inClusters:"0000,0003,0500,0406,EF00,0402,0405,0001", outClusters:"0003", model:"ZG-210Z", manufacturer:"_TZE200_ihizzpzn"
```

## Features

- Presence reporting: `presence` (present / not present)
- Mirrored `motion` (active / inactive) so motion-based apps such as Motion Lighting work out of the box (can be disabled)
- Battery reporting. `_TZE200_seq9cm6u` also reports illuminance and work state (`workState`); the ZG-210Z also reports temperature, humidity, raw pressure (`currentPressure`) and state (`pressureState`: none / sit / sedentary)
- Per-model settings (sensitivity, sampling interval, pressure threshold, report delays, temperature/humidity calibration, ...) configurable from the device page
- Timestamp of the last presence change (`lastPresenceChange`)
- Automatic reply to the device's Tuya time-sync request (0x24)
- Debug logging turns itself off after 30 minutes

## Layout

```
tuya-bed-presence/
├── README.md
├── README.en.md
└── drivers/
    └── tuya-bed-presence-sensor.groovy
```

## Installation

1. In the Hubitat web UI, open **Drivers Code → New Driver**, paste the contents of `drivers/tuya-bed-presence-sensor.groovy` and save.
2. Pair the sensor: insert the battery and hold the reset button on the case for about 5 seconds until the LED blinks.
3. If the device was already joined with another driver, change **Type** to `Tuya Zigbee Bed Presence Sensor` on the device page and click **Save Device**.
4. Click **Configure**, then press and release the strap and check that `presence` changes.

## Preferences

| Setting | Range | Description |
|---|---|---|
| Sensitivity | low / middle / high | Pressure detection sensitivity (seq9cm6u) |
| Sampling interval | 5–720 min (step 5) | Sampling interval; non-multiples of 5 are rounded (seq9cm6u) |
| Delay before reporting 'not present' | 0–3600 s | Delay after pressure is released before reporting absence (both models; ZG-210Z defaults to 5 s when unset) |
| Delay before reporting 'present' | 0–3600 s | Delay after pressure is detected before reporting presence (seq9cm6u) |
| Detection threshold / 감지 기준값 | number | Present when `currentPressure` is at or above this value (ZG-210Z). Leave blank to use the device's own judgement (`pressure_state`). Fixed hysteresis ±20 |
| Temperature calibration | -2.0–2.0 °C | (ZG-210Z) |
| Humidity calibration | -30–30 % | (ZG-210Z) |
| Mirror presence to motion | on / off | Whether to also report the `motion` attribute |

A setting is only sent when it differs from the value the device last reported.

### ZG-210Z specifics

- **Presence**: with a detection threshold set, the driver reports present when `currentPressure >= threshold` (fixed ±20 hysteresis). Going back to not present waits for the "not present" delay and is cancelled if pressure returns. Leave the threshold blank to use the device's own judgement (DP 1). The firmware threshold (DP 105) does not track the real load well, so it is not used.
- **Pending write queue**: a sleepy device often drops a write sent while it is asleep, so delay/calibration changes are kept as pending until the device echoes the value, and re-sent every time the device reports (throttled to once per 20 s, at most 15 attempts).
- **Cleanup**: saving preferences removes the 1.2.0 attributes (`pressureIntensity`, `sedentaryReminder`, `pendingSettings`) and settings.

## Attributes

| Attribute | Values |
|---|---|
| `presence` | present, not present |
| `motion` | active, inactive |
| `battery` | % |
| `illuminance` | lx |
| `sensitivity` | low, middle, high |
| `intervalTime` | minutes |
| `presenceDelay` | seconds |
| `presenceTime` | seconds |
| `workState` | presence, none, presence_5min, presence_30min, none_5min, none_30min (seq9cm6u) |
| `temperature` / `humidity` | °C (or °F) / % (ZG-210Z) |
| `pressureState` | none, sit, sedentary (ZG-210Z) |
| `currentPressure` | raw pressure value (ZG-210Z) |
| `detectionThreshold` | configured detection threshold (ZG-210Z; hidden when the threshold is blank) |
| `temperatureCalibration` / `humidityCalibration` | calibration offsets (ZG-210Z) |
| `lastPresenceChange` | yyyy-MM-dd HH:mm:ss |

## Tuya DP map

### `_TZE200_seq9cm6u`

| DP | Type | Name | Notes |
|---|---|---|---|
| 1 | enum | occupancy | 0 = occupied (pressed), 1 = unoccupied |
| 4 | value | battery | % |
| 9 | enum | sensitivity | 0 low, 1 middle, 2 high (read/write) |
| 12 | value | illuminance | lux (as classified by Z2M) |
| 101 | value | interval_time | minutes (read/write) |
| 102 | value | presence_delay | seconds (read/write) |
| 103 | value | presence_time | seconds (read/write) |
| 104 | enum | work_state | 0–5 |

### HOBEIAN ZG-210Z (`_TZE200_ihizzpzn`)

| DP | Type | Name | Notes |
|---|---|---|---|
| 1 | enum | pressure_state | 0 none, 1 sit, 2 sedentary |
| 2 | value | current_pressure | raw sensing value |
| 15 | value | battery | % |
| 101 | value | temperature | ÷10 °C |
| 102 | value | humidity | % |
| 103 | value | temperature_calibration | ÷10 °C, -2.0–2.0 (read/write) |
| 104 | value | humidity_calibration | -30–30 % (read/write) |
| 105 | value | pressure_intensity | 0–2000 (read/write) |
| 106 | value | presence_delay | 0–3600 s (read/write) |
| 107 | value | sedentary_reminder | 0–1440 min (read/write) |

The ZG-210Z also reports the standard ZCL Temperature (0402), Humidity (0405) and Power Configuration (0001) attributes, which the driver parses too.

## Known limitations

- This is a sleepy battery device. If a saved setting is not applied, wake the device by pressing the strap or briefly pressing the button. **Refresh** is also only answered while the device is awake.
- The device page shows the settings of both models, but only those of the detected model are applied.
- DP 12 (seq9cm6u) is exposed as illuminance following Z2M, but whether it is a real light reading still needs to be confirmed.

## Credits

- DP definitions: [Koenkk/zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) `TS0601_bed_presence_sensor`, `ZG-210Z`
