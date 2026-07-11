/**
 *  Public IP / WAN Failover Monitor — Stage 3: + Portainer / Proxmox Restart
 *
 *  Full pipeline: polls api.ipify.org for the current public IP, detects
 *  Primary/Failover WAN state, pushes changes to a Cloudflare DNS A record,
 *  and (after configurable delays) can restart:
 *    - one or more Docker containers via the Portainer API, and/or
 *    - one or more Proxmox LXC containers via the Proxmox VE API
 *  e.g. to fix a reverse proxy (NPM) or a standalone service (an MCP
 *  server running in its own LXC) that gets stuck on the old public IP
 *  after a WAN failover.
 *
 *  This is the final part of a 3-part driver series:
 *    Stage 1 - WAN IP detection + Primary/Failover state
 *    Stage 2 - + Cloudflare DDNS auto-update
 *    Stage 3 (this file) - + Portainer container restart / Proxmox LXC reboot
 *
 *  Attributes:
 *    - publicIP            (string)  current public IP address
 *    - wanState             (enum)    "Primary", "Failover", or "Unknown"
 *    - lastChecked          (string)  timestamp of last successful check
 *    - ddnsStatus           (string)  result of the last Cloudflare update attempt
 *    - dockerRestartStatus  (string)  result of the last Portainer restart attempt(s)
 *    - lxcRestartStatus     (string)  result of the last Proxmox LXC reboot attempt(s)
 *
 *  Author: kwon2288
 *  License: Apache License 2.0
 */

import groovy.json.JsonOutput

metadata {
    definition(name: "Public IP WAN Monitor - Stage 3", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "Refresh"

        attribute "publicIP", "string"
        attribute "wanState", "enum", ["Primary", "Failover", "Unknown"]
        attribute "lastChecked", "string"
        attribute "ddnsStatus", "string"
        attribute "dockerRestartStatus", "string"
        attribute "lxcRestartStatus", "string"

        command "refresh"
        command "forceUpdateDns"
        command "restartDockerContainer"
        command "restartProxmoxLxc"
    }

    preferences {
        input name: "primaryWanPrefix", type: "string", title: "메인 WAN 공인 IP 대역 (앞자리, 예: 121.130)",
              description: "비워두면 Failover 판단 없이 IP만 기록합니다.", required: false

        input name: "pollIntervalMinutes", type: "number", title: "폴링 주기 (분, 최소 1분)",
              defaultValue: 5, required: true

        input name: "enableCloudflareDdns", type: "bool", title: "Cloudflare DDNS 자동 업데이트 사용", defaultValue: false

        input name: "cloudflareApiToken", type: "password", title: "Cloudflare API Token (Zone.DNS Edit 권한)", required: false
        input name: "cloudflareZoneId", type: "string", title: "Cloudflare Zone ID", required: false
        input name: "cloudflareRecordId", type: "string", title: "Cloudflare DNS Record ID", required: false
        input name: "cloudflareRecordName", type: "string", title: "DNS 레코드 이름 (예: nas.yourdomain.com)", required: false
        input name: "cloudflareProxied", type: "bool", title: "Cloudflare Proxy(주황 구름) 사용", defaultValue: false

        input name: "enablePortainerRestart", type: "bool", title: "IP 변경 시 Portainer 컨테이너 재시작", defaultValue: false
        input name: "portainerUrl", type: "string", title: "Portainer URL (예: http://192.168.x.x:9000)", required: false
        input name: "portainerApiKey", type: "password", title: "Portainer API Key", required: false
        input name: "portainerEndpointId", type: "string", title: "Portainer Endpoint ID", defaultValue: "1", required: false
        input name: "portainerContainerName", type: "string", title: "재시작할 컨테이너 이름 (쉼표로 여러 개, 예: npm,mcp)", required: false
        input name: "restartDelaySeconds", type: "number", title: "IP 변경 후 재시작까지 대기 시간(초)", defaultValue: 10

        input name: "enableProxmoxRestart", type: "bool", title: "IP 변경 시 Proxmox LXC 재부팅", defaultValue: false
        input name: "proxmoxHost", type: "string", title: "Proxmox 호스트 (예: https://192.168.x.x:8006)", required: false
        input name: "proxmoxApiToken", type: "password", title: "Proxmox API Token (형식: user@realm!tokenid=secret)", required: false
        input name: "proxmoxNode", type: "string", title: "Proxmox 노드 이름", required: false
        input name: "proxmoxLxcIds", type: "string", title: "재부팅할 LXC VMID (쉼표로 여러 개, 예: 101,105)", required: false
        input name: "proxmoxRestartDelaySeconds", type: "number", title: "IP 변경 후 LXC 재부팅까지 대기 시간(초)", defaultValue: 10

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

        if (enableCloudflareDdns) {
            updateCloudflareDns(currentIp)
        }

        if (enablePortainerRestart) {
            Integer delaySec = (restartDelaySeconds ?: 10) as Integer
            if (logEnable) log.debug "Scheduling Docker container restart in ${delaySec}s"
            runIn(delaySec, "restartDockerContainer")
        }

        if (enableProxmoxRestart) {
            Integer proxDelaySec = (proxmoxRestartDelaySeconds ?: 10) as Integer
            if (logEnable) log.debug "Scheduling Proxmox LXC restart in ${proxDelaySec}s"
            runIn(proxDelaySec, "restartProxmoxLxc")
        }
    } else if (logEnable) {
        log.debug "Public IP unchanged: ${currentIp}"
    }

    sendEvent(name: "lastChecked", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    String prefix = primaryWanPrefix?.trim()
    if (prefix) {
        String newState = currentIp.startsWith(prefix) ? "Primary" : "Failover"
        String previousState = device.currentValue("wanState")

        sendEvent(name: "wanState", value: newState,
                  descriptionText: "WAN state is ${newState}",
                  isStateChange: (newState != previousState))

        if (newState != previousState) {
            log.info "WAN state changed: ${previousState} -> ${newState} (IP: ${currentIp})"
        }
    } else {
        sendEvent(name: "wanState", value: "Unknown", descriptionText: "No primary WAN prefix configured")
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
    if (!cloudflareApiToken || !cloudflareZoneId || !cloudflareRecordId || !cloudflareRecordName) {
        log.warn "Cloudflare DDNS is enabled but not fully configured (token/zoneId/recordId/recordName required)"
        sendEvent(name: "ddnsStatus", value: "Not configured")
        return
    }

    String uri = "https://api.cloudflare.com/client/v4/zones/${cloudflareZoneId}/dns_records/${cloudflareRecordId}"
    Map body = [
        type   : "A",
        name   : cloudflareRecordName,
        content: ip,
        ttl    : 1,                              // 1 = "Automatic" in Cloudflare
        proxied: (cloudflareProxied ?: false)
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
        httpPatch(params) { resp ->
            if (resp.status == 200 && resp.data?.success) {
                log.info "Cloudflare DNS record '${cloudflareRecordName}' updated to ${ip}"
                sendEvent(name: "ddnsStatus", value: "OK (${new Date().format('HH:mm:ss')})")
            } else {
                log.warn "Cloudflare update returned unexpected result: ${resp.data}"
                sendEvent(name: "ddnsStatus", value: "Error: unexpected response")
            }
        }
    } catch (Exception e) {
        log.warn "Cloudflare DNS update failed: ${e.message}"
        sendEvent(name: "ddnsStatus", value: "Error: ${e.message}")
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
            headers: ["X-API-Key": portainerApiKey, "Content-Length": "0"],
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
 * Reboots one or more Proxmox LXC containers via the Proxmox VE REST API.
 * Uses API token auth (Authorization: PVEAPIToken=user@realm!tokenid=secret),
 * which does not require a CSRF token, unlike cookie/ticket-based auth.
 * Proxmox's default certificate is self-signed, so SSL verification is
 * disabled for this call (ignoreSSLIssues) — keep this endpoint LAN-only.
 */
def restartProxmoxLxc() {
    if (!proxmoxHost || !proxmoxApiToken || !proxmoxNode || !proxmoxLxcIds) {
        log.warn "Proxmox LXC restart is enabled but not fully configured (host/apiToken/node/lxcIds required)"
        sendEvent(name: "lxcRestartStatus", value: "Not configured")
        return
    }

    List<String> vmids = proxmoxLxcIds.split(",").collect { it.trim() }.findAll { it }
    List<String> results = []

    vmids.each { vmid ->
        String uri = "${proxmoxHost}/api2/json/nodes/${proxmoxNode}/lxc/${vmid}/status/reboot"

        Map params = [
            uri             : uri,
            headers         : ["Authorization": "PVEAPIToken=${proxmoxApiToken}"],
            body            : "",
            timeout         : 15,
            ignoreSSLIssues : true
        ]

        try {
            httpPost(params) { resp ->
                if (resp.status == 200) {
                    log.info "Proxmox LXC ${vmid} reboot triggered"
                    results << "${vmid}: OK"
                } else {
                    log.warn "Proxmox LXC ${vmid} reboot returned unexpected status: ${resp.status}"
                    results << "${vmid}: Error ${resp.status}"
                }
            }
        } catch (Exception e) {
            log.warn "Proxmox LXC ${vmid} reboot failed: ${e.message}"
            results << "${vmid}: Error"
        }
    }

    boolean anyError = results.any { it.contains("Error") }
    String summary = "${anyError ? "일부 실패" : "OK"} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
    sendEvent(name: "lxcRestartStatus", value: summary)
}
