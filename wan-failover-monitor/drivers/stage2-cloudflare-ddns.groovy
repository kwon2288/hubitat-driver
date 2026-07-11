/**
 *  Public IP / WAN Failover Monitor — Stage 2: + Cloudflare DDNS
 *
 *  Builds on Stage 1 by pushing the new public IP straight to a
 *  Cloudflare DNS A record whenever it changes, independent of DSM's
 *  (or any router's) own DDNS client.
 *
 *  This is part 2 of a 3-part driver series:
 *    Stage 1 - WAN IP detection + Primary/Failover state
 *    Stage 2 (this file) - + Cloudflare DDNS auto-update
 *    Stage 3 - + Portainer container restart on IP change
 *
 *  Attributes:
 *    - publicIP    (string)  current public IP address
 *    - wanState    (enum)    "Primary", "Failover", or "Unknown"
 *    - lastChecked (string)  timestamp of last successful check
 *    - ddnsStatus  (string)  result of the last Cloudflare update attempt
 *
 *  Author: kwon2288
 *  License: Apache License 2.0
 */

import groovy.json.JsonOutput

metadata {
    definition(name: "Public IP WAN Monitor - Stage 2", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "Refresh"

        attribute "publicIP", "string"
        attribute "wanState", "enum", ["Primary", "Failover", "Unknown"]
        attribute "lastChecked", "string"
        attribute "ddnsStatus", "string"

        command "refresh"
        command "forceUpdateDns"
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

        input name: "logEnable", type: "bool", title: "디버그 로그 활성화", defaultValue: false
    }
}

def installed() {
    log.info "Public IP WAN Monitor (Stage 2) installed"
    initialize()
}

def updated() {
    log.info "Public IP WAN Monitor (Stage 2) preferences updated"
    initialize()
}

def initialize() {
    unschedule()
    Integer interval = (pollIntervalMinutes ?: 5) as Integer
    if (interval < 1) interval = 1

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
