# Tuya Zigbee Bed Presence (Pressure Strap) Sensor

[한국어](README.md)

A Hubitat Elevation driver for the Tuya TS0601 pressure-strap occupancy sensor (`_TZE200_seq9cm6u`). Place it under a pillow or mattress and it reports whether it is being pressed as a presence state.

## Supported device

| Model | Manufacturer | Notes |
|---|---|---|
| TS0601 | `_TZE200_seq9cm6u` | Z2M model `TS0601_bed_presence_sensor` |

Fingerprint:

```
profileId:"0104", endpointId:"01", inClusters:"0000,EF00", outClusters:"000A,0019", model:"TS0601", manufacturer:"_TZE200_seq9cm6u"
```

## Features

- Presence reporting: `presence` (present / not present)
- Mirrored `motion` (active / inactive) so motion-based apps such as Motion Lighting work out of the box (can be disabled)
- Battery, illuminance and work state (`workState`) reporting
- Sensitivity, sampling interval and presence/absence report delays configurable from the device page
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
| Sensitivity | low / middle / high | Pressure detection sensitivity |
| Sampling interval | 5–720 min (step 5) | Sampling interval; non-multiples of 5 are rounded |
| Delay before reporting 'not present' | 0–3600 s | Delay after pressure is released before reporting absence |
| Delay before reporting 'present' | 0–3600 s | Delay after pressure is detected before reporting presence |
| Mirror presence to motion | on / off | Whether to also report the `motion` attribute |

A setting is only sent when it differs from the value the device last reported.

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
| `workState` | presence, none, presence_5min, presence_30min, none_5min, none_30min |
| `lastPresenceChange` | yyyy-MM-dd HH:mm:ss |

## Tuya DP map

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

## Known limitations

- This is a sleepy battery device. If a saved setting is not applied, wake the device by pressing the strap or briefly pressing the button. **Refresh** is also only answered while the device is awake.
- DP 12 is exposed as illuminance following Z2M, but whether it is a real light reading still needs to be confirmed.

## Credits

- DP definitions: [Koenkk/zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) `TS0601_bed_presence_sensor`
