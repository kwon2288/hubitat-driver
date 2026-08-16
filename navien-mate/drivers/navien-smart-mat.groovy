/**
 * Navien Smart 숙면매트 (브리지 연동 v2)
 *
 * v1(자체 로그인+REST 제어)에서 이관: 나비엔 계정은 세션이 1개뿐이라 Hubitat이 직접
 * 로그인하면 브리지와 세션을 뺏고 뺏긴다. 그래서 이 버전은 나비엔 클라우드에 전혀
 * 직접 접속하지 않는다 — 전부 로컬 브리지를 거친다.
 *
 *   상태: 브리지가 AWS IoT MQTT를 구독해 로컬 브로커에 재발행한 걸 이 드라이버가 구독.
 *   제어: 이 드라이버가 브리지의 로컬 HTTP(/control)를 호출하면, 브리지가 실제 나비엔
 *         REST control로 중계.
 *
 * 브리지: https://github.com/kwon2288/hubitat-driver (navien-hubitat-bridge)
 * 원본 HA 통합: https://github.com/ripe-avocado/navien_smart_ha
 */
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final int MODE_POWER_OFF = 0
@Field static final int MODE_HEAT = 1
@Field static final int LEVEL_STANDBY = 0

metadata {
    definition(name: "Navien Smart 숙면매트", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
        capability "Refresh"
        capability "Initialize"

        attribute "mqttStatus", "string"
        attribute "zones", "string"
        attribute "single_level", "number"
        attribute "single_levelLabel", "string"
        attribute "left_level", "number"
        attribute "left_levelLabel", "string"
        attribute "right_level", "number"
        attribute "right_levelLabel", "string"

        command "setHeatLevel", [
            [name: "zone*", type: "ENUM", constraints: ["single", "left", "right"]],
            [name: "level*", type: "NUMBER", description: "0(운전 대기) ~ 8단계"]
        ]
    }

    preferences {
        input name: "bridgeHost", type: "text", title: "브리지 호스트/IP", required: true
        input name: "bridgePort", type: "number", title: "브리지 HTTP 포트", defaultValue: 8099, required: true
        input name: "mqttHost", type: "text", title: "MQTT 브로커 호스트 (Hubitat 내장 브로커 IP 등)", required: true
        input name: "mqttPort", type: "number", title: "MQTT 포트", defaultValue: 1883, required: true
        input name: "mqttUsername", type: "text", title: "MQTT 사용자명 (선택)", required: false
        input name: "mqttPassword", type: "password", title: "MQTT 비밀번호 (선택)", required: false
        input name: "mqttPrefix", type: "text", title: "MQTT 토픽 프리픽스", defaultValue: "navien", required: true
        input name: "logEnable", type: "bool", title: "디버그 로그 남기기", defaultValue: true
    }
}

// ── 라이프사이클 ───────────────────────────────────────────────────────────

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unschedule()
    discoverDevice()
    connectMqtt()
}

def uninstalled() {
    try { interfaces.mqtt.disconnect() } catch (Exception ignored) { }
}

def refresh() {
    discoverDevice()
}

// ── MQTT (브리지가 재발행한 상태 구독) ────────────────────────────────────

private void connectMqtt() {
    try {
        try { interfaces.mqtt.disconnect() } catch (Exception ignored) { }
        String uri = "tcp://${mqttHost}:${mqttPort}"
        String clientId = "hubitat-navien-${device.id}"
        interfaces.mqtt.connect(uri, clientId, mqttUsername ?: null, mqttPassword ?: null)
        pauseExecution(500)

        String deviceId = state.device?.deviceId ?: "+"
        String topic = "${mqttPrefix}/mate/${deviceId}/state"
        interfaces.mqtt.subscribe(topic)

        sendEvent(name: "mqttStatus", value: "connected")
        if (logEnable) log.debug "MQTT 접속 및 구독: ${topic}"
    } catch (Exception e) {
        log.warn "MQTT 접속 실패: ${e.message}"
        sendEvent(name: "mqttStatus", value: "실패: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

// Hubitat 플랫폼이 MQTT 연결 상태 변화 시 자동 호출한다.
def mqttClientStatus(String status) {
    if (logEnable) log.debug "MQTT 상태 변화: ${status}"
    if (status?.startsWith("Error") || status?.startsWith("Connection lost")) {
        sendEvent(name: "mqttStatus", value: "재접속 대기")
        runIn(15, "connectMqtt")
    }
}

// Hubitat 플랫폼이 구독 메시지 수신 시 자동 호출한다.
def parse(String description) {
    Map msg
    try {
        msg = interfaces.mqtt.parseMessage(description)
    } catch (Exception e) {
        log.warn "MQTT 메시지 파싱 실패: ${e.message}"
        return
    }
    if (!msg?.topic) return
    if (logEnable) log.debug "MQTT 수신: ${msg.topic}"

    if (msg.topic.endsWith("/state")) {
        handleStateMessage(msg.payload)
    }
}

private void handleStateMessage(String payloadJson) {
    def reported
    try {
        reported = new JsonSlurper().parseText(payloadJson)
    } catch (Exception e) {
        log.warn "상태 메시지 파싱 실패: ${e.message}"
        return
    }

    def dev = state.device
    if (!dev) return

    Integer mode = reported?.operationMode as Integer
    if (mode != null) {
        sendEvent(name: "switch", value: (mode == MODE_POWER_OFF) ? "off" : "on")
    }

    def heater = reported?.heater ?: [:]
    (dev.zones as List).each { zone ->
        def z = heater[zone]
        if (z == null) return
        Integer lvl = z?.level?.set as Integer
        if (lvl == null) return
        sendEvent(name: "${zone}_level", value: lvl)
        sendEvent(name: "${zone}_levelLabel", value: levelLabel(lvl))
    }
}

// ── 전원 (Switch capability) — 낙관적 표시 후 실제 상태는 MQTT가 덮어씀 ──

def on() {
    sendControl([operationMode: MODE_HEAT])
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendControl([operationMode: MODE_POWER_OFF])
    sendEvent(name: "switch", value: "off")
}

// ── 난방 단계 ──────────────────────────────────────────────────────────────

def setHeatLevel(String zone, BigDecimal level) {
    def dev = state.device
    if (!dev) {
        log.warn "기기 정보가 없습니다. refresh() 를 먼저 실행하세요."
        return
    }
    if (!(zone in (dev.zones as List))) {
        log.warn "이 기기에 없는 구역입니다: ${zone} (지원 구역: ${dev.zones})"
        return
    }

    int lvl = level as int
    int rangeMin = (dev.rangeMin ?: 1) as int
    int rangeMax = (dev.rangeMax ?: 8) as int
    if (lvl != LEVEL_STANDBY && (lvl < rangeMin || lvl > rangeMax)) {
        log.warn "단계 범위를 벗어났습니다: ${lvl} (허용 ${rangeMin}~${rangeMax}, 0=운전 대기)"
        return
    }

    boolean enabled = lvl > LEVEL_STANDBY
    sendControl([heater: [(zone): [enable: enabled, level: [set: lvl]]]])

    sendEvent(name: "${zone}_level", value: lvl)
    sendEvent(name: "${zone}_levelLabel", value: levelLabel(lvl))
    if (zone == "single") {
        sendEvent(name: "switch", value: enabled ? "on" : "off")
    }
}

private String levelLabel(int level) {
    return level == LEVEL_STANDBY ? "운전 대기" : "${level}단계"
}

// ── 기기 검색 (브리지 HTTP에서 등록정보만 받는다) ─────────────────────────

def discoverDevice() {
    def result
    try {
        result = bridgeGet("/devices")
    } catch (Exception e) {
        log.warn "브리지에서 기기 목록을 못 받았습니다: ${e.message}"
        return
    }

    List mats = (result instanceof List) ? result : []
    if (!mats) {
        log.warn "브리지에 등록된 숙면매트가 없습니다."
        return
    }
    if (mats.size() > 1 && logEnable) {
        log.debug "숙면매트가 ${mats.size()}대 있습니다. v2는 첫 번째 기기만 씁니다: ${mats[0]?.modelName}"
    }

    def dev = mats[0]
    state.device = [
        deviceSeq  : dev.deviceSeq,
        deviceId   : dev.deviceId,
        serviceCode: dev.serviceCode,
        modelCode  : dev.modelCode,
        modelName  : dev.modelName,
        zones      : dev.zones,
        unit       : dev.unit,
        rangeMin   : dev.rangeMin,
        rangeMax   : dev.rangeMax
    ]

    sendEvent(name: "zones", value: JsonOutput.toJson(dev.zones))
    if (logEnable) log.debug "기기 정보(브리지): ${state.device}"

    // 검색 뒤 deviceId가 바뀌었을 수 있으니 구독 토픽을 갱신한다.
    connectMqtt()
}

// ── 제어 요청 (브리지 로컬 HTTP로 위임 — 나비엔 클라우드 직접 호출 안 함) ──

private void sendControl(Map desiredExtra) {
    def dev = state.device
    if (!dev) {
        log.warn "기기 정보가 없습니다. refresh() 를 먼저 실행하세요."
        return
    }
    def result
    try {
        result = bridgePost("/control", [deviceId: dev.deviceId, desired: desiredExtra])
    } catch (Exception e) {
        log.warn "브리지 제어 요청 실패: ${e.message}"
        return
    }
    if (result?.ok != true) {
        log.warn "제어 실패: ${result}"
    } else if (logEnable) {
        log.debug "제어 전송 성공: ${desiredExtra}"
    }
}

// ── 브리지 HTTP 클라이언트 ────────────────────────────────────────────────

private def bridgeGet(String path) {
    return bridgeRequest("GET", path, null)
}

private def bridgePost(String path, Map body) {
    return bridgeRequest("POST", path, body)
}

private def bridgeRequest(String method, String path, Map body) {
    String uri = "http://${bridgeHost}:${bridgePort}${path}"
    Map params = [uri: uri, timeout: 15, textParser: true]
    if (body != null) {
        params.requestContentType = "application/json"
        params.body = JsonOutput.toJson(body)
    }

    def result = null
    if (method == "GET") {
        httpGet(params) { resp -> result = new JsonSlurper().parseText(resp.data.text) }
    } else {
        httpPost(params) { resp -> result = new JsonSlurper().parseText(resp.data.text) }
    }
    return result
}
