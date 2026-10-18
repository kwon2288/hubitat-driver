# Tuya 지그비 침대 재실(압력 스트랩) 센서

[English](README.en.md)

Tuya TS0601 압력 스트랩형 재실 센서 두 종(`_TZE200_seq9cm6u`, HOBEIAN ZG-210Z `_TZE200_ihizzpzn`)을 Hubitat Elevation에서 **하나의 드라이버**로 사용하기 위한 드라이버입니다. 베개나 매트리스 아래에 깔아 두면 눌림 여부를 재실(presence) 상태로 보고합니다. 기기 종류는 제조사/모델 정보로 자동 판별합니다.

## 지원 기기

| 모델 | 제조사 코드 | 비고 |
|---|---|---|
| TS0601 | `_TZE200_seq9cm6u` | Z2M 모델명 `TS0601_bed_presence_sensor` |
| ZG-210Z (HOBEIAN) | `_TZE200_ihizzpzn` | 압력 스트랩 + 온습도 센서 |

페어링 지문(fingerprint):

```
profileId:"0104", endpointId:"01", inClusters:"0000,EF00", outClusters:"000A,0019", model:"TS0601", manufacturer:"_TZE200_seq9cm6u"
profileId:"0104", endpointId:"01", inClusters:"0000,0003,0500,0406,EF00,0402,0405,0001", outClusters:"0003", model:"ZG-210Z", manufacturer:"_TZE200_ihizzpzn"
```

## 기능

- 재실 상태 보고: `presence`(present / not present)
- `motion`(active / inactive) 동시 보고 — Motion Lighting 등 모션 기반 앱에서 바로 사용 가능 (설정에서 끌 수 있음)
- 배터리 보고. `_TZE200_seq9cm6u`는 조도·동작 상태(`workState`), ZG-210Z는 온도·습도·압력값(`currentPressure`)·상태(`pressureState`: none / sit / sedentary)도 보고
- 기기별 설정(감도, 샘플링 주기, 압력 임계값, 보고 지연, 온습도 보정 등)을 기기 설정 화면에서 변경
- 마지막 재실 상태 변경 시각(`lastPresenceChange`) 기록
- 기기의 시간 동기화 요청(Tuya 0x24)에 자동 응답
- 디버그 로그 30분 후 자동 꺼짐

## 파일 구성

```
tuya-bed-presence/
├── README.md
├── README.en.md
└── drivers/
    └── tuya-bed-presence-sensor.groovy
```

## 설치

1. Hubitat 웹 UI에서 **Drivers Code → New Driver**를 열고 `drivers/tuya-bed-presence-sensor.groovy` 내용을 붙여넣은 뒤 저장합니다.
2. 센서를 페어링합니다. 배터리를 넣고 케이스의 리셋 버튼을 약 5초 누르면 LED가 깜빡이며 페어링 모드로 들어갑니다.
3. 이미 다른 드라이버로 등록된 경우, 기기 페이지에서 **Type**을 `Tuya Zigbee Bed Presence Sensor`로 바꾸고 **Save Device**를 누릅니다.
4. **Configure**를 누른 뒤 스트랩을 눌렀다 떼면서 `presence` 값이 바뀌는지 확인합니다.

## 설정

| 항목 | 범위 | 설명 |
|---|---|---|
| Sensitivity | low / middle / high | 압력 감지 감도 (seq9cm6u) |
| Sampling interval | 5–720분 (5분 단위) | 샘플링 주기. 5의 배수가 아니면 가장 가까운 값으로 보정 (seq9cm6u) |
| Delay before reporting 'not present' | 0–3600초 | 압력이 사라진 뒤 비재실로 보고하기까지의 지연 (두 기종 공통, ZG-210Z 미설정 시 5초) |
| Delay before reporting 'present' | 0–3600초 | 압력이 감지된 뒤 재실로 보고하기까지의 지연 (seq9cm6u) |
| Detection threshold / 감지 기준값 | 숫자 | `currentPressure`가 이 값 이상이면 재실 (ZG-210Z). 비워 두면 기기 자체 판정(`pressure_state`) 사용. 고정 히스테리시스 ±20 |
| Temperature calibration | -2.0–2.0 °C | 온도 보정 (ZG-210Z) |
| Humidity calibration | -30–30 % | 습도 보정 (ZG-210Z) |
| Mirror presence to motion | on / off | `motion` 속성 동시 보고 여부 |

설정값은 기기가 마지막으로 보고한 값과 다를 때만 전송됩니다.

### ZG-210Z 전용 동작

- **재실 판단**: 감지 기준값을 지정하면 드라이버가 `currentPressure >= 기준값`일 때 재실로 판단합니다(히스테리시스 ±20 고정). 비재실 전환은 "비재실 지연"만큼 기다리며, 그동안 압력이 돌아오면 취소됩니다. 기준값을 비우면 기기 자체 판정(DP 1)을 씁니다. 펌웨어 임계값(DP 105)은 실제 하중을 잘 따라가지 못해 사용하지 않습니다.
- **보류 설정 큐**: 잠든 기기는 쓰기를 놓치는 경우가 많아서, 변경한 지연·보정 설정은 기기가 같은 값을 다시 보고(에코)할 때까지 보류했다가 기기가 보고할 때마다 재전송합니다(20초 간격 제한, 최대 15회).
- **이전 버전 정리**: 설정 저장 시 1.2.0의 속성(`pressureIntensity`, `sedentaryReminder`, `pendingSettings`)과 설정을 지웁니다.

## 속성

| 속성 | 값 |
|---|---|
| `presence` | present, not present |
| `motion` | active, inactive |
| `battery` | % |
| `illuminance` | lx |
| `sensitivity` | low, middle, high |
| `intervalTime` | 분 |
| `presenceDelay` | 초 |
| `presenceTime` | 초 |
| `workState` | presence, none, presence_5min, presence_30min, none_5min, none_30min (seq9cm6u) |
| `temperature` / `humidity` | °C(또는 °F) / % (ZG-210Z) |
| `pressureState` | none, sit, sedentary (ZG-210Z) |
| `currentPressure` | 압력 원시값 (ZG-210Z) |
| `temperatureCalibration` / `humidityCalibration` | 보정값 (ZG-210Z) |
| `lastPresenceChange` | yyyy-MM-dd HH:mm:ss |

## Tuya DP 맵

### `_TZE200_seq9cm6u`

| DP | 타입 | 이름 | 비고 |
|---|---|---|---|
| 1 | enum | occupancy | 0 = 재실(눌림), 1 = 비재실 |
| 4 | value | battery | % |
| 9 | enum | sensitivity | 0 low, 1 middle, 2 high (읽기/쓰기) |
| 12 | value | illuminance | lux (Z2M 분류 기준) |
| 101 | value | interval_time | 분 (읽기/쓰기) |
| 102 | value | presence_delay | 초 (읽기/쓰기) |
| 103 | value | presence_time | 초 (읽기/쓰기) |
| 104 | enum | work_state | 0–5 |

### HOBEIAN ZG-210Z (`_TZE200_ihizzpzn`)

| DP | 타입 | 이름 | 비고 |
|---|---|---|---|
| 1 | enum | pressure_state | 0 none, 1 sit, 2 sedentary |
| 2 | value | current_pressure | 압력 원시값 |
| 15 | value | battery | % |
| 101 | value | temperature | ÷10 °C |
| 102 | value | humidity | % |
| 103 | value | temperature_calibration | ÷10 °C, -2.0–2.0 (읽기/쓰기) |
| 104 | value | humidity_calibration | -30–30 % (읽기/쓰기) |
| 105 | value | pressure_intensity | 0–2000 (읽기/쓰기) |
| 106 | value | presence_delay | 0–3600초 (읽기/쓰기) |
| 107 | value | sedentary_reminder | 0–1440분 (읽기/쓰기) |

ZG-210Z는 표준 ZCL 온도(0402)·습도(0405)·전원(0001) 속성도 함께 보고하며 드라이버가 이를 처리합니다.

## 알려진 제한

- 배터리 기기라 평소에는 잠들어 있습니다. 설정 저장 후 반영되지 않으면 스트랩을 누르거나 버튼을 짧게 눌러 기기를 깨워 주세요. **Refresh**도 기기가 깨어 있을 때만 응답합니다.
- 기기 설정 화면에는 두 기종의 항목이 모두 보이지만, 해당 기종의 항목만 적용됩니다.
- DP 12(seq9cm6u)는 Z2M에서 조도로 분류되어 있어 그대로 따랐으나, 실제 조도 값인지는 추가 확인이 필요합니다.

## 참고

- DP 정의: [Koenkk/zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) `TS0601_bed_presence_sensor`, `ZG-210Z`
