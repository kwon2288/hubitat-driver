# Navien Smart 숙면매트 → Hubitat

[English README](README.en.md)

나비엔 스마트 숙면매트(단계형/1.0L, EME-500 등)를 실시간 상태 반영과 함께
Hubitat Elevation에 연동합니다.

나비엔 클라우드에는 현재 상태(난방 단계·전원)를 읽는 REST 엔드포인트가 없습니다
— 유일한 방법은 AWS IoT Core shadow를 SigV4 서명 WebSocket으로 구독하는 것뿐이고,
계정당 로그인 세션도 정확히 1개뿐입니다(다른 곳에서 로그인하면 `code: 404`로
튕겨납니다). 이 두 가지 제약 때문에 설치 방식이 두 가지로 나뉩니다.

## 설치 방식 두 가지

| | 브리지 방식 | 허브 단독 방식 |
|---|---|---|
| 상태 | ✅ 실기기로 검증됨 (권장) | 🧪 실험적 — 아직 실기기 검증 전 |
| 구성 | Docker 브리지(Python) + Hubitat 드라이버 | Hubitat App + 드라이버만 (Docker 불필요) |
| 세션 소유자 | 브리지 | Hubitat App |
| 실시간 상태 경로 | 브리지가 AWS IoT 구독 → 로컬 MQTT 브로커 재발행 → 드라이버 구독 | 드라이버가 AWS IoT에 `wss://`로 직접 구독 |
| 필요 인프라 | Docker 호스트, MQTT 브로커 | 없음 (Hubitat 허브만) |

Hubitat 내장 `interfaces.mqtt`가 `wss://`를 받는다는 게 공식 문서엔 없는
동작입니다. [jlslate/hubitat-navien](https://github.com/jlslate/hubitat-navien)
(다른 나비엔 제품·NaviLink 대상)이 실기기로 이게 된다는 걸 먼저 보여줬고, 이
프로젝트의 "허브 단독 방식"은 그 방법을 우리 API(mate)에 맞춰 포팅한 겁니다.
다만 이게 공식 지원 동작이 아니라서 **허브 펌웨어 빌드에 따라 안 될 수도
있습니다.** 안 되더라도 전원/단계 제어는 REST로 계속 동작하고, 실시간 상태
반영만 안 됩니다 — 최악의 경우에도 크게 잃는 건 없습니다.

확실하게 되는 걸 원하시면 **브리지 방식**, Docker 인프라를 늘리기 싫고 시험
삼아 해보고 싶으시면 **허브 단독 방식**을 선택하세요. 둘 다 이 저장소 안에
있고 동시에 설치해도 서로 충돌하지 않습니다(드라이버 이름이 다릅니다).

## 아키텍처

### 브리지 방식

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
Hubitat 드라이버 (navien-smart-mat.groovy)
   ├─ interfaces.mqtt 로 로컬 브로커 구독 → 실시간 상태
   └─ on()/off()/setHeatLevel() → 브리지 로컬 HTTP (나비엔 클라우드 직접 호출 안 함)
```

### 허브 단독 방식 (실험적)

```
나비엔 클라우드 (AWS IoT + REST)
   ▲  SigV4 서명 WSS 구독 (드라이버가 직접) + REST 로그인/제어 (App이 직접)
   │                                  ▲
   │ 상태                             │ 제어
   │                                  │
Hubitat App (Connector)  ──자격증명──▶  Hubitat 드라이버 (On-Hub)
   ├─ 로그인 세션 소유 (유일)              ├─ interfaces.mqtt.connect("wss://...")
   ├─ AWS 임시자격증명 발급/갱신            │  로 AWS IoT에 직접 접속·구독
   └─ sendControl() 로 REST 제어 대행       └─ parse() 에서 shadow reported 파싱
```

Docker 컨테이너가 하나도 없습니다 — App이 브리지 역할을, 드라이버가 (구독까지
포함해서) 원래 하던 역할을 그대로 합니다.

## 요구사항

### 브리지 방식

- Hubitat 허브에서 접근 가능한 Docker 호스트 (Proxmox/Portainer, Synology 등)
- 브리지와 허브 양쪽에서 접근 가능한 MQTT 브로커. 둘 중 하나:
  - Hubitat 내장 브로커: Integrations → Add Built-In Integration →
    "MQTT Import Integration"(또는 Export) 추가 → **"Use built-in MQTT
    service"** 활성화. 브로커 데몬만 필요하고, 기기 매핑 UI는 쓰지 않습니다
    (제한사항 참고).
  - 외부 브로커 (예: `eclipse-mosquitto`).
- 단계형(1.0L) 숙면매트가 등록된 나비엔 스마트 계정.

### 허브 단독 방식

- 단계형(1.0L) 숙면매트가 등록된 나비엔 스마트 계정. 그 외엔 없습니다 —
  Docker도, 별도 MQTT 브로커도 필요 없습니다.

## 설치

### 옵션 A — 브리지 방식

#### 1. 브리지

Docker 호스트(Proxmox VM/LXC, Synology 등 — Hubitat 허브와 네트워크로 통신
가능해야 함)에서 진행합니다.

**사전 준비** — Docker와 Compose 플러그인이 있는지 먼저 확인하세요.

```bash
docker --version
docker compose version
```

둘 중 하나라도 안 나오면 먼저 설치부터 하세요 (Portainer로 관리 중이면 이
단계는 이미 되어 있을 겁니다).

**코드 받기** — 저장소를 클론합니다.

```bash
git clone https://github.com/kwon2288/hubitat-driver.git
cd hubitat-driver/navien-mate/bridge-mode/bridge
```

저장소 전체(다른 프로젝트 포함) 대신 이 프로젝트만 받고 싶으면 sparse
checkout을 쓸 수 있습니다:

```bash
git clone --filter=blob:none --sparse https://github.com/kwon2288/hubitat-driver.git
cd hubitat-driver
git sparse-checkout set navien-mate
cd navien-mate/bridge-mode/bridge
```

**설정**

```bash
cp .env.example .env
vi .env   # 원하는 편집기로
```

최소한 채워야 하는 값:

- `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` — 나비엔 스마트 계정
- `MQTT_HOST` — Hubitat 내장 브로커 또는 외부 브로커의 IP
- 브로커에 계정이 걸려 있다면 `MQTT_USERNAME` / `MQTT_PASSWORD`

나머지(`MQTT_PORT`, `MQTT_PREFIX`, `HTTP_PORT`, `LOG_LEVEL`)는 기본값 그대로
둬도 됩니다 — 각 값의 의미는 아래 "설정 값 정리" 표 참고.

**기동**

```bash
docker compose up -d --build
docker logs -f navien-bridge
```

아래 순서로 로그가 뜨면 정상입니다:

```
로그인 성공 userSeq=... homeSeq=...
HTTP API 기동: 0.0.0.0:8099
MQTT 구독 시작: <homeSeq>/mate/#
```

**정상 동작 확인**

```bash
curl http://<브리지-호스트>:8099/health
curl http://<브리지-호스트>:8099/devices
```

`/devices`에서 매트 정보(`deviceId`, `zones`, `rangeMin`/`rangeMax` 등)가
나오면 정상입니다.

**업데이트** (코드가 바뀐 뒤 다시 반영할 때)

```bash
cd hubitat-driver/navien-mate/bridge-mode/bridge
git pull
docker compose up -d --build
```

`requirements.txt`(의존성)까지 바뀐 걸 확실히 반영하려면 캐시 없이
다시 빌드하세요:

```bash
docker compose build --no-cache
docker compose up -d
```

**자주 쓰는 운영 명령**

```bash
docker compose logs -f navien-bridge   # 로그 보기
docker compose restart navien-bridge   # 코드 변경 없이 재시작
docker compose down                    # 중지 + 컨테이너 제거
docker compose up -d                   # 다시 기동
```

#### 2. Hubitat 드라이버

1. **Drivers Code** → **New Driver** → `bridge-mode/drivers/navien-smart-mat.groovy` 내용
   붙여넣기 → **Save**.
2. **Devices** → **Add Device** → **Virtual** → 새로 만든 드라이버 타입 선택.
3. **Preferences**에 브리지 호스트/포트, MQTT 브로커 호스트/포트/계정 입력 →
   **Save Preferences**. 저장 시 `initialize()`가 자동 실행되어 브리지에서 기기
   정보를 가져오고 MQTT에 접속합니다.

### 옵션 B — 허브 단독 방식 (실험적)

Docker가 전혀 필요 없습니다. 다만 실기기 검증 전이라 잘 안 될 수 있다는 걸
감안해주세요 — 안 돼도 REST 제어는 정상 동작합니다.

1. **Drivers Code** → **New Driver** → `onhub-mode/drivers/navien-smart-mat-onhub.groovy`
   내용 붙여넣기 → **Save**.
2. **Apps Code** → **New App** → `onhub-mode/apps/navien-mate-connector.groovy` 내용
   붙여넣기 → **Save**.
3. **Apps** → **Add User App** → "Navien Smart 숙면매트 (Connector)" 선택.
4. 나비엔 스마트 아이디/비밀번호 입력 → **"로그인 및 기기 검색"** 버튼.
5. 성공하면 자식 디바이스가 자동으로 생성되고, 곧바로 AWS IoT 접속을
   시도합니다.

자식 디바이스의 `connection` attribute가 `connecting` → `connected`로
바뀌는지 확인하세요. 4번 연속 실패하면 로그에 경고가 뜨고, 이후로는 제어만
REST로 계속 동작하면서 백그라운드에서 재시도합니다.

## 설정 값 정리

### 브리지 환경변수 (`bridge-mode/bridge/.env`)

| 변수 | 기본값 | 설명 |
|---|---|---|
| `NAVIEN_USERNAME` / `NAVIEN_PASSWORD` | — | 나비엔 스마트 계정 (필수) |
| `MQTT_HOST` / `MQTT_PORT` | `127.0.0.1` / `1883` | 브리지가 발행할 로컬 브로커 |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | — | 브로커 계정(있는 경우) |
| `MQTT_PREFIX` | `navien` | 토픽 프리픽스 — 드라이버의 `mqttPrefix`와 일치해야 함 |
| `HTTP_PORT` | `8099` | 로컬 제어 API 포트 |
| `LOG_LEVEL` | `INFO` | 파이썬 로그 레벨 |

### 브리지 방식 드라이버 Preferences

| 항목 | 설명 |
|---|---|
| `bridgeHost` / `bridgePort` | 브리지 HTTP API 주소 |
| `mqttHost` / `mqttPort` | 드라이버가 구독할 브로커 주소 |
| `mqttUsername` / `mqttPassword` | 브로커 계정(있는 경우) |
| `mqttPrefix` | 브리지의 `MQTT_PREFIX`와 일치해야 함 |

### 허브 단독 방식

App(Connector)의 로그인 아이디/비밀번호가 전부입니다. 드라이버(On-Hub) 쪽엔
`logEnable` 말고 설정할 게 없습니다 — 자격증명은 전부 부모 App한테서 받습니다.

## 사용법

두 드라이버(`navien-smart-mat.groovy`, `navien-smart-mat-onhub.groovy`) 모두
같은 캐패빌리티/속성/명령을 씁니다.

- `on()` / `off()` — 매트 전체 전원(`operationMode`). 좌우분리 매트에서는 두
  구역이 전원을 공유합니다(실제 기기와 동일).
- `setHeatLevel(zone, level)` — `zone`은 `single`/`left`/`right` 중 해당 매트가
  가진 것, `level`은 `0`~`8` (`0`=운전 대기).
- `single_level`/`left_level`/`right_level`과 `*_levelLabel`은 MQTT 메시지가
  도착하면 **실제 기기 상태**로 갱신됩니다 — 마지막으로 보낸 명령이 아니라.
- `refresh()` — 기기 등록정보를 다시 받아옵니다.

## 제한사항

- 단계형(1.0L) 매트만 지원 — 온도형(0.5C)과 사계절 냉방은 미구현입니다. 원본
  Home Assistant 통합도 실기기 검증이 안 돼 있어 같은 범위로 맞췄습니다.
- 계정에 매트가 여러 대면 첫 번째 기기만 씁니다.
- 허브 단독 방식은 **아직 실기기 검증 전인 실험적 기능**입니다. Hubitat이
  문서화하지 않은 `wss://` 동작에 기대고 있어서, 허브 펌웨어 빌드에 따라 아예
  안 될 수 있습니다. 그런 경우에도 REST 제어(전원/단계)는 영향 없습니다.
- 브리지 방식에서, Hubitat 내장 **MQTT Import Integration**의 기기 매핑 UI는
  사용하지 않습니다 — 테스트해본 결과 내장 캐패빌리티 템플릿 몇 개를 벗어나면
  속성 매핑이 안정적이지 않았습니다. 이 프로젝트는 그 앱이 제공하는 브로커
  데몬만 빌려 쓰고, 토픽 파싱은 전부 드라이버 자체 `parse()`에서 처리합니다.

## 트러블슈팅

### 브리지 방식

- **`WebsocketConnectionError: WebSocket handshake error, connection not
  upgraded`** — 2.0 이전 `paho-mqtt`는 기본 포트여도 WebSocket `Host:` 헤더에
  포트를 붙이는데, 이게 AWS IoT SigV4 서명 검증을 깹니다. `bridge-mode/bridge/requirements.txt`가
  `paho-mqtt>=2.1`로 고정돼 있는지 확인하세요.
- **브리지가 계속 재로그인하거나 Hubitat 제어가 간헐적으로 실패** — 나비엔 계정은
  세션이 1개뿐입니다. 같은 계정으로 인증하는 다른 프로세스(브리지 중복 실행,
  허브 단독 방식 App과 브리지를 동시에 켜둔 경우 등)가 없는지 확인하세요 —
  **두 방식을 동시에 같은 계정으로 돌리면 서로 세션을 뺏습니다.**
- **`single_level`/`left_level`/`right_level`이 안 바뀜** — 브리지 로그에
  `상태 수신: <deviceId> heater=...`가 찍히는지, 브로커에
  `navien/mate/<deviceId>/state`에 retained 메시지가 실제로 있는지 확인하세요
  (`mosquitto_sub -t 'navien/#' -v`).

### 허브 단독 방식

- **`connection` attribute가 계속 `disconnected`** — 드라이버 로그에서
  `signHostWithPort` 값이 시도마다 뒤집히는지 확인하세요. Hubitat 내장 MQTT
  클라이언트가 WebSocket 업그레이드 시 `Host:` 헤더에 포트를 붙이는지 여부가
  허브 펌웨어 빌드마다 달라 보여서, 실패할 때마다 서명 방식을 바꿔가며
  재시도합니다. 4번 다 실패하면 REST 제어만 남기고 5분 간격으로 계속
  재시도합니다.
- **App에서 "로그인 및 기기 검색"이 실패함** — 브리지 방식 트러블슈팅의
  로그인 관련 항목과 원인이 같습니다(세션 충돌, 비밀번호 오류 등). App
  화면에 나오는 오류 메시지를 그대로 참고하세요.
- **자식 디바이스는 생겼는데 상태가 하나도 안 옴** — `connection`이
  `connected`인데도 안 오면, 부모 App 로그에서 `제어 전송 성공`이 찍히는지
  (초기 상태 요청이 나갔는지) 확인하세요. 이건 REST 쪽은 정상이라는 뜻이라
  MQTT 구독 필터링(`/update/accepted` 접미사) 쪽 문제일 가능성이 높습니다.

## 참고

프로토콜은 [ripe-avocado/navien_smart_ha](https://github.com/ripe-avocado/navien_smart_ha)
(MIT License, © 2026 Eui Young Jung)를 리버스 엔지니어링해서 확인했습니다.
`bridge-mode/bridge/app.py`의 REST 인증 흐름, shadow 제어 payload 구조, AWS SigV4 WebSocket
서명 로직은 해당 프로젝트의 `api.py`/`mqtt.py`를 포팅한 것입니다.

허브 단독 방식(`onhub-mode/apps/navien-mate-connector.groovy`,
`onhub-mode/drivers/navien-smart-mat-onhub.groovy`)의 `wss://` 직접 접속과 Host 헤더
포트 서명 문제는 [jlslate/hubitat-navien](https://github.com/jlslate/hubitat-navien)
(Unlicense / 퍼블릭 도메인)이 다른 나비엔 제품(NaviLink)으로 먼저 검증한 방법을
참고해 우리 API에 맞게 포팅한 것입니다.

## 라이선스

Apache License 2.0 — [LICENSE](LICENSE) 참고. (저장소 최상위에 이미 Apache 2.0
LICENSE가 있어 전체 프로젝트를 커버한다면 이 파일은 생략해도 됩니다.) `bridge-mode/bridge/app.py`는
위에서 밝힌 MIT 라이선스 프로젝트의 로직을 포팅해 포함하고 있으며, 그 조건에 따라 MIT
표기를 여기 남깁니다.
