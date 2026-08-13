# WAN Failover Monitor

*[English](README.md)*

UniFi 5G/LTE 백업 WAN이 메인 회선을 대신하는 등의 홈 ISP failover를 공인 IP 폴링으로 감지하고, WAN 전환 시 흔히 깨지는 것들을 전부 자동으로 처리하는 Hubitat Elevation 드라이버입니다: 예전 IP를 계속 가리키는 DNS 레코드, 예전 outbound 주소에 고착된 리버스 프록시(Nginx Proxy Manager) 컨테이너, Proxmox LXC/VM에서 단독으로 도는 서비스, 그리고 — 셀룰러 백업 WAN은 대부분 CGNAT(Carrier-Grade NAT) 뒤에 있기 때문에 — 백업 WAN에서는 직접 포트포워딩 대신 Cloudflare Tunnel로 외부 트래픽을 우회시키는 것까지 포함합니다.

GitHub: 이 저장소의 `wan-failover-monitor/` 폴더.

## 아키텍처

| 상태 | DNS 레코드 | cloudflared (터널) | NPM / Proxmox 재시작 |
|---|---|---|---|
| **Primary** (메인 회선) | 다른 A레코드 도메인을 가리키는 CNAME (예: 이미 라우터 단 DDNS로 관리되는 도메인) — 또는 직접 IP를 관리하고 싶다면 A 레코드 | 정지 | IP 변경 시 트리거 |
| **Failover** (셀룰러, CGNAT) | CNAME → `<tunnelId>.cfargotunnel.com` | 실행 | IP 변경 시 트리거 |

서비스에서 쓰는 호스트네임(예: `wiki.example.com`)은 절대 바뀌지 않고, 그게 실제로 가리키는 대상만 자동으로 전환됩니다.

트래픽 경로:
- **Primary**: 클라이언트 → DNS(A/CNAME) → 공인 IP → 라우터 포트포워딩 → 대상 서비스 (직접, 또는 환경에 따라 NPM 같은 리버스 프록시를 거쳐서)
- **Failover**: 클라이언트 → DNS(CNAME) → Cloudflare 엣지 → 터널 → cloudflared → 대상 서비스로 직접 (고정 LAN IP로)

`cloudflared`는 Failover 시 리버스 프록시를 거치지 않고 각 서비스의 주소로 바로 라우팅합니다 — 고정 IP로 가는 단순 LAN 라우팅이라 Docker 네트워크 공유가 필요 없습니다. Primary 쪽에서 NPM을 쓰든 안 쓰든 터널 구성이 그것과 무관하게 단순해집니다.

각각 단독 설치 가능한 4단계로 점진적으로 구성되어 있습니다:

| 단계 | 파일 | 추가되는 기능 |
|---|---|---|
| 1 | [`drivers/stage1-wan-ip-monitor.groovy`](drivers/stage1-wan-ip-monitor.groovy) | [ipify](https://www.ipify.org/)를 이용한 공인 IP 폴링 + Primary/Failover WAN 상태 감지 |
| 2 | [`drivers/stage2-cloudflare-ddns.groovy`](drivers/stage2-cloudflare-ddns.groovy) | + IP 변경 시 Cloudflare DNS 레코드 자동 갱신 |
| 3 | [`drivers/stage3-portainer-restart.groovy`](drivers/stage3-portainer-restart.groovy) | + 딜레이 후 Portainer API로 Docker 컨테이너 자동 재시작 |
| 4 | [`drivers/stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy) | + Proxmox LXC/VM 재부팅 + Cloudflare Tunnel 자동 시작/정지 + CGNAT 대응 DNS 레코드 타입 전환 |

Stage 4가 실사용을 위한 전체 기능 통합본입니다. Stage 1~3은 참고용이나 일부 기능만 필요한 경우를 위해 별도 파일로 남겨뒀습니다.

**언어 버전**: [`stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy)는 설정 항목명/로그 메시지가 한글이고, [`stage4-proxmox-restart-en.groovy`](drivers/stage4-proxmox-restart-en.groovy)는 기능은 동일하고 영문 라벨입니다. 두 드라이버는 서로 다른 디바이스 이름(`Public IP WAN Monitor - Stage 4` vs `Public IP WAN Monitor (EN)`)으로 등록되어, 둘 중 하나만 설치하시거나 — 원하시면 테스트용으로 둘 다 별도 디바이스에 — 충돌 없이 설치하실 수 있습니다.

Hubitat 허브에 독립적인 안전망으로 아래 스크립트도 포함되어 있습니다 (Stage 4가 정상 동작 중이면 필수는 아님):

- [`scripts/check-ip-restart-npm.sh`](scripts/check-ip-restart-npm.sh) — Docker 호스트에서 실행, IP 변경 시 NPM 컨테이너 재시작
- [`scripts/check-ip-restart-mcp.sh`](scripts/check-ip-restart-mcp.sh) — 단독 Proxmox LXC *내부에서* 실행, IP 변경 시 자체 서비스 재시작(또는 자체 재부팅)

## 여기서 CGNAT가 중요한 이유

UniFi 대시보드에 표시되는 백업 WAN의 IP와 `https://api.ipify.org`가 보고하는 IP를 비교해보세요 — 셀룰러 연결에서는 보통 서로 다른 주소입니다 (UniFi에 표시된 IP가 `100.64.0.0/10` 대역이면 RFC 6598 기준 CGNAT가 확정입니다). 모바일 통신사는 CGNAT를 통해 여러 고객이 공인 IP 하나를 공유하는데, 이 경우 DDNS가 아무리 정확해도 그 공유 IP로 들어오는 인바운드 연결은 일반적으로 라우터까지 도달하지 못합니다. 대부분의 셀룰러 데이터 요금제에서는 직접 포트포워딩이 작동하지 않습니다. 우회책은 **아웃바운드 전용** 터널인 Cloudflare Tunnel(`cloudflared`)입니다 — 인바운드 연결이 전혀 필요 없어서 CGNAT 자체가 무의미해집니다. 이 드라이버는 실제로 백업 WAN에 있을 때만 `cloudflared`를 실행하고, 나머지 시간에는 꺼둡니다 (추가 지연이나 리소스 사용 없음).

## 자격증명 수집

### Cloudflare API Token
1. [Cloudflare 대시보드](https://dash.cloudflare.com) → 프로필 아이콘 → **My Profile**
2. **API Tokens** → **Create Token** → **Edit zone DNS** 템플릿 사용
3. **Zone Resources**에서 전체 zone이 아닌 특정 zone으로 범위 제한
4. Create Token → 값을 즉시 복사 (한 번만 표시됨)

### Cloudflare Zone ID
도메인의 **Overview** 페이지 → 우측 사이드바 **API** 섹션 → **Zone ID**.

### Cloudflare DNS Record ID
대시보드 UI에는 안 보이므로 직접 조회해야 합니다:
```bash
curl -s -X GET "https://api.cloudflare.com/client/v4/zones/{ZONE_ID}/dns_records?name={hostname}" \
  -H "Authorization: Bearer {API_TOKEN}"
```
응답의 `result[0].id` 필드가 Record ID입니다. 여러 레코드를 관리한다면 호스트네임별로 반복하세요.

### Cloudflare Tunnel ID
[one.dash.cloudflare.com](https://one.dash.cloudflare.com) → **Networks → Tunnels** → 해당 터널 선택 → **Overview** 탭 → 표시된 UUID (URL에도 포함되어 있음).

### Portainer API Key
Portainer UI → 사용자 아이콘 → **My account** → **Access Tokens** → **Add access token** → 값을 즉시 복사 (한 번만 표시됨).

### Portainer Endpoint ID
Portainer에서 대상 환경을 열고 URL을 확인하세요: `.../#!/{id}/docker/...` — 이 숫자가 Endpoint ID입니다 (로컬 환경 하나뿐이면 보통 `1` 또는 `2`). 또는 직접 조회:
```bash
curl -H "X-API-Key: {API_KEY}" "http://{PORTAINER_IP}:9000/api/endpoints"
```

### Proxmox API Token
1. Proxmox UI → **Datacenter → Permissions → API Tokens** → **Add**
2. **User**와 **Token ID** 지정, **Privilege Separation** 사용 여부 결정 (권장 — 사용 시 아래처럼 권한을 명시적으로 부여해야 함)
3. **Secret** 값을 즉시 복사 (한 번만 표시됨)
4. `{user}@{realm}!{tokenID}={secret}` 형식으로 조합, 예: `root@pam!hubitat=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`

Privilege Separation을 사용 중이라면, 게스트 전원 관리 권한을 토큰에 부여해야 합니다: **Datacenter → Permissions → Add** → Path `/vms/{vmid}` (게스트별) 또는 `/vms` (현재와 향후 모든 게스트 커버) → User: 토큰 계정 → Role: `PVEVMAdmin` (또는 `VM.PowerMgmt`가 포함된 커스텀 롤). LXC와 QEMU VM은 같은 권한 경로를 공유하지만 VMID별로 각각 권한을 부여해야 합니다.

## 설치

1. Hubitat 관리자 UI → **Drivers Code** → **New Driver**
2. [`drivers/stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy) 붙여넣기
3. Save
4. **Devices** → **Add Device** → **Virtual** → 방금 만든 드라이버 타입 선택
5. 아래 설정값 입력 → **Save Preferences**

## 설정값

### WAN 감지
| 항목 | 설명 |
|---|---|
| 메인 WAN 공인 IP 대역 | 메인 회선 공인 IP의 앞자리 (예: `121.130`); 현재 IP를 Primary/Failover로 분류하는 기준. 비워두면 분류 안 함. |
| 폴링 주기 | 확인 간격(분), 최소 1 |

### Cloudflare DNS
**하나 이상**의 레코드를 관리하며, preference 필드가 아니라 **커맨드로 추가/삭제**합니다 (Hubitat 텍스트 preference 필드는 보통 255자 정도로 제한되어 있어 Record ID 기준 7개 정도가 한계라, 대신 디바이스 state에 저장하는 방식이라 사실상 개수 제한이 없습니다).

**추천 구성**: 이미 라우터 단 DDNS로 관리되는 루트 도메인(예: `example.com`)이 있다면, 아래 "Primary 시 CNAME 대상 도메인"에 이 도메인을 지정해서 관리 대상 호스트네임이 이걸 따라가게 하세요 — 직접 A 레코드를 관리할 필요가 없어지고, IP가 바뀔 때마다가 아니라 Primary↔Failover 전환 시점에만 이 드라이버가 개입하면 됩니다.

| 항목 | 설명 |
|---|---|
| Cloudflare API Token | 위 "자격증명 수집" 참고 |
| Cloudflare Zone ID | 위 "자격증명 수집" 참고 |
| Proxied | Primary 상태일 때 Cloudflare 프록시(주황 구름) 유지 여부 |
| Primary 시 CNAME 대상 도메인 | 선택 사항. 설정하면(예: `example.com`) Primary 동안 직접 A 레코드 대신 이 도메인을 가리키는 CNAME으로 유지됩니다 — 그 도메인이 이미 다른 곳에서 DDNS로 관리되고 있을 때 유용하며, 이 경우 상태 전환 시에만 개입하면 되고 IP가 바뀔 때마다 손댈 필요가 없습니다. 비워두면 직접 A 레코드로 관리합니다. |
| Cloudflare Tunnel ID | 위 "자격증명 수집" 참고 |
| WAN 상태 전환 시 Cloudflare Tunnel 자동 시작/정지 | Primary↔Failover 전환 시 `cloudflared`를 자동으로 시작/정지하려면 활성화 |
| cloudflared 컨테이너 이름 | 아래 Portainer 연결 정보를 재사용 |

**관리 대상 호스트네임 설정** (preference 필드가 아니라 커맨드로):

| 커맨드 | 기능 |
|---|---|
| `addCloudflareRecord(name, recordId)` | 관리할 호스트네임 추가/갱신, 예: `addCloudflareRecord("wiki.example.com", "abc123...")` |
| `removeCloudflareRecord(name)` | 호스트네임 관리 중단 |
| `listCloudflareRecords` | 현재 목록을 로그로 출력 |

현재 목록은 **`cloudflareRecords`** 속성에도 그대로 반영되어, 디바이스의 Current States에서 바로 확인 가능합니다 — 로그를 볼 필요 없습니다.

DNS 갱신은 `forceUpdateDns`(Primary 모드) 또는 `switchCloudflareToTunnel`(Failover 모드)로 테스트하세요.

### Portainer (Docker 컨테이너 재시작)
| 항목 | 설명 |
|---|---|
| Portainer URL | 예: `http://192.168.x.x:9000` (자체서명 인증서 문제 회피를 위해 HTTP 권장, LAN 내부로만 제한) |
| Portainer API Key | 위 "자격증명 수집" 참고 |
| Portainer Endpoint ID | 위 "자격증명 수집" 참고 |
| 재시작할 컨테이너 이름 | 쉼표로 구분, 예: `npm` |
| 재시작까지 대기 시간(초) | IP 변경 후 재시작까지의 딜레이 |

`restartDockerContainer`로 테스트하세요.

### Proxmox (LXC/VM 재부팅)
| 항목 | 설명 |
|---|---|
| Proxmox 호스트 | 예: `https://192.168.x.x:8006` |
| Proxmox API Token | 위 "자격증명 수집" 참고 |
| Proxmox 노드 이름 | Proxmox UI에 표시된 노드명 |
| 재부팅할 LXC VMID | 쉼표로 구분 |
| 재부팅할 VM(QEMU) VMID | 쉼표로 구분 |
| Proxmox 재부팅까지 대기 시간(초) | IP 변경 후 재부팅까지의 딜레이 |

`restartProxmoxGuests`로 테스트하세요. Proxmox 기본 인증서는 자체서명이라 이 호출에 한해 SSL 검증을 비활성화합니다(`ignoreSSLIssues: true`) — 8006 포트는 LAN 내부로만 제한하세요.

## Cloudflare Tunnel 컨테이너 준비

```bash
docker run -d --name cloudflared --restart no \
  cloudflare/cloudflared:latest tunnel run --token <tunnel-token>
docker stop cloudflared
```
`--restart no`가 필수입니다 — `always`로 하면 이 드라이버가 정지시킬 때마다 Docker가 즉시 다시 시작해버립니다.

터널의 Public Hostname 경로는 대상 서비스의 **고정 LAN IP**로 직접 연결하세요 — 예를 들어 Proxmox LXC/VM 자체의 고정 IP나 IP를 고정해둔 컨테이너. `cloudflared`는 그 주소로 단순 LAN 라우팅만 하면 되므로 Docker 네트워크 공유가 필요 없습니다: `http://192.168.x.x:<포트>`. 다만 Docker 컨테이너의 *내부* IP는 피하세요 — 다른 IP 하드코딩과 마찬가지로 컨테이너 재시작 시 바뀔 수 있습니다.

터널 대시보드의 **호스트 이름 경로 / Public Hostname** 탭에서 *정확한* 호스트네임으로 경로를 추가하세요 (와일드카드 금지 — cloudflared는 실제 HTTP `Host` 헤더로 경로를 매칭하므로, `example.com` 경로는 `wiki.example.com` 요청과 매칭되지 않습니다). Service는 위에서 설정한 고정 IP로 지정합니다. 저장이 실패하고 해당 이름의 DNS 레코드가 이미 존재한다는 에러가 나면, 기존 레코드를 먼저 삭제한 뒤 터널 마법사가 새로 만들게 하고 — 새로 생긴 Record ID/이름을 이 드라이버 설정에 반영하세요. 이건 호스트네임당 한 번만 필요한 작업이며, 이후로는 드라이버가 그 Record ID를 계속 재사용합니다.

## 커맨드

| 커맨드 | 기능 |
|---|---|
| `refresh` | 즉시 공인 IP 재확인 |
| `forceUpdateDns` | Cloudflare 레코드(들)를 Primary 모드로 강제 전환 |
| `switchCloudflareToTunnel` | Cloudflare 레코드(들)를 Failover(터널 CNAME) 모드로 강제 전환 |
| `addCloudflareRecord(name, recordId)` | 관리할 DNS 호스트네임 추가/갱신 |
| `removeCloudflareRecord(name)` | DNS 호스트네임 관리 중단 |
| `listCloudflareRecords` | 현재 관리 중인 호스트네임 목록을 로그로 출력 (`cloudflareRecords` 속성에서도 확인 가능) |
| `restartDockerContainer` | 설정된 Portainer 컨테이너(들) 재시작 |
| `restartProxmoxGuests` | 설정된 Proxmox LXC/VM(들) 재부팅 |
| `toggleCloudflaredTunnel` | cloudflared 시작/정지. 파라미터가 정확히 `Primary` 또는 `Failover`여야 합니다 — 비워두면 항상 정지로 처리됩니다 |

## 참고 사항

- Docker restart/start API 호출은 빈 문자열 본문(`body: ""`)을 써야 하고 `Content-Length` 헤더를 수동으로 설정하면 안 됩니다 — Hubitat이 자동으로 계산하며, 둘 다 지정하면 충돌이 납니다.
- 관리용 포트(Proxmox 8006, Portainer 9000 등)는 LAN 내부로만 제한하세요.
- Cloudflare Tunnel 호스트네임 경로는 정확한 서브도메인이어야 하며, 와일드카드는 절대 쓰지 마세요.

## 자동화 아이디어

- `wanState` 변경을 Rule Machine / Home Assistant 자동화 트리거로 사용 (예: 백업 WAN 사용 중 NAS 백업 작업 일시정지, 비필수 WiFi 클라이언트 대역폭 제한)
- `ddnsStatus` / `dockerRestartStatus` / `proxmoxRestartStatus` / `cloudflaredStatus`가 `Error:`를 표시하면 알림
- UniFi 방화벽의 Policy-Based Routing(Kill Switch) 규칙과 비필수 기기에 결합해서 백업 WAN 데이터 사용량 절약

## 라이선스

Apache License 2.0 — 저장소 루트의 [LICENSE](../LICENSE) 참고.
