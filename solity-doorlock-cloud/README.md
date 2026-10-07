# Solity Doorlock (Cloud) — Hubitat 드라이버

스마트솔리티(SOLITY) 도어락을 **SmartThings 없이** 솔리티 클라우드 API로 직접 제어하는 Hubitat Elevation 커스텀 드라이버입니다.

- 원격 열기 / 잠그기
- 배터리, 데드볼트 상태, 등록된 카드·지문·비밀번호 개수
- 출입 감지 (기본 10초 주기, 도어락 배터리 소모 없음)
- **누가, 어떤 방식으로 열었는지** 표시 (지문 / 카드 / 비밀번호 / 앱 / 실내 수동)
- 대시보드용 최근 출입 내역 표
- 토큰 자동 갱신, 폰 앱과 독립된 세션

> 비공식 프로젝트입니다. 솔리티/제조사와 무관하며 앱 API가 바뀌면 동작하지 않을 수 있습니다. 본인 계정의 본인 도어락에만 사용하세요.

## 요구 사항

- Hubitat Elevation 허브 (인터넷 연결 필요 — 클라우드 경유 방식이며 로컬 제어가 아닙니다)
- 스마트솔리티 앱 계정(이메일 + 비밀번호)과 앱에 등록된 도어락
- 도어락이 솔리티 게이트웨이(브릿지)를 통해 클라우드에 연결되어 있어야 합니다

## 설치

1. Hubitat 웹 UI → **Drivers Code** → **New Driver**
2. [`solity-doorlock-cloud.groovy`](solity-doorlock-cloud.groovy) 내용을 붙여넣고 **Save**
   (또는 **Import** 버튼에 이 저장소의 raw 파일 URL 입력)
3. **Devices** → **Add Device** → **Virtual** → Type에서 `Solity Doorlock (Cloud)` 선택 후 생성
4. 기기 페이지 **Preferences**에 이메일과 비밀번호를 입력하고 **Save Preferences**

저장하면 자동으로 로그인하고, 계정의 첫 번째 도어락을 찾아 폴링을 시작합니다. 첫 로그 조회에서는 이벤트를 내지 않고 기준점만 잡은 뒤 최근 출입 내역을 채웁니다.

## 설정

| 항목 | 기본값 | 설명 |
|---|---|---|
| 솔리티 이메일(ID) | — | 스마트솔리티 앱 계정 |
| 솔리티 비밀번호 | — | SHA-256 해시로만 서버에 전송됩니다 |
| myDeviceId | 비움 | 비우면 계정의 첫 번째 기기 사용. 도어락이 여러 대면 `diagnose`로 ID 확인 후 입력 |
| 출입 감지 주기 | 10초 | 클라우드 로그만 읽으므로 도어락을 깨우지 않습니다 (5~60초) |
| 상태/배터리 폴링 주기 | 30분 | **도어락을 깨웁니다.** 짧게 잡으면 배터리가 빨리 닳습니다 (10분~12시간) |
| 열림 후 자동 잠김 복귀 | 5초 | 열림 감지 후 이 시간이 지나면 `locked`로 되돌립니다 |
| accessHistory 표시 건수 | 5건 | 3 / 5 / 8 / 10건 |
| 디버그 로그 | 켬 | 30분 뒤 자동으로 꺼집니다 |

## 명령

| 명령 | 설명 |
|---|---|
| `lock` / `unlock` | 원격 잠금 / 열기 |
| `refresh` | 상태와 출입 로그를 즉시 조회 (도어락을 깨움) |
| `login` | 강제 재로그인 |
| `diagnose` | 연결 상태, 기기 목록, 최근 출입 로그 5건의 원본을 로그에 출력 |

## 속성

### 누가 / 어떻게

| 속성 | 예시 | 설명 |
|---|---|---|
| `lastAccess` | `열림 · 홍길동 · 지문` | 마지막 출입 요약 |
| `lastAccessUser` | `홍길동` | 사용자 (없으면 `-`) |
| `lastAccessMethod` | `지문` | 방식 |
| `lastAccessType` | `open` | `open` / `close` / `other` |
| `lastAccessTime` | `2026-10-07 06:26:42` | 도어락 기록 시각 |
| `lastAccessMessage` | `홍길동 지문으로 도어락을 열었습니다.` | 솔리티 서버 메시지 원문 |
| `lastUnlockedBy` / `lastUnlockMethod` / `lastUnlockTime` | | 마지막으로 **연** 사람·방식·시각 |
| `lastLockedBy` / `lastLockMethod` | `-` / `자동잠김` | 마지막 잠김 |
| `lastCodeName` | `홍길동` | `lastUnlockedBy`와 동일 (Lock 관련 앱 호환용) |
| `accessHistory` | HTML 표 | 최근 출입 내역 (대시보드 Attribute 타일용) |

방식 값: `지문`, `카드`, `비밀번호`, `앱(원격)`, `수동(실내)`, `Hubitat`, `자동잠김`, `비상키`. 분류하지 못한 경우 서버가 준 코드가 그대로 표시됩니다.

### 상태

| 속성 | 설명 |
|---|---|
| `lock` | `locked` / `unlocked` |
| `battery` | 배터리 % |
| `cardCount` / `fingerprintCount` / `passwordCount` | 등록된 인증 수단 개수 |
| `subLatch` / `systemMode` | 도어락이 보고하는 원본 값 |
| `apiStatus` | `ok` / `auth_failed` / `error` |

## 자동화에서 쓰기

`lock` 이벤트의 설명(descriptionText)에 사용자와 방식이 들어갑니다.

```
솔리티 열림 · 홍길동 · 지문
```

**Rule Machine 알림 예시**

- Trigger: `솔리티` lock *unlocked*
- Action: Notify — `현관 %text%`

특정 사람만 걸러내려면 조건에 Custom Attribute `lastUnlockedBy`를 쓰면 됩니다. 속성은 `lock` 이벤트보다 먼저 갱신되므로 룰이 실행될 때 이미 새 값입니다.

이벤트 `data`에도 `user`, `method`, `time`, `message`, `mediaType`, `codeName`이 들어 있어 커스텀 앱에서 읽을 수 있습니다. 이벤트 `type`은 원격(앱·Hubitat)이면 `digital`, 그 외는 `physical`입니다.

**대시보드**: Attribute 타일을 추가하고 `accessHistory`를 선택하면 최근 출입 내역이 표로 나옵니다.

## 동작 원리

폴링이 두 가지로 나뉩니다.

| 종류 | 호출 | 도어락 깨움 | 기본 주기 |
|---|---|---|---|
| 출입 로그 | `GET /api_v2/retrieveLog/page/{id}` | 안 함 | 10초 |
| 상태/배터리 | `PUT /api_v2/controlDevice/{id}` (`get_status`) | 깨움 | 30분 |

- 출입 로그는 클라우드에 저장된 기록만 읽기 때문에 자주 돌려도 배터리에 영향이 없습니다. 열림 감지는 이쪽으로 합니다.
- 폴링 사이에 여러 건이 쌓이면 오래된 것부터 순서대로 모두 처리합니다.
- 로그인: `POST /api_v2/login` → `token` + `tokenPwd`. 401/403이면 한 번 재로그인 후 재시도합니다.
- 원격 개폐: `PUT /api_v2/controlDevice/{id}`, body `{"controlType":"open|close","optionValue":"1"}`
- 방식 분류: 서버의 `mediaType`은 숫자 코드라서(확인된 값: `8` = 지문, `32` = 실내 수동) 서버 메시지 문구의 키워드로 분류합니다.

## 알아둘 점

- **닫힘은 기록되지 않습니다.** 솔리티 도어락은 열림만 로그로 남깁니다. 자동잠김 기기라는 전제로, 열림 감지 후 설정한 시간이 지나면 `locked`로 되돌리고 방식을 `자동잠김`으로 표시합니다. 누가 닫았는지는 알 수 없습니다.
- **실내에서 연 경우 사용자가 없습니다.** 도어락이 사용자를 기록하지 않아 `-`로 표시됩니다.
- **감지 지연**: 도어락이 클라우드에 보고하는 시간 + 폴링 주기만큼 늦습니다.
- **상태 조회 타임아웃**은 도어락이 절전 중일 때 흔히 발생합니다. 직전 값을 유지하며 오류로 처리하지 않습니다.
- **카드 / 비밀번호 / 앱** 방식은 메시지 문구 추정으로 분류합니다. 방식이 숫자로 표시되면 `diagnose` 출력의 `mediaType`과 `msg`를 이슈로 알려주세요.
- 비밀번호는 Hubitat 기기 설정에 저장됩니다(Hubitat의 password 입력 방식). 허브 접근 권한 관리에 유의하세요.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| `apiStatus`가 `auth_failed` | 이메일/비밀번호 확인 후 Save Preferences |
| 출입 내역이 비어 있음 | `diagnose` 실행 → 로그에 `로그:` 줄이 나오는지 확인 |
| 다른 도어락이 잡힘 | `diagnose`의 `기기:` 줄에서 ID 확인 → `myDeviceId`에 입력 |
| 열림이 늦게 뜸 | 출입 감지 주기를 5초로 |
| 배터리가 빨리 닳음 | 상태/배터리 폴링 주기를 늘리기 |

## 크레딧

클라우드 API 엔드포인트와 폴링 전략은 [tpgi2013-hue/Smart-Solity-Doorlock](https://github.com/tpgi2013-hue/Smart-Solity-Doorlock) (Home Assistant 통합, MIT)를 참고해 Hubitat용으로 옮겼습니다.

## 라이선스

Apache License 2.0
