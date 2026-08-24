/**
 *  ThinQ Connect Microwave Oven
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
    definition(name: "ThinQ Connect Microwave Oven", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_microwave_oven.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState", "string"
        attribute "remainingTime", "number"
        attribute "remainingTimeDisplay", "string"
        attribute "fanSpeed", "number"
        attribute "lampBrightness", "number"
        attribute "error", "string"
        attribute "remoteControlEnabled", "string"
        
        // Commands
        command "start"
        command "stop"
        command "powerOff"
        command "setFanSpeed", ["number"]
        command "setLampBrightness", ["number"]
        command "setFanSpeedAndLampBrightness", ["number", "number"]
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

    // Normalize payload – API may return a Map or a List with one Map entry
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    def runState = getResourceEntry(data.runState)
    def timer = getResourceEntry(data.timer)
    def ventilation = getResourceEntry(data.ventilation)
    def lamp = getResourceEntry(data.lamp)
    def remoteControl = getResourceEntry(data.remoteControlEnable)

    // Process current state
    if (runState?.currentState) {
        def currentState = runState.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause|off/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process remote control
    if (remoteControl?.remoteControlEnabled != null) {
        def remoteEnabled = remoteControl.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process timer information
    def remainMinute = timer?.remainMinute ?: 0
    def remainSecond = timer?.remainSecond ?: 0
    def remainingTime = (remainMinute * 60) + remainSecond

    sendEvent(name: "remainingTime", value: remainingTime, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingTime))

    // Process ventilation fan speed
    if (ventilation?.fanSpeed != null) {
        sendEvent(name: "fanSpeed", value: ventilation.fanSpeed)
    }

    // Process lamp brightness
    if (lamp?.lampBrightness != null) {
        sendEvent(name: "lampBrightness", value: lamp.lampBrightness)
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
        operation: [
            microwaveOvenOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            microwaveOvenOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def powerOff() {
    logger("debug", "powerOff()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            microwaveOvenOperationMode: "POWER_OFF"
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

def setFanSpeed(speed) {
    logger("debug", "setFanSpeed(${speed})")
    def deviceId = getDeviceId()
    def command = [
        ventilation: [
            fanSpeed: speed
        ],
        lamp: [
            lampBrightness: safeToInt(device.currentValue("lampBrightness"))
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setLampBrightness(brightness) {
    logger("debug", "setLampBrightness(${brightness})")
    def deviceId = getDeviceId()
    def command = [
        ventilation: [
            fanSpeed: safeToInt(device.currentValue("fanSpeed"))
        ],
        lamp: [
            lampBrightness: brightness
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFanSpeedAndLampBrightness(fanSpeed, lampBrightness) {
    logger("debug", "setFanSpeedAndLampBrightness(${fanSpeed}, ${lampBrightness})")
    def deviceId = getDeviceId()
    def command = [
        ventilation: [
            fanSpeed: fanSpeed
        ],
        lamp: [
            lampBrightness: lampBrightness
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

private def getResourceEntry(resource) {
    if (!resource) return null
    if (resource instanceof List) {
        return resource.isEmpty() ? null : resource[0]
    }
    return (resource instanceof Map) ? resource : null
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
    long seconds = sec % 60
    
    if (hours > 0) {
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        return String.format("%02d:%02d", minutes, seconds)
    }
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
