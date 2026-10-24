# Replica S-PILLOW — Hubitat driver

[한국어](README.md)

A [HubiThings Replica](https://github.com/ogiewon/Hubitat/tree/master/HubiThings%20Replica) driver that brings the Samsung SmartThings **S-PILLOW** anti-snoring pillow (AIHatinroo / peoplemulti) into Hubitat. This is a trimmed build that keeps only the features needed.

## Features

- Pillow on / off (`on` / `off`)
- Pillow state `pillowState`: off, preparing, running, stopped (`switch` is on while `preparing` or `running`)
- Snore detection `soundDetected`: noSound, snoring
- Enable / disable snore detection (`enableSoundDetection` / `disableSoundDetection`), state in `soundDetectionState`
- Bedroom temperature, humidity and CO2 reporting
- Device connectivity `healthStatus`: online, offline

## Requirements

- Hubitat Elevation hub
- HubiThings Replica app installed and linked to your SmartThings account
- An S-PILLOW registered in SmartThings

## Installation

1. In the Hubitat web UI, open **Drivers Code → New Driver**, paste the contents of [`drivers/replica-s-pillow.groovy`](drivers/replica-s-pillow.groovy) and save.
2. When adding the S-PILLOW in the HubiThings Replica app, pick `Replica S-PILLOW` as the driver.
3. On the device page, press **Configure** to register the default Replica rules (defined in `getReplicaRules()`).

## Commands and attributes

| Kind | Name | Description |
|---|---|---|
| Command | `on`, `off` | Pillow on/off (SmartThings `antiSnoringPillow`) |
| Command | `enableSoundDetection`, `disableSoundDetection` | Snore detection on/off (`soundDetection`) |
| Command | `refresh`, `configure` | Refresh state / register rules |
| Attribute | `switch` | on, off — derived from `pillowState` |
| Attribute | `pillowState` | off, preparing, running, stopped |
| Attribute | `soundDetected` | noSound, snoring |
| Attribute | `soundDetectionState` | enabled, disabled |
| Attribute | `temperature`, `humidity`, `carbonDioxide` | °C (or °F), %, ppm |
| Attribute | `healthStatus` | online, offline |

## Notes

- Every SmartThings → Hubitat `set…Value` setter must be declared as a `command`, otherwise Replica blocks it with a `SecurityException`. Keep that rule when adding attributes.
- The temperature, humidity and CO2 rules are registered with `mute: true` so they do not flood the SmartThings event log.
- Preferences: `Disable Info logging` and `Enable Debug logging`.

## License

Apache License 2.0
