# WAN Failover Monitor

Hubitat Elevation driver that detects a home ISP failover (e.g. a UniFi
5G backup WAN taking over from the main line) by polling the router's
public IP address, and automates the follow-up tasks that a WAN change
usually breaks: DNS records pointing at the old IP, a reverse-proxy
container (Nginx Proxy Manager) that gets stuck routing to the old
outbound address, and a standalone service (e.g. an MCP server) running
in its own Proxmox LXC.

Built incrementally in three stages, each a self-contained driver you
can install on its own:

| Stage | File | Adds |
|---|---|---|
| 1 | [`drivers/stage1-wan-ip-monitor.groovy`](drivers/stage1-wan-ip-monitor.groovy) | Public IP polling (via [ipify](https://www.ipify.org/)) + Primary/Failover WAN state detection |
| 2 | [`drivers/stage2-cloudflare-ddns.groovy`](drivers/stage2-cloudflare-ddns.groovy) | + Automatic Cloudflare DNS A-record update when the IP changes |
| 3 | [`drivers/stage3-portainer-restart.groovy`](drivers/stage3-portainer-restart.groovy) | + Automatic Docker container restart (via Portainer API) and/or Proxmox LXC reboot (via Proxmox VE API) after a delay |

Stage 3 is the "full" driver and is what's meant for day-to-day use;
Stages 1-2 are kept as separate files mainly for reference / for anyone
who only wants a subset of the functionality.

Also included, as an independent hub-agnostic safety net (not required
if the Stage 3 driver is working):

- [`scripts/check-ip-restart-npm.sh`](scripts/check-ip-restart-npm.sh) — runs on the Docker host, restarts the NPM container on IP change
- [`scripts/check-ip-restart-mcp.sh`](scripts/check-ip-restart-mcp.sh) — runs *inside* a standalone Proxmox LXC, restarts its own service (or reboots itself) on IP change

## Why this exists

With a dual-WAN setup (main line + LTE/5G backup), a failover changes
your public IP. Several things can break as a result:

1. **DDNS records** (e.g. `nas.example.com`) still point at the old IP until something updates them.
2. **Reverse proxy containers** (Nginx Proxy Manager, etc.) can get stuck routing to the stale outbound IP/socket bindings until restarted.
3. **Standalone services in their own LXC** (not managed by Docker/Portainer) have the same problem but need a different restart mechanism (Proxmox VE API, or a self-monitoring script inside the LXC).

This driver closes that loop automatically, entirely on the Hubitat hub
— no dependency on Home Assistant or an external script having to be
running at the right time.

## Prerequisites

- Hubitat Elevation hub with outbound internet access
- A Cloudflare-managed domain (for Stage 2/3) — [API Token docs](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/)
- Docker managed via [Portainer](https://www.portainer.io/) (for Stage 3 Docker restarts), with an [API access token](https://docs.portainer.io/api/access)
- Proxmox VE (for Stage 3 LXC reboots), with an [API token](https://pve.proxmox.com/wiki/Proxmox_VE_API#API_Tokens) scoped to the relevant LXC(s)

## Installation

1. Hubitat admin UI → **Drivers Code** → **New Driver**
2. Paste the contents of the stage file you want (Stage 3 recommended for the full pipeline)
3. Save
4. **Devices** → **Add Device** → **Virtual** → select the new driver type
5. Open the device, configure preferences (see below), **Save Preferences**

## Configuration

### Stage 1 — WAN detection

| Setting | Description |
|---|---|
| 메인 WAN 공인 IP 대역 (Primary WAN IP prefix) | The leading digits of your main line's public IP (e.g. `121.130`). Used to classify the current IP as `Primary` or `Failover`. Leave blank to skip classification. |
| 폴링 주기 (Poll interval) | Minutes between checks. Minimum 1. |

### Stage 2 — adds Cloudflare DDNS

| Setting | Description |
|---|---|
| Cloudflare API Token | Needs `Zone.DNS Edit` permission, scoped to the relevant zone only |
| Cloudflare Zone ID | From the Cloudflare dashboard's domain overview page |
| Cloudflare DNS Record ID | Not shown in the UI — fetch via `GET /zones/{zone_id}/dns_records?name=...` |
| DNS 레코드 이름 (Record name) | e.g. `nas.example.com` |
| Proxied | Whether to keep Cloudflare's proxy (orange cloud) on the record |

Use the `forceUpdateDns` command to test credentials without waiting for
an actual IP change.

### Stage 3 — adds Portainer restart + Proxmox LXC reboot

**Portainer (Docker containers, e.g. NPM):**

| Setting | Description |
|---|---|
| Portainer URL | e.g. `http://192.168.x.x:9000` (HTTP recommended to avoid self-signed cert issues; keep this port LAN-only) |
| Portainer API Key | Generate under **My account → Access Tokens** |
| Portainer Endpoint ID | The numeric environment ID — check the Portainer UI URL (`#!/<id>/docker/...`) or `GET /api/endpoints` |
| 재시작할 컨테이너 이름 (Container names) | Comma-separated, e.g. `npm,mcp` — restarted sequentially |
| 재시작까지 대기 시간 (Restart delay) | Seconds to wait after the IP change before restarting |

Use the `restartDockerContainer` command to test independently of an
actual IP change.

**Common gotcha:** the Docker Engine API rejects restart/start requests
with a non-empty body (deprecated since API v1.22, removed in v1.24).
This driver explicitly sends an empty body with `Content-Length: 0` to
avoid `400 Bad Request` errors.

**Proxmox (standalone LXC containers):**

| Setting | Description |
|---|---|
| Proxmox 호스트 | e.g. `https://192.168.x.x:8006` |
| Proxmox API Token | Format: `user@realm!tokenid=secret` |
| Proxmox 노드 이름 | The node name shown in the Proxmox UI |
| 재부팅할 LXC VMID | Comma-separated VMIDs, e.g. `101,105` |
| LXC 재부팅까지 대기 시간 | Seconds to wait after the IP change before rebooting |

Use the `restartProxmoxLxc` command to test independently. Proxmox's
default certificate is self-signed, so this driver disables SSL
verification for this specific call (`ignoreSSLIssues: true`) — keep
port 8006 LAN-only.

## Automation ideas

- Trigger Rule Machine / Home Assistant automations off `wanState` changes (e.g. pause NAS backup jobs, throttle non-essential WiFi clients while on the backup WAN)
- Alert on `ddnsStatus` / `dockerRestartStatus` / `lxcRestartStatus` showing an `Error:` value
- Combine with UniFi firewall Policy-Based Routing (Kill Switch) rules on non-essential devices to conserve backup-WAN data

## License

Apache License 2.0 — see the repository root [LICENSE](../LICENSE).
