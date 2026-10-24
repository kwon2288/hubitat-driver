# Replica S-PILLOW — Hubitat 드라이버

[English](README.en.md)

삼성 SmartThings **S-PILLOW**(AIHatinroo / peoplemulti 코골이 방지 베개)를 [HubiThings Replica](https://github.com/ogiewon/Hubitat/tree/master/HubiThings%20Replica)로 Hubitat에 연동하는 드라이버입니다. 필요한 기능만 남긴 경량 빌드입니다.

## 기능

- 베개 켜기 / 끄기 (`on` / `off`)
- 베개 상태 `pillowState`: off, preparing, running, stopped (`preparing`/`running`이면 `switch`는 on)
- 코골이 감지 `soundDetected`: noSound, snoring
- 코골이 감지 기능 켜기 / 끄기 (`enableSoundDetection` / `disableSoundDetection`), 상태 `soundDetectionState`
- 침실 온도, 습도, 이산화탄소(CO2) 보고
- 기기 연결 상태 `healthStatus`: online, offline

## 요구 사항

- Hubitat Elevation 허브
- HubiThings Replica 앱 설치 및 SmartThings 계정 연동
- SmartThings에 등록된 S-PILLOW

## 설치

1. Hubitat 웹 UI에서 **Drivers Code → New Driver**를 열고 [`drivers/replica-s-pillow.groovy`](drivers/replica-s-pillow.groovy) 내용을 붙여넣어 저장합니다.
2. HubiThings Replica 앱에서 S-PILLOW를 추가할 때 드라이버로 `Replica S-PILLOW`를 선택합니다.
3. 기기 페이지에서 **Configure**를 눌러 기본 규칙(Replica rules)을 등록합니다. 규칙은 `getReplicaRules()`에 정의되어 있습니다.

## 명령과 속성

| 구분 | 이름 | 설명 |
|---|---|---|
| 명령 | `on`, `off` | 베개 켜기/끄기 (SmartThings `antiSnoringPillow`) |
| 명령 | `enableSoundDetection`, `disableSoundDetection` | 코골이 감지 켜기/끄기 (`soundDetection`) |
| 명령 | `refresh`, `configure` | 상태 갱신 / 규칙 등록 |
| 속성 | `switch` | on, off — `pillowState`에서 파생 |
| 속성 | `pillowState` | off, preparing, running, stopped |
| 속성 | `soundDetected` | noSound, snoring |
| 속성 | `soundDetectionState` | enabled, disabled |
| 속성 | `temperature`, `humidity`, `carbonDioxide` | °C(또는 °F), %, ppm |
| 속성 | `healthStatus` | online, offline |

## 알아둘 점

- SmartThings → Hubitat 방향의 `set…Value` 계열 명령은 모두 `command`로 선언되어 있어야 합니다. 그렇지 않으면 Replica가 `SecurityException`으로 막습니다. 새 속성을 추가할 때도 같은 규칙을 지켜 주세요.
- 온도·습도·CO2 규칙은 `mute: true`로 등록되어 있어 SmartThings 쪽 이벤트 로그를 어지럽히지 않습니다.
- 설정: `Disable Info logging`(정보 로그 끄기), `Enable Debug logging`(디버그 로그 켜기).

## 라이선스

Apache License 2.0
