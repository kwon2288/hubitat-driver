/**
 * Homey Bridge (Parent App)
 *
 * Pulls devices from a Homey Pro hub into Hubitat via Homey's Local REST API.
 * Requires a Homey Pro Personal Access Token (Homey app > Settings > Advanced > API Keys).
 * Homey Pro and Hubitat must be on the same local network.
 *
 * Companion driver: "Homey Generic Device" (namespace: kwon2288)
 *
 * Author: kwon2288
 */
definition(
    name: "Homey Bridge",
    namespace: "kwon2288",
    author: "kwon2288",
    description: "Bridge Homey Pro devices into Hubitat via Homey's Local API",
    category: "Integrations",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
}

mappings {
    path("/webhook/:homeyId/:capability") {
        action: [
            GET: "webhookHandler"
        ]
    }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Homey Bridge Setup", install: true, uninstall: true) {
        section("Homey Pro Connection") {
            input name: "homeyIp", type: "text", title: "Homey Pro IP Address", required: true
            input name: "homeyToken", type: "password", title: "Homey Personal Access Token", required: true
        }
        section("Update Mode") {
            input name: "updateMode", type: "enum", title: "Hubitat이 Homey 상태를 받아오는 방식",
                  options: ["polling": "폴링만 (기본, 추가 설정 없음)", "both": "폴링 + 웹훅", "webhook": "웹훅만"],
                  defaultValue: "polling", required: true, submitOnChange: true
            if ((updateMode ?: "both") in ["both", "polling"]) {
                input name: "pollInterval", type: "enum", title: "Poll Interval (minutes)",
                      options: ["1", "5", "10", "15", "30"], defaultValue: "5", required: true
            } else {
                paragraph "웹훅만 사용 중입니다. 저장/새로고침 시 최초 1회만 폴링하고, 이후로는 Homey Flow가 값을 보낼 때만 갱신됩니다."
            }
        }
        if (homeyIp && homeyToken) {
            section("Connection Test") {
                paragraph testConnection()
            }
            section("Devices") {
                href name: "toDevicesPage", page: "devicesPage",
                     title: "Select Homey Devices to Import",
                     description: "Tap to browse and select devices from Homey"
            }
        }
        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }
        section("Realtime Webhook") {
            if (state.accessToken) {
                def base = "http://${location.hub.localIP}/apps/api/${app.id}"
                paragraph "웹훅 URL 형식:<br><code>${base}/webhook/&lt;homeyId&gt;/&lt;capability&gt;?value=[[value]]&access_token=${state.accessToken}</code>"
                paragraph "Update Mode가 '웹훅만' 또는 '폴링 + 웹훅'일 때 Homey Flow에서 이 URL로 GET 요청을 보내면 즉시 상태가 반영됩니다. (엔드포인트 자체는 모드와 무관하게 항상 켜져 있습니다.)"
                if (getChildDevices()) {
                    paragraph "아래는 현재 등록된 기기별 homeyId입니다:"
                    getChildDevices().each { cd ->
                        paragraph "- ${cd.displayName}: <code>${cd.getDataValue('homeyId')}</code>"
                    }
                }
            } else {
                paragraph "저장 후 다시 열면 웹훅 URL이 표시됩니다. (Apps Code 페이지에서 이 앱의 OAuth가 활성화되어 있어야 합니다.)"
            }
        }
    }
}

def devicesPage() {
    def devices = getHomeyDevices()
    dynamicPage(name: "devicesPage", title: "Select Devices") {
        section {
            if (!devices) {
                paragraph "No devices found, or connection failed. Check IP/Token on the previous page."
            } else {
                input name: "selectedDevices", type: "enum",
                      title: "Homey devices (${devices.size()} found)",
                      options: devices.collectEntries { id, d -> [(id): (d?.name ?: id)] },
                      multiple: true, required: false, submitOnChange: false
            }
        }
    }
}

def installed() {
    log.info "Homey Bridge installed"
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.warn "createAccessToken failed - enable OAuth for this app in Apps Code first: ${e.message}"
        }
    }
    initialize()
}

def updated() {
    log.info "Homey Bridge updated"
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.warn "createAccessToken failed - enable OAuth for this app in Apps Code first: ${e.message}"
        }
    }
    initialize()
}

def initialize() {
    createChildDevices()
    unschedule()
    def mode = updateMode ?: "both"
    if (mode in ["both", "polling"]) {
        switch (pollInterval) {
            case "1":  runEvery1Minute("pollHomey"); break
            case "10": runEvery10Minutes("pollHomey"); break
            case "15": runEvery15Minutes("pollHomey"); break
            case "30": runEvery30Minutes("pollHomey"); break
            default:   runEvery5Minutes("pollHomey")
        }
        if (logEnable) log.debug "initialize: recurring polling scheduled (every ${pollInterval ?: 5} min)"
    } else {
        if (logEnable) log.debug "initialize: webhook-only mode, no recurring poll scheduled"
    }
    pollHomey() // one-time sync on save/startup regardless of mode
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ---------- Homey API helpers ----------

private Map authHeader() {
    return ["Authorization": "Bearer ${homeyToken}"]
}

private String homeyBase() {
    return "http://${homeyIp}/api/manager/devices/device"
}

def testConnection() {
    def devices = getHomeyDevices()
    if (devices == null) return "\u274c Connection failed \u2014 check IP and token."
    return "\u2705 Connected. ${devices.size()} device(s) found on Homey."
}

Map getHomeyDevices() {
    def result = null
    try {
        httpGet([uri: homeyBase() + "/", headers: authHeader(), timeout: 10]) { resp ->
            if (resp.status == 200) {
                result = resp.data
            } else {
                log.warn "Homey device list returned status ${resp.status}"
            }
        }
    } catch (e) {
        log.warn "getHomeyDevices error: ${e.message}"
    }
    if (logEnable) log.debug "getHomeyDevices: ${result ? result.size() : 0} device(s) retrieved"
    return result
}

// ---------- Child device management ----------

def createChildDevices() {
    if (!selectedDevices) return
    def homeyDevices = getHomeyDevices()
    if (!homeyDevices) return

    selectedDevices.each { id ->
        def dni = "homey-${id}"
        def existing = getChildDevice(dni)
        if (!existing) {
            def hd = homeyDevices[id]
            try {
                def cd = addChildDevice("kwon2288", "Homey Generic Device", dni, [
                    name: hd?.name ?: "Homey Device",
                    label: hd?.name ?: "Homey Device",
                    isComponent: false
                ])
                cd.updateDataValue("homeyId", id)
                if (logEnable) log.debug "Created child device for ${hd?.name} (${id})"
            } catch (e) {
                log.warn "Could not create child device for ${id}: ${e.message}"
            }
        }
    }

    // Remove children that are no longer selected
    getChildDevices().each { cd ->
        def id = cd.getDataValue("homeyId")
        if (id && !selectedDevices.contains(id)) {
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
}

// ---------- Polling (Homey -> Hubitat) ----------

def pollHomey() {
    if (logEnable) log.debug "pollHomey: starting poll cycle"
    def homeyDevices = getHomeyDevices()
    if (!homeyDevices) {
        log.warn "pollHomey: could not reach Homey"
        return
    }
    getChildDevices().each { cd ->
        def id = cd.getDataValue("homeyId")
        def hd = homeyDevices[id]
        if (logEnable) log.debug "pollHomey: child=${cd.deviceNetworkId} homeyId=${id} found=${hd != null} hasCapsObj=${hd?.capabilitiesObj != null}"
        if (hd?.capabilitiesObj) {
            cd.updateFromHomey(hd.capabilitiesObj)
        } else {
            log.warn "pollHomey: no capabilitiesObj for device ${id} (name=${hd?.name})"
        }
    }
}

// ---------- Called by child driver to send commands (Hubitat -> Homey) ----------

def sendCommand(String dni, String capability, value) {
    def cd = getChildDevice(dni)
    def id = cd?.getDataValue("homeyId")
    if (!id) {
        log.warn "sendCommand: no homeyId for ${dni}"
        return
    }
    def url = "${homeyBase()}/${id}/capability/${capability}/"
    def params = [
        uri: url,
        headers: authHeader(),
        body: [value: value],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: 10
    ]
    try {
        httpPut(params) { resp ->
            if (logEnable) log.debug "sendCommand ${capability}=${value} to ${id} -> status ${resp.status}, response=${resp.data}"
        }
    } catch (e) {
        log.warn "sendCommand ${capability}=${value} to ${id} FAILED: ${e.message}"
    }
}

def refreshDevice(String dni) {
    pollHomey()
}

// ---------- Realtime webhook (Homey Flow -> Hubitat) ----------
// Called via GET /apps/api/<app-id>/webhook/<homeyId>/<capability>?value=...&access_token=...

def webhookHandler() {
    def homeyId = params.homeyId
    def capability = params.capability
    def rawValue = params.value
    def value = parseWebhookValue(rawValue)

    def dni = "homey-${homeyId}"
    def cd = getChildDevice(dni)
    if (!cd) {
        log.warn "webhookHandler: no child device for homeyId ${homeyId}"
        render(contentType: "application/json", data: '{"status":"unknown device"}', status: 404)
        return
    }
    if (logEnable) log.debug "webhookHandler: ${dni} ${capability} raw='${rawValue}' -> ${value}"
    cd.updateFromHomey([(capability): [value: value]])
    render(contentType: "application/json", data: '{"status":"ok"}', status: 200)
}

private parseWebhookValue(String raw) {
    if (raw == null) return null
    if (raw == "true") return true
    if (raw == "false") return false
    if (raw.isNumber()) return raw.toBigDecimal()
    return raw
}
