# hubitat-driver

[English README](README.en.md)

Hubitat Elevation용 커스텀 드라이버 모음입니다. 프로젝트별로 폴더 하나씩 나눠서
관리하며, 각 프로젝트 폴더는 자체 README, 드라이버 코드, (필요한 경우) 부속
스크립트까지 포함해 독립적으로 구성됩니다.

## 드라이버

| 프로젝트 | 설명 |
|---|---|
| `wan-failover-monitor/` | 공인 IP 폴링으로 UniFi 5G/LTE WAN 페일오버를 감지하고, Cloudflare DDNS를 자동 갱신하며, 영향받은 Docker 컨테이너(Portainer 경유)/Proxmox LXC(Proxmox VE API 경유)를 재시작합니다 |
| `awair-omni-local/` | Awair Omni 공기질 모니터의 로컬 API를 폴링해 온도, 습도, CO2, VOC, PM2.5, 조도, 소음, 그리고 로컬에서 계산한 EPA AQI를 가져옵니다. 정식 저장소/HPM 패키지는 `Hubitat-AwAir`이며, 여기 있는 사본은 둘러보기 편의용으로만 유지합니다. |
| `samsung-soundbar-local/` | 2024년 이후 삼성 Wi-Fi 사운드바를 TCP 1516의 로컬 JSON-RPC API로 로컬(LAN 전용) 제어합니다 — 전원, 볼륨, 음소거, 입력 소스, 사운드 모드, 서브우퍼. 프로토콜은 ZtF가 Home Assistant용으로 리버스 엔지니어링한 것을 네이티브 Hubitat 드라이버로 포팅했습니다. |
| `navien-mate/` | 나비엔 스마트 숙면매트(단계형/1.0L, EME-500 등)를 실시간 상태 반영과 함께 연동합니다. Hubitat MQTT 클라이언트가 WebSocket을 지원하지 않고 나비엔 계정은 세션이 1개뿐이라, 별도 Docker 브리지(`bridge/`)가 AWS IoT SigV4 WebSocket 구독과 나비엔 로그인 세션을 전담하고 드라이버는 로컬 MQTT+HTTP로만 통신합니다. |
| `dhlottery-lotto645/` | 동행복권 로또 6/45 당첨번호를 매주 토요일 추첨 직후 자동으로 가져옵니다. 개편된 동행복권 당첨결과 페이지가 사용하는 조회 엔드포인트를 호출하며(구 `common.do` API는 현재 리다이렉트로 막혀 동작하지 않습니다), 등록해 둔 내 번호와 대조해 1~5등/낙첨까지 판정합니다. |

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
