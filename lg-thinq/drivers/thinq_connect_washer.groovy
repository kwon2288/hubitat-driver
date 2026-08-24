/**
 *  ThinQ Connect Washer
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Washer", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_washer.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "ContactSensor"
        capability "Refresh"

        attribute "runTime", "number"
        attribute "runTimeDisplay", "string"
        attribute "remainingTime", "number"
        attribute "remainingTimeDisplay", "string"
        attribute "totalTime", "number"
        attribute "totalTimeDisplay", "string"
        attribute "finishTimeDisplay", "string"
        attribute "currentState", "string"
        attribute "error", "string"
        attribute "operationMode", "string"
        attribute "remoteControlEnabled", "string"
        attribute "cycleCount", "number"
        attribute "detergentSetting", "string"
        
        // Timer attributes
        attribute "relativeHourToStart", "number"
        attribute "relativeMinuteToStart", "number"
        attribute "relativeHourToStop", "number"
        attribute "relativeMinuteToStop", "number"
        
        // Commands
        command "start"
        command "stop"
        command "powerOff"
        command "setDelayStart", ["number"]
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
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()

        mqttConnectUntilSuccessful()
    }
    
    refresh()
}

def refresh() {
    logger("debug", "refresh()")
    // parent.getDeviceStatus(getDeviceDetails(), device)
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
    data = data[0]

    if (!data) return

    // Process current state
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process operation mode
    if (data.operation?.washerOperationMode) {
        def opMode = cleanEnumValue(data.operation.washerOperationMode)
        sendEvent(name: "operationMode", value: opMode)
    }

    // Process remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    if (data.timer?.remainHour != null) {
      updateDataValue("remainHour", data.timer?.remainHour.toString())
    }

    if (data.timer?.totalHour != null) {
      updateDataValue("totalHour", data.timer?.totalHour.toString())
    }

    if (data.timer?.totalMinute != null) {
      updateDataValue("totalMinute", data.timer?.totalMinute.toString())
    }

    // Process timer information
    def remainHour = data.timer?.remainHour ?: getDataValue("remainHour").toInteger() ?: 0
    def remainMinute = data.timer?.remainMinute ?: 0
    def totalHour = data.timer?.totalHour ?: getDataValue("totalHour").toInteger() ?: 0
    def totalMinute = data.timer?.totalMinute ?: getDataValue("totalMinute").toInteger() ?: 0

    def remainingTime = (remainHour * 3600) + (remainMinute * 60)
    def totalTime = (totalHour * 3600) + (totalMinute * 60)
    def runTime = totalTime - remainingTime

    sendEvent(name: "remainingTime", value: remainingTime, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingTime))
    sendEvent(name: "totalTime", value: totalTime, unit: "seconds")
    sendEvent(name: "totalTimeDisplay", value: convertSecondsToTime(totalTime))
    sendEvent(name: "runTime", value: runTime, unit: "seconds")
    sendEvent(name: "runTimeDisplay", value: convertSecondsToTime(runTime))

    logger("debug", "remainingTime: ${remainingTime}")


    // Calculate finish time
    Date currentTime = new Date()
    use(groovy.time.TimeCategory) {
        currentTime = currentTime + (remainingTime as int).seconds
    }
    def finishTimeDisplay = currentTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
    sendEvent(name: "finishTimeDisplay", value: finishTimeDisplay)

    // Process delay timer
    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "relativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "relativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "relativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "relativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }

    // Process cycle count
    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "cycleCount", value: data.cycle.cycleCount)
    }

    // Process detergent setting
    if (data.detergent?.detergentSetting) {
        def detergent = cleanEnumValue(data.detergent.detergentSetting)
        sendEvent(name: "detergentSetting", value: detergent)
    }

    // Process error state
    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "error", value: errorState)
    }
}

def start() {
    logger("debug", "start()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def powerOff() {
    logger("debug", "powerOff()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "POWER_OFF"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def on() {
    start()
}

def off() {
    stop()
}

def setDelayStart(hours) {
    logger("debug", "setDelayStart(${hours})")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStart: hours
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def getDeviceDetails() {
    def deviceId = getDeviceId()
    return parent.state.foundDevices.find { it.id == deviceId }
}

def cleanEnumValue(value) {
    if (value == null) return ""
    
    // Convert enum values to readable format
    return value.toString()
        .replaceAll(/^[A-Z_]+_/, "")  // Remove prefix
        .replaceAll(/_/, " ")         // Replace underscores with spaces
        .toLowerCase()                // Convert to lowercase
        .split(' ')                   // Split into words
        .collect { it.capitalize() }  // Capitalize each word
        .join(' ')                    // Join back together
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
