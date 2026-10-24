# VARRAM Mealle Feeder — Hubitat Driver

바램펫(VARRAM) 스마트 자동급식기 **밀리**를 Hubitat Elevation에서 제어하는 비공식 드라이버입니다.

[English](README.en.md)

밀리는 공개 API가 없고 Matter·SmartThings·HomeKit·IFTTT를 모두 지원하지 않습니다. 이 드라이버는 공식 앱의 트래픽을 분석해 클라우드 API를 그대로 사용합니다.

## 기능

- 수동 급여 (`feed(gram)`) — 1회 최대량 / 최소 간격 / 일일 누적 상한 3중 가드
- 급여 스케줄 슬롯별 ON/OFF
- 오늘 배급량·섭취량·잔여량 조회
- 급여 실패 감지 (사료부족 등) 및 실패 횟수 집계
- 사료 부족, 전원 상태, 펌웨어 버전 노출

## 요구사항

- Hubitat Elevation (플랫폼 2.3.x 이상)
- 바램펫 계정에 등록된 밀리 기기
- 인터넷 연결 (클라우드 API 의존 — 로컬 제어 불가)

## 설치 (Kakao ID 버전, 권장)

카카오 계정으로 가입한 경우 mitmproxy 없이 **Kakao ID 하나**로 설정할 수 있습니다. 드라이버가 스스로 로그인해 User ID / Device ID / Pet ID를 자동으로 찾습니다.

1. Hubitat → **Drivers Code** → **New Driver** → [`drivers/varram-mealle-feeder-kakao.groovy`](drivers/varram-mealle-feeder-kakao.groovy) 붙여넣기 → **Save**
2. **Devices** → **Add Device** → **Virtual** → Type `VARRAM Mealle Feeder Kakao`
3. `Kakao ID`와 `seedSchedule`(아래 스케줄 설정 참고)을 입력하고 **Save Preferences**
4. 로그에 `자동 검색: user=OK device=… pet=…`이 나오면 성공

- 로그인: `POST auth.varram-robot.com/v2/auth/kakao {"kakao_id":"<숫자>"}` → `data.auth_data.access_token / refresh_token`. 카카오 OAuth 증명 없이 숫자 ID만 보내므로 **Kakao ID는 비밀번호처럼 취급**해야 합니다.
- 자동 검색: JWT의 `userid`, `GET /res/user/info`, `GET /get/connected_pet`, `GET /res/dev/info`
- 폴백: refresh token 갱신이 실패(HTTP 오류)하면 Kakao ID로 자동 재로그인합니다. HTTP 403 상황에서 동작을 확인했습니다. 서버가 HTTP 200 + `{"type":"fail"}`로 거부하는 경우는 아직 처리하지 않습니다.
- Kakao ID는 앱 트래픽 캡처에서 위 요청 본문으로 확인합니다. 슬롯별 시간/그램은 서버에서 자동으로 가져올 수 없어 `seedSchedule`로 한 번 입력합니다.
- 재로그인이 새 세션을 발급하면서 공식 앱이나 기존 드라이버의 토큰을 무효화할 수 있습니다(미검증).
- **Kakao ID, 토큰, 비밀번호는 위키·GitHub에 기록하지 마세요.**

### Kakao ID 확인 방법

Kakao ID는 카카오 로그인 시 앱이 서버로 보내는 숫자 값(카카오 회원번호)이며, 앱 화면에는 표시되지 않습니다. 로그인 요청을 캡처해서 확인합니다. 한 번만 하면 됩니다.

1. 아래 [설정값 확보](#설정값-확보)와 같은 방법으로 mitmproxy를 준비합니다 (프록시 지정, `mitm.it` 인증서 설치 및 신뢰 설정).
2. 바램펫 앱에서 **로그아웃**합니다. 이미 로그인된 상태에서는 로그인 요청이 나가지 않습니다.
3. 앱을 완전 종료 후 다시 실행하고 **카카오로 로그인**합니다.
4. mitmweb에서 `auth.varram-robot.com/v2/auth/kakao`로 가는 `POST` 요청을 찾습니다. 필터창에 `kakao`를 입력하면 편합니다.
5. **Request** 탭의 본문에서 `kakao_id` 값을 복사합니다.

```
{"kakao_id":"1234567890"}
```

6. 드라이버의 `Kakao ID` preference에 숫자만 붙여 넣습니다.

주의: 로그아웃/로그인으로 앱의 세션이 새로 발급되므로, 기존 드라이버(토큰 방식)를 같이 쓰고 있다면 그쪽 refresh token이 무효가 될 수 있습니다(미검증). 같은 응답에는 토큰도 들어 있으므로 캡처 파일(`*.flows`)은 커밋하지 마세요.

### 이메일 계정으로 가입한 경우

카카오가 아닌 계정은 `Kakao ID` 대신 **이메일과 비밀번호**를 넣습니다. 같은 Kakao 드라이버(`VARRAM Mealle Feeder Kakao`)가 지원하며, Kakao ID가 비어 있을 때만 사용합니다.

- 설정: `이메일`, `비밀번호` (두 항목 모두 입력), 나머지는 비워 둡니다.
- 로그인: `POST auth.varram-robot.com/v2/login` — 폼 데이터 `email` + `password`. 이후 토큰, ID 자동 검색, 폴백(갱신 실패 시 재로그인)은 카카오와 같습니다.
- 응답 구조는 카카오와 같은 것으로 가정했습니다. 첫 실행 시 로그의 `이메일 로그인 응답 키: …`로 확인하세요. 토큰을 못 찾으면 이 줄의 키 이름을 기준으로 파서를 맞추면 됩니다.
- 비밀번호는 Hubitat 설정과 백업에 평문으로 저장됩니다. 바램펫 전용 비밀번호를 쓰고, 다른 곳과 겹치지 않게 하세요.
- 앱에서 로그인한 상태의 캡처 파일에는 비밀번호가 그대로 들어 있습니다. 문서·저장소·위키에 올리지 마세요.

## 설치 (기존 방식: 토큰 캡처)


1. Hubitat → **Drivers Code** → **New Driver**
2. [`drivers/varram-mealle-feeder.groovy`](drivers/varram-mealle-feeder.groovy) 내용을 붙여넣고 **Save**
3. **Devices** → **Add Device** → **Virtual** → Type에서 `VARRAM Mealle Feeder` 선택
4. 아래 설정값 입력 후 **Save Preferences**

> HPM(Hubitat Package Manager)은 지원하지 않습니다.

## 설정값 확보

드라이버는 앱과 동일한 방식으로 인증합니다. 다음 네 가지가 필요합니다.

| 항목 | 설명 |
|---|---|
| User ID | 계정 식별자 |
| Refresh Token | 장기 인증 토큰 (갱신 시 회전) |
| Device ID | 밀리 본체 식별자 |
| Pet ID | 급여 이력 조회용 (이력이 반려동물 기준으로 묶임) |

공식 앱에 이 값을 보여주는 화면이 없으므로 **앱 트래픽을 직접 캡처해야 합니다.**

1. mitmproxy 실행 — `mitmweb --listen-port 8080 --set block_global=false`
2. 폰 Wi-Fi에 수동 프록시 지정
3. Safari로 `mitm.it` 접속 → 인증서 설치
4. **설정 → 일반 → 정보 → 인증서 신뢰 설정**에서 토글 ON (누락 시 앱이 인증서 오류를 냄)
5. 앱을 완전 종료 후 재실행하고 로그인 → 기기 목록 → 수동 급여 → 냠냠리포트 순으로 조작

`POST auth.varram-robot.com/token`의 **응답 본문**에서 `refresh_token`을, 요청 본문에서 `user_id`를 얻습니다. `device_id`와 `pet_id`는 각각 `/get/mobile/info/feeder/{device_id}`, `/get/device/info/feed/v2/{pet_id}` 경로에 나타납니다.

앱에는 SSL pinning이 없어 별도 우회가 필요하지 않습니다.

## 스케줄 설정

Hubitat 텍스트 preference는 약 255자 제한이 있어 압축 포맷을 사용합니다.

```
시간,그램,온오프[,이름]  을 세미콜론으로 구분
```

예시 (7끼 활성, 21시 슬롯만 꺼짐):

```
00:06,6,1;03:00,6,1;06:00,6,1;09:00,6,1;12:00,6,1;15:00,6,1;18:00,6,1;21:00,6,0
```

저장 후 `getSchedule` 커맨드를 실행하면 `scheduleSummary`에 현재 상태가 표시됩니다. JSON 형식도 지원하지만 길이 때문에 preference에는 들어가지 않으므로 `importSchedule` 커맨드로 넘기세요.

`seedSchedule`을 바꾸거나 `importSchedule`을 써도 **드라이버가 가진 스케줄만 바뀝니다.** 기기에 실제로 적용하려면 `pushSchedule`을 실행하세요 (Kakao 드라이버). 시간·그램을 바꿨다면 전송 후 앱의 스케줄 화면에서 확인하세요.

**서버는 슬롯의 ON/OFF 상태를 보관하지 않습니다.** 따라서 드라이버가 스케줄의 단일 소유자로 동작합니다. 공식 앱에서 스케줄을 변경했다면 `importSchedule`로 드라이버를 다시 맞춰야 합니다. 그러지 않으면 다음 토글 시 앱의 변경분이 덮어써집니다.

## 연결 방식

벤더 서버는 기기당 WebSocket 세션을 하나만 허용합니다. 드라이버가 소켓을 계속 물고 있으면 **공식 앱에서 기기가 오프라인으로 표시됩니다.**

| 모드 | 동작 |
|---|---|
| `ondemand` (기본) | 명령이 있을 때만 접속, 15초 후 자동 종료. 앱과 공존 |
| `persistent` | 상시 연결. 실시간 push를 받지만 앱 연결이 끊김 |

온디맨드 모드에서는 상태 갱신을 HTTP 이력 조회(15분 주기)로 대체합니다. `eat` / `feed_start` 같은 실시간 이벤트는 받을 수 없고, 섭취 데이터에 최대 15분 지연이 생깁니다.

## Attribute

| 이름 | 설명 |
|---|---|
| `dailyGivenGram` / `dailyIntakeGram` | 로드셀 실측 기준 배급량 / 섭취량 |
| `appGivenGram` / `appIntakeGram` | 공식 앱 표시값과 동일하게 환산한 값 |
| `feedOkCount` / `feedFailCount` / `lastFailTime` | 급여 성공·실패 횟수와 마지막 실패 시각 |
| `lastBowlEmptyTime` | 사용자가 식기를 비운 마지막 시각 (`none_bowl` 기록) |
| `leftFeed` | 그릇 잔여량 |
| `lastEatTime` | 마지막 섭취 시각 |
| `foodLack` | 사료통 부족 여부 |
| `feedingStatus` | `waiting` / `feed_start` / `feed_finish` / `eat` |
| `scheduleSummary` / `scheduleActiveGram` / `scheduleSlots` | 스케줄 현황 |
| `connection` | `online` / `connecting` / `idle` / `offline` |
| `powerSource` / `firmware` | 전원 종류, 펌웨어 버전 |

### 실측값과 앱 표시값이 다른 이유

`gram` 필드는 로드셀이 잰 실제 토출량(보정값, 예: 5.2g)이지만, 공식 앱은 스케줄 설정량(예: 6g) 기준으로 환산해 표시합니다.

```
앱 표시 섭취량 = floor(실측섭취 × 설정량 ÷ 실측토출량)
```

두 값을 모두 노출하므로 용도에 맞게 선택하면 됩니다. 실제 섭취 무게가 필요하면 `dailyIntakeGram`, 앱 화면과 대조하려면 `appIntakeGram`을 쓰세요.

## 커맨드

`feed(gram)` `pullHistory` `dumpHistory` `getSchedule` `importSchedule` `exportSchedule` `pushSchedule` `scheduleSlotOn(slot)` `scheduleSlotOff(slot)` `scheduleAllOn` `scheduleAllOff` `dumpDeviceInfo` `closeSession` `reconnect`

슬롯 번호는 시간순 정렬 기준 1부터입니다.

## 자동화 예시

`feedFailCount > 0` 조건으로 Rule Machine 알림을 만들어두면 사료통이 비어 급여가 실패한 것을 바로 알 수 있습니다. 급식기는 실패해도 조용히 넘어가므로 이 알림이 실질적으로 가장 유용합니다.

## 급여 이력 status 값

| status | 의미 | 드라이버 처리 |
|---|---|---|
| `feed_finish` | 토출 직후 | 배급량에 반영 |
| `waiting` | 먹은 뒤 상태 (마지막 값의 `left`가 잔여량) | 섭취량 계산 |
| `empty_feed` | 급여 실패 (사료 부족 / 소량 토출) | 실패로 집계, `lastFailTime` 갱신 |
| `none_bowl` | 사용자가 식기를 비움 (`gram=0`) | 실패 아님, `lastBowlEmptyTime`에 시각 기록 |

## 알려진 제약

- 실패 사유(사료부족 / 소량토출)를 구분하지 못합니다. 사용 중인 API 버전이 두 경우를 같은 상태값으로 반환합니다.
- 클라우드 의존이므로 벤더 서버가 중단되면 함께 멈춥니다.
- Refresh Token 사슬이 끊기면(앱 로그아웃, 비밀번호 변경 등) 재캡처가 필요합니다.
- 앱 업데이트로 API가 바뀌면 동작이 멈출 수 있습니다.

## 면책

바램펫과 무관한 비공식 프로젝트입니다. 자동 급식기는 반려동물의 식사에 직접 관여하는 기기이므로, 자동화를 구성하기 전에 반드시 가드 설정값을 확인하고 소량으로 충분히 시험하세요.

## License

Apache License 2.0
