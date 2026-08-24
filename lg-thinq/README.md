# ThinQ Connect Integration for Hubitat

LG 공식 ThinQ Connect API(PAT 인증 방식)를 사용하는 Hubitat용 LG 기기 통합입니다. 기존 리버스 엔지니어링 방식을 대체하는 공식 문서화 API 기반 통합입니다.

공식 API 문서: https://smartsolution.developer.lge.com/en/apiManage/thinq_connect
프레임워크 원본: [jonozzz/hubitat-thinqconnect](https://github.com/jonozzz/hubitat-thinqconnect)

> 📌 이 문서는 원본 프레임워크의 README를 기반으로, 개인적으로 수정·추가한 드라이버 내용을 반영하여 재구성한 버전입니다. **[커스텀]** 표시가 있는 항목은 원본 프레임워크에 없거나 크게 수정된 부분입니다.

---

## 주요 특징

- **공식 API**: 리버스 엔지니어링 대신 LG의 공식 문서화된 ThinQ Connect API 사용
- **PAT 인증**: 복잡한 OAuth 플로우 없이 Personal Access Token으로 간단하게 인증
- **실시간 업데이트**: MQTT를 통한 즉각적인 상태 반영
- **다양한 기기 지원**: 세탁기, 건조기, 식기세척기, 냉장고, 오븐 외 **[커스텀]** 에어컨(벽걸이/시스템), 공기청정기, 스타일러, 제습기, 미니워시, 정수기(모니터링) 지원
- **안정성**: Home Assistant 통합과 동일한 기반 위에서 구축

---

## 사전 요구사항

1. **PAT 토큰** — LG ThinQ Connect 포털에서 발급
2. **MQTT 인증서** — Client Certificate, Private Key, CA Certificate
3. **지원 기기** — ThinQ Connect를 지원하는 LG 가전제품

---

## 설치 방법

### 1. 앱 및 드라이버 설치

1. `thinq_connect_core.groovy`를 Hubitat 허브에 **App**으로 설치
2. 각 기기별 드라이버를 **Device Driver**로 설치 (아래 "지원 기기" 표 참고)

### 2. PAT 토큰 발급

1. LG ThinQ Connect 포털 접속
2. 신규 또는 기존 애플리케이션 사용
3. Personal Access Token(PAT) 발급
4. 국가/지역 확인

### 3. MQTT 인증서 준비

`openssl` 또는 [csrgenerator.com](https://csrgenerator.com/) 같은 온라인 서비스로 생성 가능합니다.

- **Private Key** (RSA, PEM 포맷)
  ```bash
  openssl genpkey -outform PEM -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out priv.key
  ```
- **Client Certificate Request** (csrconfig.txt 기반 CSR)
  ```bash
  openssl req -new -nodes -key priv.key -config csrconfig.txt -out cert.csr
  ```

한 번 생성하면 재사용 가능합니다.

### 4. 통합 설정

1. Apps → Add User App → ThinQ Connect Integration
2. 설정 마법사 진행:
   - PAT 토큰 입력
   - 국가 선택
   - API 연결 테스트
   - MQTT 인증서 등록
   - 연동할 기기 선택

---

## 지원 기기

### 원본 프레임워크 지원

| 기기 | Device Type | 상태 |
|---|---|---|
| 세탁기 | `DEVICE_WASHER` | ✅ 정상 |
| 건조기 | `DEVICE_DRYER` | ✅ 정상 |
| 식기세척기 | `DEVICE_DISH_WASHER` | ✅ 정상 |
| WashTower | `DEVICE_WASHTOWER_WASHER/DRYER` | ✅ 정상 |
| 전자레인지 | `DEVICE_MICROWAVE_OVEN` | ✅ 정상 |
| 냉장고 | `DEVICE_REFRIGERATOR` | ⚠️ 구현되었으나 미검증 |
| 오븐/쿡탑 | `DEVICE_OVEN`, `DEVICE_COOKTOP` | ⚠️ 구현되었으나 미검증 |
| 에어컨 (원본) | `DEVICE_AIR_CONDITIONER` | ⚠️ 구현되었으나 미검증 |

### [커스텀] 개인 추가/수정 드라이버

| 기기 | 드라이버 파일 | 비고 |
|---|---|---|
| 공기청정기 | `thinq_connect_air_purifier.groovy` | MQTT/refresh 로직 신규 구현, PM1.0/2.5/10, smell(냄새), 습도, 총오염도 센서 추가 |
| 에어컨 (시스템형) | `thinq_connect_air_conditioner.groovy` | switch 판단 로직 개선, 절대/상대 타이머 버그 수정, displayLight 추가 |
| **벽걸이 에어컨 (전용)** | `thinq_connect_wall_ac.groovy` | 시스템 에어컨 전용 기능(이중온도, 풍향 각도 등) 제외, 송풍/제습/공기청정 전원 자동 ON 처리 |
| 스타일러 | `thinq_connect_styler.groovy` | MQTT 완료 알림 누락 대응 refresh 로직, 오수통/급수통 에러코드 구분 |
| 제습기 | `thinq_connect_dehumidifier.groovy` | 습도 센서, 물통 가득참 감지, 운전모드 표시(쓰기는 모델별 상이) |
| 미니워시 | `thinq_connect_mini_washer.groovy` | 세탁기 드라이버 기반, `locationName: MINI` 처리 |
| 정수기 | `thinq_connect_water_purifier.groovy` | API 제약상 모니터링 전용 (코크 상태/살균 상태/물 종류) |

> 정수기는 LG API 자체가 제어 기능 없이 상태 조회만 지원합니다.

---

## [커스텀] 드라이버 상세

### 공기청정기 (Air Purifier)

**속성**: `currentState`, `airPurifierMode`, `airFlowSpeed`, `pm1.0`, `pm2.5`, `pm10`, `pm1.0Level`, `pm2.5Level`, `pm10Level`, `smell`(Good/Normal/Bad/Very Bad), `totalPollution`, `humidity`, `filterRemainPercent`

**주요 수정사항**:
- 원본 부모앱 의존 구조에서 독립적인 MQTT + refresh 구조로 전환
- LG API 오탈자(`oder` → `odor`) 대응
- 냄새 수치를 숫자(1~4)에서 문자(Good/Normal/Bad/Very Bad)로 변환

### 벽걸이 에어컨 (Wall AC) — 시스템 에어컨과 별도 드라이버

**주요 커맨드**: `setCoolMode()`, `setHeatMode()`, `setAutoMode()`, `setFanMode()`(송풍), `setAirDryMode()`(제습), `setEnergySavingMode()`, `startSleepMode(hours)`(취침모드 — 목표온도 +1도, 약풍, sleepTimer 조합)

**시스템 에어컨과의 차이점**:
- 이중 온도 설정(`twoSetTemperature`), 풍향 각도 단계 조절, 공기청정 단독 모드 등 벽걸이에 없는 기능 제외
- 송풍/제습 모드가 전원이 꺼진 상태에서도 자동으로 POWER_ON 후 모드 설정하도록 처리
- `unsetStartTimer()`/`unsetStopTimer()`가 `-1` 전송 방식으로 정상 동작하도록 수정 (기존 `0` 전송은 즉시 실행으로 오동작)
- `displayLight` 커맨드 추가 (모델에 따라 미지원 가능)
- 취침모드(열대야 모드)는 API에 전용 엔드포인트가 없어 온도/풍속/타이머 조합으로 구현

### 스타일러 (Styler)

**주요 수정사항**:
- `PAUSE` 상태에서 switch가 잘못 `off`로 표시되던 문제 수정
- MQTT 완료 알림(`STYLING_IS_COMPLETE`)에 `runState`가 누락되는 경우를 대비해 `remainingTime` 기반 자동 refresh 예약 로직 추가
- 오수통 가득참(`NEED_WATER_DRAIN`)과 급수통 부족(`NEED_WATER_REPLENISHMENT`) 에러코드 구분 매핑

### 제습기 (Dehumidifier)

**주요 수정사항**:
- `dehumidifierOperationMode`를 전원 상태의 유일한 기준으로 사용
- `WATER_IS_FULL` push 알림 수신 시 자동 refresh
- 운전 모드(`dehumidifierJobMode`)는 API 스펙상 읽기 전용이나, 일부 모델에서 쓰기 명령이 동작하는 것을 확인하여 `setDehumidifierMode()` 커맨드 추가 (모델별 상이)

### 미니워시 (Mini Washer)

- 세탁기 드라이버와 동일한 `DEVICE_WASHER` 타입이지만 `locationName: "MINI"`로 구분되는 콤보/워시타워 미니워시 대응
- 상태 응답이 배열로 오는 경우 `locationName == "MINI"` 항목을 우선 탐색

---

## 데이터 구조 예시

```json
{
  "runState": {
    "currentState": "RUNNING"
  },
  "operation": {
    "washerOperationMode": "START"
  },
  "timer": {
    "remainHour": 1,
    "remainMinute": 30,
    "totalHour": 2,
    "totalMinute": 0
  },
  "remoteControlEnable": {
    "remoteControlEnabled": true
  }
}
```

---

## API 엔드포인트

- **Base URL**: `https://api-{region}.lgthinq.com`
- **Device List**: `GET /devices`
- **Device Profile**: `GET /devices/{deviceId}/profile`
- **Device Status**: `GET /devices/{deviceId}/state`
- **Device Control**: `POST /devices/{deviceId}/control`
- **MQTT Registration**: `POST /client`
- **Push Notifications**: `POST /push/{deviceId}/subscribe`

---

## 문제 해결 (Troubleshooting)

### 연결 문제
1. PAT 토큰 유효성 확인
2. 국가/지역 설정 확인
3. LG ThinQ 앱에 기기가 정상 등록되어 있는지 확인

### MQTT 문제
1. CSR 포맷(PEM) 확인
2. 인증서 만료 여부 확인
3. MQTT 서버 URL 확인
4. LG API는 인증서 발급 요청을 제한합니다 — **분당 1회 이하**로 요청 권장

### [커스텀] switch 상태가 실제와 다를 때
- 에어컨: `airConOperationMode`를 전원 상태의 1차 기준으로 사용하는지 확인. 없으면 `currentState`로 폴백
- 스타일러/제습기: MQTT push가 전체 상태값 없이 알림만 오는 경우가 있어 `runIn()` 기반 자동 refresh가 걸려 있는지 확인

### [커스텀] 타이머가 취소되지 않을 때
- `0` 대신 `-1`을 전송하는 `unsetStartTimer()` / `unsetStopTimer()` 사용

### 기기를 찾을 수 없을 때
1. 기기 타입이 지원되는지 확인
2. LG ThinQ 앱에서 기기가 온라인인지 확인
3. 기기별 로그에서 구체적인 에러 확인

### 자주 발생하는 에러 메시지

- **"Connection failed"**: PAT 토큰 및 인터넷 연결 확인
- **"MQTT setup failed"**: 인증서 포맷 및 내용 확인
- **"No devices found"**: 기기 등록 및 지원 여부 확인
- **"API GET failed"**: PAT 토큰 유효성 및 권한 확인
- **[커스텀] `MissingMethodException` (Groovy)**: `=~` 연산자가 boolean이 아닌 Matcher 객체를 반환하는 경우이거나, null 값이 함수에 그대로 전달되는 경우 — 파라미터 null 체크 및 `.matches()` / `in [...]` 사용 권장

---

## 로깅

디버그 로깅으로 문제를 추적할 수 있습니다:
1. 앱 또는 드라이버 설정에서 Log Level을 `debug`로 변경
2. Hubitat 로그에서 상세 내용 확인
3. API 응답 코드 및 MQTT 연결 상태 확인

각 [커스텀] 드라이버는 `logDescText` preference를 통해 상태 변화를 별도로 info 레벨에 남길 수 있습니다.

---

## 고급 설정

### 커스텀 MQTT 서버
자체 MQTT 브로커 사용 시:
1. TLS를 지원하는 MQTT 브로커 준비
2. 적절한 인증서 생성
3. 통합 설정에서 MQTT 서버 URL 지정

### 다중 지역 지원
국가에 따라 API 지역이 자동 감지됩니다:
- **AIC**: 미주 (US, CA 등)
- **KIC**: 한국/아시아태평양 (KR, JP, AU 등)
- **EIC**: 유럽/중동/아프리카 (GB, DE, FR 등)

---

## 파일 구조

```
apps/
└── thinq_connect_core.groovy              # 메인 부모 앱

drivers/
├── thinq_connect_washer.groovy            # 세탁기
├── thinq_connect_dryer.groovy             # 건조기
├── thinq_connect_dishwasher.groovy        # 식기세척기
├── thinq_connect_mini_washer.groovy       # [커스텀] 미니워시
├── thinq_connect_air_purifier.groovy      # [커스텀] 공기청정기
├── thinq_connect_air_conditioner.groovy   # [커스텀] 에어컨 (시스템형)
├── thinq_connect_wall_ac.groovy           # [커스텀] 에어컨 (벽걸이 전용)
├── thinq_connect_styler.groovy            # [커스텀] 스타일러
├── thinq_connect_dehumidifier.groovy      # [커스텀] 제습기
├── thinq_connect_water_purifier.groovy    # [커스텀] 정수기 (모니터링 전용)
└── README.md                              # 이 문서
```

---

## 버전 히스토리

- **v1.0**: PAT 인증 기반 초기 릴리스, 세탁기/건조기 지원, MQTT 실시간 업데이트, 공식 API 통합
- **[커스텀] v1.1**: 공기청정기, 에어컨(시스템/벽걸이), 스타일러, 제습기, 미니워시, 정수기 드라이버 추가 및 각종 상태 동기화 버그 수정

---

## 라이선스

원본 Home Assistant 통합과 동일한 라이선스를 따릅니다.

---

## 향후 계획

- 벽걸이 에어컨 취침모드(sleepTimer 기반)의 실사용 검증
- 제습기 운전모드 쓰기 가능 여부 모델별 정리
- 냉장고/오븐/쿡탑 드라이버 실사용 검증
- MQTT 인증서 관리 자동화 개선
