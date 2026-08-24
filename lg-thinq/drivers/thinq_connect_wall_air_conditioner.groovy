/**
 *  ThinQ Connect Wall AC (벽걸이 에어컨)
 *  Based on jonozzz hubitat-thinqconnect framework (thinq_connect_core.groovy)
 *
 *  API Spec:
 *  - operation.airConOperationMode (read/write)
 *      POWER_ON | POWER_OFF
 *
 *  - airConJobMode.currentJobMode (read/write)
 *      COOL | HEAT | AUTO | FAN | AIR_DRY | ENERGY_SAVING
 *
 *  - airFlow.windStrength / windStrengthDetail (read/write)
 *      LOW | LOW_MID | MID | HIGH_MID | HIGH | POWER | AUTO
 *
 *  - windDirection (read/write)
 *      rotateUpDown    : boolean toggle (up/down swing on/off)
 *      rotateLeftRight : boolean toggle (left/right swing on/off)
 *      No angle level control available on wall-mounted models
 *
 *  - timer (read/write)
 *      relativeHourToStart | relativeHourToStop (minute must always be 0)
 *      absoluteHourToStart | absoluteMinuteToStart
 *      absoluteHourToStop  | absoluteMinuteToStop
 *      Send -1 to cancel relative timers
 *
 *  - display.displayLight (read/write) — model dependent
 *      DISPLAY_LIGHT_ON | DISPLAY_LIGHT_OFF
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Wall AC", namespace: "kwon2288", author: "Custom") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "Thermostat"

        attribute "currentState",                "string"
        attribute "currentJobMode",              "string"
        attribute "airConOperationMode",         "string"
        attribute "currentTemperature",          "number"
        attribute "targetTemperature",           "number"
        attribute "temperatureUnit",             "string"
        attribute "supportedThermostatModes",    "JSON_OBJECT"
        attribute "supportedThermostatFanModes", "JSON_OBJECT"
        attribute "windStrength",                "string"
        attribute "rotateUpDown",                "string"   // enabled / disabled
        attribute "rotateLeftRight",             "string"   // enabled / disabled
        attribute "displayLight",                "string"   // on / off
        attribute "powerSaveEnabled",            "string"
        attribute "filterRemainPercent",         "number"
        attribute "error",                       "string"

        // Timer attributes
        attribute "relativeHourToStart",         "number"
        attribute "relativeHourToStop",          "number"
        attribute "absoluteHourToStart",         "number"
        attribute "absoluteMinuteToStart",       "number"
        attribute "absoluteHourToStop",          "number"
        attribute "absoluteMinuteToStop",        "number"

        // ── Basic commands ─────────────────────────────────────
        command "start"
        command "stop"

        // ── Mode commands (POWER_ON + mode in one step) ────────
        command "setCoolMode"
        command "setHeatMode"
        command "setAutoMode"
        command "setFanMode"          // 송풍
        command "setAirDryMode"       // 제습
        command "setEnergySavingMode"

        // ── Fine control ───────────────────────────────────────
        command "setAirConJobMode", [[name:"mode", type:"ENUM",
            constraints:["COOL","HEAT","AUTO","FAN","AIR_DRY","ENERGY_SAVING"]]]
        command "setTargetTemperature", ["number"]
        command "setWindStrength", [[name:"strength", type:"ENUM",
            constraints:["LOW","LOW_MID","MID","HIGH_MID","HIGH","POWER","AUTO"]]]

        // Wind direction — toggle only (no angle level on wall-mount models)
        command "setRotateUpDown",    [[name:"enabled", type:"ENUM", constraints:["true","false"]]]
        command "setRotateLeftRight", [[name:"enabled", type:"ENUM", constraints:["true","false"]]]

        command "setDisplayLight", [[name:"state", type:"ENUM", constraints:["on","off"]]]
        command "setPowerSave",    [[name:"enabled", type:"ENUM", constraints:["true","false"]]]

        // ── Timers ─────────────────────────────────────────────
        command "setDelayStart",  [[name:"hours",    type:"NUMBER", description:"Hours until start"]]
        command "setDelayStop",   [[name:"hours",    type:"NUMBER", description:"Hours until stop"]]
        command "unsetStartTimer"
        command "unsetStopTimer"
        command "setAbsoluteStart", [[name:"timeHHmm", type:"NUMBER", description:"HHmm format (e.g. 2130 = 9:30 PM)"]]
        command "setAbsoluteStop",  [[name:"timeHHmm", type:"NUMBER", description:"HHmm format (e.g. 2130 = 9:30 PM)"]]
    }

    preferences {
        input name: "isFahrenheit", type: "bool",   title: "<b>Fahrenheit</b>", description: "<i>Use fahrenheit degrees</i>", defaultValue: false
        input name: "logLevel",     title: "Log Level",            type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
        input name: "logDescText",  title: "Log Description Text", type: "bool", defaultValue: false, required: false
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

    sendEvent(name: "supportedThermostatModes",    value: groovy.json.JsonOutput.toJson(["off","heat","cool","auto","emergency heat"]))
    sendEvent(name: "supportedThermostatFanModes", value: groovy.json.JsonOutput.toJson(["auto","circulate","on"]))
    if (device.currentValue("thermostatMode")           == null) sendEvent(name: "thermostatMode",           value: "off")
    if (device.currentValue("thermostatOperatingState") == null) sendEvent(name: "thermostatOperatingState", value: "idle")
    if (device.currentValue("thermostatFanMode")        == null) sendEvent(name: "thermostatFanMode",        value: "auto")

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

// ── Basic Switch ──────────────────────────────────────────────────────────────

def on()  { start() }
def off() { stop() }

def start() {
    logger("debug", "start()")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airConOperationMode: "POWER_ON"]])
    sendEvent(name: "switch", value: "on")
}

def stop() {
    logger("debug", "stop()")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airConOperationMode: "POWER_OFF"]])
    sendEvent(name: "switch", value: "off")
}

// ── Mode Commands (POWER_ON + mode in sequence) ───────────────────────────────

def setCoolMode() {
    logger("debug", "setCoolMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "COOL"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Cool")
}

def setHeatMode() {
    logger("debug", "setHeatMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "HEAT"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Heat")
}

def setAutoMode() {
    logger("debug", "setAutoMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "AUTO"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Auto")
}

def setFanMode() {
    // 송풍 — fan only, no cooling/heating
    logger("debug", "setFanMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "FAN"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Fan")
}

def setAirDryMode() {
    // 제습 — dehumidification mode
    logger("debug", "setAirDryMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "AIR_DRY"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Air Dry")
}

def setEnergySavingMode() {
    logger("debug", "setEnergySavingMode()")
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_ON"]])
    pauseExecution(500)
    parent.sendDeviceCommand(deviceId, [airConJobMode: [currentJobMode: "ENERGY_SAVING"]])
    sendEvent(name: "switch",         value: "on")
    sendEvent(name: "currentJobMode", value: "Energy Saving")
}

// ── setAirConJobMode (manual, assumes AC already on) ─────────────────────────

def setAirConJobMode(mode) {
    logger("debug", "setAirConJobMode(${mode})")
    parent.sendDeviceCommand(getDeviceId(), [airConJobMode: [currentJobMode: mode]])
}

// ── Thermostat capability ─────────────────────────────────────────────────────

def setThermostatMode(mode) {
    logger("debug", "setThermostatMode(${mode})")
    switch (mode) {
        case "off":            stop();         break
        case "heat":           setHeatMode();  break
        case "cool":           setCoolMode();  break
        case "auto":           setAutoMode();  break
        case "emergency heat": setHeatMode();  break
        default: logger("warn", "setThermostatMode: unknown mode '${mode}'")
    }
}

def heat()          { setThermostatMode("heat") }
def cool()          { setThermostatMode("cool") }
def auto()          { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("emergency heat") }

def setThermostatFanMode(fanMode) {
    logger("debug", "setThermostatFanMode(${fanMode})")
    def strength = hubFanModeToLgWindStrength(fanMode)
    if (strength) setWindStrength(strength)
}

def fanAuto()      { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def fanOn()        { setThermostatFanMode("on") }

// ── Temperature ───────────────────────────────────────────────────────────────

def setTargetTemperature(temperature) {
    logger("debug", "setTargetTemperature(${temperature})")
    parent.sendDeviceCommand(getDeviceId(), [
        temperatureInUnits: [targetTemperature: temperature, unit: isFahrenheit ? "F" : "C"]
    ])
}

def setCoolingSetpoint(temperature) { setTargetTemperature(temperature) }
def setHeatingSetpoint(temperature) { setTargetTemperature(temperature) }

// ── Airflow ───────────────────────────────────────────────────────────────────

def setWindStrength(strength) {
    logger("debug", "setWindStrength(${strength})")
    def windKey = getDataValue("windStrengthKey") ?: "windStrength"
    parent.sendDeviceCommand(getDeviceId(), [airFlow: [(windKey): strength]])
}

// Wind direction — toggle only (no angle level on wall-mount models)
def setRotateUpDown(enabled) {
    logger("debug", "setRotateUpDown(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [windDirection: [rotateUpDown: toBooleanValue(enabled)]])
    sendEvent(name: "rotateUpDown", value: toBooleanValue(enabled) ? "enabled" : "disabled")
}

def setRotateLeftRight(enabled) {
    logger("debug", "setRotateLeftRight(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [windDirection: [rotateLeftRight: toBooleanValue(enabled)]])
    sendEvent(name: "rotateLeftRight", value: toBooleanValue(enabled) ? "enabled" : "disabled")
}

// ── Display / Power Save ──────────────────────────────────────────────────────

def setDisplayLight(state) {
    logger("debug", "setDisplayLight(${state})")
    def apiValue = (state == "on") ? "DISPLAY_LIGHT_ON" : "DISPLAY_LIGHT_OFF"
    parent.sendDeviceCommand(getDeviceId(), [display: [displayLight: apiValue]])
    sendEvent(name: "displayLight", value: state)
}

def setPowerSave(enabled) {
    logger("debug", "setPowerSave(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [powerSave: [powerSaveEnabled: toBooleanValue(enabled)]])
    sendEvent(name: "powerSaveEnabled", value: toBooleanValue(enabled) ? "enabled" : "disabled")
}

// ── Timers ────────────────────────────────────────────────────────────────────

def setDelayStart(hours) {
    logger("debug", "setDelayStart(${hours})")
    // LG API spec: relativeMinuteToStart must always be 0
    parent.sendDeviceCommand(getDeviceId(), [timer: [relativeHourToStart: hours as int, relativeMinuteToStart: 0]])
}

def setDelayStop(hours) {
    logger("debug", "setDelayStop(${hours})")
    // LG API spec: relativeMinuteToStop must always be 0
    parent.sendDeviceCommand(getDeviceId(), [timer: [relativeHourToStop: hours as int, relativeMinuteToStop: 0]])
}

def unsetStartTimer() {
    logger("debug", "unsetStartTimer()")
    // LG API: send -1 to cancel timer (0 is treated as immediate, not cancel)
    parent.sendDeviceCommand(getDeviceId(), [timer: [relativeHourToStart: -1, relativeMinuteToStart: -1]])
    sendEvent(name: "relativeHourToStart", value: 0)
}

def unsetStopTimer() {
    logger("debug", "unsetStopTimer()")
    parent.sendDeviceCommand(getDeviceId(), [timer: [relativeHourToStop: -1, relativeMinuteToStop: -1]])
    sendEvent(name: "relativeHourToStop", value: 0)
}

def setAbsoluteStart(timeHHmm) {
    logger("debug", "setAbsoluteStart(${timeHHmm})")
    def t = timeHHmm as int
    parent.sendDeviceCommand(getDeviceId(), [timer: [absoluteHourToStart: t.intdiv(100), absoluteMinuteToStart: t % 100]])
}

def setAbsoluteStop(timeHHmm) {
    logger("debug", "setAbsoluteStop(${timeHHmm})")
    def t = timeHHmm as int
    parent.sendDeviceCommand(getDeviceId(), [timer: [absoluteHourToStop: t.intdiv(100), absoluteMinuteToStop: t % 100]])
}

// ── State Parsing ─────────────────────────────────────────────────────────────

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // 1. Run state
    def currentState = data.runState?.currentState
    if (currentState) sendEvent(name: "currentState", value: currentState)

    // 2. Job mode
    def currentJobModeRaw = data.airConJobMode?.currentJobMode
    if (currentJobModeRaw) sendEvent(name: "currentJobMode", value: cleanEnumValue(currentJobModeRaw))

    // 3. Operation mode
    def airConOpModeRaw = data.operation?.airConOperationMode ?: null
    if (airConOpModeRaw != null) sendEvent(name: "airConOperationMode", value: cleanEnumValue(airConOpModeRaw))

    // 4. Switch state
    def switchState = "off"
    if (airConOpModeRaw == "POWER_ON") {
        switchState = "on"
    } else if (airConOpModeRaw == null && currentState != null) {
        // Fallback: no operation mode in response
        switchState = (currentState in ["POWER_OFF", "OFF"]) ? "off" : "on"
    }
    sendEvent(name: "switch", value: switchState)
    if (logDescText) log.info "${device.displayName} opMode:${airConOpModeRaw} currentState:${currentState} → switch:${switchState}"

    // 5. Thermostat mode & operating state
    def isPoweredOff = (switchState == "off")
    if (currentJobModeRaw != null || airConOpModeRaw != null) {
        sendEvent(name: "thermostatMode", value: lgJobModeToThermostatMode(currentJobModeRaw, isPoweredOff))
    }
    sendEvent(name: "thermostatOperatingState", value: isPoweredOff ? "idle" : lgJobModeToOperatingState(currentJobModeRaw))

    // 6. Temperature
    if (data.temperatureInUnits) {
        def preferredUnit = isFahrenheit ? "F" : "C"
        def tempEntry = (data.temperatureInUnits instanceof List)
            ? (data.temperatureInUnits.find { it.unit == preferredUnit } ?: data.temperatureInUnits[0])
            : data.temperatureInUnits

        if (tempEntry) {
            if (tempEntry.unit               != null) sendEvent(name: "temperatureUnit",  value: tempEntry.unit)
            if (tempEntry.currentTemperature != null) {
                sendEvent(name: "currentTemperature", value: tempEntry.currentTemperature, unit: tempEntry.unit)
                sendEvent(name: "temperature",        value: tempEntry.currentTemperature, unit: tempEntry.unit)
            }
            if (tempEntry.targetTemperature  != null) {
                sendEvent(name: "targetTemperature",  value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "thermostatSetpoint", value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "coolingSetpoint",    value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "heatingSetpoint",    value: tempEntry.targetTemperature, unit: tempEntry.unit)
            }
        }
    }

    // 7. Airflow
    if (data.airFlow) {
        def windKey = data.airFlow.windStrengthDetail != null ? "windStrengthDetail"
                    : data.airFlow.windStrength       != null ? "windStrength" : null
        if (windKey) updateDataValue("windStrengthKey", windKey)

        def wind = data.airFlow.windStrength ?: data.airFlow.windStrengthDetail
        if (wind) {
            sendEvent(name: "windStrength",      value: cleanEnumValue(wind))
            sendEvent(name: "thermostatFanMode", value: lgWindStrengthToFanMode(wind))
        }
    }

    // 8. Wind direction — toggle only (enabled / disabled)
    if (data.windDirection?.rotateUpDown    != null) sendEvent(name: "rotateUpDown",    value: data.windDirection.rotateUpDown    ? "enabled" : "disabled")
    if (data.windDirection?.rotateLeftRight != null) sendEvent(name: "rotateLeftRight", value: data.windDirection.rotateLeftRight ? "enabled" : "disabled")

    // 9. Display light
    if (data.display?.displayLight != null) {
        sendEvent(name: "displayLight", value: data.display.displayLight == "DISPLAY_LIGHT_ON" ? "on" : "off")
    }

    // 10. Power save
    if (data.powerSave?.powerSaveEnabled != null) {
        sendEvent(name: "powerSaveEnabled", value: data.powerSave.powerSaveEnabled ? "enabled" : "disabled")
    }

    // 11. Timers
    def relStopTimer  = data.timer?.relativeStopTimer  ?: data.timer?.relativeHourToStop
    def relStartTimer = data.timer?.relativeStartTimer ?: data.timer?.relativeHourToStart
    if (relStopTimer  != null && relStopTimer  != "UNSET") sendEvent(name: "relativeHourToStop",  value: relStopTimer)
    if (relStartTimer != null && relStartTimer != "UNSET") sendEvent(name: "relativeHourToStart", value: relStartTimer)
    if (data.timer?.absoluteHourToStart   != null) sendEvent(name: "absoluteHourToStart",   value: data.timer.absoluteHourToStart)
    if (data.timer?.absoluteMinuteToStart != null) sendEvent(name: "absoluteMinuteToStart", value: data.timer.absoluteMinuteToStart)
    if (data.timer?.absoluteHourToStop    != null) sendEvent(name: "absoluteHourToStop",    value: data.timer.absoluteHourToStop)
    if (data.timer?.absoluteMinuteToStop  != null) sendEvent(name: "absoluteMinuteToStop",  value: data.timer.absoluteMinuteToStop)

    // 12. Filter
    if (data.filterInfo?.filterRemainPercent != null) {
        sendEvent(name: "filterRemainPercent", value: data.filterInfo.filterRemainPercent, unit: "%")
    }

    // 13. Error
    if (data.error) sendEvent(name: "error", value: cleanEnumValue(data.error))
}

// ── Thermostat Mode Helpers ───────────────────────────────────────────────────

private String lgJobModeToThermostatMode(String jobMode, boolean poweredOff) {
    if (poweredOff || !jobMode) return "off"
    switch (jobMode.toUpperCase()) {
        case "HEAT":          return "heat"
        case "COOL":          return "cool"
        case "AUTO":          return "auto"
        case "AIR_DRY":       return "cool"
        case "FAN":           return "auto"
        case "ENERGY_SAVING": return "cool"
        default:              return "auto"
    }
}

private String lgJobModeToOperatingState(String jobMode) {
    if (!jobMode) return "idle"
    switch (jobMode.toUpperCase()) {
        case "HEAT":          return "heating"
        case "COOL":          return "cooling"
        case "AUTO":          return "idle"
        case "FAN":           return "fan only"
        case "AIR_DRY":       return "cooling"
        case "ENERGY_SAVING": return "cooling"
        default:              return "idle"
    }
}

private String lgWindStrengthToFanMode(String wind) {
    if (!wind) return "auto"
    switch (wind.toUpperCase()) {
        case "AUTO":     return "auto"
        case "LOW":      return "circulate"
        case "LOW_MID":  return "circulate"
        case "MID":      return "on"
        case "HIGH_MID": return "on"
        case "HIGH":     return "on"
        case "POWER":    return "on"
        default:         return "auto"
    }
}

private String hubFanModeToLgWindStrength(String fanMode) {
    switch (fanMode?.toLowerCase()) {
        case "auto":      return "AUTO"
        case "circulate": return "LOW"
        case "on":        return "MID"
        default:          return null
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
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

private Boolean toBooleanValue(value) {
    if (value instanceof Boolean) return value
    if (value == null) return false
    return value.toString().trim().toLowerCase() in ["true", "on", "enabled", "yes", "1", "set"]
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
