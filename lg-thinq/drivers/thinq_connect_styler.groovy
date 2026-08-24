/**
 *  ThinQ Connect Styler
 *  Based on jonozzz hubitat-thinqconnect framework (thinq_connect_core.groovy)
 *
 *  API Spec:
 *  - operation.stylerOperationMode (write only)
 *      START | STOP | POWER_OFF | WAKE_UP
 *
 *  - runState.currentState (read only)
 *      POWER_OFF | INITIAL | RUNNING | PAUSE | COMPLETE | ERROR | DIAGNOSIS |
 *      NIGHT_DRY | RESERVED | PRESTEAM | PREHEAT | STEAM | STAY | COOLING |
 *      DRYING | END_COOLING | STERILIZE | RUNNING_END | FOTA | SLEEP
 *
 *  - timer (read only)
 *      remainHour | remainMinute | relativeHourToStop |
 *      relativeMinuteToStop | totalHour | totalMinute
 *
 *  - remoteControlEnable.remoteControlEnabled (read only, boolean)
 *
 *  - error
 *      WATER_LEAKS_ERROR        | DOOR_CLOSE_ERROR          | DOOR_OPEN_ERROR      |
 *      NEED_WATER_DRAIN         | STEAM_HEAT_ERROR          | NEED_WATER_REPLENISHMENT |
 *      WATER_LEVEL_SENSOR_ERROR | TEMPERATURE_SENSOR_ERROR  | LE_ERROR | LE2_ERROR
 *
 *  - notification push
 *      STYLING_IS_COMPLETE | ERROR_HAS_OCCURRED
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

// States where switch = "off"
@Field static final Set OFF_STATES = ["INITIAL", "POWER_OFF", "ERROR", "COMPLETE", "RUNNING_END"] as Set

metadata {
    definition(name: "ThinQ Connect Styler", namespace: "jonozzz", author: "Custom") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState",          "string"
        attribute "remainingTime",         "number"
        attribute "remainingTimeDisplay",  "string"
        attribute "finishTimeDisplay",     "string"
        attribute "remoteControlEnabled",  "string"
        attribute "error",                 "string"

        command "start"
        command "stop"
        command "powerOff"
    }

    preferences {
        input name: "pollInterval", title: "Polling Interval", type: "enum",
              options: ["0":"Off", "1":"1 min", "2":"2 min", "5":"5 min", "10":"10 min", "15":"15 min", "30":"30 min"],
              defaultValue: "5", required: false
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
    unschedule()

    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()
        mqttConnectUntilSuccessful()
    }

    schedulePoll()
    refresh()
}

// ── Polling ───────────────────────────────────────────────────────────────────

private schedulePoll() {
    def interval = settings.pollInterval ?: "5"
    if (interval == "0") {
        logger("info", "Polling disabled")
        return
    }
    switch (interval) {
        case "1":  runEvery1Minute("refresh");   break
        case "2":  runEvery2Minutes("refresh");  break
        case "5":  runEvery5Minutes("refresh");  break
        case "10": runEvery10Minutes("refresh"); break
        case "15": runEvery15Minutes("refresh"); break
        case "30": runEvery30Minutes("refresh"); break
    }
    logger("info", "Polling scheduled every ${interval} minute(s)")
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

// ── Switch / Commands ─────────────────────────────────────────────────────────

def on()  { start() }

def off() {
    logger("debug", "off()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "STOP" ]
    ])
    sendEvent(name: "switch",             value: "off")
    sendEvent(name: "currentState",       value: "PAUSE")
    // Clear stale time displays on manual stop
    sendEvent(name: "remainingTime",        value: 0,   unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: "--")
    sendEvent(name: "finishTimeDisplay",    value: "--")
}

def start() {
    logger("debug", "start()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "START" ]
    ])
    sendEvent(name: "switch",       value: "on")
    sendEvent(name: "currentState", value: "RUNNING")
}

def stop() {
    logger("debug", "stop()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "STOP" ]
    ])
    sendEvent(name: "switch",             value: "off")
    sendEvent(name: "currentState",       value: "PAUSE")
    sendEvent(name: "remainingTime",        value: 0,   unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: "--")
    sendEvent(name: "finishTimeDisplay",    value: "--")
}

def powerOff() {
    logger("debug", "powerOff()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "POWER_OFF" ]
    ])
    sendEvent(name: "switch",             value: "off")
    sendEvent(name: "currentState",       value: "POWER_OFF")
    sendEvent(name: "remainingTime",        value: 0,   unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: "--")
    sendEvent(name: "finishTimeDisplay",    value: "--")
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

    // 1. Run state & switch
    if (data.runState?.currentState != null) {
        def currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)

        def isOff    = OFF_STATES.contains(currentState)
        def switchVal = isOff ? "off" : "on"
        sendEvent(name: "switch", value: switchVal)
        if (logDescText) log.info "${device.displayName} State: ${currentState}, Switch: ${switchVal}"

        // Clear stale time when cycle ends
        if (isOff) {
            sendEvent(name: "finishTimeDisplay",    value: "--")
            sendEvent(name: "remainingTimeDisplay", value: "--")
            sendEvent(name: "remainingTime",        value: 0, unit: "seconds")
        }

        // Refresh after completion to get final state
        // (MQTT push on completion may not include full runState)
        if (currentState in ["COMPLETE", "RUNNING_END"]) {
            runIn(5, "refresh")
        }
    } else if (!data.runState && device.currentValue("switch") == "on") {
        // Push notification arrived without runState (e.g. STYLING_IS_COMPLETE)
        // Trigger refresh to get actual final state
        logger("debug", "No runState in payload — scheduling refresh for final state")
        runIn(5, "refresh")
    }

    // 2. Remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def enabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: enabled)
    }

    // 3. Remaining time
    def remainHour   = (data.timer?.remainHour   ?: 0) as int
    def remainMinute = (data.timer?.remainMinute ?: 0) as int
    def remainingSec = (remainHour * 3600) + (remainMinute * 60)

    sendEvent(name: "remainingTime",        value: remainingSec, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingSec))

    // Schedule a refresh just after cycle should complete
    // Handles cases where MQTT completion notification is missing
    if (remainingSec > 0 && device.currentValue("switch") == "on") {
        def refreshDelay = remainingSec + 30  // 30 second buffer after expected completion
        logger("debug", "Scheduling completion refresh in ${refreshDelay}s (remainingSec=${remainingSec})")
        runIn(refreshDelay, "refresh")
    } else if (remainingSec == 0 && device.currentValue("switch") == "on") {
        runIn(15, "refresh")
    }

    // 4. Finish time display
    if (remainingSec > 0) {
        Date finishTime = new Date()
        use(groovy.time.TimeCategory) {
            finishTime = finishTime + remainingSec.seconds
        }
        sendEvent(name: "finishTimeDisplay",
                  value: finishTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone))
    }

    // 5. Error
    if (data.error) {
        sendEvent(name: "error", value: convertErrorCode(data.error))
        if (logDescText) log.warn "${device.displayName} Error: ${data.error}"
    } else {
        sendEvent(name: "error", value: "none")
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

// Convert LG API error codes to readable messages
def convertErrorCode(code) {
    def errorMap = [
        "NEED_WATER_DRAIN"         : "Drain Tank Full",
        "NEED_WATER_REPLENISHMENT" : "Water Tank Empty",
        "WATER_LEAKS_ERROR"        : "Water Leakage",
        "WATER_LEVEL_SENSOR_ERROR" : "Water Level Sensor Error",
        "DOOR_CLOSE_ERROR"         : "Door Close Error",
        "DOOR_OPEN_ERROR"          : "Door Open Error",
        "STEAM_HEAT_ERROR"         : "Steam Heat Error",
        "TEMPERATURE_SENSOR_ERROR" : "Temperature Sensor Error",
        "LE_ERROR"                 : "LE Error",
        "LE2_ERROR"                : "LE2 Error",
        "NO_ERROR"                 : "none"
    ]
    return errorMap[code] ?: cleanEnumValue(code)
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

def convertSecondsToTime(int sec) {
    if (sec <= 0) return "00:00"
    long hours   = sec / 3600
    long minutes = (sec % 3600) / 60
    return String.format("%02d:%02d", hours, minutes)
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
