# Homey Bridge

Homey Pro의 로컬 REST API를 이용해 Homey 기기를 Hubitat으로 가져오는 커스텀 앱 +
드라이버입니다.

[English README](./README.en.md)

## 구성

```
homey-bridge/
├── apps/
│   └── homey-bridge-app.groovy          # 부모 앱
├── drivers/
│   ├── homey-generic-device-driver.groovy   # 범용 자식 드라이버 (스위치/센서류)
│   └── homey-onair-radio-driver.groovy      # 라디오 전용 자식 드라이버 (MusicPlayer)
└── docs/
    └── REALTIME-WEBHOOK.md              # 웹훅(실시간 반영) 설정 가이드
```

## 주요 기능

- Homey Pro의 로컬 REST API(`/api/manager/devices/device/...`)로 기기 목록 조회 및 제어
- 선택한 기기마다 Hubitat child device 자동 생성/삭제
- **Update Mode** 선택 가능:
  - `폴링만` (기본값, 추가 설정 불필요)
  - `폴링 + 웹훅`
  - `웹훅만`
- 웹훅 사용 시 Homey Flow → Hubitat 로컬 API 엔드포인트로 실시간 상태 반영
  (자세한 설정법은 [docs/REALTIME-WEBHOOK.md](./docs/REALTIME-WEBHOOK.md) 참고)

## 드라이버

### Homey Generic Device

on/off, 밝기, 색상/색온도, 커튼, 온습도/조도/전력/모션/접촉/배터리 센서,
버튼(PushableButton)까지 매핑하는 범용 드라이버.

| Homey capability | Hubitat 매핑 |
|---|---|
| `onoff` | Switch |
| `dim` | SwitchLevel |
| `light_hue` / `light_saturation` | ColorControl |
| `light_temperature` | ColorTemperature (2200K-6500K 근사 매핑) |
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

Homey의 media 계열 capability(`speaker_playing`, `speaker_next`, `speaker_prev`,
`speaker_track`, `speaker_artist`)를 쓰는 라디오/스피커류 전용 드라이버.
표준 `MusicPlayer` 캡퍼빌리티를 사용해 대시보드/Rule Machine/음성 비서 연동에서
정상적인 타입으로 인식됩니다.

- **작동**: Play / Pause / Next / Previous
- **읽기 전용 표시**: 트랙명, 아티스트, 볼륨(기기에 따라 쓰기가 막혀있을 수 있음 -
  `lge_volume_set`처럼 `setable:false`인 custom capability가 대표적인 예)
- **무시됨**: Mute/Unmute (해당 capability 자체가 없는 기기의 경우)
- 명령 실행 후 2초 뒤 자동으로 상태를 재조회해서 다음 폴링을 기다리지 않고 반영

## 설치 순서

1. **드라이버 등록**
   Hubitat 관리자 페이지 → `Drivers Code` → `New Driver` → `drivers/` 안의
   각 `.groovy` 파일 내용을 붙여넣고 저장 (파일마다 각각 새 드라이버로 등록)

2. **앱 등록**
   `Apps Code` → `New App` → `apps/homey-bridge-app.groovy` 내용을 붙여넣고 저장

3. **Homey Personal Access Token 발급**
   Homey 앱 → 설정 → 고급 설정 → API 키 (Homey Pro 전용, Homey Cloud 모델은 로컬
   API 미지원)

4. **앱 설치**
   `Apps` → `Add User App` → `Homey Bridge` 선택
   - Homey Pro IP 주소, 토큰 입력
   - Update Mode / Poll Interval 선택
   - 저장하면 연결 테스트 결과(✅/❌)가 바로 표시됨

5. **기기 선택**
   `Select Homey Devices to Import` 진입 → 원하는 기기 체크 → 저장
   → child device 자동 생성 (라디오류는 생성 후 Device Information에서 Type을
   `Homey OnAir Radio`로 변경)

## 알려진 제한 사항

- 단일 Homey 허브만 지원 (`singleInstance: true`) — 여러 대를 쓰려면 앱 정의에서
  이 옵션 제거 필요
- 색상/색온도 매핑은 근사치 — 실제 전구 반응 보고 튜닝 필요할 수 있음
- Homey와 Hubitat이 같은 로컬 네트워크에 있어야 함
- 일부 기기의 일부 capability는 Homey API 상 `setable:false`로 제한되어 있어
  Hubitat에서 쓰기가 원천적으로 불가능함 (Homey Flow 액션 카드로도 우회 불가한
  경우 물리 리모컨/Homey 앱에서 직접 조작 필요)

## 확장하기

`updateFromHomey()`의 switch문에 case를 추가하면 새 capability를 쉽게 매핑할 수
있습니다. 어떤 capability를 가진 기기인지 모르겠다면, child device의
`logDebug` preference를 켜고 Refresh를 눌러 Hubitat `Logs`에서
`Unmapped Homey capability ...` 로그를 확인하세요.
