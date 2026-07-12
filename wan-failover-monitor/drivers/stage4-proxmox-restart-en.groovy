/**
 *  Public IP / WAN Failover Monitor — Full Pipeline
 *
 *  Polls api.ipify.org for the current public IP, detects Primary/Failover
 *  WAN state, pushes changes to a Cloudflare DNS A record, and (after
 *  configurable delays, in seconds) can restart:
 *    - one or more Docker containers via the Portainer API, and/or
 *    - one or more Proxmox guests (LXC containers and/or QEMU VMs) via
 *      the Proxmox VE API
 *  e.g. to fix a reverse proxy (NPM) or a standalone service (an MCP
 *  server running in its own LXC/VM) that gets stuck on the old public IP
 *  after a WAN failover.
 *
 *  Attributes:
 *    - publicIP             (string)  current public IP address
 *    - wanState              (enum)    "Primary", "Failover", or "Unknown"
 *    - lastChecked           (string)  timestamp of last successful check
 *    - ddnsStatus            (string)  result of the last Cloudflare update attempt
 *    - dockerRestartStatus   (string)  result of the last Portainer restart attempt(s)
 *    - proxmoxRestartStatus  (string)  result of the last Proxmox LXC/VM reboot attempt(s)
 *
 *  Author: kwon2288
 *  License: Apache License 2.0
 */

import groovy.json.JsonOutput

metadata {
    definition(name: "Public IP WAN Monitor (EN)", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "Refresh"

        attribute "publicIP", "string"
        attribute "wanState", "enum", ["Primary", "Failover", "Unknown"]
        attribute "lastChecked", "string"
        attribute "ddnsStatus", "string"
        attribute "dockerRestartStatus", "string"
        attribute "proxmoxRestartStatus", "string"
        attribute "cloudflaredStatus", "string"
        attribute "cloudflareRecords", "string"

        command "refresh"
        command "forceUpdateDns"
        command "switchCloudflareToTunnel"
        command "addCloudflareRecord", [[name: "name", type: "STRING", description: "Hostname, e.g. nas.example.com"], [name: "recordId", type: "STRING", description: "Cloudflare DNS Record ID"]]
        command "removeCloudflareRecord", [[name: "name", type: "STRING", description: "Hostname to remove"]]
        command "listCloudflareRecords"
        command "restartDockerContainer"
        command "restartProxmoxGuests"
        command "toggleCloudflaredTunnel", [[name: "wanStateNow", type: "STRING", description: "'Primary' or 'Failover' — determines start vs stop"]]
    }

    preferences {
        input name: "primaryWanPrefix", type: "string", title: "Primary WAN public IP prefix (e.g. 121.130)",
              description: "Leave blank to skip Primary/Failover classification and just log the IP.", required: false

        input name: "pollIntervalMinutes", type: "number", title: "Poll interval (minutes, minimum 1)",
              defaultValue: 5, required: true

        input name: "enableCloudflareDdns", type: "bool", title: "Enable Cloudflare DDNS auto-update", defaultValue: false

        input name: "cloudflareApiToken", type: "password", title: "Cloudflare API Token (Zone.DNS Edit permission)", required: false
        input name: "cloudflareZoneId", type: "string", title: "Cloudflare Zone ID", required: false
        input name: "cloudflareProxied", type: "bool", title: "Use Cloudflare Proxy (orange cloud) for the Primary A record", defaultValue: false
        input name: "cloudflarePrimaryTarget", type: "string", title: "CNAME target domain while Primary (e.g. example.com; leave blank to manage a direct A record with the current public IP instead)", required: false
        input name: "cloudflareTunnelId", type: "string", title: "Cloudflare Tunnel ID (used for the Failover CNAME switch; find it in the Zero Trust dashboard)", required: false

        input name: "enablePortainerRestart", type: "bool", title: "Restart Portainer container(s) on IP change", defaultValue: false
        input name: "portainerUrl", type: "string", title: "Portainer URL (e.g. http://192.168.x.x:9000)", required: false
        input name: "portainerApiKey", type: "password", title: "Portainer API Key", required: false
        input name: "portainerEndpointId", type: "string", title: "Portainer Endpoint ID", defaultValue: "1", required: false
        input name: "portainerContainerName", type: "string", title: "Container name(s) to restart (comma-separated, e.g. npm,mcp)", required: false
        input name: "restartDelaySeconds", type: "number", title: "Delay before restart after IP change (seconds)", defaultValue: 10

        input name: "enableProxmoxRestart", type: "bool", title: "Reboot Proxmox LXC/VM on IP change", defaultValue: false
        input name: "proxmoxHost", type: "string", title: "Proxmox host (e.g. https://192.168.x.x:8006)", required: false
        input name: "proxmoxApiToken", type: "password", title: "Proxmox API Token (format: user@realm!tokenid=secret)", required: false
        input name: "proxmoxNode", type: "string", title: "Proxmox node name", required: false
        input name: "proxmoxLxcIds", type: "string", title: "LXC VMID(s) to reboot (comma-separated, e.g. 101,105)", required: false
        input name: "proxmoxVmIds", type: "string", title: "QEMU VM VMID(s) to reboot (comma-separated, e.g. 201,202)", required: false
        input name: "proxmoxRestartDelaySeconds", type: "number", title: "Delay before Proxmox LXC/VM reboot after IP change (seconds)", defaultValue: 10

        input name: "enableCloudflaredToggle", type: "bool", title: "Auto start/stop Cloudflare Tunnel (cloudflared) on WAN state transition", defaultValue: false
        input name: "cloudflaredContainerName", type: "string", title: "cloudflared container name (reuses Portainer settings)", required: false

        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
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
    refreshCloudflareRecordsAttribute()
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
 * Returns the list of managed DNS record name/ID pairs. Records are
 * managed entirely via the addCloudflareRecord / removeCloudflareRecord
 * commands (stored in device state) — no size limit, unlike a text
 * preference field. The current list is also mirrored into the
 * cloudflareRecords attribute so it's visible in the device's Current
 * States without needing to check logs.
 */
private List<Map> getCloudflareRecordPairs() {
    return state.cloudflareRecords ?: []
}

private void refreshCloudflareRecordsAttribute() {
    List<Map> pairs = getCloudflareRecordPairs()
    String summary = pairs.isEmpty() ? "(none)" : "${pairs.collect { it.name }.join(', ')} (${pairs.size()} total)"
    sendEvent(name: "cloudflareRecords", value: summary)
}

/**
 * Adds (or updates, if the hostname already exists) a DNS record to manage.
 */
def addCloudflareRecord(String name, String recordId) {
    if (!state.cloudflareRecords) state.cloudflareRecords = []
    state.cloudflareRecords.removeAll { it.name == name }
    state.cloudflareRecords << [name: name, id: recordId]
    log.info "Added/updated Cloudflare record: ${name} -> ${recordId} (total managed: ${state.cloudflareRecords.size()})"
    refreshCloudflareRecordsAttribute()
}

def removeCloudflareRecord(String name) {
    if (!state.cloudflareRecords) {
        log.warn "No records to remove"
        refreshCloudflareRecordsAttribute()
        return
    }
    state.cloudflareRecords.removeAll { it.name == name }
    log.info "Removed Cloudflare record: ${name} (remaining: ${state.cloudflareRecords.size()})"
    refreshCloudflareRecordsAttribute()
}

def listCloudflareRecords() {
    refreshCloudflareRecordsAttribute()
    List<Map> pairs = getCloudflareRecordPairs()
    log.info "Configured Cloudflare records (${pairs.size()} total): ${pairs.collect { it.name }.join(', ')}"
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
    String summary = "${fields.modeLabel} ${anyError ? "(partial failure)" : ""} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
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
 * in the "container names to restart" field), which the Docker Engine API accepts
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
    String summary = "${anyError ? "partial failure" : "OK"} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
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
    String summary = "${anyError ? "partial failure" : "OK"} (${new Date().format('HH:mm:ss')}) - ${results.join(', ')}"
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
