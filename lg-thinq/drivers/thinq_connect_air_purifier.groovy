/**
 *  ThinQ Connect Air Purifier
 *  Based on jonozzz hubitat-thinqconnect framework
 *
 *  Changes:
 *  - pm1_0 → pm1.0 / pm2_5 → pm2.5
 *  - smell level converted from number (1-4) to text (Good/Normal/Bad/Very Bad)
 *  - Added odor, totalPollution, humidity sensors
 *  - Handles LG API typo: "oder" instead of "odor"
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Air Purifier", namespace: "jonozzz", author: "Custom") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "FanControl"

        attribute "currentState",        "string"
        attribute "airPurifierMode",     "string"
        attribute "airFlowSpeed",        "string"

        // ── Air Quality Sensors ───────────────────────────────
        attribute "pm1.0",               "number"   // PM1.0  (API key: PM1)
        attribute "pm2.5",               "number"   // PM2.5  (API key: PM2)
        attribute "pm10",                "number"   // PM10   (API key: PM10)
        attribute "pm1.0Level",          "string"
        attribute "pm2.5Level",          "string"
        attribute "pm10Level",           "string"

        attribute "smell",               "string"   // Odor level (API key: "oder" - LG typo)
        attribute "totalPollution",      "number"   // Total pollution index
        attribute "humidity",            "number"   // Humidity

        attribute "filterRemainPercent", "number"
        attribute "connectionStatus",    "string"
    }

    preferences {
        input name: "logLevel",    title: "Log Level",            type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
        input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
    }
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────

def installed() {
    logger("debug", "installed()")
    initialize()
}

def updated() {
    logger("debug", "updated()")
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
}

def initialize() {
    logger("debug", "initialize()")
    // Only the master device handles MQTT connection
    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()
        mqttConnectUntilSuccessful()
    }
    refresh()
}

// ── MQTT ──────────────────────────────────────────────────────────────────────

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")
    try {
        def mqtt = parent.retrieveMqttDetails()
        interfaces.mqtt.connect(
            mqtt.server, mqtt.clientId, null, null,
            tlsVersion: "1.2",
            privateKey: mqtt.privateKey,
            caCertificate: mqtt.caCertificate,
            clientCertificate: mqtt.certificate,
            cleanSession: true,
            ignoreSSLIssues: true
        )
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    } catch (e) {
        logger("warn", "Lost connection to MQTT, retrying in 15 seconds ${e}")
        runIn(15, "mqttConnectUntilSuccessful")
        return false
    }
}

def parse(message) {
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")
    parent.processMqttMessage(this, payload)
}

def mqttClientStatus(String message) {
    logger("debug", "mqttClientStatus(${message})")
    if (message.startsWith("Error:")) {
        logger("error", "MQTT Error: ${message}")
        try { interfaces.mqtt.disconnect() } catch (e) {}
        mqttConnectUntilSuccessful()
    }
}

// ── Refresh ───────────────────────────────────────────────────────────────────

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

// ── Switch ────────────────────────────────────────────────────────────────────

def on() {
    logger("debug", "on()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ airPurifierOperationMode: "POWER_ON" ]
    ])
    sendEvent(name: "switch", value: "on")
}

def off() {
    logger("debug", "off()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ airPurifierOperationMode: "POWER_OFF" ]
    ])
    sendEvent(name: "switch", value: "off")
}

// ── FanControl ────────────────────────────────────────────────────────────────

def setSpeed(String speed) {
    logger("debug", "setSpeed(${speed})")
    // Map Hubitat FanControl standard values to LG API values
    def speedMap = [
        "low"         : "LOW",
        "medium-low"  : "LOW_MID",
        "medium"      : "MID",
        "medium-high" : "HIGH_MID",
        "high"        : "HIGH",
        "auto"        : "AUTO",
        "on"          : "AUTO"
    ]
    def apiSpeed = speedMap[speed?.toLowerCase()] ?: speed?.toUpperCase()
    parent.sendDeviceCommand(getDeviceId(), [
        airFlow: [ windStrength: apiSpeed ]
    ])
    sendEvent(name: "speed", value: speed)
}

// ── State Parsing ─────────────────────────────────────────────────────────────

def processStateData(data) {
    logger("debug", "processStateData(${data})")
    if (!data) return
    // Handle API responses wrapped in a List
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // 1. Power state
    def opMode = data.operation?.airPurifierOperationMode
    if (opMode != null) {
        def isOn = (opMode == "POWER_ON")
        sendEvent(name: "switch", value: isOn ? "on" : "off")
        if (logDescText) log.info "${device.displayName} switch → ${isOn ? 'on' : 'off'}"
    }

    // 2. Run state and job mode
    if (data.runState?.currentState) {
        sendEvent(name: "currentState", value: data.runState.currentState)
    }
    if (data.airPurifierJobMode?.currentJobMode) {
        sendEvent(name: "airPurifierMode", value: cleanEnumValue(data.airPurifierJobMode.currentJobMode))
    }

    // 3. Fan speed — map LG API values back to Hubitat FanControl standard values
    if (data.airFlow?.windStrength != null) {
        def windStrength = data.airFlow.windStrength
        def hubitatSpeed = [
            "LOW"      : "low",
            "LOW_MID"  : "medium-low",
            "MID"      : "medium",
            "HIGH_MID" : "medium-high",
            "HIGH"     : "high",
            "AUTO"     : "auto"
        ][windStrength] ?: windStrength.toLowerCase()
        sendEvent(name: "speed",        value: hubitatSpeed)
        sendEvent(name: "airFlowSpeed", value: windStrength)
    }

    // 4. Air quality sensors
    if (data.airQualitySensor != null) {
        def s = data.airQualitySensor

        // Particulate matter readings
        if (s.PM1  != null) sendEvent(name: "pm1.0", value: s.PM1,  unit: "µg/m³")
        if (s.PM2  != null) sendEvent(name: "pm2.5", value: s.PM2,  unit: "µg/m³")
        if (s.PM10 != null) sendEvent(name: "pm10",  value: s.PM10, unit: "µg/m³")

        // Particulate matter level grades
        if (s.PM1Level  != null) sendEvent(name: "pm1.0Level", value: cleanEnumValue(s.PM1Level))
        if (s.PM2Level  != null) sendEvent(name: "pm2.5Level", value: cleanEnumValue(s.PM2Level))
        if (s.PM10Level != null) sendEvent(name: "pm10Level",  value: cleanEnumValue(s.PM10Level))

        // Smell level — LG API uses "oder" (typo for "odor")
        if (s.oder != null) sendEvent(name: "smell", value: convertSmellLevel(s.oder))

        // Total pollution index
        if (s.totalPollution != null) sendEvent(name: "totalPollution", value: s.totalPollution)

        // Humidity
        if (s.humidity != null) sendEvent(name: "humidity", value: s.humidity, unit: "%")

        if (logDescText) log.info "${device.displayName} PM1.0:${s.PM1} PM2.5:${s.PM2} PM10:${s.PM10} smell:${convertSmellLevel(s.oder)} total:${s.totalPollution}"
    }

    // 5. Filter remaining life — handle both "filterInfo" and legacy "filter" keys
    def filterPct = data.filterInfo?.filterRemainPercent ?: data.filter?.filterRemainPercent
    if (filterPct != null) {
        sendEvent(name: "filterRemainPercent", value: filterPct, unit: "%")
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

// Convert LG API odor number (1-4) to readable label
def convertSmellLevel(level) {
    def levelMap = [
        1: "Good",
        2: "Normal",
        3: "Bad",
        4: "Very Bad"
    ]
    return levelMap[level as int] ?: "Unknown (${level})"
}

// Convert SCREAMING_SNAKE_CASE enum values to Title Case for display
def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .replaceAll(/_/, " ")
        .toLowerCase()
        .split(' ')
        .collect { it.capitalize() }
        .join(' ')
}

private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx    = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}
