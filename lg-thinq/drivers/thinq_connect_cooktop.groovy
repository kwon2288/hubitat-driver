/**
 *  ThinQ Connect Cooktop
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]
@Field List<String> COOKTOP_LOCATIONS = [
    "CENTER",
    "CENTER_FRONT",
    "CENTER_REAR",
    "LEFT_FRONT",
    "LEFT_REAR",
    "RIGHT_FRONT",
    "RIGHT_REAR",
    "BURNER_1",
    "BURNER_2",
    "BURNER_3",
    "BURNER_4",
    "BURNER_5",
    "BURNER_6",
    "BURNER_7",
    "BURNER_8",
    "INDUCTION_1",
    "INDUCTION_2",
    "SOUSVIDE_1"
]

metadata {
    definition(name: "ThinQ Connect Cooktop", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_cooktop.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState", "string"
        attribute "operationMode", "string"
        attribute "locationName", "string"
        attribute "powerLevel", "number"
        attribute "remoteControlEnabled", "string"
        
        // Timer attributes
        attribute "remainHour", "number"
        attribute "remainMinute", "number"
        
        // Commands
        command "setOperationMode", ["string"]
        command "setPowerLevel", ["number"]
        command "setRemainHour", ["number"]
        command "setRemainMinute", ["number"]
        command "getDeviceProfile"
    }

    preferences {
        section {
            input name: "zoneLocation", title: "Cooktop Zone Location", type: "enum", options: ["AUTO"] + COOKTOP_LOCATIONS, defaultValue: "AUTO", required: false
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

    // If a fixed location is configured, use it; otherwise keep last detected location
    def configuredLocation = getConfiguredLocation()
    if (configuredLocation) {
        updateDataValue("locationName", configuredLocation)
        sendEvent(name: "locationName", value: configuredLocation)
    }
    else {
        def lastDetectedLocation = getDataValue("locationName")
        if (lastDetectedLocation) {
            sendEvent(name: "locationName", value: lastDetectedLocation)
        }
    }

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

    // Optional top-level operation resource
    if (data.operation?.operationMode) {
        def operationMode = cleanEnumValue(data.operation.operationMode)
        sendEvent(name: "operationMode", value: operationMode)
    }

    // Select location-specific section when resources are arrays by location
    def locationName = getActiveLocation()

    // Auto-detect active zone when location is not fixed in preferences
    if (!getConfiguredLocation()) {
        def detectedLocation = detectActiveLocation(data)
        if (detectedLocation && detectedLocation != locationName) {
            logger("debug", "Auto-detected active cooktop zone: ${detectedLocation}")
            locationName = detectedLocation
            updateDataValue("locationName", detectedLocation)
            sendEvent(name: "locationName", value: detectedLocation)
        }
    }

    def zoneEntry = selectLocationEntry(data.cookingZone, locationName)
    if (zoneEntry?.currentState) {
        def currentState = zoneEntry.currentState
        sendEvent(name: "currentState", value: currentState)

        def switchState = (currentState =~ /(?i)power_off|pause|off/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)

        if (logDescText) {
            log.info "${device.displayName} [${locationName}] CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    def powerEntry = selectLocationEntry(data.power, locationName)
    if (powerEntry?.powerLevel != null) {
        sendEvent(name: "powerLevel", value: powerEntry.powerLevel)
    }

    def remoteEntry = selectLocationEntry(data.remoteControlEnable, locationName)
    if (remoteEntry?.remoteControlEnabled != null) {
        def remoteEnabled = remoteEntry.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    def timerEntry = selectLocationEntry(data.timer, locationName)
    if (timerEntry?.remainHour != null) {
        sendEvent(name: "remainHour", value: timerEntry.remainHour)
    }
    if (timerEntry?.remainMinute != null) {
        sendEvent(name: "remainMinute", value: timerEntry.remainMinute)
    }
}

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
}

def on() {
    logger("debug", "on()")
    // No generic ON command in API; use setPowerLevel for the selected zone
}

def off() {
    logger("debug", "off()")
    // No generic OFF command in API; use setPowerLevel(0) or timer controls for the selected zone
}

def setOperationMode(mode) {
    logger("debug", "setOperationMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            operationMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setPowerLevel(level) {
    logger("debug", "setPowerLevel(${level})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        // Keep timer in payload to mirror Python custom command behavior
        timer: [
            remainHour: (device.currentValue("remainHour") ?: 0) as Integer,
            remainMinute: (device.currentValue("remainMinute") ?: 0) as Integer
        ],
        power: [
            powerLevel: level
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRemainHour(hours) {
    logger("debug", "setRemainHour(${hours})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        // Keep power/timer together to mirror Python custom command behavior
        power: [
            powerLevel: (device.currentValue("powerLevel") ?: 0) as Integer
        ],
        timer: [
            remainHour: hours,
            remainMinute: (device.currentValue("remainMinute") ?: 0) as Integer
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRemainMinute(minutes) {
    logger("debug", "setRemainMinute(${minutes})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        // Keep power/timer together to mirror Python custom command behavior
        power: [
            powerLevel: (device.currentValue("powerLevel") ?: 0) as Integer
        ],
        timer: [
            remainHour: (device.currentValue("remainHour") ?: 0) as Integer,
            remainMinute: minutes
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

private def getActiveLocation() {
    def configuredLocation = getConfiguredLocation()
    def loc = configuredLocation ?: getDataValue("locationName") ?: "CENTER"
    return COOKTOP_LOCATIONS.contains(loc) ? loc : "CENTER"
}

private def getConfiguredLocation() {
    def loc = zoneLocation
    if (!loc || loc == "AUTO") return null
    return COOKTOP_LOCATIONS.contains(loc) ? loc : null
}

private def detectActiveLocation(data) {
    // Priority 1: active power level (>0)
    def byPower = findLocationByResource(data.power) { entry ->
        ((entry?.powerLevel ?: 0) as Integer) > 0
    }
    if (byPower) return byPower

    // Priority 2: current state that looks active
    def byState = findLocationByResource(data.cookingZone) { entry ->
        def state = (entry?.currentState ?: "").toString()
        return state && !(state =~ /(?i)^power_off$|^off$|^pause$|^standby$/)
    }
    if (byState) return byState

    // Priority 3: active timer
    def byTimer = findLocationByResource(data.timer) { entry ->
        def h = (entry?.remainHour ?: 0) as Integer
        def m = (entry?.remainMinute ?: 0) as Integer
        return (h > 0 || m > 0)
    }
    if (byTimer) return byTimer

    // Fallback: first valid location in known resources
    return firstLocationFromResources([data.cookingZone, data.power, data.remoteControlEnable, data.timer])
}

private def findLocationByResource(resource, Closure<Boolean> matcher) {
    if (!(resource instanceof List)) return null

    def found = resource.find { entry ->
        def loc = extractLocationName(entry)
        return loc && COOKTOP_LOCATIONS.contains(loc) && matcher(entry)
    }

    return extractLocationName(found)
}

private def firstLocationFromResources(resources) {
    for (resource in resources) {
        if (resource instanceof List) {
            def found = resource.find { entry ->
                def loc = extractLocationName(entry)
                return loc && COOKTOP_LOCATIONS.contains(loc)
            }
            def loc = extractLocationName(found)
            if (loc) return loc
        }
    }
    return null
}

private def extractLocationName(entry) {
    if (!(entry instanceof Map)) return null
    return entry?.location?.locationName ?: entry?.locationName
}

private def selectLocationEntry(resource, String locationName) {
    if (!resource) return null

    if (resource instanceof List) {
        def entry = resource.find {
            it?.location?.locationName == locationName || it?.locationName == locationName
        }
        return entry ?: resource[0]
    }

    return (resource instanceof Map) ? resource : null
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
