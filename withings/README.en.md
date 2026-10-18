# Withings

[한국어 README](./README.md)

App + drivers that integrate Withings devices (Sleep Analyzer, scales, blood pressure
monitors, thermometers, activity trackers) with Hubitat. This is a copy of
[lnjustin/hubitat-withings](https://github.com/lnjustin/hubitat-withings) (v1.7.4,
originally by Dominick Meglio) **patched to run on Hubitat 2.5.x firmware**.

## Why it was patched

Starting with Hubitat 2.5.x, app and driver code may no longer touch `metaClass`. The
original `Withings User` app extends `String` at the top of the script with
`String.metaClass.toSHA1`, and since that line runs every time the app executes, the
whole app fails immediately with:

```
java.lang.SecurityException: Subscript property 'metaClass' is not allowed from app or driver code on line 78 (method run)
```

Withings webhooks (bed in/out, sleep summary, weight, …) keep arriving, but no device
ever updates. The upstream repo has had no updates since January 2025, so the fix lives
here.

## Changes (v1.7.5)

- `apps/Withings_User.groovy`: replaced the `String.metaClass.toSHA1` extension with a
  plain `toSHA1(String str, String salt)` method and updated its caller
- The hash algorithm and output are unchanged, so existing devices (e.g. the virtual
  activity tracker DNI) are unaffected
- All other files (`Withings_Integration.groovy`, `drivers/*`) are identical to upstream

## Layout

```
withings/
├── apps/
│   ├── Withings_Integration.groovy   # parent app (OAuth client settings)
│   └── Withings_User.groovy          # per-user child app (v1.7.5, patched)
└── drivers/
    ├── Withings_Activity_Tracker.groovy
    ├── Withings_Blood_Pressure_Monitor.groovy
    ├── Withings_Scale.groovy
    ├── Withings_Sleep_Sensor.groovy
    └── Withings_Thermometer.groovy
```

## Applying the fix

If you already run the original, just replace the code of **Apps Code → Withings User**
with `apps/Withings_User.groovy` and save. No reconfiguration or Withings
re-authorization is needed.

For a fresh install, follow the upstream README (register a Withings developer app →
install the parent app → add users), using this folder's `Withings User` file.

> If you installed the original through HPM, an HPM update or Repair may overwrite this
> fix with the upstream version.

## License

BSD 3-Clause License, same as upstream (see each file's header). The repository-root
Apache 2.0 license does not apply to this folder.
