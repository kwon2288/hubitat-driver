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

## Installation

If the account was created with Kakao login, a **Kakao ID alone** is enough; no token capture is needed. The driver logs in by itself and auto-discovers User / Device / Pet IDs.

1. Hubitat → **Drivers Code** → **New Driver** → paste [`drivers/varram-mealle-feeder.groovy`](drivers/varram-mealle-feeder.groovy) → **Save**
2. **Devices** → **Add Device** → **Virtual** → type `VARRAM Mealle Feeder`
3. Enter `Kakao ID`, then **Save Preferences**
4. Success looks like `자동 검색: user=OK device=… pet=…` in the log

- Login: `POST auth.varram-robot.com/v2/auth/kakao {"kakao_id":"<numeric>"}` → `data.auth_data.access_token / refresh_token`. The server takes the numeric ID without any Kakao OAuth proof, so **treat the Kakao ID like a password**.
- Discovery: JWT `userid`, `GET /res/user/info`, `GET /get/connected_pet`, `GET /res/dev/info`
- Fallback: if a token refresh fails with an HTTP error, the driver re-logs in with the Kakao ID (verified with HTTP 403). A rejection returned as HTTP 200 + `{"type":"fail"}` is not handled yet.
- Per-slot time/gram/ON-OFF are fetched from the server automatically.
- A fresh login may invalidate the official app's refresh token (unverified).
- **Never commit the Kakao ID, tokens or passwords.**

### Finding your Kakao ID

The Kakao ID is the numeric value (your Kakao member number) the app sends to the server at Kakao login. It is not shown anywhere in the app, so you read it from the login request. You only need to do this once.

1. Set up mitmproxy as in [Preparing mitmproxy](#preparing-mitmproxy) (proxy setting, install and trust the `mitm.it` certificate).
2. **Log out** of the VARRAM app. No login request is sent while you are still signed in.
3. Fully close and reopen the app, then **log in with Kakao**.
4. In mitmweb, find the `POST` to `auth.varram-robot.com/v2/auth/kakao` (type `kakao` in the filter box).
5. Copy the `kakao_id` value from the **Request** body:

```
{"kakao_id":"1234567890"}
```

6. Paste only the number into the driver's `Kakao ID` preference.

Note: logging out and in issues a new app session. The response also contains tokens, so never commit the capture file (`*.flows`).

### Accounts created with an email

For non-Kakao accounts, enter the **email and password** instead of the Kakao ID. The same driver (`VARRAM Mealle Feeder`) handles it, and it is used only when the Kakao ID is empty.

- Settings: `Email` and `Password` (both required); leave the rest empty.
- Login: `POST auth.varram-robot.com/v2/login`, form fields `email` plus `password`. Tokens, ID discovery and the fallback (re-login when a refresh fails) work as with Kakao.
- The response is assumed to match the Kakao one. Check the first run's log line `이메일 로그인 응답 키: …`; if no token is found, adjust the parser using the key names shown.
- The password is stored in plain text in Hubitat settings and backups. Use a VARRAM-only password.
- A capture of an in-app login contains the password in clear text. Never put it in docs, the repo or the wiki.

> HPM (Hubitat Package Manager) is not supported.

## Preparing mitmproxy

The Kakao ID is not shown in the app, so you read it from the login request.

1. Run mitmproxy — `mitmweb --listen-port 8080 --set block_global=false`
2. Set a manual proxy on your phone's Wi-Fi settings
3. Visit `mitm.it` in Safari and install the certificate
4. Enable the toggle under **Settings → General → About → Certificate Trust Settings** — skipping this leaves the app throwing certificate errors
5. Force-quit and relaunch the app

The app has no SSL pinning, so no bypass is needed.

## Schedule

The schedule is fetched from the server automatically; there is nothing to enter. The server stores the full schedule including per-slot time, gram and ON/OFF (`request_schedule_data`). The driver reads it on start and every 30 minutes and shows it in `scheduleSummary`. Run `getSchedule` to pick up changes made in the official app immediately.

- Per-slot ON/OFF: `scheduleSlotOn(slot)` / `scheduleSlotOff(slot)` / `scheduleAllOn` / `scheduleAllOff` — changes are sent to the feeder and saved to the server right away.
- Changing times or grams: pass the new schedule to `importSchedule`, then run `pushSchedule`. `importSchedule` alone only changes the schedule **held by the driver**, which reverts to the server's copy within 30 minutes. Verify in the app's schedule screen afterwards (sending time/gram changes is not yet well tested).
- `readServerSchedule`: read-only command that just logs the server's copy.

`importSchedule` accepts a JSON array or the compact format (`time,grams,onoff[,name]`, semicolon-separated):

```
00:06,6,1;03:00,6,1;06:00,6,1;09:00,6,1;12:00,6,1;15:00,6,1;18:00,6,1;21:00,6,0
```

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

`feed(gram)` `pullHistory` `dumpHistory` `getSchedule` `importSchedule` `exportSchedule` `readServerSchedule` `pushSchedule` `scheduleSlotOn(slot)` `scheduleSlotOff(slot)` `scheduleAllOn` `scheduleAllOff` `dumpDeviceInfo` `closeSession` `reconnect`

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
