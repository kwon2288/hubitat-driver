# Navien Smart 숙면매트 → Hubitat

나비엔 스마트 숙면매트(단계형/1.0L, EME-500 등)를 실시간 상태 반영과 함께
Hubitat Elevation에 연동합니다.

나비엔 클라우드에는 현재 상태(난방 단계·전원)를 읽는 REST 엔드포인트가 없습니다
— 유일한 방법은 AWS IoT Core shadow를 SigV4 서명 WebSocket으로 구독하는 것뿐입니다.
Hubitat 내장 `interfaces.mqtt`는 WebSocket 전송을 지원하지 않기 때문에, 이 프로젝트는
작업을 두 부분으로 나눕니다.

- **`bridge/`** — 나비엔 클라우드 세션을 소유하고, AWS IoT를 WebSocket으로 구독해서
  일반 MQTT 브로커로 상태를 재발행하는 작은 Python/Docker 서비스.
- **`drivers/navien-smart-mat.groovy`** — 그 브로커를 구독해 상태를 받고, 명령은
  브리지의 로컬 HTTP API로 보내는 Hubitat 드라이버(나비엔 클라우드에 직접 접속하지 않음).

## 왜 드라이버 하나로 끝내지 않았는가

1. **Hubitat MQTT 클라이언트는 TCP/TLS 전용입니다.** `wss://`를 지원하지 않아서
   Groovy 드라이버 혼자서는 AWS IoT Core를 구독할 수 없습니다.
2. **계정당 로그인 세션이 정확히 1개입니다.** 브리지와 Hubitat 드라이버가 각자
   로그인하면 서로 세션을 뺏습니다(나비엔 쪽 `code: 404`). 그래서 브리지가 세션의
   유일한 소유자이고, 드라이버는 나비엔에 직접 인증하지 않습니다.

## 아키텍처

```
나비엔 클라우드 (AWS IoT + REST)
   ▲  SigV4 서명 WSS 구독 + REST 로그인/제어  (세션 1개 — 브리지만 보유)
   │
브리지 (Docker, Python)
   ├─ 로그인/세션 갱신 소유
   ├─ AWS IoT 구독 → reported 상태를 로컬 브로커에 재발행
   └─ 로컬 HTTP API로 제어 요청 노출
   │                                  │
   │ 상태 (MQTT, retained)            │ 제어 (HTTP)
   ▼                                  ▼
로컬 MQTT 브로커                  브리지가 나비엔 REST로 중계
(Hubitat 내장 브로커 또는
 외부 브로커 무관)
   │
   ▼
Hubitat 드라이버
   ├─ interfaces.mqtt 로 로컬 브로커 구독 → 실시간 상태
   └─ on()/off()/setHeatLevel() → 브리지 로컬 HTTP (나비엔 클라우드 직접 호출 안 함)
```

## 요구사항

- Hubitat 허브에서 접근 가능한 Docker 호스트 (Proxmox/Portainer, Synology 등)
- 브리지와 허브 양쪽에서 접근 가능한 MQTT 브로커. 둘 중 하나:
  - Hubitat 내장 브로커: Integrations → Add Built-In Integration →
    "MQTT Import Integration"(또는 Export) 추가 → **"Use built-in MQTT
    service"** 활성화. 브로커 데몬만 필요하고, 기기 매핑 UI는 쓰지 않습니다
    (제한사항 참고).
  - 외부 브로커 (예: `eclipse-mosquitto`).
- 단계형(1.0L) 숙면매트가 등록된 나비엔 스마트 계정.

## 설치

### 1. 브리지

```bash
cd bridge
cp .env.example .env
# .env 수정: NAVIEN_USERNAME/PASSWORD, MQTT_HOST/PORT/USERNAME/PASSWORD
docker compose up -d --build
docker logs -f navien-bridge
```

`로그인 성공 userSeq=... homeSeq=...`에 이어 `MQTT 구독 시작: <homeSeq>/mate/#`가
보이면 정상입니다. 기기 검색 확인:

```bash
curl http://<브리지-호스트>:8099/devices
```

### 2. Hubitat 드라이버

1. **Drivers Code** → **New Driver** → `drivers/navien-smart-mat.groovy` 내용
   붙여넣기 → **Save**.
2. **Devices** → **Add Device** → **Virtual** → 새로 만든 드라이버 타입 선택.
3. **Preferences**에 브리지 호스트/포트, MQTT 브로커 호스트/포트/계정 입력 →
   **Save Preferences**. 저장 시 `initialize()`가 자동 실행되어 브리지에서 기기
   정보를 가져오고 MQTT에 접속합니다.

## 설정 값 정리

### 브리지 환경변수 (`bridge/.env`)

| 변수 | 기본값 | 설명 |
|---|---|---|
| `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` | — | 나비엔 스마트 계정 (필수) |
| `MQTT_HOST` / `MQTT_PORT` | `127.0.0.1` / `1883` | 브리지가 발행할 로컬 브로커 |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | — | 브로커 계정(있는 경우) |
| `MQTT_PREFIX` | `navien` | 토픽 프리픽스 — 드라이버의 `mqttPrefix`와 일치해야 함 |
| `HTTP_PORT` | `8099` | 로컬 제어 API 포트 |
| `LOG_LEVEL` | `INFO` | 파이썬 로그 레벨 |

### 드라이버 Preferences

| 항목 | 설명 |
|---|---|
| `bridgeHost` / `bridgePort` | 브리지 HTTP API 주소 |
| `mqttHost` / `mqttPort` | 드라이버가 구독할 브로커 주소 |
| `mqttUsername` / `mqttPassword` | 브로커 계정(있는 경우) |
| `mqttPrefix` | 브리지의 `MQTT_PREFIX`와 일치해야 함 |

## 사용법

- `on()` / `off()` — 매트 전체 전원(`operationMode`). 좌우분리 매트에서는 두
  구역이 전원을 공유합니다(실제 기기와 동일).
- `setHeatLevel(zone, level)` — `zone`은 `single`/`left`/`right` 중 해당 매트가
  가진 것, `level`은 `0`~`8` (`0`=운전 대기).
- `single_level`/`left_level`/`right_level`과 `*_levelLabel`은 MQTT 메시지가
  도착하면 **실제 기기 상태**로 갱신됩니다 — 마지막으로 보낸 명령이 아니라.
- `refresh()` — 브리지에서 기기 등록정보를 다시 받아옵니다.

## 제한사항 (v1)

- 단계형(1.0L) 매트만 지원 — 온도형(0.5C)과 사계절 냉방은 미구현입니다. 원본
  Home Assistant 통합도 실기기 검증이 안 돼 있어 같은 범위로 맞췄습니다.
- 계정에 매트가 여러 대면 첫 번째 기기만 씁니다.
- Hubitat 내장 **MQTT Import Integration**의 기기 매핑 UI는 사용하지 않습니다 —
  테스트해본 결과 내장 캐패빌리티 템플릿 몇 개를 벗어나면 속성 매핑이 안정적이지
  않았습니다. 이 프로젝트는 그 앱이 제공하는 브로커 데몬만 빌려 쓰고, 토픽 파싱은
  전부 드라이버 자체 `parse()`에서 처리합니다.

## 트러블슈팅

- **`WebsocketConnectionError: WebSocket handshake error, connection not
  upgraded`** — 2.0 이전 `paho-mqtt`는 기본 포트여도 WebSocket `Host:` 헤더에
  포트를 붙이는데, 이게 AWS IoT SigV4 서명 검증을 깹니다. `bridge/requirements.txt`가
  `paho-mqtt>=2.1`로 고정돼 있는지 확인하세요.
- **브리지가 계속 재로그인하거나 Hubitat 제어가 간헐적으로 실패** — 나비엔 계정은
  세션이 1개뿐입니다. 같은 계정으로 인증하는 다른 프로세스(브리지 중복 실행,
  자체 로그인하는 v1 방식 드라이버 등)가 없는지 확인하세요.
- **`single_level`/`left_level`/`right_level`이 안 바뀜** — 브리지 로그에
  `상태 수신: <deviceId> heater=...`가 찍히는지, 브로커에
  `navien/mate/<deviceId>/state`에 retained 메시지가 실제로 있는지 확인하세요
  (`mosquitto_sub -t 'navien/#' -v`).

## 참고

프로토콜은 [ripe-avocado/navien_smart_ha](https://github.com/ripe-avocado/navien_smart_ha)
(MIT License, © 2026 Eui Young Jung)를 리버스 엔지니어링해서 확인했습니다.
`bridge/app.py`의 REST 인증 흐름, shadow 제어 payload 구조, AWS SigV4 WebSocket
서명 로직은 해당 프로젝트의 `api.py`/`mqtt.py`를 포팅한 것입니다.

## 라이선스

Apache License 2.0 — [LICENSE](LICENSE) 참고. (저장소 최상위에 이미 Apache 2.0
LICENSE가 있어 전체 프로젝트를 커버한다면 이 파일은 생략해도 됩니다.) `bridge/app.py`는
위에서 밝힌 MIT 라이선스 프로젝트의 로직을 포팅해 포함하고 있으며, 그 조건에 따라 MIT
표기를 여기 남깁니다.
