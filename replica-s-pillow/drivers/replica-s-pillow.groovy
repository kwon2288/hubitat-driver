/**
*  Replica S-PILLOW (AIHatinroo / peoplemulti)
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*
*  HubiThings Replica driver for Samsung SmartThings "S-PILLOW" anti-snoring pillow.
*  Trimmed build: pillow on/off control, pillow state, snore detection, and bedroom
*  temperature/humidity/CO2 only.
*  NOTE: every ST->Hubitat setter MUST be declared as a command or Replica blocks it
*  with a SecurityException.
*/
public static String version() {return "1.1.1"}

metadata
{
    definition(name: "Replica S-PILLOW", namespace: "replica", author: "kwon2288", importUrl:"")
    {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "CarbonDioxideMeasurement"
        capability "Configuration"
        capability "Refresh"

        attribute "pillowState", "enum", ["off", "preparing", "running", "stopped"]
        attribute "soundDetected", "enum", ["noSound", "snoring"]
        attribute "soundDetectionState", "enum", ["enabled", "disabled"]
        attribute "healthStatus", "enum", ["offline", "online"]

        // native commands (Hubitat -> ST)
        command "enableSoundDetection"
        command "disableSoundDetection"

        // Replica setters MUST be declared as commands or Replica blocks them (SecurityException)
        command "setSwitchValue",             [[name:"switch*",type:"ENUM"]]
        command "setSwitchOn"
        command "setSwitchOff"
        command "setPillowStateValue",        [[name:"pillowState*",type:"ENUM"]]
        command "setSoundDetectedValue",      [[name:"soundDetected*",type:"ENUM"]]
        command "setSoundDetectionStateValue",[[name:"soundDetectionState*",type:"ENUM"]]
        command "setTemperatureValue",        [[name:"temperature*",type:"NUMBER"]]
        command "setHumidityValue",           [[name:"humidity*",type:"NUMBER"]]
        command "setCarbonDioxideValue",      [[name:"carbonDioxide*",type:"NUMBER"]]
        command "setHealthStatusValue",       [[name:"healthStatus*",type:"ENUM"]]
    }
    preferences {
        input(name:"deviceInfoDisable", type: "bool", title: "Disable Info logging:", defaultValue: false)
        input(name:"deviceDebugEnable", type: "bool", title: "Enable Debug logging:", defaultValue: false)
    }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
    refresh()
}

def configure() {
    logInfo "${device.displayName} configured default rules"
    initialize()
    updateDataValue("rules", getReplicaRules())
    sendCommand("configure")
}

static Map getReplicaCommands() {
    return ([
        "setSwitchValue":[[name:"switch*",type:"ENUM"]], "setSwitchOn":[], "setSwitchOff":[],
        "setPillowStateValue":[[name:"pillowState*",type:"ENUM"]],
        "setSoundDetectedValue":[[name:"soundDetected*",type:"ENUM"]],
        "setSoundDetectionStateValue":[[name:"soundDetectionState*",type:"ENUM"]],
        "setTemperatureValue":[[name:"temperature*",type:"NUMBER"]],
        "setHumidityValue":[[name:"humidity*",type:"NUMBER"]],
        "setCarbonDioxideValue":[[name:"carbonDioxide*",type:"NUMBER"]],
        "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]
    ])
}

def setSwitchValue(value) {
    sendEvent(name: "switch", value: value, descriptionText: "${device.displayName} switch is $value")
    logInfo "${device.displayName} switch is $value"
}
def setSwitchOn()  { setSwitchValue("on") }
def setSwitchOff() { setSwitchValue("off") }

def setPillowStateValue(value) {
    sendEvent(name: "pillowState", value: value, descriptionText: "${device.displayName} pillow state is $value")
    setSwitchValue( (value in ["preparing","running"]) ? "on" : "off" )
    logInfo "${device.displayName} pillow state is $value"
}

def setSoundDetectedValue(value) {
    sendEvent(name: "soundDetected", value: value, descriptionText: "${device.displayName} sound detected is $value")
    logInfo "${device.displayName} sound detected is $value"
}

def setSoundDetectionStateValue(value) {
    sendEvent(name: "soundDetectionState", value: value, descriptionText: "${device.displayName} sound detection is $value")
    logInfo "${device.displayName} sound detection is $value"
}

def setTemperatureValue(value) {
    String unit = "°${getTemperatureScale()}"
    sendEvent(name: "temperature", value: value, unit: unit, descriptionText: "${device.displayName} temperature is $value $unit")
    logInfo "${device.displayName} temperature is $value $unit"
}

def setHumidityValue(value) {
    sendEvent(name: "humidity", value: value, unit: "%", descriptionText: "${device.displayName} humidity is $value %")
    logInfo "${device.displayName} humidity is $value %"
}

def setCarbonDioxideValue(value) {
    sendEvent(name: "carbonDioxide", value: value, unit: "ppm", descriptionText: "${device.displayName} CO2 is $value ppm")
    logInfo "${device.displayName} CO2 is $value ppm"
}

def setHealthStatusValue(value) {
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

static Map getReplicaTriggers() {
    return ([
        "on":[], "off":[],
        "enableSoundDetection":[], "disableSoundDetection":[],
        "refresh":[]
    ])
}

private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    data.version=version()
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now()])
}

def on()  { sendCommand("on") }
def off() { sendCommand("off") }

def enableSoundDetection()  { sendCommand("enableSoundDetection") }
def disableSoundDetection() { sendCommand("disableSoundDetection") }

void refresh() { sendCommand("refresh") }

static String getReplicaRules() {
    return """{"version":1,"components":[
{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"antiSnoringPillow","label":"command: on()"},"type":"hubitatTrigger"},
{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"antiSnoringPillow","label":"command: off()"},"type":"hubitatTrigger"},
{"trigger":{"name":"enableSoundDetection","label":"command: enableSoundDetection()","type":"command"},"command":{"name":"enableSoundDetection","type":"command","capability":"soundDetection","label":"command: enableSoundDetection()"},"type":"hubitatTrigger"},
{"trigger":{"name":"disableSoundDetection","label":"command: disableSoundDetection()","type":"command"},"command":{"name":"disableSoundDetection","type":"command","capability":"soundDetection","label":"command: disableSoundDetection()"},"type":"hubitatTrigger"},
{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},
{"trigger":{"type":"attribute","properties":{"value":{"enum":["off","preparing","running","stopped"],"type":"string"}},"additionalProperties":false,"required":["value"],"capability":"antiSnoringPillow","attribute":"state","label":"attribute: state.*"},"command":{"name":"setPillowStateValue","label":"command: setPillowStateValue(pillowState*)","type":"command","parameters":[{"name":"pillowState*","type":"ENUM"}]},"type":"smartTrigger"},
{"trigger":{"type":"attribute","properties":{"value":{"enum":["noSound","snoring"],"type":"string"}},"additionalProperties":false,"required":["value"],"capability":"soundDetection","attribute":"soundDetected","label":"attribute: soundDetected.*"},"command":{"name":"setSoundDetectedValue","label":"command: setSoundDetectedValue(soundDetected*)","type":"command","parameters":[{"name":"soundDetected*","type":"ENUM"}]},"type":"smartTrigger"},
{"trigger":{"type":"attribute","properties":{"value":{"enum":["enabled","disabled"],"type":"string"}},"additionalProperties":false,"required":["value"],"capability":"soundDetection","attribute":"soundDetectionState","label":"attribute: soundDetectionState.*"},"command":{"name":"setSoundDetectionStateValue","label":"command: setSoundDetectionStateValue(soundDetectionState*)","type":"command","parameters":[{"name":"soundDetectionState*","type":"ENUM"}]},"type":"smartTrigger"},
{"trigger":{"type":"attribute","properties":{"value":{"title":"TemperatureValue","type":"number","minimum":-460,"maximum":10000},"unit":{"type":"string","enum":["F","C"]}},"additionalProperties":false,"required":["value","unit"],"capability":"temperatureMeasurement","attribute":"temperature","label":"attribute: temperature.*"},"command":{"name":"setTemperatureValue","label":"command: setTemperatureValue(temperature*)","type":"command","parameters":[{"name":"temperature*","type":"NUMBER"}]},"type":"smartTrigger","mute":true},
{"trigger":{"title":"Percent","type":"attribute","properties":{"value":{"type":"number","minimum":0,"maximum":100},"unit":{"type":"string","enum":["%"],"default":"%"}},"additionalProperties":false,"required":["value"],"capability":"relativeHumidityMeasurement","attribute":"humidity","label":"attribute: humidity.*"},"command":{"name":"setHumidityValue","label":"command: setHumidityValue(humidity*)","type":"command","parameters":[{"name":"humidity*","type":"NUMBER"}]},"type":"smartTrigger","mute":true},
{"trigger":{"type":"attribute","properties":{"value":{"minimum":0,"maximum":1000000,"type":"number"},"unit":{"enum":["ppm"],"default":"ppm","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"carbonDioxideMeasurement","attribute":"carbonDioxide","label":"attribute: carbonDioxide.*"},"command":{"name":"setCarbonDioxideValue","label":"command: setCarbonDioxideValue(carbonDioxide*)","type":"command","parameters":[{"name":"carbonDioxide*","type":"NUMBER"}]},"type":"smartTrigger","mute":true},
{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true}
]}"""
}

private logInfo(msg)  { if(settings?.deviceInfoDisable != true) { log.info  "${msg}" } }
private logDebug(msg) { if(settings?.deviceDebugEnable == true) { log.debug "${msg}" } }
private logWarn(msg)  { log.warn   "${msg}" }
private logError(msg) { log.error  "${msg}" }
