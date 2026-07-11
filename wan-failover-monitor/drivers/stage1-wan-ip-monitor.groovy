/**
 *  Public IP / WAN Failover Monitor — Stage 1: IP Detection Only
 *
 *  Polls api.ipify.org for the current public IP address and compares it
 *  against an expected "primary WAN" IP prefix to detect ISP failover
 *  (e.g. a UniFi 5G backup WAN taking over from the main line).
 *
 *  This is the base stage of a 3-part driver series:
 *    Stage 1 (this file) - WAN IP detection + Primary/Failover state
 *    Stage 2             - + Cloudflare DDNS auto-update
 *    Stage 3             - + Portainer container restart on IP change
 *
 *  Attributes:
 *    - publicIP    (string)  current public IP address
 *    - wanState    (enum)    "Primary", "Failover", or "Unknown"
 *    - lastChecked (string)  timestamp of last successful check
 *
 *  Author: kwon2288
 *  License: Apache License 2.0
 */

metadata {
    definition(name: "Public IP WAN Monitor - Stage 1", namespace: "kwon2288", author: "kwon2288") {
        capability "Sensor"
        capability "Refresh"

        attribute "publicIP", "string"
        attribute "wanState", "enum", ["Primary", "Failover", "Unknown"]
        attribute "lastChecked", "string"

        command "refresh"
    }

    preferences {
        input name: "primaryWanPrefix", type: "string", title: "메인 WAN 공인 IP 대역 (앞자리, 예: 121.130)",
              description: "비워두면 Failover 판단 없이 IP만 기록합니다.", required: false

        input name: "pollIntervalMinutes", type: "number", title: "폴링 주기 (분, 최소 1분)",
              defaultValue: 5, required: true

        input name: "logEnable", type: "bool", title: "디버그 로그 활성화", defaultValue: false
    }
}

def installed() {
    log.info "Public IP WAN Monitor (Stage 1) installed"
    initialize()
}

def updated() {
    log.info "Public IP WAN Monitor (Stage 1) preferences updated"
    initialize()
}

def initialize() {
    unschedule()
    Integer interval = (pollIntervalMinutes ?: 5) as Integer
    if (interval < 1) interval = 1

    // schedule(cron) doesn't support arbitrary minute intervals cleanly, so use a fixed-minute cron pattern instead
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
