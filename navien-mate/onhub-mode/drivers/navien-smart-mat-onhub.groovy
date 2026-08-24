/**
 * Navien Smart 숙면매트 — 허브 단독 버전 (v3)
 *
 * Docker 브리지 없이 이 드라이버가 AWS IoT에 wss:// MQTT로 직접 접속해 실시간
 * 상태를 구독한다. 이건 Hubitat 공식 문서에 없는 동작(wss:// 지원)에 기대고
 * 있어서 허브 펌웨어 빌드에 따라 안 될 수 있다 — 그런 경우에도 제어(전원/
 * 단계)는 REST 경유로 정상 동작한다. 상태 실시간 반영만 안 될 뿐이다.
 *
 * 부모 앱 "Navien Smart 숙면매트 (Connector)" 가 로그인 세션/AWS 임시자격증명을
 * 관리하고, 이 드라이버에 자격증명과 제어 대행(REST)을 제공한다.
 *
 * 참고: https://github.com/ripe-avocado/navien_smart_ha (REST/AWS IoT 프로토콜)
 *       https://github.com/jlslate/hubitat-navien (wss:// 직접 접속 및 Host
 *       헤더 포트 서명 문제를 다른 나비엔 제품으로 먼저 검증한 참고 구현)
 */
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final String IOT_SERVICE = "iotdevicegateway"
@Field static final int MODE_POWER_OFF = 0
@Field static final int MODE_HEAT = 1
@Field static final int LEVEL_STANDBY = 0

metadata {
    definition(name: "Navien Smart 숙면매트 (On-Hub)", namespace: "kwon2288", author: "kwon2288") {
        capability "Switch"
        capability "Refresh"
        capability "Initialize"

        attribute "connection", "enum", ["connected", "connecting", "disconnected"]
        attribute "zones", "string"
        attribute "single_level", "number"
        attribute "single_levelLabel", "string"
        attribute "left_level", "number"
        attribute "left_levelLabel", "string"
        attribute "right_level", "number"
        attribute "right_levelLabel", "string"

        command "reconnect"
        command "setHeatLevel", [
            [name: "zone*", type: "ENUM", constraints: ["single", "left", "right"]],
            [name: "level*", type: "NUMBER", description: "0(운전 대기) ~ 8단계"]
        ]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "디버그 로그 남기기", defaultValue: true
    }
}

// ── 라이프사이클 ───────────────────────────────────────────────────────────

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unschedule()
    refreshRegistry()
    runIn(2, "connect")
    runEvery5Minutes("healthCheck")
}

def uninstalled() {
    disconnectMqtt()
}

def reconnect() {
    disconnectMqtt()
    runIn(2, "connect")
}

// 부모 앱이 AWS 자격증명을 갱신했을 때 호출한다 — 서명이 자격증명에 묶여
// 있어서 기존 연결을 버리고 새로 붙어야 한다.
def credentialsRefreshed() {
    if (logEnable) log.debug "부모 앱이 AWS 자격증명을 갱신함 — 재접속"
    reconnect()
}

def refresh() {
    refreshRegistry()
}

private void healthCheck() {
    if (!interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "헬스체크: MQTT 연결 끊김 — 재접속 시도"
        connect()
    }
}

// ── 기기 레지스트리 (부모 앱에서 받아옴 — 등록정보만, 실시간 상태 아님) ──

private String deviceId() { return getDataValue("deviceId") }

private void refreshRegistry() {
    def dev
    try {
        dev = parent?.getDeviceRegistry(deviceId())
    } catch (Exception e) {
        log.warn "부모 앱에서 기기 정보를 못 받았습니다: ${e.message}"
        return
    }
    if (!dev) {
        log.warn "부모 앱에 이 기기(${deviceId()}) 등록정보가 없습니다."
        return
    }
    state.device = dev
    sendEvent(name: "zones", value: JsonOutput.toJson(dev.zones))
    if (logEnable) log.debug "기기 정보(부모 앱): ${dev}"
}

// ── MQTT (AWS IoT에 직접 wss:// 접속) ─────────────────────────────────────

def connect() {
    Map creds
    try {
        creds = parent.getAwsCredentials()
    } catch (Exception e) {
        log.warn "부모 앱에서 AWS 자격증명을 못 받았습니다: ${e.message}"
        sendEvent(name: "connection", value: "disconnected")
        runIn(120, "connect")
        return
    }
    if (!creds?.accessKeyId || !creds?.secretKey || !creds?.sessionToken || !creds?.homeSeq) {
        log.warn "AWS 자격증명이 비어 있습니다 — 부모 앱 로그인 상태를 확인하세요."
        sendEvent(name: "connection", value: "disconnected")
        runIn(300, "connect")
        return
    }
    state.homeSeq = creds.homeSeq
    state.clientId = state.clientId ?: "${UUID.randomUUID()}-U${creds.userSeq}"

    sendEvent(name: "connection", value: "connecting")

    // Hubitat 내장 MQTT 클라이언트가 WebSocket 업그레이드 시 Host 헤더에 포트를
    // 붙이는 걸로 보인다(허브 빌드마다 다를 수 있음 — jlslate/hubitat-navien
    // 참고). 서명에 포트를 포함할지 실패하면 다음 시도에서 뒤집는다.
    boolean signWithPort = (state.signHostWithPort == null) ? true : (state.signHostWithPort as boolean)
    state.signHostWithPort = signWithPort
    String url = presignIotWebsocketUrl(creds, signWithPort)
    if (logEnable) {
        log.debug "AWS IoT 접속 시도: ${creds.endpoint} (호스트에 포트 포함 서명=${signWithPort})"
    }

    try {
        disconnectMqtt()
        interfaces.mqtt.connect(url, state.clientId, null, null, cleanSession: true)
    } catch (Exception e) {
        log.warn "MQTT 접속 실패: ${e.message}"
        connectFailed()
        return
    }
    runIn(3, "onConnectAttempt")
}

private void disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception ignored) { }
}

def onConnectAttempt() {
    if (!interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "MQTT 연결 확인 실패"
        connectFailed()
        return
    }
    state.connectFailures = 0
    sendEvent(name: "connection", value: "connected")
    if (logEnable) log.debug "AWS IoT 접속 성공"

    String topic = "${state.homeSeq}/mate/#"
    try {
        interfaces.mqtt.subscribe(topic)
        if (logEnable) log.debug "구독: ${topic}"
    } catch (Exception e) {
        log.warn "구독 실패: ${e.message}"
    }
    runIn(2, "requestInitialState")
}

private void connectFailed() {
    int failures = ((state.connectFailures ?: 0) as int) + 1
    state.connectFailures = failures
    // 다음 시도는 Host 헤더 포트 포함 여부를 뒤집어서 재시도한다 (허브 빌드마다
    // 다를 수 있어서 — jlslate/hubitat-navien 도 같은 이유로 이렇게 한다).
    state.signHostWithPort = !(state.signHostWithPort as boolean)
    sendEvent(name: "connection", value: "disconnected")
    int delay = failures >= 4 ? 300 : 30
    if (failures == 4) {
        log.warn "MQTT 접속에 ${failures}번 실패했습니다. 제어(전원/단계)는 REST로 계속 " +
                 "동작하고, 실시간 상태만 반영되지 않습니다. 계속 재시도합니다."
    }
    runIn(delay, "connect")
}

// Hubitat 플랫폼이 MQTT 연결 상태 변화 시 자동 호출한다.
def mqttClientStatus(String status) {
    if (logEnable) log.debug "MQTT 상태 변화: ${status}"
    if (status?.startsWith("Error") || status?.startsWith("Connection lost")) {
        sendEvent(name: "connection", value: "disconnected")
        runIn(15, "connect")
    }
}

private void requestInitialState() {
    // 빈 desired로 찔러서 현재 shadow 상태를 다시 올려달라고 요청한다.
    sendControl([:])
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
    if (!msg?.payload) return

    def event
    try {
        event = new JsonSlurper().parseText(msg.payload)
    } catch (Exception e) {
        if (logEnable) log.debug "MQTT payload JSON 파싱 실패: ${e.message}"
        return
    }

    // shadow 이벤트 안에 들어있는 topic 필드로 필터링한다 (실제 MQTT 토픽과는
    // 다른, AWS IoT shadow 자체의 topic 문자열이다).
    String shadowTopic = event?.topic ?: ""
    if (!shadowTopic.endsWith("/update/accepted")) return

    def reported = event?.payload?.state?.reported
    if (!(reported instanceof Map)) return

    handleReportedState(reported)
}

private void handleReportedState(Map reported) {
    def dev = state.device
    if (!dev) return

    Integer mode = reported?.operationMode as Integer
    if (mode != null) {
        sendEvent(name: "switch", value: (mode == MODE_POWER_OFF) ? "off" : "on")
    }

    def heater = reported?.heater ?: [:]
    (dev.zones as List)?.each { zone ->
        def z = heater[zone]
        if (z == null) return
        Integer lvl = z?.level?.set as Integer
        if (lvl == null) return
        sendEvent(name: "${zone}_level", value: lvl)
        sendEvent(name: "${zone}_levelLabel", value: levelLabel(lvl))
    }
}

// ── 전원/단계 제어 (REST — 부모 앱에 위임, MQTT 연결 여부와 무관하게 동작) ─

def on() {
    sendControl([operationMode: MODE_HEAT])
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendControl([operationMode: MODE_POWER_OFF])
    sendEvent(name: "switch", value: "off")
}

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

private void sendControl(Map desired) {
    def result
    try {
        result = parent.sendControl(deviceId(), desired)
    } catch (Exception e) {
        log.warn "부모 앱 제어 요청 실패: ${e.message}"
        return
    }
    if (result?.ok != true) {
        log.warn "제어 실패: ${result}"
    } else if (logEnable) {
        log.debug "제어 전송 성공: ${desired}"
    }
}

// ── AWS SigV4 (WebSocket 사전서명 경로) ───────────────────────────────────

private String presignIotWebsocketUrl(Map creds, boolean signHostWithPort) {
    String host = creds.endpoint
    String canonicalHost = signHostWithPort ? "${host}:443" : host
    String region = creds.region
    Date now = new Date()
    String amzDate = utcFormat("yyyyMMdd'T'HHmmss'Z'", now)
    String dateStamp = utcFormat("yyyyMMdd", now)
    String scope = "${dateStamp}/${region}/${IOT_SERVICE}/aws4_request"

    String canonicalQuery = [
        "X-Amz-Algorithm=AWS4-HMAC-SHA256",
        "X-Amz-Credential=" + uriEncode("${creds.accessKeyId}/${scope}"),
        "X-Amz-Date=${amzDate}",
        "X-Amz-Expires=86400",
        "X-Amz-SignedHeaders=host"
    ].join("&")

    String canonicalRequest = [
        "GET", "/mqtt", canonicalQuery, "host:${canonicalHost}", "", "host", sha256Hex("")
    ].join("\n")

    String stringToSign = [
        "AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)
    ].join("\n")

    byte[] signingKey = signatureKey(creds.secretKey as String, dateStamp, region)
    String signature = toHex(hmacSha256(signingKey, stringToSign))

    return "wss://${host}:443/mqtt?${canonicalQuery}&X-Amz-Signature=${signature}" +
           "&X-Amz-Security-Token=" + uriEncode(creds.sessionToken as String)
}

private byte[] signatureKey(String secret, String dateStamp, String region) {
    byte[] kDate = hmacSha256(("AWS4" + secret).getBytes("UTF-8"), dateStamp)
    byte[] kRegion = hmacSha256(kDate, region)
    byte[] kService = hmacSha256(kRegion, IOT_SERVICE)
    return hmacSha256(kService, "aws4_request")
}

private byte[] hmacSha256(byte[] key, String data) {
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.getBytes("UTF-8"))
}

private String sha256Hex(String data) {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256")
    return toHex(md.digest(data.getBytes("UTF-8")))
}

private String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder()
    bytes.each { b -> sb.append(String.format("%02x", b)) }
    return sb.toString()
}

private String uriEncode(String value) {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}

private String utcFormat(String pattern, Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat(pattern)
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
    return sdf.format(date)
}
