# 실시간 웹훅 설정 가이드 (Homey Flow → Hubitat)

`Homey Bridge` 앱은 기본적으로 폴링(주기적 조회) 방식으로 동작합니다. 이 문서는
폴링을 기다리지 않고 Homey에서 상태가 바뀌는 즉시 Hubitat에 반영되도록 하는
"웹훅" 방식을 설정하는 방법을 다룹니다.

**대상**: 즉시 반영이 꼭 필요한 경우에만 필요합니다. 폴링만으로 충분하면 이 문서는
무시해도 됩니다 (`Update Mode`를 "폴링만"으로 두면 이 설정은 전혀 필요 없음).

## 1. 개념

- **폴링**: Hubitat이 몇 분마다 한 번씩 Homey에 "지금 상태 뭐야?"라고 물어보는 방식.
  구현이 단순하지만 최대 poll interval만큼 지연이 생김.
- **웹훅**: 반대로 Homey가 상태 변경을 감지하면 그 즉시 Hubitat에 "이거 바뀌었어"라고
  알려주는 방식. Homey Flow + Hubitat 앱에 만들어둔 HTTP 엔드포인트로 구현.

`Homey Bridge` 앱은 두 방식을 `Update Mode` 설정으로 선택할 수 있고, 웹훅
엔드포인트 자체는 모드와 무관하게 항상 켜져 있습니다.

## 2. 사전 준비: Hubitat 앱 OAuth 활성화

웹훅 엔드포인트는 Hubitat의 로컬 API(OAuth 토큰 기반) 기능을 사용합니다.

1. Hubitat 관리자 메뉴 → `Apps Code` → `Homey Bridge` 항목 열기
2. 우측 상단 메뉴에서 `OAuth` 클릭 → `Enable OAuth in Apps` 체크 → `Update`
3. 이 단계를 빼먹으면 앱이 `createAccessToken()` 호출 시 에러를 남기고, 웹훅
   URL도 생성되지 않습니다.

## 3. 웹훅 URL 확인

1. `Apps` → `Homey Bridge` 열기
2. 하단 `Realtime Webhook` 섹션에 아래 두 가지가 표시됩니다:
   - 웹훅 URL 형식 (허브 IP, 앱 ID, access_token이 이미 채워진 상태)
   - 현재 등록된 기기별 `homeyId` 목록

URL 형식은 다음과 같습니다:

```
http://<허브IP>/apps/api/<app-id>/webhook/<homeyId>/<capability>?value=[[값]]&access_token=<토큰>
```

| 자리표시자 | 의미 | 어디서 구하나 |
|---|---|---|
| `<허브IP>` | Hubitat 허브의 로컬 IP | 앱 화면에 자동 표시 |
| `<app-id>` | Homey Bridge 앱의 내부 ID | 앱 화면에 자동 표시 |
| `<토큰>` | OAuth access token | 앱 화면에 자동 표시 |
| `<homeyId>` | Homey 상의 기기 고유 ID (UUID) | 앱 화면의 기기 목록에서 복사 |
| `<capability>` | 값이 바뀐 Homey capability id | 예: `speaker_playing`, `speaker_track` |
| `[[값]]` | 변경된 새 값 | Homey Flow 태그 삽입 기능으로 채움 (직접 타이핑 X) |

## 4. Homey에 Logic 앱 설치 확인

Homey 기본 앱스토어에서 **Logic** 검색 → 설치 여부 확인 (대부분 기본 탑재되어
있음). 이 앱이 Flow에서 임의의 URL로 HTTP 요청을 보내는 카드를 제공합니다.

Flow 카드 검색 시 "웹훅"이라는 이름으로는 안 나옵니다 — Homey에는 웹훅 전용
카드가 따로 없고, 우리가 범용 HTTP 요청 카드를 웹훅 용도로 쓰는 것입니다.
카드 이름은 다음과 같은 형태입니다:

> 로직 — 메서드를 URL에 헤더/본문 값으로 요청하기

두 개 버전(태그 삽입 가능 버전 포함)이 있는데 아무거나 써도 됩니다.

## 5. Flow 만들기 (예: 재생 상태)

1. Homey 앱 → 해당 기기 → `Flow` 탭 → 새 Flow
2. **WHEN**: 기기의 "재생 중(speaker_playing)이(가) 변경되었을 때" 트리거 추가
3. **THEN**: Logic의 "메서드를 URL에 헤더/본문 값으로 요청하기" 카드 추가
   - **메서드**: `GET`
   - **URL**: 3번에서 확인한 형식대로 채우되, `<capability>`는 `speaker_playing`으로 고정
   - `[[값]]` 자리는 커서를 두고 태그 삽입 버튼으로 이 Flow의 WHEN이 주는
     변경값 태그를 끼워넣기 (직접 `true`/`false` 타이핑해도 되지만, 태그를
     쓰면 값이 자동으로 최신 상태를 반영함)
   - **Headers / Body**: GET 요청이라 비워둬도 됨
4. 저장

## 6. 다른 capability에도 반복

같은 방식으로 필요한 capability마다 Flow를 하나씩 만들면 됩니다:

| 하고 싶은 것 | WHEN 트리거 | capability |
|---|---|---|
| 재생/일시정지 실시간 반영 | 재생 중이(가) 변경되었을 때 | `speaker_playing` |
| 현재 방송국명 실시간 반영 | 트랙이(가) 변경되었을 때 | `speaker_track` |
| 아티스트 실시간 반영 | 아티스트가(이) 변경되었을 때 | `speaker_artist` |

## 7. 값 형식 주의사항

Homey Flow의 변경값 태그가 어떤 문자열로 나오는지는 카드/로케일에 따라 다를 수
있습니다. 지금 코드는 `value`가 정확히 `"true"` 또는 `"false"` 문자열일 때만
불리언으로 인식합니다. 만약 `playing`/`paused` 같은 다른 단어로 나온다면:

- Flow에서 태그 대신 값을 직접 `true`/`false`로 고정 입력하거나 (가장 간단)
- Hubitat 앱의 `parseWebhookValue()` 함수에 해당 단어를 매핑하는 코드를 추가

## 8. 테스트 및 확인

1. 라디오 기기에서 Play/Pause를 눌러 실제로 상태를 바꿈
2. Hubitat `Logs` 페이지에서 아래와 같은 줄이 찍히는지 확인:
   ```
   webhookHandler: homey-<id> speaker_playing raw='true' -> true
   ```
3. 안 찍히면:
   - Homey Flow가 실제로 실행됐는지 Homey 앱의 Flow 실행 이력에서 확인
   - URL의 `<homeyId>`, `access_token`이 정확한지 재확인
   - Hubitat이 외부(Homey)에서 접근 가능한 네트워크 위치에 있는지 확인
     (같은 로컬 네트워크여야 함, 방화벽/VLAN 분리 여부 점검)

## 9. 참고: 폴링은 계속 필요한가?

`Update Mode`가 "웹훅만"이어도 저장/새로고침 시 최초 1회는 동기화를 위해
폴링이 한 번 실행됩니다. 이후로는 Flow가 보내는 값에만 의존하므로, Flow를
안 만든 capability는 값이 갱신되지 않습니다. 모든 capability마다 Flow를 만들기
번거롭다면 `Update Mode`를 "폴링 + 웹훅"으로 두는 것을 권장합니다 — Flow를
설정한 것은 즉시 반영되고, 나머지는 폴링이 계속 커버해줍니다.
