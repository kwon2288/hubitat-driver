# Awair Omni Local

[English](README.en.md)

[Awair Omni](https://www.getawair.com/products/omni) 공기질 측정기용 Hubitat 드라이버입니다. 클라우드 없이 기기의 **로컬 API**를 폴링해서 온도, 습도, CO2, VOC, PM2.5, 조도, 소음 값을 가져오고, 로컬에서 계산한 EPA AQI도 함께 제공합니다. 조도·소음 센서가 없는 Awair R2도 사용할 수 있습니다. R2는 해당 값만 건너뜁니다.

> **정식 저장소 안내:** 이 드라이버의 `importUrl`(Hubitat 내장 드라이버 업데이터가 사용)은 원래 저장소인
> [`kwon2288/Hubitat-AwAir`](https://github.com/kwon2288/Hubitat-AwAir)를 가리키며,
> [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/)(HPM)에도 그 저장소가 등록되어 있습니다.
> 설치와 업데이트의 기준은 그 저장소이고, 여기 있는 사본은 제 Hubitat 드라이버를 한곳에서 둘러볼 수 있도록 두는 것뿐입니다.
> HPM이나 `importUrl`로 설치하신다면 이미 올바른 곳에서 받고 계신 겁니다.

## 캡퍼빌리티

- `Polling`, `Configuration`, `Initialize`, `Sensor`
- `TemperatureMeasurement`, `CarbonDioxideMeasurement`, `RelativeHumidityMeasurement`, `AirQuality`

## 속성

| 속성 | 설명 |
|---|---|
| `temperature`, `humidity`, `carbonDioxide`, `voc`, `pm25`, `lux`, `noise` | 센서 원본 측정값. 기기가 보고하지 않는 값(예: R2의 `lux`, `noise`)은 갱신하지 않습니다 |
| `airQuality` | Awair 자체 점수(0~100) |
| `airQualityIndex` | PM2.5 이동 평균으로 로컬에서 계산한 EPA AQI |
| `aiq_desc`, `pm25_desc`, `co2_desc`, `voc_desc` | 사람이 읽기 쉬운 단계 표시(`good` / `fair` / `poor` / `bad` / `hazardous`) |

## 설정

| 항목 | 설명 |
|---|---|
| IP Address | Awair 기기의 로컬 IP. 앞뒤 공백, `http://`, 끝의 `/`는 자동으로 정리합니다 |
| API Path | 기본값 `/air-data/latest` |
| Polling interval | 상태 확인 간격(초), 기본값 300 |

설정을 저장하면 예약된 폴링을 초기화한 뒤 바로 다시 폴링을 시작합니다.

## 문제 해결

- 요청이 실패하면 로그에 `Awair request to http://<IP> failed: HTTP <상태 코드> ...`가 남습니다. 어느 주소로 요청했고 어떤 응답을 받았는지 확인할 수 있습니다.
- IP를 바꾼 뒤 값이 갱신되지 않으면 Preferences를 저장하고, 허브와 같은 네트워크에서 `http://<IP>/air-data/latest`가 열리는지 확인하세요.
- 디버그 로그가 필요하면 `Enable debug logging`을 켜세요.

## 설치

권장: [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/)에서 `Hubitat-AwAir` 패키지로 설치합니다.

수동: Hubitat 관리 화면 → **Drivers Code** → **New Driver** → [`drivers/Awair_Onmi_Local.groovy`](drivers/Awair_Onmi_Local.groovy) 내용을 붙여넣고 → Save.

## 라이선스

[CC0 1.0 Universal](LICENSE.txt) — 퍼블릭 도메인 헌정.
