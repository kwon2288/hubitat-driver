/**
 *  ThinQ Connect WashTower
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
    definition(name: "ThinQ Connect WashTower", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_washtower.groovy") {
        capability "Sensor"
        capability "Initialize"
        capability "Refresh"

        // Washer attributes
        attribute "washerRunTime", "number"
        attribute "washerRunTimeDisplay", "string"
        attribute "washerRemainingTime", "number"
        attribute "washerRemainingTimeDisplay", "string"
        attribute "washerTotalTime", "number"
        attribute "washerTotalTimeDisplay", "string"
        attribute "washerFinishTimeDisplay", "string"
        attribute "washerCurrentState", "string"
        attribute "washerError", "string"
        attribute "washerOperationMode", "string"
        attribute "washerRemoteControlEnabled", "string"
        attribute "washerCycleCount", "number"
        attribute "washerDetergentSetting", "string"
        attribute "washerRelativeHourToStart", "number"
        attribute "washerRelativeMinuteToStart", "number"
        attribute "washerRelativeHourToStop", "number"
        attribute "washerRelativeMinuteToStop", "number"

        // Dryer attributes
        attribute "dryerRunTime", "number"
        attribute "dryerRunTimeDisplay", "string"
        attribute "dryerRemainingTime", "number"
        attribute "dryerRemainingTimeDisplay", "string"
        attribute "dryerTotalTime", "number"
        attribute "dryerTotalTimeDisplay", "string"
        attribute "dryerFinishTimeDisplay", "string"
        attribute "dryerCurrentState", "string"
        attribute "dryerError", "string"
        attribute "dryerOperationMode", "string"
        attribute "dryerRemoteControlEnabled", "string"
        attribute "dryerCycleCount", "number"
        attribute "dryerDryLevel", "string"
        attribute "dryerTemperatureLevel", "string"
        attribute "dryerRelativeHourToStart", "number"
        attribute "dryerRelativeMinuteToStart", "number"
        attribute "dryerRelativeHourToStop", "number"
        attribute "dryerRelativeMinuteToStop", "number"

        // Commands
        command "getDeviceProfile"

        command "startWasher"
        command "stopWasher"
        command "powerOffWasher"
        command "setWasherDelayStart", ["number"]

        command "startDryer"
        command "stopDryer"
        command "powerOffDryer"
        command "setDryerDelayStart", ["number"]

        command "startBoth"
        command "stopBoth"
        command "powerOffBoth"
    }

    preferences {
        section {
            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
        }
    }
}

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
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
        mqttConnectUntilSuccessful()
    }

    refresh()
}

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")

    try {
        def mqtt = parent.retrieveMqttDetails()

        interfaces.mqtt.connect(mqtt.server,
                                mqtt.clientId,
                                null,
                                null,
                                tlsVersion: "1.2",
                                privateKey: mqtt.privateKey,
                                caCertificate: mqtt.caCertificate,
                                clientCertificate: mqtt.certificate,
                                cleanSession: true,
                                ignoreSSLIssues: true)
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    }
    catch (e) {
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

        try {
            interfaces.mqtt.disconnect()
        }
        catch (e) {
        }
        mqttConnectUntilSuccessful()
    }
}

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return

    // Normalize payload – API may return a Map or a List with one Map
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    def washerState = normalizeSection(data.washer)
    def dryerState = normalizeSection(data.dryer)

    // Fallback for unexpected flat payloads
    if (!washerState && !dryerState) {
        if (data.operation?.washerOperationMode || data.detergent || data.cycle) {
            washerState = data
        }
        if (data.operation?.dryerOperationMode || data.dryLevel || data.temperature) {
            dryerState = data
        }
    }

    if (washerState) {
        processWasherState(washerState)
    }
    if (dryerState) {
        processDryerState(dryerState)
    }
}

private def normalizeSection(section) {
    if (!section) return null

    if (section instanceof List) {
        if (section.isEmpty()) return null
        section = section.find {
            it?.location?.locationName in ["MAIN", "WASHER", "DRYER"] ||
            it?.locationName in ["MAIN", "WASHER", "DRYER"]
        } ?: section[0]
    }

    return (section instanceof Map) ? section : null
}

private def processWasherState(data) {
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        sendEvent(name: "washerCurrentState", value: currentState)
    }

    if (data.operation?.washerOperationMode) {
        def opMode = cleanEnumValue(data.operation.washerOperationMode)
        sendEvent(name: "washerOperationMode", value: opMode)
    }

    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "washerRemoteControlEnabled", value: remoteEnabled)
    }

    if (data.timer?.remainHour != null) {
        updateDataValue("washerRemainHour", data.timer.remainHour.toString())
    }
    if (data.timer?.totalHour != null) {
        updateDataValue("washerTotalHour", data.timer.totalHour.toString())
    }
    if (data.timer?.totalMinute != null) {
        updateDataValue("washerTotalMinute", data.timer.totalMinute.toString())
    }

    def remainHour = data.timer?.remainHour != null ? data.timer.remainHour : safeToInt(getDataValue("washerRemainHour"))
    def remainMinute = data.timer?.remainMinute ?: 0
    def totalHour = data.timer?.totalHour != null ? data.timer.totalHour : safeToInt(getDataValue("washerTotalHour"))
    def totalMinute = data.timer?.totalMinute != null ? data.timer.totalMinute : safeToInt(getDataValue("washerTotalMinute"))

    def remainingTime = (remainHour * 3600) + (remainMinute * 60)
    def totalTime = (totalHour * 3600) + (totalMinute * 60)
    def runTime = totalTime - remainingTime

    sendEvent(name: "washerRemainingTime", value: remainingTime, unit: "seconds")
    sendEvent(name: "washerRemainingTimeDisplay", value: convertSecondsToTime(remainingTime))
    sendEvent(name: "washerTotalTime", value: totalTime, unit: "seconds")
    sendEvent(name: "washerTotalTimeDisplay", value: convertSecondsToTime(totalTime))
    sendEvent(name: "washerRunTime", value: runTime, unit: "seconds")
    sendEvent(name: "washerRunTimeDisplay", value: convertSecondsToTime(runTime))

    Date currentTime = new Date()
    use(groovy.time.TimeCategory) {
        currentTime = currentTime + (remainingTime as int).seconds
    }
    def finishTimeDisplay = currentTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
    sendEvent(name: "washerFinishTimeDisplay", value: finishTimeDisplay)

    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "washerRelativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "washerRelativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "washerRelativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "washerRelativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }

    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "washerCycleCount", value: data.cycle.cycleCount)
    }

    if (data.detergent?.detergentSetting) {
        def detergent = cleanEnumValue(data.detergent.detergentSetting)
        sendEvent(name: "washerDetergentSetting", value: detergent)
    }

    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "washerError", value: errorState)
    }
}

private def processDryerState(data) {
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        sendEvent(name: "dryerCurrentState", value: currentState)
    }

    if (data.operation?.dryerOperationMode) {
        def opMode = cleanEnumValue(data.operation.dryerOperationMode)
        sendEvent(name: "dryerOperationMode", value: opMode)
    }

    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "dryerRemoteControlEnabled", value: remoteEnabled)
    }

    if (data.timer?.remainHour != null) {
        updateDataValue("dryerRemainHour", data.timer.remainHour.toString())
    }
    if (data.timer?.totalHour != null) {
        updateDataValue("dryerTotalHour", data.timer.totalHour.toString())
    }
    if (data.timer?.totalMinute != null) {
        updateDataValue("dryerTotalMinute", data.timer.totalMinute.toString())
    }

    def remainHour = data.timer?.remainHour != null ? data.timer.remainHour : safeToInt(getDataValue("dryerRemainHour"))
    def remainMinute = data.timer?.remainMinute ?: 0
    def totalHour = data.timer?.totalHour != null ? data.timer.totalHour : safeToInt(getDataValue("dryerTotalHour"))
    def totalMinute = data.timer?.totalMinute != null ? data.timer.totalMinute : safeToInt(getDataValue("dryerTotalMinute"))

    def remainingTime = (remainHour * 3600) + (remainMinute * 60)
    def totalTime = (totalHour * 3600) + (totalMinute * 60)
    def runTime = totalTime - remainingTime

    sendEvent(name: "dryerRemainingTime", value: remainingTime, unit: "seconds")
    sendEvent(name: "dryerRemainingTimeDisplay", value: convertSecondsToTime(remainingTime))
    sendEvent(name: "dryerTotalTime", value: totalTime, unit: "seconds")
    sendEvent(name: "dryerTotalTimeDisplay", value: convertSecondsToTime(totalTime))
    sendEvent(name: "dryerRunTime", value: runTime, unit: "seconds")
    sendEvent(name: "dryerRunTimeDisplay", value: convertSecondsToTime(runTime))

    Date currentTime = new Date()
    use(groovy.time.TimeCategory) {
        currentTime = currentTime + (remainingTime as int).seconds
    }
    def finishTimeDisplay = currentTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
    sendEvent(name: "dryerFinishTimeDisplay", value: finishTimeDisplay)

    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "dryerRelativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "dryerRelativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "dryerRelativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "dryerRelativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }

    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "dryerCycleCount", value: data.cycle.cycleCount)
    }

    if (data.dryLevel?.dryLevel) {
        def dryLevelValue = cleanEnumValue(data.dryLevel.dryLevel)
        sendEvent(name: "dryerDryLevel", value: dryLevelValue)
    }

    if (data.temperature?.temperatureLevel) {
        def tempLevel = cleanEnumValue(data.temperature.temperatureLevel)
        sendEvent(name: "dryerTemperatureLevel", value: tempLevel)
    }

    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "dryerError", value: errorState)
    }
}

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
}

def startWasher() {
    logger("debug", "startWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "START"]]])
}

def stopWasher() {
    logger("debug", "stopWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "STOP"]]])
}

def powerOffWasher() {
    logger("debug", "powerOffWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "POWER_OFF"]]])
}

def setWasherDelayStart(hours) {
    logger("debug", "setWasherDelayStart(${hours})")
    sendWashtowerCommand([washer: [timer: [relativeHourToStart: hours]]])
}

def startDryer() {
    logger("debug", "startDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "START"]]])
}

def stopDryer() {
    logger("debug", "stopDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "STOP"]]])
}

def powerOffDryer() {
    logger("debug", "powerOffDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "POWER_OFF"]]])
}

def setDryerDelayStart(hours) {
    logger("debug", "setDryerDelayStart(${hours})")
    sendWashtowerCommand([dryer: [timer: [relativeHourToStart: hours]]])
}

def startBoth() {
    logger("debug", "startBoth()")
    startWasher()
    startDryer()
}

def stopBoth() {
    logger("debug", "stopBoth()")
    stopWasher()
    stopDryer()
}

def powerOffBoth() {
    logger("debug", "powerOffBoth()")
    powerOffWasher()
    powerOffDryer()
}

private def sendWashtowerCommand(command) {
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, command)
}

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def getDeviceDetails() {
    def deviceId = getDeviceId()
    return parent.state.foundDevices.find { it.id == deviceId }
}

private Integer safeToInt(value) {
    if (value == null) return 0
    try {
        return value.toString().toInteger()
    }
    catch (e) {
        return 0
    }
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

    long hours = sec / 3600
    long minutes = (sec % 3600) / 60

    return String.format("%02d:%02d", hours, minutes)
}

/**
* @param level Level to log at, see LOG_LEVELS for options
* @param msg Message to log
*/
private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) {
            setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        }
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}
