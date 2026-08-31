# hubitat-driver

[English README](README.en.md)

Hubitat Elevation용 커스텀 드라이버 모음입니다. 프로젝트별로 폴더 하나씩 나눠서
관리하며, 각 프로젝트 폴더는 자체 README, 드라이버 코드, (필요한 경우) 부속
스크립트까지 포함해 독립적으로 구성됩니다.

## 드라이버

| 프로젝트 | 설명 |
|---|---|
| [`awair-omni-local/`](awair-omni-local/README.md) | Awair Omni 공기질 모니터의 로컬 API를 폴링해 온도, 습도, CO2, VOC, PM2.5, 조도, 소음, 그리고 로컬에서 계산한 EPA AQI를 가져옵니다. 정식 저장소/HPM 패키지는 `Hubitat-AwAir`이며, 여기 있는 사본은 둘러보기 편의용으로만 유지합니다. |
| [`homey-bridge/`](homey-bridge/README.md) | Homey Pro 기기를 로컬 REST API(`/api/manager/devices/device/...`)로 Hubitat에 연동합니다. 순수 on/off 기기는 전용 드라이버(`homey-switch-driver`), 밝기/색상 조절 기기는 전용 드라이버(`homey-dimmer-driver`), 라디오·스피커류는 표준 MusicPlayer 캡퍼빌리티 기반 전용 드라이버(`homey-onair-radio-driver`), 나머지 센서/버튼/커튼류는 범용 드라이버(`homey-generic-device-driver`)로 매핑합니다. 업데이트 방식은 폴링 / 폴링+웹훅(Homey Flow 연동) / 웹훅만 중에서 선택할 수 있습니다. |
| [`lg-thinq/`](lg-thinq/README.md) | LG 공식 ThinQ Connect API(PAT 인증) 기반 통합. 세탁기·건조기·식기세척기 등 기본 지원 기기 외에 공기청정기, 에어컨(시스템형/벽걸이 전용), 스타일러, 제습기, 미니워시, 정수기(모니터링 전용) 드라이버를 추가·수정하여 포함합니다. [jonozzz/hubitat-thinqconnect](https://github.com/jonozzz/hubitat-thinqconnect) 프레임워크 기반. |
| [`lotto645/`](lotto645/README.md) | 동행복권 로또 6/45 당첨번호를 매주 토요일 추첨 직후 자동으로 가져옵니다. 개편된 동행복권 당첨결과 페이지가 사용하는 조회 엔드포인트를 호출하며(구 `common.do` API는 현재 리다이렉트로 막혀 동작하지 않습니다), 등록해 둔 내 번호와 대조해 1~5등/낙첨까지 판정합니다. |
| [`navien-mate/`](navien-mate/README.md) | 나비엔 스마트 숙면매트(단계형/1.0L, EME-500 등)를 실시간 상태 반영과 함께 연동합니다. 폴더가 방식별로 나뉩니다: `bridge-mode/`(Docker 브리지)와 `onhub-mode/`(App+드라이버만, Docker 불필요) — 둘 다 실기기로 검증됨. |
| [`samsung-soundbar-local/`](samsung-soundbar-local/README.md) | 2024년 이후 삼성 Wi-Fi 사운드바를 TCP 1516의 로컬 JSON-RPC API로 로컬(LAN 전용) 제어합니다 — 전원, 볼륨, 음소거, 입력 소스, 사운드 모드, 서브우퍼. 프로토콜은 ZtF가 Home Assistant용으로 리버스 엔지니어링한 것을 네이티브 Hubitat 드라이버로 포팅했습니다. |
| [`wan-failover-monitor/`](wan-failover-monitor/README.md) | 공인 IP 폴링으로 UniFi 5G/LTE WAN 페일오버를 감지하고, Cloudflare DDNS를 자동 갱신하며, 영향받은 Docker 컨테이너(Portainer 경유)/Proxmox LXC(Proxmox VE API 경유)를 재시작합니다 |

드라이버는 계속 추가될 예정입니다 — 설치·설정에 관한 프로젝트별 상세 내용은 각
프로젝트 자체의 `README.md`를 참고하세요.

## 저장소 구조

```
hubitat-driver/
├── README.md                  (이 파일, 한국어 기본)
├── README.en.md                English version
├── LICENSE                    (Apache 2.0, 저장소 전체에 적용)
└── <project-name>/
    ├── README.md               프로젝트별 문서
    ├── drivers/                 해당 프로젝트의 .groovy 드라이버 파일
    │   └── *.groovy
    └── scripts/                 선택적 부속 스크립트 (Hubitat 외부용)
        └── *.sh
```

새 드라이버를 추가한다는 건 이 구조를 그대로 따르는 새 최상위 `<project-name>/`
폴더를 추가한다는 뜻입니다.

## 만든 사람

kwon2288 — 다른 공개 드라이버는 `Hubitat-AwAir`도 참고하세요.

## 라이선스

기본적으로 저장소 전체에 Apache License 2.0이 적용됩니다 — [LICENSE](LICENSE)
참고. 개별 프로젝트 폴더는 자체 `LICENSE` 파일로 다른 라이선스를 지정할 수
있습니다(예: `awair-omni-local/`은 CC0 사용) — 그 경우 해당 폴더 내용에는
프로젝트 단위 라이선스가 우선합니다.
