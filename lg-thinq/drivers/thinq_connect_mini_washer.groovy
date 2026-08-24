/**
 *  ThinQ Connect Mini Washer
 *  Based on jonozzz hubitat-thinqconnect framework (thinq_connect_core.groovy)
 *
 *  Same device type as DEVICE_WASHER but uses locationName: "MINI"
 *  Used for combo washers or WashTower units with a mini tub
 *
 *  API Spec:
 *  - operation.washerOperationMode (write only)
 *      START | STOP | POWER_OFF | WAKE_UP
 *
 *  - runState.currentState (read only)
 *      POWER_OFF | INITIAL | RUNNING | PAUSE | COMPLETE |
 *      ERROR | RESERVED | RINSING | SPINNING | DRYING | END
 *
 *  - timer (read only)
 *      remainHour | remainMinute | totalHour | totalMinute |
 *      relativeHourToStart | relativeMinuteToStart |
 *      relativeHourToStop  | relativeMinuteToStop
 *
 *  - remoteControlEnable.remoteControlEnabled (read only, boolean)
 *
 *  - error
 *      OUT_OF_BALANCE_ERROR | WATER_LEVEL_SENSOR_ERROR | DOOR_LOCK_ERROR |
 *      OVERFILL_ERROR | UNABLE_TO_LOCK_ERROR | POWER_FAIL_ERROR |
 *      TEMPERATURE_SENSOR_ERROR | WATER_DRAIN_ERROR | DOOR_OPEN_ERROR |
 *      WATER_SUPPLY_ERROR
 *
 *  - notification push
 *      WASHING_IS_COMPLETE | ERROR_DURING_WASHING
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

// States where switch = "off"
@Field static final Set OFF_STATES = ["POWER_OFF", "INITIAL", "END", "COMPLETE", "ERROR"] as Set

metadata {
    definition(name: "ThinQ Connect Mini Washer", namespace: "kwon2288", author: "Custom") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState",          "string"
        attribute "operationMode",         "string"
        attribute "remoteControlEnabled",  "string"
        attribute "remainingTime",         "number"
        attribute "remainingTimeDisplay",  "string"
        attribute "totalTime",             "number"
        attribute "totalTimeDisplay",      "string"
        attribute "runTime",               "number"
        attribute "runTimeDisplay",        "string"
        attribute "finishTimeDisplay",     "string"
        attribute "error",                 "string"

        // Timer attributes
        attribute "relativeHourToStart",   "number"
        attribute "relativeMinuteToStart", "number"
        attribute "relativeHourToStop",    "number"
        attribute "relativeMinuteToStop",  "number"

        command "start"
        command "stop"
        command "powerOff"
        command "setDelayStart", [[name:"hours", type:"NUMBER", description:"Delay start in hours"]]
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

// ── Switch / Commands ─────────────────────────────────────────────────────────

def on()  { start() }
def off() { stop() }

def start() {
    logger("debug", "start()")
    parent.sendDeviceCommand(getDeviceId(), [
        location : [ locationName: "MINI" ],
        operation: [ washerOperationMode: "START" ]
    ])
    sendEvent(name: "switch",       value: "on")
    sendEvent(name: "currentState", value: "RUNNING")
}

def stop() {
    logger("debug", "stop()")
    parent.sendDeviceCommand(getDeviceId(), [
        location : [ locationName: "MINI" ],
        operation: [ washerOperationMode: "STOP" ]
    ])
    sendEvent(name: "switch",       value: "off")
    sendEvent(name: "currentState", value: "PAUSE")
}

def powerOff() {
    logger("debug", "powerOff()")
    parent.sendDeviceCommand(getDeviceId(), [
        location : [ locationName: "MINI" ],
        operation: [ washerOperationMode: "POWER_OFF" ]
    ])
    sendEvent(name: "switch",             value: "off")
    sendEvent(name: "currentState",       value: "POWER_OFF")
    sendEvent(name: "remainingTime",        value: 0,   unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: "--")
    sendEvent(name: "finishTimeDisplay",    value: "--")
}

def setDelayStart(hours) {
    logger("debug", "setDelayStart(${hours})")
    parent.sendDeviceCommand(getDeviceId(), [
        location: [ locationName: "MINI" ],
        timer   : [ relativeHourToStart: hours as int ]
    ])
}

// ── State Parsing ─────────────────────────────────────────────────────────────

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return

    // API returns an array of location-based entries
    // Find the MINI location entry; fall back to first entry if not found
    def entry = null
    if (data instanceof List) {
        entry = data.find { it?.location?.locationName == "MINI" }
        if (!entry) {
            logger("warn", "MINI location not found in response, using first entry")
            entry = data[0]
        }
    } else if (data instanceof Map) {
        entry = data
    }

    if (!entry) return
    logger("debug", "Mini wash entry: ${entry}")

    // 1. Current state & switch
    if (entry.runState?.currentState) {
        def currentState = entry.runState.currentState
        sendEvent(name: "currentState", value: currentState)

        def isOff     = OFF_STATES.contains(currentState)
        def switchVal = isOff ? "off" : "on"
        sendEvent(name: "switch", value: switchVal)

        // Clear stale time when cycle ends
        if (isOff) {
            sendEvent(name: "finishTimeDisplay",    value: "--")
            sendEvent(name: "remainingTimeDisplay", value: "--")
            sendEvent(name: "remainingTime",        value: 0, unit: "seconds")
        }

        if (logDescText) log.info "${device.displayName} State: ${currentState}, Switch: ${switchVal}"

        // Refresh after completion to catch cases where MQTT push has no state
        if (currentState in ["COMPLETE", "END"]) {
            runIn(5, "refresh")
        }
    } else if (!entry.runState && device.currentValue("switch") == "on") {
        // Push arrived without runState — refresh to get actual state
        logger("debug", "No runState in payload — scheduling refresh")
        runIn(5, "refresh")
    }

    // 2. Operation mode
    if (entry.operation?.washerOperationMode) {
        sendEvent(name: "operationMode", value: cleanEnumValue(entry.operation.washerOperationMode))
    }

    // 3. Remote control
    if (entry.remoteControlEnable?.remoteControlEnabled != null) {
        sendEvent(name: "remoteControlEnabled",
                  value: entry.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled")
    }

    // 4. Timer
    def remainHour   = (entry.timer?.remainHour   ?: 0) as int
    def remainMinute = (entry.timer?.remainMinute ?: 0) as int
    def totalHour    = (entry.timer?.totalHour    ?: 0) as int
    def totalMinute  = (entry.timer?.totalMinute  ?: 0) as int

    def remainingSec = (remainHour * 3600) + (remainMinute * 60)
    def totalSec     = (totalHour  * 3600) + (totalMinute  * 60)
    def runSec       = totalSec - remainingSec

    sendEvent(name: "remainingTime",        value: remainingSec, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingSec))
    sendEvent(name: "totalTime",            value: totalSec,     unit: "seconds")
    sendEvent(name: "totalTimeDisplay",     value: convertSecondsToTime(totalSec))
    sendEvent(name: "runTime",              value: runSec,       unit: "seconds")
    sendEvent(name: "runTimeDisplay",       value: convertSecondsToTime(runSec))

    // Finish time
    if (remainingSec > 0) {
        Date finishTime = new Date()
        use(groovy.time.TimeCategory) {
            finishTime = finishTime + remainingSec.seconds
        }
        sendEvent(name: "finishTimeDisplay",
                  value: finishTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone))

        // Schedule refresh after expected completion
        if (device.currentValue("switch") == "on") {
            runIn(remainingSec + 30, "refresh")
        }
    }

    // Delay timer
    if (entry.timer?.relativeHourToStart   != null) sendEvent(name: "relativeHourToStart",   value: entry.timer.relativeHourToStart)
    if (entry.timer?.relativeMinuteToStart != null) sendEvent(name: "relativeMinuteToStart", value: entry.timer.relativeMinuteToStart)
    if (entry.timer?.relativeHourToStop    != null) sendEvent(name: "relativeHourToStop",    value: entry.timer.relativeHourToStop)
    if (entry.timer?.relativeMinuteToStop  != null) sendEvent(name: "relativeMinuteToStop",  value: entry.timer.relativeMinuteToStop)

    // 5. Error
    if (entry.error) {
        sendEvent(name: "error", value: convertErrorCode(entry.error))
        if (logDescText) log.warn "${device.displayName} Error: ${entry.error}"
    } else {
        sendEvent(name: "error", value: "none")
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def convertErrorCode(code) {
    def errorMap = [
        "OUT_OF_BALANCE_ERROR"      : "Out Of Balance",
        "WATER_LEVEL_SENSOR_ERROR"  : "Water Level Sensor Error",
        "DOOR_LOCK_ERROR"           : "Door Lock Error",
        "OVERFILL_ERROR"            : "Overfill Error",
        "UNABLE_TO_LOCK_ERROR"      : "Unable To Lock",
        "POWER_FAIL_ERROR"          : "Power Fail",
        "TEMPERATURE_SENSOR_ERROR"  : "Temperature Sensor Error",
        "WATER_DRAIN_ERROR"         : "Water Drain Error",
        "DOOR_OPEN_ERROR"           : "Door Open",
        "WATER_SUPPLY_ERROR"        : "Water Supply Error",
        "NO_ERROR"                  : "none"
    ]
    return errorMap[code] ?: cleanEnumValue(code)
}

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
