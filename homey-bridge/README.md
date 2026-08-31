# Homey Bridge

Homey Pro의 로컬 REST API를 이용해 Homey 기기를 Hubitat으로 가져오는 커스텀 앱 +
드라이버입니다.

[English README](./README.en.md)

## 구성

```
homey-bridge/
├── apps/
│   └── homey-bridge-app.groovy          # 부모 앱
└── drivers/
    ├── homey-switch-driver.groovy           # 순수 on/off 전용 자식 드라이버
    ├── homey-dimmer-driver.groovy           # 밝기/색상/색온도 전용 자식 드라이버
    ├── homey-generic-device-driver.groovy   # 센서/버튼/커튼 전용 자식 드라이버
    └── homey-onair-radio-driver.groovy      # 라디오 전용 자식 드라이버 (MusicPlayer)
```

## 주요 기능

- Homey Pro의 로컬 REST API(`/api/manager/devices/device/...`)로 기기 목록 조회 및 제어
- 선택한 기기마다 Hubitat child device 자동 생성/삭제 (신규 생성 시 기본 드라이버는
  `Homey Generic Device` — 스위치/조명/라디오류는 생성 후 Device Information에서
  Type을 수동으로 바꿔줘야 함, 아래 설치 순서 5번 참고)
- **Update Mode** 선택 가능:
  - `폴링만` (기본값, 추가 설정 불필요)
  - `폴링 + 웹훅`
  - `웹훅만`

## 드라이버

### Homey Switch

순수 on/off만 다루는 최소 드라이버. 플러그, 벽 스위치처럼 밝기·색상 조절이 없는
기기용.

| Homey capability | Hubitat 매핑 |
|---|---|
| `onoff` | Switch |

### Homey Dimmer

밝기/색상/색온도를 다루는 드라이버. 밝기 조절이 되는 기기는 자체적으로 on/off도
필요하므로 Switch 캡퍼빌리티도 함께 포함됨.

| Homey capability | Hubitat 매핑 |
|---|---|
| `onoff` | Switch |
| `dim` | SwitchLevel |
| `light_hue` / `light_saturation` | ColorControl |
| `light_temperature` | ColorTemperature (2200K-6500K 근사 매핑) |

### Homey Generic Device

온습도/조도/전력/모션/접촉/배터리 센서, 버튼(PushableButton), 커튼까지
매핑하는 센서·기타 전용 드라이버.

| Homey capability | Hubitat 매핑 |
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
   → child device 자동 생성 (기본 Type은 `Homey Generic Device` — 순수 on/off
   기기는 `Homey Switch`, 밝기/색상 조절 기기는 `Homey Dimmer`, 라디오류는
   `Homey OnAir Radio`로 Device Information에서 Type을 수동으로 변경)

## Capability ID / homeyId 찾는 방법

새 기기를 추가하거나 웹훅을 설정하려면, 그 기기가 Homey에서 실제로 어떤
capability id를 쓰는지, 그리고 Hubitat 내부에서 어떤 `homeyId`로 등록됐는지
알아야 합니다. Homey 개발자 문서를 뒤질 필요 없이 이 드라이버들이 이미 로그로
다 찍어줍니다.

1. 대상 child device 페이지 → `Preferences`의 `Enable debug logging` 체크 →
   하단 `Save Preferences` 클릭
2. 같은 페이지의 `Refresh` 명령 클릭 (폴링 주기를 기다릴 필요 없이 즉시 조회)
3. Hubitat 관리자 메뉴 → `Logs` (Live Logs) 페이지에서 아래 형태의 줄을 확인:
   ```
   updateFromHomey received: [speaker_playing:[..., id:speaker_playing, getable:true, setable:true, value:false], ...]
   ```
   여기 나오는 각 최상위 키(`speaker_playing`, `measure_temperature` 등)가 바로
   그 기기의 실제 capability id입니다. `setable`/`getable` 값을 보면 그
   capability를 Hubitat에서 쓰기/읽기 가능한지도 바로 알 수 있습니다
   (`setable:false`면 아무리 명령을 보내도 Homey 서버 단에서 거부됨).
4. 매핑되지 않은 capability는 `Unmapped Homey capability <id> = <value>` 형태로
   별도 표시됩니다. 이 id를 참고해서 드라이버의 `updateFromHomey()` switch문에
   case를 추가하면 됩니다.
5. child device의 `homeyId`(웹훅 URL 등에 필요)는 부모 앱(`Homey Bridge`) 설정
   화면 하단 `Realtime Webhook` 섹션에 기기별로 나열되어 있습니다.

## 실시간 웹훅 설정 (선택사항)

`Update Mode`가 "폴링만"이면 이 섹션은 건너뛰어도 됩니다. 폴링을 기다리지 않고
Homey에서 상태가 바뀌는 즉시 Hubitat에 반영하고 싶을 때만 필요합니다.

### 개념

- **폴링**: Hubitat이 몇 분마다 한 번씩 Homey에 상태를 물어보는 방식. 구현은
  단순하지만 최대 poll interval만큼 지연 발생.
- **웹훅**: Homey가 상태 변경을 감지하면 즉시 Hubitat에 알려주는 방식. Homey
  Flow + 이 앱에 만들어둔 HTTP 엔드포인트로 구현. 엔드포인트 자체는
  `Update Mode` 설정과 무관하게 항상 켜져 있습니다.

### 1. Hubitat 앱 OAuth 활성화 (최초 1회)

1. `Apps Code` → `Homey Bridge` 열기
2. 우측 상단 메뉴 → `OAuth` → `Enable OAuth in Apps` 체크 → `Update`
3. 이 단계를 빼먹으면 `createAccessToken()` 호출이 실패하고 웹훅 URL도 생성되지
   않습니다.

### 2. 웹훅 URL 확인

`Apps` → `Homey Bridge` → 하단 `Realtime Webhook` 섹션에서 확인:

```
http://<허브IP>/apps/api/<app-id>/webhook/<homeyId>/<capability>?value=[[값]]&access_token=<토큰>
```

`<허브IP>`, `<app-id>`, `<토큰>`은 이 섹션에 이미 채워진 값을 그대로 복사하고,
`<homeyId>`는 바로 아래 기기 목록에서, `<capability>`는 위
["Capability ID 찾는 방법"](#capability-id--homeyid-찾는-방법)을 참고해서
채웁니다.

### 3. Homey에 Logic 앱 설치 확인

Homey 앱스토어에서 **Logic** 검색 → 설치 여부 확인 (기본 탑재된 경우가 많음).
Flow에서 임의의 URL로 HTTP 요청을 보내는 카드를 제공합니다. 카드 이름 검색 시
"웹훅"으로는 안 나옵니다 — Homey에 웹훅 전용 카드가 따로 있는 게 아니라, 범용
HTTP 요청 카드를 웹훅 용도로 쓰는 것입니다. 카드 이름은 다음과 같은 형태입니다:

> 로직 — 메서드를 URL에 헤더/본문 값으로 요청하기

### 4. Flow 만들기 (예: 재생 상태)

1. Homey 앱 → 해당 기기 → `Flow` 탭 → 새 Flow
2. **WHEN**: "재생 중(speaker_playing)이(가) 변경되었을 때" 트리거 추가
3. **THEN**: Logic의 "메서드를 URL에 헤더/본문 값으로 요청하기" 카드 추가
   - **메서드**: `GET`
   - **URL**: 위 형식대로 채우되 `<capability>`는 `speaker_playing`으로 고정
   - `[[값]]` 자리는 태그 삽입 버튼으로 이 Flow의 WHEN이 주는 변경값 태그를
     끼워넣거나, 간단히 `true`/`false`를 직접 입력해도 됨
   - Headers/Body는 GET이라 비워둬도 됨
4. 저장

같은 방식으로 다른 capability(`speaker_track`, `speaker_artist` 등)에도
Flow를 하나씩 더 만들면 그 값들도 실시간으로 반영됩니다. 모든 capability마다
Flow를 만들기 번거로우면 `Update Mode`를 "폴링 + 웹훅"으로 두는 걸 권장합니다 —
Flow를 만든 항목은 즉시 반영되고, 나머지는 폴링이 계속 커버해줍니다.

### 5. 값 형식 주의사항

Flow 태그가 주는 문자열이 `true`/`false`가 아니라 `playing`/`paused` 같은
단어로 나올 수도 있습니다. 이때는 Flow에서 태그 대신 값을 직접 `true`/`false`로
고정 입력하는 게 가장 간단합니다.

### 6. 테스트

1. 기기에서 실제로 상태를 바꿈 (예: Play/Pause)
2. Hubitat `Logs`에서 다음과 같은 줄 확인:
   ```
   webhookHandler: homey-<id> speaker_playing raw='true' -> true
   ```
3. 안 찍히면: Homey 앱의 Flow 실행 이력에서 Flow가 실제로 실행됐는지, URL의
   `homeyId`/`access_token`이 정확한지, Homey와 Hubitat이 같은 로컬
   네트워크(방화벽/VLAN 분리 여부)에 있는지 확인하세요.

## 알려진 제한 사항

- 단일 Homey 허브만 지원 (`singleInstance: true`) — 여러 대를 쓰려면 앱 정의에서
  이 옵션 제거 필요
- 색상/색온도 매핑은 근사치 — 실제 전구 반응 보고 튜닝 필요할 수 있음
- Homey와 Hubitat이 같은 로컬 네트워크에 있어야 함
- 일부 기기의 일부 capability는 Homey API 상 `setable:false`로 제한되어 있어
  Hubitat에서 쓰기가 원천적으로 불가능함 (Homey Flow 액션 카드로도 우회 불가한
  경우 물리 리모컨/Homey 앱에서 직접 조작 필요)

## 확장하기

새 capability를 매핑하려면 [Capability ID 찾는 방법](#capability-id--homeyid-찾는-방법)으로
id를 확인한 뒤, `updateFromHomey()`의 switch문에 case를 추가하면 됩니다.
