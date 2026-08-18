# 로또 6/45 당첨번호 (동행복권)

동행복권 로또 6/45 당첨번호를 자동으로 가져오는 [Hubitat Elevation](https://hubitat.com) 커스텀 드라이버입니다.
매주 토요일 추첨 직후 자동으로 최신 회차를 조회하고, 등록해 둔 내 번호와 대조해 등수까지 판정합니다.

## 기능

- 매주 토요일 21:10 / 21:45 자동 조회 (2회 시도)
- 최신 회차 자동 계산 (1회차 추첨일 기준 경과 주 수), 미발표 시 이전 회차로 자동 폴백
- 특정 회차 수동 조회 (`fetchDraw` 커맨드)
- 내 번호 대조 → 1~5등 / 낙첨 판정
- 대시보드 타일용 한 줄 요약(`summary`) 어트리뷰트 제공

## 어트리뷰트

| 이름 | 설명 | 예시 |
|---|---|---|
| `drawNo` | 회차 | `1237` |
| `drawDate` | 추첨일 | `2026-08-15` |
| `numbers` | 당첨번호 6개 | `10, 20, 23, 34, 37, 40` |
| `bonusNo` | 보너스 번호 | `36` |
| `summary` | 한 줄 요약 | `1237회 (2026-08-15)  10, 20, 23, 34, 37, 40  + 36` |
| `firstWinAmount` | 1등 1게임당 당첨금 | `1,214,932,680원` |
| `firstWinners` | 1등 당첨자 수 | `23` |
| `myMatchCount` | 내 번호 일치 개수 | `3` |
| `myRank` | 내 번호 등수 | `5등` / `낙첨` |
| `lastChecked` | 마지막 조회 시각 | `2026-08-18 10:55:45` |

## 설치

1. Hubitat 관리자 → **Drivers Code** → **New Driver** → **Import**
2. 아래 주소 입력 후 Import → Save
   ```
   https://raw.githubusercontent.com/kwon2288/hubitat-driver/main/lotto645/lotto-645-driver.groovy
   ```
3. **Devices** → **Add Device** → **Virtual** → Type 에서 `Lotto 645 Winning Numbers` 선택
4. 디바이스 페이지에서 필요 시 내 번호를 입력하고 **Save Preferences**

드라이버에 `importUrl`이 포함되어 있어, 이후 업데이트는 Drivers Code 화면에서 Import 버튼만 누르면 최신 코드를 받아올 수 있습니다.

## 설정

| 항목 | 설명 |
|---|---|
| 매주 토요일 추첨 후 자동 조회 | 스케줄 등록 여부 (기본 켜짐) |
| 내 번호 | 쉼표 구분 6개. 예: `3,11,17,25,33,41` |
| 디버그 로그 | 1시간 후 자동 해제 |

## 데이터 출처

동행복권 당첨결과 페이지가 사용하는 조회 엔드포인트를 그대로 호출합니다.

```
GET https://www.dhlottery.co.kr/lt645/selectPstLt645InfoNew.do?srchLtEpsd={회차}
```

요청 회차와 이전 9회차가 함께 반환되며, 미발표 회차는 빈 배열(`data.list: []`)로 응답합니다.
공식 API가 아니므로 사이트 개편 시 경로나 필드명이 바뀔 수 있습니다.
(구 엔드포인트 `common.do?method=getLottoNumber` 는 현재 302 리다이렉트로 동작하지 않습니다.)

## 라이선스

저장소 루트의 [LICENSE](../LICENSE) (Apache License 2.0) 를 따릅니다.
