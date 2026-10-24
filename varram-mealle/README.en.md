# VARRAM Mealle Feeder — Hubitat Driver

An unofficial Hubitat Elevation driver for the **VARRAM Mealle** smart pet feeder (sold in Korea).

[한국어](README.md)

The Mealle has no public API and supports neither Matter, SmartThings, HomeKit, nor IFTTT. This driver talks to the vendor cloud API, reverse engineered from the official app's traffic.

## Features

- Manual feeding (`feed(gram)`) with three safety guards: per-dose cap, minimum interval, daily total limit
- Per-slot schedule ON/OFF
- Today's dispensed amount, intake, and bowl remainder
- Feed failure detection (empty hopper) with failure count
- Food shortage, power source, and firmware version exposed

## Requirements

- Hubitat Elevation (platform 2.3.x or later)
- A Mealle registered to a VARRAM account
- Internet access — the device is cloud-only, local control is not possible

## Installation (Kakao ID version, recommended)

If the account was created with Kakao login, a **Kakao ID alone** is enough; no mitmproxy token capture. The driver logs in by itself and auto-discovers User / Device / Pet IDs.

1. Hubitat → **Drivers Code** → **New Driver** → paste [`drivers/varram-mealle-feeder-kakao.groovy`](drivers/varram-mealle-feeder-kakao.groovy) → **Save**
2. **Devices** → **Add Device** → **Virtual** → type `VARRAM Mealle Feeder Kakao`
3. Enter `Kakao ID` and `seedSchedule` (see Schedule configuration), then **Save Preferences**
4. Success looks like `자동 검색: user=OK device=… pet=…` in the log

- Login: `POST auth.varram-robot.com/v2/auth/kakao {"kakao_id":"<numeric>"}` → `data.auth_data.access_token / refresh_token`. The server takes the numeric ID without any Kakao OAuth proof, so **treat the Kakao ID like a password**.
- Discovery: JWT `userid`, `GET /res/user/info`, `GET /get/connected_pet`, `GET /res/dev/info`
- Fallback: if a token refresh fails with an HTTP error, the driver re-logs in with the Kakao ID (verified with HTTP 403). A rejection returned as HTTP 200 + `{"type":"fail"}` is not handled yet.
- Per-slot time/gram cannot be fetched from the server; enter them once via `seedSchedule`.
- A fresh login may invalidate the official app's or the old driver's refresh token (unverified).
- **Never commit the Kakao ID, tokens or passwords.**

### Finding your Kakao ID

The Kakao ID is the numeric value (your Kakao member number) the app sends to the server at Kakao login. It is not shown anywhere in the app, so you read it from the login request. You only need to do this once.

1. Set up mitmproxy as in [Obtaining credentials](#obtaining-credentials) (proxy setting, install and trust the `mitm.it` certificate).
2. **Log out** of the VARRAM app. No login request is sent while you are still signed in.
3. Fully close and reopen the app, then **log in with Kakao**.
4. In mitmweb, find the `POST` to `auth.varram-robot.com/v2/auth/kakao` (type `kakao` in the filter box).
5. Copy the `kakao_id` value from the **Request** body:

```
{"kakao_id":"1234567890"}
```

6. Paste only the number into the driver's `Kakao ID` preference.

Note: logging out and in issues a new app session, which may invalidate the refresh token of a legacy token-based driver running alongside (unverified). The response also contains tokens, so never commit the capture file (`*.flows`).

### Accounts created with email / phone number

For non-Kakao accounts, enter the **email (or phone number) and password** instead of the Kakao ID. The same Kakao driver (`VARRAM Mealle Feeder Kakao`) handles it, and it is used only when the Kakao ID is empty.

- Settings: `Email or phone number` and `Password` (both required); leave the rest empty.
- Login: `POST auth.varram-robot.com/v2/login`, form fields `email` or `phone_number` plus `password`. Tokens, ID discovery and the fallback (re-login when a refresh fails) work as with Kakao.
- The response is assumed to match the Kakao one. Check the first run's log line `이메일 로그인 응답 키: …`; if no token is found, adjust the parser using the key names shown.
- The password is stored in plain text in Hubitat settings and backups. Use a VARRAM-only password.
- A capture of an in-app login contains the password in clear text. Never put it in docs, the repo or the wiki.

## Installation (legacy: token capture)


1. Hubitat → **Drivers Code** → **New Driver**
2. Paste [`drivers/varram-mealle-feeder.groovy`](drivers/varram-mealle-feeder.groovy) and **Save**
3. **Devices** → **Add Device** → **Virtual** → select type `VARRAM Mealle Feeder`
4. Fill in the settings below and **Save Preferences**

> HPM (Hubitat Package Manager) is not supported.

## Obtaining credentials

The driver authenticates exactly as the app does. Four values are required:

| Field | Description |
|---|---|
| User ID | Account identifier |
| Refresh Token | Long-lived token; rotates on every renewal |
| Device ID | The feeder itself |
| Pet ID | Used for feeding history (history is keyed by pet, not device) |

The official app never displays these, so you must **capture the app's traffic yourself**.

1. Run mitmproxy — `mitmweb --listen-port 8080 --set block_global=false`
2. Set a manual proxy on your phone's Wi-Fi settings
3. Visit `mitm.it` in Safari and install the certificate
4. Enable the toggle under **Settings → General → About → Certificate Trust Settings** — skipping this leaves the app throwing certificate errors
5. Force-quit and relaunch the app, then walk through login → device list → manual feed → feeding report

Take `refresh_token` from the **response body** of `POST auth.varram-robot.com/token` and `user_id` from its request body. `device_id` and `pet_id` appear in the paths of `/get/mobile/info/feeder/{device_id}` and `/get/device/info/feed/v2/{pet_id}`.

The app has no SSL pinning, so no bypass is needed.

## Schedule configuration

Hubitat text preferences are limited to roughly 255 characters, so a compact format is used:

```
time,grams,onoff[,name]  separated by semicolons
```

Example — seven meals active, the 21:00 slot disabled:

```
00:06,6,1;03:00,6,1;06:00,6,1;09:00,6,1;12:00,6,1;15:00,6,1;18:00,6,1;21:00,6,0
```

Run the `getSchedule` command after saving; `scheduleSummary` will show the current state. JSON is also accepted, but it won't fit in a preference field — pass it through the `importSchedule` command instead.

Changing `seedSchedule` or using `importSchedule` only changes the schedule **held by the driver**. Run `pushSchedule` (Kakao driver) to apply it to the feeder; if you changed times or grams, verify in the app's schedule screen afterwards.

**The vendor server does not store per-slot ON/OFF state.** The driver is therefore the single owner of the schedule. If you change the schedule in the official app, re-sync the driver with `importSchedule`, or the next toggle will overwrite your app-side changes.

## Connection modes

The vendor server appears to allow only one WebSocket session per device. If the driver holds the socket open, **the official app shows the feeder as offline.**

| Mode | Behaviour |
|---|---|
| `ondemand` (default) | Connects only when a command is queued, closes 15 s later. Coexists with the app |
| `persistent` | Stays connected for real-time push, but disconnects the app |

In on-demand mode, state is refreshed through the HTTP history endpoint every 15 minutes instead. Real-time `eat` / `feed_start` events are unavailable and intake data lags by up to 15 minutes.

## Attributes

| Name | Description |
|---|---|
| `dailyGivenGram` / `dailyIntakeGram` | Dispensed / consumed, by load-cell measurement |
| `appGivenGram` / `appIntakeGram` | Same figures converted to match the official app's display |
| `feedOkCount` / `feedFailCount` / `lastFailTime` | Successful and failed feeds, last failure timestamp |
| `lastBowlEmptyTime` | Last time the user emptied the bowl (`none_bowl` record) |
| `leftFeed` | Food remaining in the bowl |
| `lastEatTime` | Last time the pet ate |
| `foodLack` | Hopper low |
| `feedingStatus` | `waiting` / `feed_start` / `feed_finish` / `eat` |
| `scheduleSummary` / `scheduleActiveGram` / `scheduleSlots` | Schedule overview |
| `connection` | `online` / `connecting` / `idle` / `offline` |
| `powerSource` / `firmware` | Power type, firmware versions |

### Why measured and app-displayed values differ

The `gram` field is the load cell's actual dispensed weight (a calibrated value, e.g. 5.2 g), while the official app displays figures scaled to the configured portion size (e.g. 6 g):

```
app intake = floor(measured intake × configured portion ÷ measured dispense)
```

Both are exposed. Use `dailyIntakeGram` for true weight, or `appIntakeGram` when cross-checking against the app.

## Commands

`feed(gram)` `pullHistory` `dumpHistory` `getSchedule` `importSchedule` `exportSchedule` `pushSchedule` `scheduleSlotOn(slot)` `scheduleSlotOff(slot)` `scheduleAllOn` `scheduleAllOff` `dumpDeviceInfo` `closeSession` `reconnect`

Slot numbers are 1-based, ordered by time.

## Automation tip

A Rule Machine notification on `feedFailCount > 0` tells you immediately when a scheduled feed failed because the hopper ran dry. The feeder fails silently otherwise, which makes this the single most useful rule to set up.

## History status values

| status | Meaning | Driver handling |
|---|---|---|
| `feed_finish` | Right after dispensing | Counted as given |
| `waiting` | After eating (last record's `left` is the remainder) | Used for intake |
| `empty_feed` | Feeding failed (low food / small dispense) | Counted as failure, updates `lastFailTime` |
| `none_bowl` | User emptied the bowl (`gram=0`) | Not a failure; time stored in `lastBowlEmptyTime` |

## Known limitations

- Failure reasons (empty hopper vs. partial dispense) cannot be distinguished; the API version in use reports both identically.
- Cloud-dependent — if the vendor service goes down, so does the integration.
- If the refresh token chain breaks (app logout, password change), you must capture credentials again.
- App updates may change the API and break this driver.

## Disclaimer

This project is not affiliated with VARRAM. An automatic feeder directly controls a pet's meals — review the guard settings and test with small portions before wiring it into any automation.

## License

Apache License 2.0
