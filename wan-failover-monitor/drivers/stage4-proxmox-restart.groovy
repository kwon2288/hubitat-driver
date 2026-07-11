/**
 *  Public IP / WAN Failover Monitor — Stage 4: Full Pipeline
 *
 *  Polls api.ipify.org for the current public IP, detects Primary/Failover
 *  WAN state, and automates everything that breaks when a home ISP fails
 *  over to a CGNAT'd backup WAN (e.g. UniFi 5G/LTE backup):
 *
 *    - Cloudflare DNS: keeps one or more hostnames pointed at the right
 *      place — either a direct A record (or a CNAME to another DDNS-
 *      managed domain) while on Primary, or a CNAME to a Cloudflare
 *      Tunnel while on Failover (since cellular WANs are almost always
 *      behind Carrier-Grade NAT, so inbound connections to the "public"
 *      IP reported by ipify won't actually reach the router).
 *    - Portainer: restarts one or more Docker containers (e.g. a reverse
 *      proxy like Nginx Proxy Manager that gets stuck on the old IP).
 *    - Proxmox VE: reboots one or more LXC containers and/or QEMU VMs
 *      (e.g. a standalone service not managed by Docker/Portainer).
 *    - cloudflared: starts/stops a Cloudflare Tunnel container so it's
 *      only running while actually needed (Failover), not burning
 *      resources or adding latency while on Primary.
 *
 *  Attributes:
 *    - publicIP             (string)  current public IP address
 *    - wanState              (enum)    "Primary", "Failover", or "Unknown"
 *    - lastChecked           (string)  timestamp of last successful check
 *    - ddnsStatus            (string)  result of the last Cloudflare update attempt(s)
 *    - dockerRestartStatus   (string)  result of the last Portainer restart attempt(s)
 *    - proxmoxRestartStatus  (string)  result of the last Proxmox LXC/VM reboot attempt(s)
 *    - cloudflaredStatus     (string)  result of the last cloudflared start/stop attempt
 *
 *  Author: kwon2288
 *  License: Apache License 2.0
 */

import groovy.json.JsonOutput

metadata {
    definition(name: "Public IP WAN Monitor - Stage 4", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "Refresh"

        attribute "publicIP", "string"
        attribute "wanState", "enum", ["Primary", "Failover", "Unknown"]
        attribute "lastChecked", "string"
        attribute "ddnsStatus", "string"
        attribute "dockerRestartStatus", "string"
        attribute "proxmoxRestartStatus", "string"
        attribute "cloudflaredStatus", "string"

        command "refresh"
        command "forceUpdateDns"
        command "switchCloudflareToTunnel"
        command "restartDockerContainer"
        command "restartProxmoxGuests"
        command "toggleCloudflaredTunnel", [[name: "wanStateNow", type: "STRING", description: "'Primary' or 'Failover' — determines start vs stop"]]
    }

    preferences {
        input name: "primaryWanPrefix", type: "string", title: "메인 WAN 공인 IP 대역 (앞자리, 예: 121.130)",
              description: "비워두면 Failover 판단 없이 IP만 기록합니다.", required: false

        input name: "pollIntervalMinutes", type: "number", title: "폴링 주기 (분, 최소 1분)",
              defaultValue: 5, required: true

        input name: "enableCloudflareDdns", type: "bool", title: "Cloudflare DDNS 자동 업데이트 사용", defaultValue: false

        input name: "cloudflareApiToken", type: "password", title: "Cloudflare API Token (Zone.DNS Edit 권한)", required: false
        input name: "cloudflareZoneId", type: "string", title: "Cloudflare Zone ID", required: false
        input name: "cloudflareRecordId", type: "string", title: "Cloudflare DNS Record ID (쉼표로 여러 개, 이름 목록과 같은 순서로)", required: false
        input name: "cloudflareRecordName", type: "string", title: "DNS 레코드 이름 (쉼표로 여러 개, 예: wiki.yourdomain.com,nas.yourdomain.com)", required: false
        input name: "cloudflareProxied", type: "bool", title: "Cloudflare Proxy(주황 구름) 사용 (평소 A레코드용)", defaultValue: false
        input name: "cloudflarePrimaryTarget", type: "string", title: "Primary 시 CNAME 대상 도메인 (예: kwonmin.com, 비워두면 대신 공인 IP로 A레코드 직접 관리)", required: false
        input name: "cloudflareTunnelId", type: "string", title: "Cloudflare Tunnel ID (Failover 시 CNAME 전환용, Zero Trust 대시보드에서 확인)", required: false

        input name: "enablePortainerRestart", type: "bool", title: "IP 변경 시 Portainer 컨테이너 재시작", defaultValue: false
        input name: "portainerUrl", type: "string", title: "Portainer URL (예: http://192.168.x.x:9000)", required: false
        input name: "portainerApiKey", type: "password", title: "Portainer API Key", required: false
        input name: "portainerEndpointId", type: "string", title: "Portainer Endpoint ID", defaultValue: "1", required: false
        input name: "portainerContainerName", type: "string", title: "재시작할 컨테이너 이름 (쉼표로 여러 개, 예: npm,mcp)", required: false
        input name: "restartDelaySeconds", type: "number", title: "IP 변경 후 재시작까지 대기 시간(초)", defaultValue: 10

        input name: "enableProxmoxRestart", type: "bool", title: "IP 변경 시 Proxmox LXC/VM 재부팅", defaultValue: false
        input name: "proxmoxHost", type: "string", title: "Proxmox 호스트 (예: https://192.168.x.x:8006)", required: false
        input name: "proxmoxApiToken", type: "password", title: "Proxmox API Token (형식: user@realm!tokenid=secret)", required: false
        input name: "proxmoxNode", type: "string", title: "Proxmox 노드 이름", required: false
        input name: "proxmoxLxcIds", type: "string", title: "재부팅할 LXC VMID (쉼표로 여러 개, 예: 101,105)", required: false
        input name: "proxmoxVmIds", type: "string", title: "재부팅할 VM(QEMU) VMID (쉼표로 여러 개, 예: 201,202)", required: false
        input name: "proxmoxRestartDelaySeconds", type: "number", title: "IP 변경 후 Proxmox LXC/VM 재부팅까지 대기 시간 (초)", defaultValue: 10

        input name: "enableCloudflaredToggle", type: "bool", title: "WAN 상태 전환 시 Cloudflare Tunnel(cloudflared) 자동 시작/정지", defaultValue: false
        input name: "cloudflaredContainerName", type: "string", title: "cloudflared 컨테이너 이름 (Portainer 설정 재사용)", required: false

        input name: "logEnable", type: "bool", title: "디버그 로그 활성화", defaultValue: false
    }
}

def installed() {
    log.info "Public IP WAN Monitor installed"
    initialize()
}

def updated() {
    log.info "Public IP WAN Monitor preferences updated"
    initialize()
}

def initialize() {
    unschedule()
    Integer interval = (pollIntervalMinutes ?: 5) as Integer
    if (interval < 1) interval = 1

    // schedule(cron) doesn't support arbitrary minute intervals cleanly, so use runIn chaining instead
    if (interval == 1) {
        schedule("0 * * * * ?", refresh)
    } else {
        schedule("0 */${interval} * * * ?", refresh)
    }

    if (logEnable) log.debug "Scheduled polling every ${interval} minute(s)"
    refresh()
}

def refresh() {
    if (logEnable) log.debug "Refreshing public IP..."

    try {
        httpGet([uri: "https://api.ipify.org", query: [format: "json"], contentType: "application/json", timeout: 10]) { resp ->
            if (resp.status == 200 && resp.data?.ip) {
                String currentIp = resp.data.ip
                processIp(currentIp)
            } else {
                log.warn "Unexpected response from ipify: status=${resp.status}"
                setWanStateUnknown()
            }
        }
    } catch (Exception e) {
        log.warn "Failed to fetch public IP: ${e.message}"
        setWanStateUnknown()
    }
}

private void processIp(String currentIp) {
    String previousIp = device.currentValue("publicIP")
    boolean ipChanged = (currentIp != previousIp)

    if (ipChanged) {
        sendEvent(name: "publicIP", value: currentIp, descriptionText: "Public IP is now ${currentIp}")
        log.info "Public IP changed: ${previousIp} -> ${currentIp}"

        if (enablePortainerRestart) {
            Integer delaySec = (restartDelaySeconds ?: 10) as Integer
            if (logEnable) log.debug "Scheduling Docker container restart in ${delaySec}s"
            runIn(delaySec, "restartDockerContainer")
        }

        if (enableProxmoxRestart) {
            Integer proxDelaySec = (proxmoxRestartDelaySeconds ?: 10) as Integer
            if (logEnable) log.debug "Scheduling Proxmox LXC/VM restart in ${proxDelaySec}s"
            runIn(proxDelaySec, "restartProxmoxGuests")
        }
    } else if (logEnable) {
        log.debug "Public IP unchanged: ${currentIp}"
    }

    sendEvent(name: "lastChecked", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    String prefix = primaryWanPrefix?.trim()
    if (prefix) {
        String newState = currentIp.startsWith(prefix) ? "Primary" : "Failover"
        String previousState = device.currentValue("wanState")
        boolean stateChanged = (newState != previousState)
        boolean knownTransition = stateChanged && (previousState == "Primary" || previousState == "Failover")

        sendEvent(name: "wanState", value: newState,
                  descriptionText: "WAN state is ${newState}",
                  isStateChange: stateChanged)

        if (stateChanged) {
            log.info "WAN state changed: ${previousState} -> ${newState} (IP: ${currentIp})"

            if (enableCloudflaredToggle && knownTransition) {
                toggleCloudflaredTunnel(newState)
            }
        }

        // Cloudflare DNS handling — record stays a CNAME to the tunnel for the
        // whole time we're on Failover (regardless of further IP churn on that
        // WAN), and only gets switched back to a direct A record once we're
        // confirmed back on Primary. This avoids the A/CNAME conflict that
        // happens if both are attempted on the same record name.
        if (enableCloudflareDdns) {
            if (newState == "Failover" && knownTransition && cloudflareTunnelId) {
                switchCloudflareToTunnel()
            } else if (newState != "Failover") {
                boolean primaryIsCnameMode = cloudflarePrimaryTarget?.trim()
                if (stateChanged && knownTransition) {
                    // Just came back from Failover — always re-apply the Primary target
                    updateCloudflareDns(currentIp)
                } else if (ipChanged && !primaryIsCnameMode) {
                    // Legacy A-record mode only: keep it current with the live IP.
                    // CNAME mode doesn't need this — the target domain tracks its
                    // own IP independently.
                    updateCloudflareDns(currentIp)
                }
            }
        }
    } else {
        sendEvent(name: "wanState", value: "Unknown", descriptionText: "No primary WAN prefix configured")

        // No prefix configured means we can't tell Primary from Failover, so
        // fall back to the simple "always keep the A record current" behavior.
        if (enableCloudflareDdns && ipChanged) {
            updateCloudflareDns(currentIp)
        }
    }
}

private void setWanStateUnknown() {
    if (device.currentValue("wanState") != "Unknown") {
        sendEvent(name: "wanState", value: "Unknown", descriptionText: "Failed to determine WAN state (lookup failed)")
    }
}

/**
 * Manually push the currently known publicIP to Cloudflare, regardless of
 * whether it changed since the last poll. Useful for testing credentials
 * or forcing a sync after re-configuring the DNS record fields.
 */
def forceUpdateDns() {
    String currentIp = device.currentValue("publicIP")
    if (!currentIp) {
        log.warn "No publicIP known yet - run refresh() first"
        return
    }
    updateCloudflareDns(currentIp)
}

private void updateCloudflareDns(String ip) {
    String target = cloudflarePrimaryTarget?.trim()

    if (target) {
        // CNAME mode: point at the given domain (e.g. the DDNS-managed root
        // domain), which itself tracks the current public IP independently.
        // This means we don't need to re-run this on every IP change while
        // on Primary — only when we transition back onto Primary from
        // Failover (see processIp).
        applyToAllCloudflareRecords(
            type: "CNAME",
            content: target,
            proxied: (cloudflareProxied ?: false),
            modeLabel: "CNAME->${target}"
        )
    } else {
        // Legacy mode: manage a direct A record pointing at the current IP.
        applyToAllCloudflareRecords(
            type: "A",
            content: ip,
            proxied: (cloudflareProxied ?: false),
            modeLabel: "A->${ip}"
        )
    }
}

/**
 * Switches all configured DNS record(s) from a direct A record to a CNAME
 * pointing at the Cloudflare Tunnel (<tunnelId>.cfargotunnel.com).
 * Cloudflare requires tunnel CNAMEs to be proxied (orange cloud),
 * regardless of the cloudflareProxied preference used for the normal
 * A/CNAME-to-domain record.
 */
def switchCloudflareToTunnel() {
    if (!cloudflareTunnelId) {
        log.warn "Cloudflare Tunnel CNAME switch requested but cloudflareTunnelId is not set"
        sendEvent(name: "ddnsStatus", value: "Not configured (missing tunnel ID)")
        return
    }

    applyToAllCloudflareRecords(
        type: "CNAME",
        content: "${cloudflareTunnelId}.cfargotunnel.com",
        proxied: true,
        modeLabel: "CNAME->tunnel"
    )
}

/**
 * Parses the comma-separated "DNS 레코드 이름" / "Cloudflare DNS Record ID"
 * fields into paired [name, id] entries (matched by position — the Nth
 * name goes with the Nth ID). Logs a warning if the counts don't match,
 * since that almost always means one field was edited without the other.
 */
private List<Map> getCloudflareRecordPairs() {
    List<String> names = (cloudflareRecordName ?: "").split(",").collect { it.trim() }.findAll { it }
    List<String> ids = (cloudflareRecordId ?: "").split(",").collect { it.trim() }.findAll { it }

    if (names.size() != ids.size()) {
        log.warn "Cloudflare record name count (${names.size()}) doesn't match record ID count (${ids.size()}) - check both fields have the same number of comma-separated entries, in the same order"
    }

    List<Map> pairs = []
    int count = Math.min(names.size(), ids.size())
    for (int i = 0; i < count; i++) {
        pairs << [name: names[i], id: ids[i]]
    }
    return pairs
}

/**
 * Applies the same type/content/proxied change to every configured DNS
 * record (comma-separated name/ID pairs), sequentially. Aggregates a
 * single summary into the ddnsStatus attribute, e.g.:
 *   "OK, CNAME->tunnel (14:32:10) - wiki.kwonmin.com: OK, nas.kwonmin.com: OK"
 */
private void applyToAllCloudflareRecords(Map fields) {
    if (!cloudflareApiToken || !cloudflareZoneId) {
        log.warn "Cloudflare DDNS is enabled but API token/zone ID are not configured"
        sendEvent(name: "ddnsStatus", value: "Not configured")
        return
    }

    List<Map> pairs = getCloudflareRecordPairs()
    if (pairs.isEmpty()) {
        log.warn "Cloudflare DDNS is enabled but no valid record name/ID pairs are configured"
        sendEvent(name: "ddnsStatus", value: "Not configured")
        return
    }

    List<String> results = []
    pairs.each { pair ->
        results << patchCloudflareDnsRecord(pair.id, pair.name, fields)
    }

    boolean anyError = results.any { it.contains("Error") }
    String summary = "${fields.modeLabel} ${anyError ? "(일부 실패)" : ""} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
    sendEvent(name: "ddnsStatus", value: summary)
}

private String patchCloudflareDnsRecord(String recordId, String recordName, Map fields) {
    String uri = "https://api.cloudflare.com/client/v4/zones/${cloudflareZoneId}/dns_records/${recordId}"
    Map body = [
        type   : fields.type,
        name   : recordName,
        content: fields.content,
        ttl    : 1,                              // 1 = "Automatic" in Cloudflare
        proxied: fields.proxied
    ]

    Map params = [
        uri              : uri,
        headers          : ["Authorization": "Bearer ${cloudflareApiToken}"],
        requestContentType: "application/json",
        contentType      : "application/json",
        body             : JsonOutput.toJson(body),
        timeout          : 10
    ]

    try {
        String result = "${recordName}: Error"
        httpPatch(params) { resp ->
            if (resp.status == 200 && resp.data?.success) {
                log.info "Cloudflare DNS record '${recordName}' updated: type=${fields.type}, content=${fields.content}"
                result = "${recordName}: OK"
            } else {
                log.warn "Cloudflare update for '${recordName}' returned unexpected result: ${resp.data}"
                result = "${recordName}: Error"
            }
        }
        return result
    } catch (Exception e) {
        log.warn "Cloudflare DNS update for '${recordName}' failed: ${e.message}"
        return "${recordName}: Error"
    }
}

/**
 * Restarts one or more Docker containers via the Portainer API (proxied
 * Docker Engine API). Containers are referenced by name (comma-separated
 * in the "재시작할 컨테이너 이름" field), which the Docker Engine API accepts
 * directly as long as each name is unique on that endpoint. Containers are
 * restarted sequentially; if one fails, the rest are still attempted.
 */
def restartDockerContainer() {
    if (!portainerUrl || !portainerApiKey || !portainerContainerName) {
        log.warn "Portainer restart is enabled but not fully configured (url/apiKey/containerName required)"
        sendEvent(name: "dockerRestartStatus", value: "Not configured")
        return
    }

    List<String> containerNames = portainerContainerName.split(",").collect { it.trim() }.findAll { it }
    String endpointId = portainerEndpointId ?: "1"

    List<String> results = []

    containerNames.each { containerName ->
        String uri = "${portainerUrl}/api/endpoints/${endpointId}/docker/containers/${containerName}/restart"

        Map params = [
            uri    : uri,
            headers: ["X-API-Key": portainerApiKey],
            body   : "",
            timeout: 15
        ]

        try {
            httpPost(params) { resp ->
                // Docker Engine API returns 204 No Content on a successful restart
                if (resp.status == 204 || resp.status == 200) {
                    log.info "Docker container '${containerName}' restarted via Portainer"
                    results << "${containerName}: OK"
                } else {
                    log.warn "Portainer restart of '${containerName}' returned unexpected status: ${resp.status}"
                    results << "${containerName}: Error ${resp.status}"
                }
            }
        } catch (Exception e) {
            log.warn "Portainer restart of '${containerName}' failed: ${e.message}"
            results << "${containerName}: Error"
        }
    }

    boolean anyError = results.any { it.contains("Error") }
    String summary = "${anyError ? "일부 실패" : "OK"} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
    sendEvent(name: "dockerRestartStatus", value: summary)
}

/**
 * Reboots one or more Proxmox guests (LXC containers and/or QEMU VMs) via
 * the Proxmox VE REST API. Uses API token auth
 * (Authorization: PVEAPIToken=user@realm!tokenid=secret), which does not
 * require a CSRF token, unlike cookie/ticket-based auth. Proxmox's default
 * certificate is self-signed, so SSL verification is disabled for this
 * call (ignoreSSLIssues) — keep this endpoint LAN-only.
 *
 * LXC and QEMU guests use different API paths (/lxc/{id}/... vs
 * /qemu/{id}/...), so each list is processed against its own endpoint.
 */
def restartProxmoxGuests() {
    if (!proxmoxHost || !proxmoxApiToken || !proxmoxNode || (!proxmoxLxcIds && !proxmoxVmIds)) {
        log.warn "Proxmox restart is enabled but not fully configured (host/apiToken/node + at least one of lxcIds/vmIds required)"
        sendEvent(name: "proxmoxRestartStatus", value: "Not configured")
        return
    }

    List<String> results = []

    List<String> lxcIds = (proxmoxLxcIds ?: "").split(",").collect { it.trim() }.findAll { it }
    lxcIds.each { vmid -> results << rebootProxmoxGuest("lxc", vmid) }

    List<String> vmIds = (proxmoxVmIds ?: "").split(",").collect { it.trim() }.findAll { it }
    vmIds.each { vmid -> results << rebootProxmoxGuest("qemu", vmid) }

    boolean anyError = results.any { it.contains("Error") }
    String summary = "${anyError ? "일부 실패" : "OK"} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
    sendEvent(name: "proxmoxRestartStatus", value: summary)
}

private String rebootProxmoxGuest(String guestType, String vmid) {
    String label = "${guestType}:${vmid}"
    String uri = "${proxmoxHost}/api2/json/nodes/${proxmoxNode}/${guestType}/${vmid}/status/reboot"

    Map params = [
        uri            : uri,
        headers        : ["Authorization": "PVEAPIToken=${proxmoxApiToken}"],
        body           : "",
        timeout        : 15,
        ignoreSSLIssues: true
    ]

    try {
        String result = "${label}: Error"
        httpPost(params) { resp ->
            if (resp.status == 200) {
                log.info "Proxmox ${guestType.toUpperCase()} ${vmid} reboot triggered"
                result = "${label}: OK"
            } else {
                log.warn "Proxmox ${guestType.toUpperCase()} ${vmid} reboot returned unexpected status: ${resp.status}"
                result = "${label}: Error ${resp.status}"
            }
        }
        return result
    } catch (Exception e) {
        log.warn "Proxmox ${guestType.toUpperCase()} ${vmid} reboot failed: ${e.message}"
        return "${label}: Error"
    }
}

/**
 * Starts or stops the cloudflared (Cloudflare Tunnel) container via the
 * Portainer API, based on the WAN state transition. Called automatically
 * from processIp() whenever wanState flips between Primary and Failover
 * (not on every poll — only on an actual transition), and can also be
 * called manually for testing by passing "Primary" or "Failover".
 *
 * Intent: cloudflared stays stopped while on the primary WAN (which has
 * a real public IP and works fine with direct DDNS + port forwarding),
 * and is started only while on the CGNAT'd 5G backup WAN (where inbound
 * connections can't reach the router directly, so an outbound tunnel is
 * required instead).
 */
def toggleCloudflaredTunnel(String wanStateNow) {
    if (!portainerUrl || !portainerApiKey || !cloudflaredContainerName) {
        log.warn "Cloudflare Tunnel toggle is enabled but not fully configured (portainerUrl/portainerApiKey/cloudflaredContainerName required)"
        sendEvent(name: "cloudflaredStatus", value: "Not configured")
        return
    }

    String action = (wanStateNow == "Failover") ? "start" : "stop"
    String endpointId = portainerEndpointId ?: "1"
    String uri = "${portainerUrl}/api/endpoints/${endpointId}/docker/containers/${cloudflaredContainerName}/${action}"

    Map params = [
        uri    : uri,
        headers: ["X-API-Key": portainerApiKey],
        body   : "",
        timeout: 15
    ]

    try {
        httpPost(params) { resp ->
            // Docker Engine API returns 204 No Content on success, 304 if already in that state
            if (resp.status == 204 || resp.status == 304 || resp.status == 200) {
                log.info "cloudflared ${action} triggered (WAN state: ${wanStateNow})"
                sendEvent(name: "cloudflaredStatus", value: "${action == "start" ? "Started" : "Stopped"} (${new Date().format('HH:mm:ss')})")
            } else {
                log.warn "cloudflared ${action} returned unexpected status: ${resp.status}"
                sendEvent(name: "cloudflaredStatus", value: "Error: status ${resp.status}")
            }
        }
    } catch (Exception e) {
        log.warn "cloudflared ${action} failed: ${e.message}"
        sendEvent(name: "cloudflaredStatus", value: "Error: ${e.message}")
    }
}
