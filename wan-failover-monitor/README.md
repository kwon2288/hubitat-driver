# WAN Failover Monitor

Hubitat Elevation driver that detects a home ISP failover (e.g. a UniFi
5G/LTE backup WAN taking over from the main line) by polling the
router's public IP, and automates everything that a WAN change usually
breaks: stale DNS records, a reverse-proxy container (Nginx Proxy
Manager) stuck on the old outbound address, a standalone service in its
own Proxmox LXC/VM, and — since cellular backup WANs are almost always
behind Carrier-Grade NAT (CGNAT) — routing external traffic through a
Cloudflare Tunnel instead of direct port forwarding while on the backup
WAN.

GitHub: this repository, `wan-failover-monitor/` folder.

## Architecture

| State | DNS record | cloudflared (tunnel) | NPM / Proxmox restarts |
|---|---|---|---|
| **Primary** (main line) | CNAME to another A-record domain (e.g. one already tracked by router-level DDNS) — or a direct A record, if you'd rather manage the IP yourself | Stopped | Triggered on IP change |
| **Failover** (cellular, CGNAT) | CNAME → `<tunnelId>.cfargotunnel.com` | Running | Triggered on IP change |

The hostname your services use (e.g. `wiki.example.com`) never changes
— only what it resolves to switches automatically.

Traffic paths:
- **Primary**: client → DNS (A/CNAME) → public IP → router port forward → target service (directly, or via a reverse proxy like NPM)
- **Failover**: client → DNS (CNAME) → Cloudflare edge → tunnel → cloudflared → target service (directly, or via NPM, depending on what you set as the tunnel's Service URL)

Whether NPM sits in the path or not is entirely up to how you configure
the tunnel's Public Hostname Service field — point it at NPM if you
want NPM's routing/TLS handling to keep applying, or point it straight
at a service's own address (e.g. a fixed LAN IP) to bypass NPM
entirely while on Failover.

Built incrementally in four stages, each a self-contained driver you
can install on its own:

| Stage | File | Adds |
|---|---|---|
| 1 | [`drivers/stage1-wan-ip-monitor.groovy`](drivers/stage1-wan-ip-monitor.groovy) | Public IP polling (via [ipify](https://www.ipify.org/)) + Primary/Failover WAN state detection |
| 2 | [`drivers/stage2-cloudflare-ddns.groovy`](drivers/stage2-cloudflare-ddns.groovy) | + Automatic Cloudflare DNS record update when the IP changes |
| 3 | [`drivers/stage3-portainer-restart.groovy`](drivers/stage3-portainer-restart.groovy) | + Automatic Docker container restart (via Portainer API) after a delay |
| 4 | [`drivers/stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy) | + Proxmox LXC/VM reboot + Cloudflare Tunnel auto start/stop + CGNAT-aware DNS record-type switching |

Stage 4 is the full driver, meant for day-to-day use. Stages 1-3 are
kept as separate files for reference or partial installs.

**Language variants**: [`stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy) has Korean preference labels/log messages; [`stage4-proxmox-restart-en.groovy`](drivers/stage4-proxmox-restart-en.groovy) is functionally identical with English labels. Both register under different device names (`Public IP WAN Monitor - Stage 4` vs `Public IP WAN Monitor (EN)`), so you can install either — or both, on separate test devices — without conflict.

Also included, as an independent hub-agnostic safety net (not required
once Stage 4 is working):

- [`scripts/check-ip-restart-npm.sh`](scripts/check-ip-restart-npm.sh) — runs on the Docker host, restarts the NPM container on IP change
- [`scripts/check-ip-restart-mcp.sh`](scripts/check-ip-restart-mcp.sh) — runs *inside* a standalone Proxmox LXC, restarts its own service (or reboots itself) on IP change

## Why CGNAT matters here

Compare the backup WAN's IP as shown in UniFi's dashboard against what
`https://api.ipify.org` reports — on a cellular connection these are
usually different addresses (if the UniFi-reported IP falls in
`100.64.0.0/10`, CGNAT is confirmed per RFC 6598). Mobile carriers
share one public IP across many customers via CGNAT, and inbound
connections to that shared IP generally never reach your router, no
matter how accurate your DDNS is. Direct port forwarding does not work
over most cellular data plans. The workaround is an **outbound-only**
tunnel — Cloudflare Tunnel (`cloudflared`) — which needs no inbound
connection at all, so CGNAT is irrelevant. This driver only runs
`cloudflared` while actually on the backup WAN, keeping it off (no
added latency or resource use) the rest of the time.

## Gathering credentials

### Cloudflare API Token
1. [Cloudflare dashboard](https://dash.cloudflare.com) → profile icon → **My Profile**
2. **API Tokens** → **Create Token** → use the **Edit zone DNS** template
3. Under **Zone Resources**, scope it to the specific zone (not all zones)
4. Create Token → copy the value immediately (shown once)

### Cloudflare Zone ID
Domain's **Overview** page → right sidebar **API** section → **Zone ID**.

### Cloudflare DNS Record ID
Not shown in the dashboard UI — query it:
```bash
curl -s -X GET "https://api.cloudflare.com/client/v4/zones/{ZONE_ID}/dns_records?name={hostname}" \
  -H "Authorization: Bearer {API_TOKEN}"
```
The `result[0].id` field in the response is the Record ID. Repeat per
hostname if managing multiple records.

### Cloudflare Tunnel ID
[one.dash.cloudflare.com](https://one.dash.cloudflare.com) → **Networks → Tunnels** → select the tunnel → **Overview** tab → UUID shown there (also in the URL).

### Portainer API Key
Portainer UI → user icon → **My account** → **Access Tokens** → **Add access token** → copy the value immediately (shown once).

### Portainer Endpoint ID
Open the target environment in Portainer and check the URL: `.../#!/{id}/docker/...` — that number is the Endpoint ID (usually `1` or `2` for a single local environment). Or query it:
```bash
curl -H "X-API-Key: {API_KEY}" "http://{PORTAINER_IP}:9000/api/endpoints"
```

### Proxmox API Token
1. Proxmox UI → **Datacenter → Permissions → API Tokens** → **Add**
2. Pick a **User** and **Token ID**; decide whether to use **Privilege Separation** (recommended — requires explicitly granting permissions below)
3. Copy the **Secret** immediately (shown once)
4. Combine into the format `{user}@{realm}!{tokenID}={secret}`, e.g. `root@pam!hubitat=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`

If Privilege Separation is on, grant the token permission to power-manage guests: **Datacenter → Permissions → Add** → Path `/vms/{vmid}` (per guest) or `/vms` (covers all current and future guests) → User: the token's user → Role: `PVEVMAdmin` (or a custom role including `VM.PowerMgmt`). LXC and QEMU VMs share this same permission path but need it granted per VMID.

## Installation

1. Hubitat admin UI → **Drivers Code** → **New Driver**
2. Paste [`drivers/stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy)
3. Save
4. **Devices** → **Add Device** → **Virtual** → select the new driver type
5. Configure preferences below → **Save Preferences**

## Configuration

### WAN detection
| Setting | Description |
|---|---|
| 메인 WAN 공인 IP 대역 | Leading digits of the main line's public IP (e.g. `121.130`); classifies current IP as Primary/Failover. Blank = no classification. |
| 폴링 주기 | Minutes between checks, minimum 1 |

### Cloudflare DNS
Manages **one or more** records, added/removed via commands rather than a
preference field (Hubitat's text preference fields cap out around 255
characters — only about 7 record IDs' worth — so records are stored in
device state instead, with no practical limit).

**Recommended setup**: if you already have a root domain (e.g.
`example.com`) tracked by router-level DDNS, point managed hostnames at
it via "Primary 시 CNAME 대상 도메인" below rather than managing a
direct A record — one less thing this driver needs to keep in sync,
and it only needs to act on Primary↔Failover transitions instead of
every IP change.

| Setting | Description |
|---|---|
| Cloudflare API Token | From "Gathering credentials" above |
| Cloudflare Zone ID | From "Gathering credentials" above |
| Proxied | Keep Cloudflare's proxy (orange cloud) while on Primary |
| Primary 시 CNAME 대상 도메인 | Optional. If set (e.g. `example.com`), the record stays a CNAME to this domain while on Primary instead of a direct A record — useful when that domain is already DDNS-managed elsewhere, so this driver only needs to act on state transitions, not every IP change. Blank = manage a direct A record. |
| Cloudflare Tunnel ID | From "Gathering credentials" above |
| WAN 상태 전환 시 Cloudflare Tunnel 자동 시작/정지 | Enable to auto start/stop `cloudflared` on Primary↔Failover transitions |
| cloudflared 컨테이너 이름 | Reuses the Portainer connection settings below |

**Managing which hostnames are covered** (via commands, not a preference field):

| Command | Purpose |
|---|---|
| `addCloudflareRecord(name, recordId)` | Add or update a managed hostname, e.g. `addCloudflareRecord("wiki.example.com", "abc123...")` |
| `removeCloudflareRecord(name)` | Stop managing a hostname |
| `listCloudflareRecords` | Log the current list |

The current list is also mirrored into the **`cloudflareRecords`**
attribute, visible directly in the device's Current States — no need to
check logs.

Test DNS updates with `forceUpdateDns` (Primary mode) or
`switchCloudflareToTunnel` (Failover mode).

### Portainer (Docker container restart)
| Setting | Description |
|---|---|
| Portainer URL | e.g. `http://192.168.x.x:9000` (HTTP avoids self-signed cert issues; keep LAN-only) |
| Portainer API Key | From "Gathering credentials" above |
| Portainer Endpoint ID | From "Gathering credentials" above |
| 재시작할 컨테이너 이름 | Comma-separated, e.g. `npm` |
| 재시작까지 대기 시간(초) | Delay after IP change before restarting |

Test with `restartDockerContainer`.

### Proxmox (LXC/VM reboot)
| Setting | Description |
|---|---|
| Proxmox 호스트 | e.g. `https://192.168.x.x:8006` |
| Proxmox API Token | From "Gathering credentials" above |
| Proxmox 노드 이름 | Node name as shown in the Proxmox UI |
| 재부팅할 LXC VMID | Comma-separated |
| 재부팅할 VM(QEMU) VMID | Comma-separated |
| Proxmox 재부팅까지 대기 시간(초) | Delay after IP change before rebooting |

Test with `restartProxmoxGuests`. Proxmox's default certificate is
self-signed, so this driver disables SSL verification for this call
(`ignoreSSLIssues: true`) — keep port 8006 LAN-only.

## Setting up the Cloudflare Tunnel container

```bash
docker run -d --name cloudflared --restart no \
  cloudflare/cloudflared:latest tunnel run --token <tunnel-token>
docker stop cloudflared
```
`--restart no` is required — with `always`, Docker immediately restarts the container every time this driver stops it.

Point the tunnel's Public Hostname routes at whatever `cloudflared` can
reach on your LAN — two approaches work:

- **Container name, same Docker network**: if routing through a
  container (e.g. a reverse proxy) by name, put `cloudflared` on the
  same *user-defined* Docker network as that container (the default
  `bridge` network doesn't support container-name DNS resolution):
  ```bash
  docker network create tunnel-net
  docker network connect tunnel-net <target-container>
  docker network connect tunnel-net cloudflared
  ```
  Service URL: `http://<container-name>:<port>`

- **Direct static IP** (simpler, no network setup needed): if the
  target service has a fixed LAN IP that doesn't change on restart —
  e.g. a Proxmox LXC/VM's own static IP, or a container with a pinned
  IP — just point the Service URL straight at it:
  `http://192.168.x.x:<port>`. No shared Docker network required,
  since `cloudflared` just needs plain LAN routing to that address.
  Avoid using a Docker container's *internal* IP this way, though —
  those can change on container restart, same as with any other
  IP-hardcoded connection.

In the tunnel dashboard's **호스트 이름 경로 / Public Hostname** tab, add
a route for the *exact* hostname (no wildcards — cloudflared matches
routes against the actual HTTP `Host` header, so a route for
`example.com` won't match `wiki.example.com`), with Service
`http://npm:80` (container name, not IP). If saving fails because a DNS
record with that name already exists, delete the existing record first
and let the tunnel wizard create its own — then update this driver's
Record ID/Name fields to match. This is a one-time step per hostname;
afterward the driver reuses that record ID indefinitely.

## Commands

| Command | Purpose |
|---|---|
| `refresh` | Re-check public IP immediately |
| `forceUpdateDns` | Force the Cloudflare record(s) into Primary mode |
| `switchCloudflareToTunnel` | Force the Cloudflare record(s) into Failover (tunnel CNAME) mode |
| `addCloudflareRecord(name, recordId)` | Add/update a managed DNS hostname |
| `removeCloudflareRecord(name)` | Stop managing a DNS hostname |
| `listCloudflareRecords` | Log the current managed hostname list (also visible in the `cloudflareRecords` attribute) |
| `restartDockerContainer` | Restart configured Portainer container(s) |
| `restartProxmoxGuests` | Reboot configured Proxmox LXC/VM(s) |
| `toggleCloudflaredTunnel` | Start/stop cloudflared. Requires the parameter to be exactly `Primary` or `Failover` — leaving it blank always resolves to stop |

## Notes

- Docker restart/start API calls must use an empty string body (`body: ""`) with no manually-set `Content-Length` header — Hubitat computes it automatically, and setting both causes a conflict.
- Keep management ports (Proxmox 8006, Portainer 9000, etc.) LAN-only.
- Cloudflare Tunnel hostname routes must be exact subdomains, never wildcards.

## Automation ideas

- Trigger Rule Machine / Home Assistant automations off `wanState` changes (e.g. pause NAS backup jobs, throttle non-essential WiFi clients while on the backup WAN)
- Alert on `ddnsStatus` / `dockerRestartStatus` / `proxmoxRestartStatus` / `cloudflaredStatus` showing an `Error:` value
- Combine with UniFi firewall Policy-Based Routing (Kill Switch) rules on non-essential devices to conserve backup-WAN data

## License

Apache License 2.0 — see the repository root [LICENSE](../LICENSE).
