/*
*  Hubitat Driver for Awair Omni
*/

metadata {
    definition(name: "Awair Omni Local", namespace: "awair", author: "kwon2288", importUrl: "https://raw.githubusercontent.com/kwon2288/Hubitat-AwAir/master/Awair_Onmi_Local.groovy") {
        capability "Polling" // poll()
        capability "Configuration" // configure()
        capability "Initialize" // initialize()
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "CarbonDioxideMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "AirQuality"

        attribute "pm25", "number"
        attribute "temperature", "number"
        attribute "voc", "number"
        attribute "humidity", "number"
        attribute "airQuality", "number"
        attribute "lux", "number"
	attribute "noise", "number"
	attribute "carbonDioxide", "number"
        attribute "airQualityIndex", "number"

        attribute "aiq_desc", "ENUM", ["unknown", "poor", "fair", "good"]
        attribute "pm25_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
        attribute "co2_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
        attribute "voc_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
    }

    preferences {
        input("ip", "text", title: "IP Address", description: "IP of Awair Device", required: true, defaultValue: "192.168.0.x")
        input("urlPath", "text", title: "API Path", description: "Path to the Awair Air Data", required: true, defaultValue: "/air-data/latest")

        input name: "pollingInterval", type: "number", title: "Time (seconds) between status checks", defaultValue: 300

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// Runs when the driver is installed
void installed() {
    if (logEnable) {
        log.debug "installed..."
    }

    initialize()
    runIn(2, poll)
}

// Runs when the driver preferences are saved/updated
void updated() {
    configure()
    runIn(2, poll)
}

// Method for the initialize capability
// Currently, this just resets the attributes and calls the method to poll the device
def initialize() {
    if (logEnable) {
        log.debug "initializing values"
    }

    //Clear and initialize any state variables
    state.clear()
    state.pm25readings = []

    //Set some initial values
    fireUpdate("voc", -1, "ppb", "voc is ${-1} ppb")
    fireUpdate("pm25", -1, "ug/m3", "pm25 is ${-1} ug/m3")
    fireUpdate("airQuality", -1, "", "airQuality is ${-1}")
    fireUpdate("temperature", -1, "°${location.temperatureScale}", "Temperature is ${-1}°${location.temperatureScale}")
    fireUpdate("carbonDioxide", -1, "ppm", "carbonDioxide is ${-1} ppm")
    fireUpdate("humidity", -1, "%", "humidity is ${-1}")
    fireUpdate("lux", -1, "lux", "Lux is ${-1} lux")
    fireUpdate("noise", -1, "dB", "Noise is ${-1} dB")
    fireUpdate("airQualityIndex", 0, "", "Current calculated AQI is 0")

    fireUpdate_small("aiq_desc", "unknown")
    fireUpdate_small("voc_desc", "unknown")
    fireUpdate_small("co2_desc", "unknown")
    fireUpdate_small("pm25_desc", "unknown")

    runIn(2, poll)
}

// Method for the configuration capability
// TODO - Don't use this, instead integrate into the main code
def configure() {
    try {
        def Params = [
                uri        : "http://" + ip,
                path       : "/settings/config/data",
                contentType: "application/json"
        ]

        asynchttpGet('parseConfig', Params)

        if (logEnable) {
            log.debug "poll state"
        }
    } catch (Exception e) {
        if (logEnable) {
            log.error "error occured calling httpget ${e}"
        } else {
            log.error "error occured calling httpget"
        }
    }

    runIn(pollingInterval, poll)
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("txtEnable", [value: "false", type: "bool"])
}

def parseConfig(response, data) {
    try {
        if (response.getStatus() == 200 || response.getStatus() == 207) {
            if (logEnable) {
                log.debug "start parsing"
            }

            awairConfig = parseJson(response.data)

            //Awair UUID
            updateDataValue("Device UUID", awairConfig.device_uuid)

            //Awair MAC
            updateDataValue("MAC Address", awairConfig.wifi_mac)

            //Awair Firmware Version
            updateDataValue("Firmware", awairConfig.fw_version)

        } else {
            log.error "parsing error"
        }
    } catch (Exception e) {
        log.error "error #5415 : ${e}"
    }
}


// Method for the polling capability
def poll() {
    try {
        def Params = [
                uri        : "http://" + ip,
                path       : urlPath,
                contentType: "application/json"
        ]

        asynchttpGet('receiveData', Params)

        if (logEnable) {
            log.debug "poll state"
        }
    } catch (Exception e) {
        if (logEnable) {
            log.error "error occured calling httpget ${e}"
        } else {
            log.error "error occured calling httpget"
        }
    }

    runIn(pollingInterval, poll)
}

def receiveData(response, data) {
    try {
        if (response.getStatus() == 200 || response.getStatus() == 207) {
            if (logEnable) {
                log.debug "start parsing"
            }

            awairData = parseJson(response.data)

            // VOC
            currVocDesc = getAttribute("voc_desc")
            vocLevel = awairData.voc
            newVocDesc = "unknown"

            fireUpdate("voc", vocLevel, "ppb", "voc is ${vocLevel} ppb")

            // Calculate VOC Descriptive Text
            if (vocLevel > 8332) {
                newVocDesc = "hazardous"
            } else if (vocLevel > 3333) {
                newVocDesc = "bad"
            } else if (vocLevel > 1000) {
                newVocDesc = "poor"
            } else if (vocLevel > 333) {
                newVocDesc = "fair"
            } else {
                newVocDesc = "good"
            }

            if (currVocDesc != newVocDesc) {
                fireUpdate_small("voc_desc", newVocDesc)
            }

            // PM 2.5
            currPm25Desc = getAttribute("alert_pm25")
            pm25Level = awairData.pm25
            newPm25Desc = "unknown"

            fireUpdate("pm25", pm25Level, "ug/m3", "pm25 is ${pm25Level} ug/m3")

            // Calculate PM 2.5 Descriptive Text
            if (pm25Level > 75) {
                newPm25Desc = "hazardous"
            } else if (pm25Level > 55) {
                newPm25Desc = "bad"
            } else if (pm25Level > 35) {
                newPm25Desc = "poor"
            } else if (pm25Level > 15) {
                newPm25Desc = "fair"
            } else {
                newPm25Desc = "good"
            }

            if (currPm25Desc != newPm25Desc) {
                fireUpdate_small("pm25_desc", newPm25Desc)
            }

            //Figure out the EPA AQI
            state.pm25readings << pm25Level
            currAqi = calculateAqi()

            fireUpdate("airQualityIndex", currAqi, "", "Current calculated AQI is ${currAqi}")


            // AIQ Score
            currAiqDesc = getAttribute("aiq_desc") // Grab the current descriptive text for the AIQ score
            aiqScore = awairData.score
            newAiqScore = "unknown"

            fireUpdate("airQuality", aiqScore, "", "Awair Score is ${aiqScore}")

            // Calculate the text description for the Score
            if (aiqScore > 80) {
                newAiqScore = "good"
            } else if (aiqScore > 60) {
                newAiqScore = "fair"
            } else {
                newAiqScore = "poor"
            }

            if (currAiqDesc != newAiqScore) {
                fireUpdate_small("aiq_desc", newAiqScore)
            }

            // Temperature
            temperature = convertTemperatureIfNeeded(awairData.temp, "c", 1)
            fireUpdate("temperature", temperature, "°${location.temperatureScale}", "Temperature is ${temperature}°${location.temperatureScale}")

            // CO2
            currCo2Desc = getAttribute("co2_desc")
            co2Level = awairData.co2
            newCo2Desc = "unknown"

            fireUpdate("carbonDioxide", co2Level, "ppm", "carbonDioxide is ${co2Level} ppm")

            // Calculate CO2 Descriptive Text
            if (co2Level > 2500) {
                newCo2Desc = "hazardous"
            } else if (co2Level > 1500) {
                newCo2Desc = "bad"
            } else if (co2Level > 1000) {
                newCo2Desc = "poor"
            } else if (co2Level > 600) {
                newCo2Desc = "fair"
            } else {
                newCo2Desc = "good"
            }

            if (currCo2Desc != newCo2Desc) {
                fireUpdate_small("co2_desc", newCo2Desc)
            }

            // Humidity
            fireUpdate("humidity", (int) awairData.humid, "%", "humidity is ${awairData.humid}")
			
	    // Lux
            fireUpdate("lux", (int) awairData.lux, "lux", "lux is ${awairData.lux} lux")
			
            // Noise
            fireUpdate("noise", (int) awairData.spl_a, "dB", "Noise is ${awairData.spl_a} dB")

        } else {
            log.error "parsing error"
        }
    } catch (Exception e) {
        log.error "error #5415 : ${e}"
    }
}

// Calculate the AQI based on the stored PM2.5 Values
int calculateAqi() {
    // AQI PM2.5 Breakpoints
    // From data at https://aqs.epa.gov/aqsweb/documents/codetables/aqi_breakpoints.html
    // Using "PM2.5 - Local Conditions" data
    // Updated 2021 July 19
    def aqiBreakpoints = [
            [bpLow: 0.0, bpHigh: 12.0, aqiLow: 0, aqiHigh: 50, category: "Good"],
            [bpLow: 12.1, bpHigh: 35.4, aqiLow: 51, aqiHigh: 100, category: "Moderate"],
            [bpLow: 35.5, bpHigh: 55.4, aqiLow: 101, aqiHigh: 150, category: "Unhealthy for Sensitive Groups"],
            [bpLow: 55.5, bpHigh: 150.4, aqiLow: 151, aqiHigh: 200, category: "Unhealthy"],
            [bpLow: 150.5, bpHigh: 250.4, aqiLow: 201, aqiHigh: 300, category: "Very Unhealthy"],
            [bpLow: 250.5, bpHigh: 350.4, aqiLow: 301, aqiHigh: 400, category: "Hazardous"],
            [bpLow: 350.5, bpHigh: 500.4, aqiLow: 401, aqiHigh: 500, category: "Hazardous"],
            [bpLow: 500.5, bpHigh: 99999.9, aqiLow: 501, aqiHigh: 999, category: "Hazardous"]
    ]

    while (state.pm25readings.size() > 5) { //TODO - Do we want a dynamic max size based on update frequency?
        state.pm25readings.removeAt(0)
    }

    def totalPM25 = 0
    state.pm25readings.each { totalPM25 = totalPM25 + it }

    def avgPM25 = totalPM25 / state.pm25readings.size()
    state.avgPM25 = avgPM25 // TODO

    def aqiTier = [:]
    aqiBreakpoints.each {
        if (avgPM25 >= it.bpLow && myValue <= it.bpHigh) {
            aqiTier = it
        }
    }
    state.aqiBreakpoint = aqiTier // TODO

    //Now the fancy AQI formula
    def rawAqi = (aqiTier.aqiHigh - aqiTier.aqiLow) / (aqiTier.bpHigh - aqiTier.bpLow) * (avgPM25 - aqiTier.bpLow) + aqiTier.aqiLow

    state.rawAqi = rawAqi // TODO

    return Math.round(rawAqi)
}

void fireUpdate(name, value, unit, description) {
    result = [
            name           : name,
            value          : value,
            unit           : unit,
            descriptionText: description
    ]

    eventProcess(result)
}

void fireUpdate_small(name, value) {
    result = [
            name : name,
            value: value
    ]
    eventProcess(result)
}

def getAttribute(name) {
    return device.currentValue(name).toString()
}

void eventProcess(Map evt) {
    if (getAttribute(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange = true
        sendEvent(evt)

        if (txtEnable) log.info device.getName() + " " + evt.descriptionText
        if (logEnable) log.debug "result : " + evt
    }
}
