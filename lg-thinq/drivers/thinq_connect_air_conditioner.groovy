/**
 *  ThinQ Connect Air Conditioner
 *
 *  Copyright 2026
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Air Conditioner", namespace: "jonozzz", author: "Ionut Turturica") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "Thermostat"

        attribute "currentState",                "string"
        attribute "currentJobMode",              "string"
        attribute "airConOperationMode",         "string"
        attribute "airCleanOperationMode",       "string"
        attribute "currentTemperature",          "number"
        attribute "targetTemperature",           "number"
        attribute "minTargetTemperature",        "number"
        attribute "maxTargetTemperature",        "number"
        attribute "heatTargetTemperature",       "number"
        attribute "coolTargetTemperature",       "number"
        attribute "temperatureUnit",             "string"
        attribute "twoSetEnabled",               "string"
        attribute "supportedThermostatModes",    "JSON_OBJECT"
        attribute "supportedThermostatFanModes", "JSON_OBJECT"
        attribute "windStrength",                "string"
        attribute "windStep",                    "number"
        attribute "rotateUpDown",                "string"
        attribute "rotateLeftRight",             "string"
        attribute "light",                       "string"
        attribute "displayLight",                "string"   // on / off
        attribute "powerSaveEnabled",            "string"
        attribute "airQualityMonitoringEnabled", "string"
        attribute "pm1",                         "number"
        attribute "pm2",                         "number"
        attribute "pm10",                        "number"
        attribute "odorLevel",                   "string"
        attribute "humidity",                    "number"
        attribute "totalPollutionLevel",         "string"
        attribute "filterRemainPercent",         "number"
        attribute "filterUsedTime",              "number"
        attribute "filterLifetime",              "number"
        attribute "error",                       "string"

        // Timer attributes
        attribute "relativeHourToStart",         "number"
        attribute "relativeMinuteToStart",       "number"
        attribute "relativeHourToStop",          "number"
        attribute "relativeMinuteToStop",        "number"
        attribute "absoluteHourToStart",         "number"
        attribute "absoluteMinuteToStart",       "number"
        attribute "absoluteHourToStop",          "number"
        attribute "absoluteMinuteToStop",        "number"

        // Sleep timer attributes
        attribute "sleepRelativeHourToStop",     "number"
        attribute "sleepRelativeMinuteToStop",   "number"

        command "start"
        command "stop"
        command "getDeviceProfile"
        command "startAirCleanOnly"
        command "stopAirClean"
        command "setAirConOperationMode",  ["string"]
        command "setAirCleanOperationMode",["string"]
        command "setAirConJobMode", [[name:"Set AirConJobMode", type:"ENUM", constraints:["COOL","HEAT","AUTO","AIR_CLEAN","ENERGY_SAVING","AIR_DRY","FAN"]]]
        command "setTargetTemperature",    ["number"]
        command "setHeatTargetTemperature",["number"]
        command "setCoolTargetTemperature",["number"]
        command "setWindStrength", [[name:"Set Wind Strength", type:"ENUM", constraints:["LOW","MID","HIGH","POWER","AUTO"]]]
        command "setWindStep",             ["number"]
        command "setRotateUpDown",         ["string"]
        command "setRotateLeftRight",      ["string"]
        command "setLight",                ["string"]
        command "setPowerSave",            ["string"]
        command "setTwoSetEnabled",        ["string"]
        command "setDelayStart", [[name:"Set Delay Start", type:"NUMBER", description:"Delay Start in hours (e.g. 2 = 2 hours later)"]]
        command "setDelayStop",  [[name:"Set Delay Stop",  type:"NUMBER", description:"Delay Stop in hours (e.g. 1 = 1 hour later)"]]
        command "unsetStopTimer"
        command "unsetStartTimer"
        command "setAbsoluteStart", [[name:"absoluteStart", type:"NUMBER", description:"Time in HHmm format (e.g. 2130 = 9:30 PM)"]]
        command "setAbsoluteStop",  [[name:"absoluteStop",  type:"NUMBER", description:"Time in HHmm format (e.g. 2130 = 9:30 PM)"]]
    }

    preferences {
        section {
            input name: "isFahrenheit", type: "bool",   title: "<b>Fahrenheit</b>", description: "<i>Use fahrenheit degrees</i>", defaultValue: true
            input name: "logLevel",     title: "Log Level",            type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "logDescText",  title: "Log Description Text", type: "bool", defaultValue: false, required: false
        }
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

// ── State Parsing ─────────────────────────────────────────────────────────────

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // 1. Current run state
    def currentState = null
    if (data.runState?.currentState) {
        currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)
    }

    // 2. Job mode
    def currentJobModeRaw = null
    if (data.airConJobMode?.currentJobMode) {
        currentJobModeRaw = data.airConJobMode.currentJobMode
        sendEvent(name: "currentJobMode", value: cleanEnumValue(currentJobModeRaw))
    }

    // 3. Operation modes
    def airConOpModeRaw    = data.operation?.airConOperationMode    ?: null
    def airCleanOpModeRaw  = data.operation?.airCleanOperationMode  ?: null

    if (airConOpModeRaw != null) {
        sendEvent(name: "airConOperationMode", value: cleanEnumValue(airConOpModeRaw))
    }
    if (airCleanOpModeRaw != null) {
        sendEvent(name: "airCleanOperationMode", value: cleanEnumValue(airCleanOpModeRaw))
    }

    // 4. Switch state
    // airConOperationMode is authoritative; airClean runs independently even when AC is OFF
    def switchState = "off"
    if (airConOpModeRaw == "POWER_ON") {
        switchState = "on"
    } else if (airCleanOpModeRaw == "START") {
        // Air clean runs without compressor — device is still "on"
        switchState = "on"
    } else if (airConOpModeRaw == null && currentState != null) {
        // Fallback: operation mode absent in response, derive from currentState
        switchState = (currentState in ["POWER_OFF", "OFF"]) ? "off" : "on"
    }
    sendEvent(name: "switch", value: switchState)
    if (logDescText) log.info "${device.displayName} opMode:${airConOpModeRaw} airClean:${airCleanOpModeRaw} currentState:${currentState} → switch:${switchState}"

    // 5. Thermostat mode & operating state
    def isPoweredOff = (switchState == "off")
    if (currentJobModeRaw != null || airConOpModeRaw != null) {
        def thermostatMode = lgJobModeToThermostatMode(currentJobModeRaw, isPoweredOff)
        sendEvent(name: "thermostatMode", value: thermostatMode)
    }
    def operatingState = isPoweredOff ? "idle" : lgJobModeToOperatingState(currentJobModeRaw)
    sendEvent(name: "thermostatOperatingState", value: operatingState)

    // 6. Temperature
    if (data.temperatureInUnits) {
        def tempEntry = null
        if (data.temperatureInUnits instanceof List) {
            def preferredUnit = isFahrenheit ? "F" : "C"
            tempEntry = data.temperatureInUnits.find { it.unit == preferredUnit } ?: data.temperatureInUnits[0]
        } else {
            tempEntry = data.temperatureInUnits
        }
        if (tempEntry) {
            if (tempEntry.unit)                        sendEvent(name: "temperatureUnit",    value: tempEntry.unit)
            if (tempEntry.currentTemperature != null)  sendEvent(name: "currentTemperature", value: tempEntry.currentTemperature, unit: tempEntry.unit)
            if (tempEntry.currentTemperature != null)  sendEvent(name: "temperature",        value: tempEntry.currentTemperature, unit: tempEntry.unit)
            if (tempEntry.targetTemperature  != null) {
                sendEvent(name: "targetTemperature",  value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "thermostatSetpoint", value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "coolingSetpoint",    value: tempEntry.targetTemperature, unit: tempEntry.unit)
            }
            if (tempEntry.minTargetTemperature != null) sendEvent(name: "minTargetTemperature", value: tempEntry.minTargetTemperature)
            if (tempEntry.maxTargetTemperature != null) sendEvent(name: "maxTargetTemperature", value: tempEntry.maxTargetTemperature)
            if (tempEntry.heatTargetTemperature != null) sendEvent(name: "heatTargetTemperature", value: tempEntry.heatTargetTemperature, unit: tempEntry.unit)
            if (tempEntry.coolTargetTemperature != null) sendEvent(name: "coolTargetTemperature", value: tempEntry.coolTargetTemperature, unit: tempEntry.unit)
        }
    }

    // 7. Two-set temperature
    if (data.twoSetTemperature?.twoSetEnabled != null) {
        sendEvent(name: "twoSetEnabled", value: data.twoSetTemperature.twoSetEnabled ? "enabled" : "disabled")
    }
    if (data.twoSetTemperatureInUnits) {
        def twoSetEntry = null
        if (data.twoSetTemperatureInUnits instanceof List) {
            def preferredUnit = isFahrenheit ? "F" : "C"
            twoSetEntry = data.twoSetTemperatureInUnits.find { it.unit == preferredUnit } ?: data.twoSetTemperatureInUnits[0]
        } else {
            twoSetEntry = data.twoSetTemperatureInUnits
        }
        if (twoSetEntry?.heatTargetTemperature != null) {
            sendEvent(name: "heatTargetTemperature", value: twoSetEntry.heatTargetTemperature, unit: twoSetEntry.unit)
            sendEvent(name: "heatingSetpoint",       value: twoSetEntry.heatTargetTemperature, unit: twoSetEntry.unit)
        }
        if (twoSetEntry?.coolTargetTemperature != null) {
            sendEvent(name: "coolTargetTemperature", value: twoSetEntry.coolTargetTemperature, unit: twoSetEntry.unit)
            sendEvent(name: "coolingSetpoint",       value: twoSetEntry.coolTargetTemperature, unit: twoSetEntry.unit)
        }
    } else {
        def heatVal = device.currentValue("heatTargetTemperature")
        if (heatVal != null) sendEvent(name: "heatingSetpoint", value: heatVal)
    }

    // 8. Airflow
    if (data.airFlow) {
        def windKey = data.airFlow.windStrengthDetail != null ? "windStrengthDetail" : (data.airFlow.windStrength != null ? "windStrength" : null)
        if (windKey) updateDataValue("windStrengthKey", windKey)

        def wind = data.airFlow.windStrength ?: data.airFlow.windStrengthDetail
        if (wind) {
            sendEvent(name: "windStrength",      value: cleanEnumValue(wind))
            sendEvent(name: "thermostatFanMode", value: lgWindStrengthToFanMode(wind))
        }
    }
    if (data.airFlow?.windStep != null) sendEvent(name: "windStep", value: data.airFlow.windStep)

    // 9. Wind direction
    if (data.windDirection?.rotateUpDown   != null) sendEvent(name: "rotateUpDown",   value: data.windDirection.rotateUpDown   ? "enabled" : "disabled")
    if (data.windDirection?.rotateLeftRight != null) sendEvent(name: "rotateLeftRight", value: data.windDirection.rotateLeftRight ? "enabled" : "disabled")

    // 10. Display
    if (data.display?.light != null) {
        sendEvent(name: "light", value: data.display.light ? "on" : "off")
    }
    // Display light (LED on/off — DISPLAY_LIGHT_ON / DISPLAY_LIGHT_OFF)
    if (data.display?.displayLight != null) {
        def lightState = (data.display.displayLight == "DISPLAY_LIGHT_ON") ? "on" : "off"
        sendEvent(name: "displayLight", value: lightState)
    }

    // 11. Power save
    if (data.powerSave?.powerSaveEnabled != null) {
        sendEvent(name: "powerSaveEnabled", value: data.powerSave.powerSaveEnabled ? "enabled" : "disabled")
    }

    // 12. Timers
    if (data.timer?.relativeHourToStart   != null) sendEvent(name: "relativeHourToStart",   value: data.timer.relativeHourToStart)
    if (data.timer?.relativeMinuteToStart != null) sendEvent(name: "relativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    if (data.timer?.relativeHourToStop    != null) sendEvent(name: "relativeHourToStop",    value: data.timer.relativeHourToStop)
    if (data.timer?.relativeMinuteToStop  != null) sendEvent(name: "relativeMinuteToStop",  value: data.timer.relativeMinuteToStop)
    if (data.timer?.absoluteHourToStart   != null) sendEvent(name: "absoluteHourToStart",   value: data.timer.absoluteHourToStart)
    if (data.timer?.absoluteMinuteToStart != null) sendEvent(name: "absoluteMinuteToStart", value: data.timer.absoluteMinuteToStart)
    if (data.timer?.absoluteHourToStop    != null) sendEvent(name: "absoluteHourToStop",    value: data.timer.absoluteHourToStop)
    if (data.timer?.absoluteMinuteToStop  != null) sendEvent(name: "absoluteMinuteToStop",  value: data.timer.absoluteMinuteToStop)

    // 13. Sleep timer
    if (data.sleepTimer?.relativeHourToStop   != null) sendEvent(name: "sleepRelativeHourToStop",   value: data.sleepTimer.relativeHourToStop)
    if (data.sleepTimer?.relativeMinuteToStop != null) sendEvent(name: "sleepRelativeMinuteToStop", value: data.sleepTimer.relativeMinuteToStop)

    // 14. Air quality sensor
    if (data.airQualitySensor?.monitoringEnabled != null) sendEvent(name: "airQualityMonitoringEnabled", value: data.airQualitySensor.monitoringEnabled ? "enabled" : "disabled")
    if (data.airQualitySensor?.PM1    != null) sendEvent(name: "pm1",               value: data.airQualitySensor.PM1)
    if (data.airQualitySensor?.PM2    != null) sendEvent(name: "pm2",               value: data.airQualitySensor.PM2)
    if (data.airQualitySensor?.PM10   != null) sendEvent(name: "pm10",              value: data.airQualitySensor.PM10)
    if (data.airQualitySensor?.odorLevel)      sendEvent(name: "odorLevel",         value: cleanEnumValue(data.airQualitySensor.odorLevel))
    if (data.airQualitySensor?.humidity != null) sendEvent(name: "humidity",        value: data.airQualitySensor.humidity, unit: "%")
    if (data.airQualitySensor?.totalPollutionLevel) sendEvent(name: "totalPollutionLevel", value: cleanEnumValue(data.airQualitySensor.totalPollutionLevel))

    // 15. Filter info
    if (data.filterInfo?.filterRemainPercent != null) sendEvent(name: "filterRemainPercent", value: data.filterInfo.filterRemainPercent, unit: "%")
    if (data.filterInfo?.usedTime            != null) sendEvent(name: "filterUsedTime",      value: data.filterInfo.usedTime,            unit: "hours")
    if (data.filterInfo?.filterLifetime      != null) sendEvent(name: "filterLifetime",      value: data.filterInfo.filterLifetime,      unit: "hours")

    // 16. Error
    if (data.error) sendEvent(name: "error", value: cleanEnumValue(data.error))
}

// ── Switch ────────────────────────────────────────────────────────────────────

def on()    { start() }
def off()   { stop() }

def start() {
    logger("debug", "start()")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airConOperationMode: "POWER_ON"]])
}

def stop() {
    logger("debug", "stop()")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airConOperationMode: "POWER_OFF"]])
}

// ── Air Clean ─────────────────────────────────────────────────────────────────

def startAirCleanOnly() {
    logger("debug", "startAirCleanOnly()")
    def deviceId = getDeviceId()
    // Step 1: Turn off AC cooling
    parent.sendDeviceCommand(deviceId, [operation: [airConOperationMode: "POWER_OFF"]])
    pauseExecution(1000)
    // Step 2: Start air clean independently (fan + filter only, no compressor)
    parent.sendDeviceCommand(deviceId, [operation: [airCleanOperationMode: "START"]])
    sendEvent(name: "switch",       value: "on")
    sendEvent(name: "currentJobMode", value: "Air Clean")
}

def stopAirClean() {
    logger("debug", "stopAirClean()")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airCleanOperationMode: "STOP"]])
    sendEvent(name: "switch",       value: "off")
    sendEvent(name: "currentJobMode", value: "")
}

def setAirConOperationMode(mode) {
    logger("debug", "setAirConOperationMode(${mode})")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airConOperationMode: mode]])
}

def setAirCleanOperationMode(mode) {
    logger("debug", "setAirCleanOperationMode(${mode})")
    parent.sendDeviceCommand(getDeviceId(), [operation: [airCleanOperationMode: mode]])
}

def setAirConJobMode(mode) {
    logger("debug", "setAirConJobMode(${mode})")
    parent.sendDeviceCommand(getDeviceId(), [airConJobMode: [currentJobMode: mode]])
}

// ── Thermostat ────────────────────────────────────────────────────────────────

def setThermostatMode(mode) {
    logger("debug", "setThermostatMode(${mode})")
    switch (mode) {
        case "off":            stop(); break
        case "heat":           start(); setAirConJobMode("HEAT"); break
        case "cool":           start(); setAirConJobMode("COOL"); break
        case "auto":           start(); setAirConJobMode("AUTO"); break
        case "emergency heat": start(); setAirConJobMode("HEAT"); break
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
    parent.sendDeviceCommand(getDeviceId(), [temperatureInUnits: [targetTemperature: temperature, unit: isFahrenheit ? "F" : "C"]])
}

def setHeatTargetTemperature(temperature) {
    logger("debug", "setHeatTargetTemperature(${temperature})")
    parent.sendDeviceCommand(getDeviceId(), [temperatureInUnits: [heatTargetTemperature: temperature, unit: isFahrenheit ? "F" : "C"]])
}

def setCoolTargetTemperature(temperature) {
    logger("debug", "setCoolTargetTemperature(${temperature})")
    parent.sendDeviceCommand(getDeviceId(), [temperatureInUnits: [coolTargetTemperature: temperature, unit: isFahrenheit ? "F" : "C"]])
}

def setCoolingSetpoint(temperature) {
    logger("debug", "setCoolingSetpoint(${temperature})")
    setTargetTemperature(temperature)
}

def setHeatingSetpoint(temperature) {
    logger("debug", "setHeatingSetpoint(${temperature})")
    setHeatTargetTemperature(temperature)
}

// ── Airflow ───────────────────────────────────────────────────────────────────

def setWindStrength(strength) {
    logger("debug", "setWindStrength(${strength})")
    def windKey = getDataValue("windStrengthKey") ?: "windStrength"
    parent.sendDeviceCommand(getDeviceId(), [airFlow: [(windKey): strength]])
}

def setWindStep(step) {
    logger("debug", "setWindStep(${step})")
    parent.sendDeviceCommand(getDeviceId(), [airFlow: [windStep: step]])
}

// ── Wind Direction ────────────────────────────────────────────────────────────

def setRotateUpDown(enabled) {
    logger("debug", "setRotateUpDown(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [windDirection: [rotateUpDown: toBooleanValue(enabled)]])
}

def setRotateLeftRight(enabled) {
    logger("debug", "setRotateLeftRight(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [windDirection: [rotateLeftRight: toBooleanValue(enabled)]])
}

// ── Display ───────────────────────────────────────────────────────────────────

def setLight(state) {
    logger("debug", "setLight(${state})")
    parent.sendDeviceCommand(getDeviceId(), [display: [light: state]])
}

// ── Misc ──────────────────────────────────────────────────────────────────────

def setPowerSave(enabled) {
    logger("debug", "setPowerSave(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [powerSave: [powerSaveEnabled: toBooleanValue(enabled)]])
}

def setTwoSetEnabled(enabled) {
    logger("debug", "setTwoSetEnabled(${enabled})")
    parent.sendDeviceCommand(getDeviceId(), [twoSetTemperature: [twoSetEnabled: toBooleanValue(enabled)]])
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

def unsetStopTimer() {
    logger("debug", "unsetStopTimer()")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStop  : 0,
            relativeMinuteToStop: 0
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
    // Clear local attributes
    sendEvent(name: "relativeHourToStop",   value: 0)
    sendEvent(name: "relativeMinuteToStop", value: 0)
}

def unsetStartTimer() {
    logger("debug", "unsetStartTimer()")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStart: 0,
            relativeMinuteToStart: 0
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setAbsoluteStart(timeHHmm) {
    logger("debug", "setAbsoluteStart(${timeHHmm})")
    def t      = timeHHmm as int
    def hour   = t.intdiv(100)
    def minute = t % 100
    parent.sendDeviceCommand(getDeviceId(), [timer: [absoluteHourToStart: hour, absoluteMinuteToStart: minute]])
}

def setAbsoluteStop(timeHHmm) {
    logger("debug", "setAbsoluteStop(${timeHHmm})")
    def t      = timeHHmm as int
    def hour   = t.intdiv(100)
    def minute = t % 100
    parent.sendDeviceCommand(getDeviceId(), [timer: [absoluteHourToStop: hour, absoluteMinuteToStop: minute]])
}

// ── Device Profile ────────────────────────────────────────────────────────────

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
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
        case "AIR_CLEAN":     return "auto"
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
        case "AIR_CLEAN":     return "fan only"
        default:              return "idle"
    }
}

private String lgWindStrengthToFanMode(String wind) {
    if (!wind) return "auto"
    switch (wind.toUpperCase()) {
        case "AUTO":  return "auto"
        case "LOW":   return "circulate"
        case "MID":   return "on"
        case "HIGH":  return "on"
        case "POWER": return "on"
        default:      return "auto"
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

def getDeviceDetails() {
    def deviceId = getDeviceId()
    return parent.state.foundDevices.find { it.id == deviceId }
}

def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .replaceAll(/^[A-Z_]+_/, "")
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

private Boolean toBooleanValue(value) {
    if (value instanceof Boolean) return value
    if (value == null) return false
    def normalized = value.toString().trim().toLowerCase()
    return normalized in ["true", "on", "enabled", "yes", "1", "set"]
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
