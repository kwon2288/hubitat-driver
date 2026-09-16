# Tuya 지그비 침대 재실(압력 스트랩) 센서

[English](README.en.md)

Tuya TS0601 압력 스트랩형 재실 센서(`_TZE200_seq9cm6u`)를 Hubitat Elevation에서 사용하기 위한 드라이버입니다. 베개나 매트리스 아래에 깔아 두면 눌림 여부를 재실(presence) 상태로 보고합니다.

## 지원 기기

| 모델 | 제조사 코드 | 비고 |
|---|---|---|
| TS0601 | `_TZE200_seq9cm6u` | Z2M 모델명 `TS0601_bed_presence_sensor` |

페어링 지문(fingerprint):

```
profileId:"0104", endpointId:"01", inClusters:"0000,EF00", outClusters:"000A,0019", model:"TS0601", manufacturer:"_TZE200_seq9cm6u"
```

## 기능

- 재실 상태 보고: `presence`(present / not present)
- `motion`(active / inactive) 동시 보고 — Motion Lighting 등 모션 기반 앱에서 바로 사용 가능 (설정에서 끌 수 있음)
- 배터리, 조도, 동작 상태(`workState`) 보고
- 감도, 샘플링 주기, 재실/비재실 보고 지연 시간을 기기 설정 화면에서 변경
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
| Sensitivity | low / middle / high | 압력 감지 감도 |
| Sampling interval | 5–720분 (5분 단위) | 샘플링 주기. 5의 배수가 아니면 가장 가까운 값으로 보정 |
| Delay before reporting 'not present' | 0–3600초 | 압력이 사라진 뒤 비재실로 보고하기까지의 지연 |
| Delay before reporting 'present' | 0–3600초 | 압력이 감지된 뒤 재실로 보고하기까지의 지연 |
| Mirror presence to motion | on / off | `motion` 속성 동시 보고 여부 |

설정값은 기기가 마지막으로 보고한 값과 다를 때만 전송됩니다.

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
| `workState` | presence, none, presence_5min, presence_30min, none_5min, none_30min |
| `lastPresenceChange` | yyyy-MM-dd HH:mm:ss |

## Tuya DP 맵

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

## 알려진 제한

- 배터리 기기라 평소에는 잠들어 있습니다. 설정 저장 후 반영되지 않으면 스트랩을 누르거나 버튼을 짧게 눌러 기기를 깨워 주세요. **Refresh**도 기기가 깨어 있을 때만 응답합니다.
- DP 12는 Z2M에서 조도로 분류되어 있어 그대로 따랐으나, 실제 조도 값인지는 추가 확인이 필요합니다.

## 참고

- DP 정의: [Koenkk/zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) `TS0601_bed_presence_sensor`
