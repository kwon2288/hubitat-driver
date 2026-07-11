# WAN Failover Monitor

Hubitat Elevation driver that detects a home ISP failover (e.g. a UniFi
5G backup WAN taking over from the main line) by polling the router's
public IP address, and automates the follow-up tasks that a WAN change
usually breaks: DNS records pointing at the old IP, a reverse-proxy
container (Nginx Proxy Manager) that gets stuck routing to the old
outbound address, a standalone service (e.g. an MCP server) running in
its own Proxmox LXC or VM, and — since cellular backup WANs are almost
always behind Carrier-Grade NAT — routing external traffic through a
Cloudflare Tunnel instead of direct port forwarding while on the
backup WAN.

Built incrementally in four stages, each a self-contained driver you
can install on its own:

| Stage | File | Adds |
|---|---|---|
| 1 | [`drivers/stage1-wan-ip-monitor.groovy`](drivers/stage1-wan-ip-monitor.groovy) | Public IP polling (via [ipify](https://www.ipify.org/)) + Primary/Failover WAN state detection |
| 2 | [`drivers/stage2-cloudflare-ddns.groovy`](drivers/stage2-cloudflare-ddns.groovy) | + Automatic Cloudflare DNS record update when the IP changes |
| 3 | [`drivers/stage3-portainer-restart.groovy`](drivers/stage3-portainer-restart.groovy) | + Automatic Docker container restart (via Portainer API) after a delay |
| 4 | [`drivers/stage4-proxmox-restart.groovy`](drivers/stage4-proxmox-restart.groovy) | + Proxmox LXC/VM reboot + Cloudflare Tunnel auto start/stop + CGNAT-aware DNS record-type switching (A/CNAME ↔ CNAME-to-tunnel) |

Stage 4 is the "full" driver and is what's meant for day-to-day use;
Stages 1-3 are kept as separate files mainly for reference / for anyone
who only wants a subset of the functionality.

Also included, as an independent hub-agnostic safety net (not required
if the Stage 4 driver is working):

- [`scripts/check-ip-restart-npm.sh`](scripts/check-ip-restart-npm.sh) — runs on the Docker host, restarts the NPM container on IP change
- [`scripts/check-ip-restart-mcp.sh`](scripts/check-ip-restart-mcp.sh) — runs *inside* a standalone Proxmox LXC, restarts its own service (or reboots itself) on IP change

## Why this exists

With a dual-WAN setup (main line + LTE/5G backup), a failover changes
your public IP. Several things can break as a result:

1. **DDNS records** (e.g. `nas.example.com`) still point at the old IP until something updates them.
2. **Reverse proxy containers** (Nginx Proxy Manager, etc.) can get stuck routing to the stale outbound IP/socket bindings until restarted.
3. **Standalone services in their own LXC/VM** (not managed by Docker/Portainer) have the same problem but need a different restart mechanism (Proxmox VE API, or a self-monitoring script inside the guest).
4. **Cellular backup WANs are almost always behind Carrier-Grade NAT (CGNAT).** The IP your router receives from the carrier and the IP the outside world actually sees for you are two different things — and inbound connections to that "public" IP generally never reach your router at all, no matter how accurate your DDNS is. Direct port forwarding simply does not work over most mobile carrier connections. See [CGNAT and the Cloudflare Tunnel failover](#cgnat-and-the-cloudflare-tunnel-failover) below.

This driver closes that loop automatically, entirely on the Hubitat hub
— no dependency on Home Assistant or an external script having to be
running at the right time.

## Prerequisites

- Hubitat Elevation hub with outbound internet access
- A Cloudflare-managed domain — [API Token docs](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/)
- (Optional, for the Failover tunnel) A [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/) already created, with `cloudflared` running as a Docker container
- Docker managed via [Portainer](https://www.portainer.io/) (for Stage 3+ Docker restarts / cloudflared start-stop), with an [API access token](https://docs.portainer.io/api/access)
- Proxmox VE (for Stage 4 LXC/VM reboots), with an [API token](https://pve.proxmox.com/wiki/Proxmox_VE_API#API_Tokens) scoped to the relevant guest(s)

## Installation

1. Hubitat admin UI → **Drivers Code** → **New Driver**
2. Paste the contents of the stage file you want (Stage 4 recommended for the full pipeline)
3. Save
4. **Devices** → **Add Device** → **Virtual** → select the new driver type
5. Open the device, configure preferences (see below), **Save Preferences**

## Configuration

### Stage 1 — WAN detection

| Setting | Description |
|---|---|
| 메인 WAN 공인 IP 대역 (Primary WAN IP prefix) | The leading digits of your main line's public IP (e.g. `121.130`). Used to classify the current IP as `Primary` or `Failover`. Leave blank to skip classification. |
| 폴링 주기 (Poll interval) | Minutes between checks. Minimum 1. |

### Stage 2 — adds Cloudflare DNS management

The driver can manage **one or more** DNS records — enter comma-separated
lists in the same order for names and IDs:

| Setting | Description |
|---|---|
| Cloudflare API Token | Needs `Zone.DNS Edit` permission, scoped to the relevant zone only |
| Cloudflare Zone ID | From the Cloudflare dashboard's domain overview page (right sidebar) |
| DNS 레코드 이름 (Record names) | Comma-separated, e.g. `wiki.example.com,nas.example.com` |
| Cloudflare DNS Record ID | Comma-separated, **same order** as the names above (see below for how to find these) |
| Proxied | Whether to keep Cloudflare's proxy (orange cloud) on the record while on Primary |
| Primary 시 CNAME 대상 도메인 | Optional. If set (e.g. `example.com`), the record is kept as a **CNAME pointing at this domain** instead of a direct A record while on Primary — useful if that domain is *already* DDNS-managed elsewhere (e.g. by your router), so this driver doesn't need to track the live IP itself for every poll, only on state transitions. Leave blank to fall back to direct A-record management. |

**How to find a DNS Record ID** (not shown anywhere in the Cloudflare dashboard UI):

```bash
curl -s -X GET "https://api.cloudflare.com/client/v4/zones/{ZONE_ID}/dns_records?name=wiki.example.com" \
  -H "Authorization: Bearer {API_TOKEN}"
```

The `id` field in the JSON response (`result[0].id`) is what goes into
the "Cloudflare DNS Record ID" field. Repeat per hostname if managing
multiple records, and keep the name/ID lists in matching order.

Use the `forceUpdateDns` command to test credentials/config without
waiting for an actual IP change.

### Stage 3 — adds Portainer restart

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
with a non-empty body (deprecated since API v1.22, removed in v1.24),
and Hubitat's `httpPost` can trigger a *separate* `Content-Length header
already present` error if you manually set that header on top of an
empty `body: ""` (Hubitat computes it automatically). This driver sends
`body: ""` **without** a manual `Content-Length` header to avoid both
issues.

### Stage 4 — adds Proxmox LXC/VM reboot

| Setting | Description |
|---|---|
| Proxmox 호스트 | e.g. `https://192.168.x.x:8006` |
| Proxmox API Token | Format: `user@realm!tokenid=secret` |
| Proxmox 노드 이름 | The node name shown in the Proxmox UI |
| 재부팅할 LXC VMID | Comma-separated LXC VMIDs, e.g. `101,105` |
| 재부팅할 VM(QEMU) VMID | Comma-separated QEMU VM VMIDs, e.g. `201,202` |
| Proxmox 재부팅까지 대기 시간(초) | Seconds to wait after the IP change before rebooting (shared by LXC + VM), independent of the Stage 3 Portainer delay |

Use the `restartProxmoxGuests` command to test independently. Proxmox's
default certificate is self-signed, so this driver disables SSL
verification for this specific call (`ignoreSSLIssues: true`) — keep
port 8006 LAN-only.

LXC and QEMU guests share the same underlying Proxmox permission path
(`/vms/{vmid}`), but permissions must still be granted per VMID (or on
the parent `/vms` path to cover all current and future guests). A
common failure mode is a `403 Permission check failed (/vms/{vmid},
VM.PowerMgmt)` error — this means the API token authenticated fine but
lacks the `VM.PowerMgmt` privilege (via the `PVEVMAdmin` role, or a
custom role) on that specific VMID. Fix via **Datacenter → Permissions
→ Add**, granting the token's user the appropriate role on `/vms/{vmid}`
or `/vms`.

## CGNAT and the Cloudflare Tunnel failover

### The problem

Compare what UniFi's own dashboard shows for the backup WAN's IP against
what an external service like `https://api.ipify.org` reports — on a
cellular (LTE/5G) backup connection, **these are usually two different
addresses.** Mobile carriers almost universally use Carrier-Grade NAT
(CGNAT): the address your router receives is only valid inside the
carrier's network, and the carrier does a second layer of NAT before
your traffic reaches the real Internet, sharing one public IP across
many customers (RFC 6598 reserves `100.64.0.0/10` specifically for
this). The practical consequence: **no matter how accurate your DDNS
record is, inbound connections to that public IP will not reach your
router**, because the carrier's NAT has no rule to forward them back to
you. Port forwarding simply doesn't work over most cellular data plans.

### The fix

Instead of relying on inbound connections while on the backup WAN, run
[`cloudflared`](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/)
(Cloudflare Tunnel) as a Docker container. It works around CGNAT
entirely by opening an **outbound-only** connection to Cloudflare's
edge — no inbound connection is ever needed, so CGNAT is irrelevant.
This driver only runs `cloudflared` while actually on the backup WAN
(via Portainer start/stop), and switches the DNS record between a
direct A/CNAME (Primary) and a CNAME to the tunnel (Failover) so a
single hostname keeps working either way.

### Setting it up

**1) Create the tunnel and get its ID**

Cloudflare dashboard → `one.dash.cloudflare.com` → **Networks → Tunnels**
→ create a tunnel → copy its **Tunnel ID** (a UUID, shown on the
tunnel's overview page).

**2) Run `cloudflared` as a Docker container, initially stopped**

```bash
docker run -d --name cloudflared --restart no \
  cloudflare/cloudflared:latest tunnel run --token <tunnel-token>
docker stop cloudflared
```
`--restart no` is important — with `always`, Docker will immediately
restart the container every time this driver stops it.

**3) Put `cloudflared` on the same Docker network as your reverse proxy**

The default `bridge` network does **not** support container-name DNS
resolution, so `cloudflared` won't be able to reach `npm` by name unless
both are on a *user-defined* network:

```bash
docker network create npm-tunnel-net
docker network connect npm-tunnel-net npm
docker network connect npm-tunnel-net cloudflared
```

**4) Add the Public Hostname route in the tunnel dashboard**

Tunnel → **호스트 이름 경로 / Public Hostname** tab → add a route:
- Domain/Subdomain: the *exact* hostname you want to keep working (e.g.
  `wiki.example.com`) — **not** the zone apex, and no wildcards. cloudflared
  matches routes against the actual HTTP `Host` header of each request,
  so a route for `example.com` will not match a request for
  `wiki.example.com`.
- Service: `http://npm:80` (container name, not IP — IPs can change on
  container restart)
- Saving this will try to auto-create a DNS record for that hostname.
  **If a record with that name already exists (which it will, once this
  driver is managing it), delete the existing record first**, let the
  tunnel wizard create its own, then update this driver's Record ID/Name
  fields to match the new record (see "How to find a DNS Record ID"
  above). This is a one-time setup step per hostname — once the driver
  owns the record, its own `PATCH` calls no longer conflict with
  anything.

**5) Enable the toggle in this driver**

| Setting | Description |
|---|---|
| WAN 상태 전환 시 Cloudflare Tunnel 자동 시작/정지 | Enable to auto start/stop `cloudflared` on Primary↔Failover transitions |
| cloudflared 컨테이너 이름 | Reuses the Portainer connection settings from Stage 3 |
| Cloudflare Tunnel ID | The UUID from step 1 |

Test manually with `toggleCloudflaredTunnel("Failover")` /
`toggleCloudflaredTunnel("Primary")` and `switchCloudflareToTunnel`.

**Important:** the manual test command needs the parameter typed
exactly as `Primary` or `Failover` in the box Hubitat shows next to the
button — leaving it blank silently defaults to `stop`, since the driver
declares the command with an explicit `STRING` parameter
(`command "toggleCloudflaredTunnel", [[name: "wanStateNow", type: "STRING", ...]]`)
specifically so that input box shows up.

### How it behaves end-to-end

| Transition | DNS record | cloudflared | Docker/Proxmox restarts |
|---|---|---|---|
| Primary (steady state) | A record (or CNAME to your DDNS domain) → current IP | Stopped | Not triggered by state alone |
| **Primary → Failover** | Switched to CNAME → `<tunnelId>.cfargotunnel.com` | Started | Portainer/Proxmox restarts fire on the IP change that caused the transition |
| Failover (steady state, even if the underlying 5G IP keeps changing) | Left alone — CNAME doesn't need updating just because the CGNAT'd IP behind it changed | Left running | Portainer/Proxmox restarts still fire per plain IP-change events, if enabled |
| **Failover → Primary** | Switched back to A/CNAME → current IP | Stopped | Restarts fire on the IP change |

This keeps API calls to a minimum — Cloudflare/Portainer/Proxmox are
only touched on an actual state transition or a genuine IP change, not
on every single poll.

## Automation ideas

- Trigger Rule Machine / Home Assistant automations off `wanState` changes (e.g. pause NAS backup jobs, throttle non-essential WiFi clients while on the backup WAN)
- Alert on `ddnsStatus` / `dockerRestartStatus` / `proxmoxRestartStatus` / `cloudflaredStatus` showing an `Error:` value
- Combine with UniFi firewall Policy-Based Routing (Kill Switch) rules on non-essential devices to conserve backup-WAN data

## License

Apache License 2.0 — see the repository root [LICENSE](../LICENSE).
