/**
 *  ThinQ Connect Refrigerator
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
    definition(name: "ThinQ Connect Refrigerator", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_refrigerator.groovy") {
        capability "Sensor"
        capability "ContactSensor"
        capability "Initialize"
        capability "Refresh"

        attribute "doorState", "string"
        attribute "temperatureUnit", "string"
        attribute "targetTemperatureC", "number"
        attribute "targetTemperatureF", "number"
        attribute "powerSaveEnabled", "string"
        attribute "ecoFriendlyMode", "string"
        attribute "sabbathMode", "string"
        attribute "rapidFreeze", "string"
        attribute "expressMode", "string"
        attribute "expressFridge", "string"
        attribute "freshAirFilter", "string"
        attribute "usedTime", "number"
        attribute "waterFilterInfoUnit", "string"
        
        // Commands
        command "setTargetTemperatureC", ["number"]
        command "setTargetTemperatureF", ["number"]
        command "setRapidFreeze", ["string"]
        command "setExpressMode", ["string"]
        command "setExpressFridge", ["string"]
        command "setFreshAirFilter", ["string"]
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
    // Normalize payload – API may return a Map or a List with a single Map entry
    if (!data) return

    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    // If we still don't have a Map, bail out
    if (!(data instanceof Map)) return

    // Process door status
    if (data.doorStatus) {
        def doorEntry = null
        if (data.doorStatus instanceof List) {
            // Prefer the MAIN location if present, otherwise first entry
            doorEntry = data.doorStatus.find { it.locationName == "MAIN" } ?: data.doorStatus[0]
        }
        else {
            doorEntry = data.doorStatus
        }
        if (doorEntry?.doorState) {
            def doorState = doorEntry.doorState
            sendEvent(name: "doorState", value: doorState)

            def contactValue = (doorState == "OPEN") ? "open" : "closed"
            sendEvent(name: "contact", value: contactValue, descriptionText: "${device.displayName} contact is ${contactValue}")

            if (logDescText) {
                log.info "${device.displayName} DoorState: ${doorState} | Contact: ${contactValue}"
            }
        }
    }

    // Process temperature information
    if (data.temperatureInUnits) {
        def tempEntry = null
        if (data.temperatureInUnits instanceof List) {
            // Prefer FRIDGE location, otherwise first
            tempEntry = data.temperatureInUnits.find { it.locationName == "FRIDGE" } ?: data.temperatureInUnits[0]
        }
        else {
            tempEntry = data.temperatureInUnits
        }

        if (tempEntry) {
            // Handle temperature unit
            if (tempEntry.unit) {
                sendEvent(name: "temperatureUnit", value: tempEntry.unit)
            }
            
            // Handle target temperatures
            if (tempEntry.targetTemperatureC != null) {
                sendEvent(name: "targetTemperatureC", value: tempEntry.targetTemperatureC)
            }
            if (tempEntry.targetTemperatureF != null) {
                sendEvent(name: "targetTemperatureF", value: tempEntry.targetTemperatureF)
            }
        }
    }

    // Process power save
    if (data.powerSave?.powerSaveEnabled != null) {
        def powerSave = data.powerSave.powerSaveEnabled ? "enabled" : "disabled"
        sendEvent(name: "powerSaveEnabled", value: powerSave)
    }

    // Process eco friendly mode
    if (data.ecoFriendly?.ecoFriendlyMode) {
        def ecoMode = cleanEnumValue(data.ecoFriendly.ecoFriendlyMode)
        sendEvent(name: "ecoFriendlyMode", value: ecoMode)
    }

    // Process sabbath mode
    if (data.sabbath?.sabbathMode != null) {
        def sabbathMode = data.sabbath.sabbathMode ? "enabled" : "disabled"
        sendEvent(name: "sabbathMode", value: sabbathMode)
    }

    // Process refrigeration features
    if (data.refrigeration?.rapidFreeze != null) {
        def rapidFreeze = data.refrigeration.rapidFreeze ? "enabled" : "disabled"
        sendEvent(name: "rapidFreeze", value: rapidFreeze)
    }
    if (data.refrigeration?.expressMode != null) {
        def expressMode = data.refrigeration.expressMode ? "enabled" : "disabled"
        sendEvent(name: "expressMode", value: expressMode)
    }
    if (data.refrigeration?.expressFridge != null) {
        def expressFridge = data.refrigeration.expressFridge ? "enabled" : "disabled"
        sendEvent(name: "expressFridge", value: expressFridge)
    }
    if (data.refrigeration?.freshAirFilter) {
        def freshAirFilter = cleanEnumValue(data.refrigeration.freshAirFilter)
        sendEvent(name: "freshAirFilter", value: freshAirFilter)
    }

    // Process water filter info
    if (data.waterFilterInfo?.usedTime != null) {
        sendEvent(name: "usedTime", value: data.waterFilterInfo.usedTime)
    }
    if (data.waterFilterInfo?.unit) {
        sendEvent(name: "waterFilterInfoUnit", value: data.waterFilterInfo.unit)
    }
}

def setTargetTemperatureC(temperature) {
    logger("debug", "setTargetTemperatureC(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "FRIDGE"  // Default location, in a real implementation this might need to be configurable
        ],
        temperatureInUnits: [
            targetTemperatureC: temperature,
            unit: "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTargetTemperatureF(temperature) {
    logger("debug", "setTargetTemperatureF(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "FRIDGE"  // Default location, in a real implementation this might need to be configurable
        ],
        temperatureInUnits: [
            targetTemperatureF: temperature,
            unit: "F"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRapidFreeze(mode) {
    logger("debug", "setRapidFreeze(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            rapidFreeze: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setExpressMode(mode) {
    logger("debug", "setExpressMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            expressMode: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setExpressFridge(mode) {
    logger("debug", "setExpressFridge(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            expressFridge: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFreshAirFilter(mode) {
    logger("debug", "setFreshAirFilter(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            freshAirFilter: mode
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
