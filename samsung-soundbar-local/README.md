# Samsung Soundbar Local - Hubitat 드라이버

2024년형 이후 삼성 Wi-Fi 사운드바를 **Hubitat Elevation**에서 로컬(LAN)로 직접
제어하는 드라이버입니다. 클라우드나 SmartThings API 키 없이, TCP **1516**
포트로 열리는 사운드바 로컬 JSON-RPC API를 직접 호출합니다. SmartThings 앱이
같은 네트워크에서 사운드바를 제어할 때 쓰는 것과 동일한 프로토콜입니다.

프로토콜은 [ZtF](https://github.com/ZtF)님이 Home Assistant용으로 리버스
엔지니어링한
[hass-samsung-soundbar-local](https://github.com/ZtF/hass-samsung-soundbar-local)를
기반으로, Hubitat 네이티브 드라이버로 옮긴 것입니다.

[→ English README](README.md)

---

## 지원 모델

원본 프로젝트 및 커뮤니티 보고 기준:

`HW-Q990D` · `HW-Q930D` · `HW-Q800D` · `HW-QS730D` · `HW-S800D` · `HW-S801D` ·
`HW-S700D` · `HW-S60D` · `HW-S61D` · `HW-LS60D`

일부 F시리즈(예: `HW-Q990F`)도 동작한다는 보고가 있습니다.
**2023년 이전 모델 및 C시리즈는 이 로컬 API 자체가 없어 동작하지 않습니다.**

## 기능

- 표준 `Switch` capability — 전원 on/off
- 표준 `AudioVolume` capability — 볼륨 설정, 볼륨 업/다운, 음소거/해제
- 표준 `MediaInputSource` capability — 드랍다운 선택:
  `HDMI_IN1`, `HDMI_IN2`, `E_ARC`, `ARC`, `D_IN`, `BT`, `WIFI_IDLE`
- 사운드 모드 드랍다운: `STANDARD`, `SURROUND`, `GAME`, `MOVIE`, `MUSIC`,
  `CLEARVOICE`, `DTS_VIRTUAL_X`, `ADAPTIVE`
- 우퍼 `+` / `-` 커맨드
- 폴링 주기 설정 가능한 자동 상태 갱신(refresh)
- AccessToken 자동 발급/캐싱 및 인증 오류 시 자동 재발급

## 요구 사항

- 사운드바가 **삼성 SmartThings 앱**에 등록되어 Wi-Fi에 연결되어 있어야 함
- 사운드바 네트워크 설정에서 **"IP 제어(IP control)"가 켜져 있어야 함**
  — 이 옵션이 있어야 드라이버가 AccessToken을 발급받을 수 있습니다
- 사운드바와 Hubitat 허브가 같은 LAN/VLAN에 있어야 함 (클라우드를 거치지
  않는 로컬 전용 API라 라우팅되지 않습니다)

## 설치 방법

1. Hubitat → **Drivers Code** → **New Driver**
2. [`samsung_soundbar_local.groovy`](samsung-soundbar-local/samsung_soundbar_local.groovy)
   내용을 붙여넣거나, **Import** 기능으로 아래 주소 사용:
   ```
   https://raw.githubusercontent.com/kwon2288/hubitat-driver/main/samsung-soundbar-local/samsung_soundbar_local.groovy
   ```
3. **Save**
4. **Devices** → **Add Device** → **Virtual**(또는 기존 기기) → **Type**을
   `Samsung Soundbar Local`로 지정
5. 기기 **Preferences**에서 사운드바 IP 주소 입력 후 **Save Preferences**
6. **Refresh** 실행 — 최초 호출 시 자동으로 AccessToken을 발급받습니다

## 설정값

| 설정 | 설명 | 기본값 |
|---|---|---|
| IP address | 사운드바 로컬 IP (DHCP 고정 할당 권장) | 필수 |
| Auto-refresh interval | Disabled / 1 / 5 / 10 / 30분 | 5분 |
| Debug logging | 상세 RPC 로그, 30분 후 자동 꺼짐 | on |
| Descriptive text logging | 상태 요약 로그 | on |

## 커맨드

| 커맨드 | Capability | 비고 |
|---|---|---|
| `on()` / `off()` | Switch | |
| `setVolume(level)` | AudioVolume | 절대값 설정 API가 없어 VOL_UP/DOWN을 목표치까지 반복 호출 |
| `volumeUp()` / `volumeDown()` | AudioVolume | |
| `mute()` / `unmute()` | AudioVolume | |
| `setInputSource(source)` | MediaInputSource | 드랍다운 |
| `setSoundMode(mode)` | custom | 드랍다운 |
| `subwooferUp()` / `subwooferDown()` | custom | |
| `refresh()` | Refresh | 전원/볼륨/음소거/입력/사운드모드/코덱/식별자 조회 |
| `resetAccessToken()` | custom | 캐시된 토큰 삭제, 다음 호출 시 강제 재발급 |

## 알려진 제약사항

- **절대 볼륨 설정 API가 없습니다.** 사운드바는 `VOL_UP` / `VOL_DOWN` 리모트
  키 스텝만 제공하므로, `setVolume()`은 현재 볼륨을 읽어 목표치까지 짧은
  간격으로 스텝을 반복합니다. 큰 폭으로 변경(예: 10 → 80)하면 몇 초 정도
  걸릴 수 있습니다 — 원본 HA 통합도 동일한 방식으로 처리합니다.
- 2024년형 이후 Wi-Fi 사운드바만 이 로컬 API를 지원합니다. 그 이전 모델은
  AccessToken 발급 단계에서 실패합니다.
- 입력 소스 전환 동작은 모델/펌웨어별로 차이가 있을 수 있습니다 — 원본
  프로젝트 커뮤니티에서 일부 모델의 특정 HDMI 입력이 전환되지 않는다는
  보고가 있습니다.

## 크레딧

- 로컬 API 프로토콜 리버스 엔지니어링:
  [ZtF](https://github.com/ZtF) —
  [hass-samsung-soundbar-local](https://github.com/ZtF/hass-samsung-soundbar-local) (MIT)

## 라이선스

MIT
