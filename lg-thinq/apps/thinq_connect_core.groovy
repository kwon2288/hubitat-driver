/**
 *
 *  LG ThinQ Connect Integration
 *
 *  Copyright 2026
 *
 *  Uses the official LG ThinQ Connect API with PAT token authentication
 *  Based on the pythinqconnect SDK and Home Assistant integration
 *
 */

import groovy.transform.Field
import java.text.SimpleDateFormat
import groovy.json.JsonSlurper
import java.security.MessageDigest

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

definition(
    name: "ThinQ Connect Integration",
    namespace: "jonozzz",
    author: "Ionut Turturica",
    description: "Integrate LG ThinQ smart devices with Hubitat using official ThinQ Connect API.",
    category: "My Apps",
    importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/apps/thinq_connect_core.groovy",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    documentationLink: "https://smartsolution.developer.lge.com/en/apiManage/thinq_connect")

preferences {
    page(name: "prefMain")
    page(name: "prefAuth")
    page(name: "prefMqtt")
    page(name: "prefMQTTClient")
    page(name: "prefDevices")
}

@Field static def supportedDeviceTypes = [
    "DEVICE_WASHER",
    "DEVICE_DRYER", 
    "DEVICE_DISH_WASHER",
    "DEVICE_REFRIGERATOR",
    "DEVICE_OVEN",
    "DEVICE_COOKTOP",
    "DEVICE_WASHTOWER",
    "DEVICE_WASHTOWER_WASHER",
    "DEVICE_WASHTOWER_DRYER",
    "DEVICE_MICROWAVE_OVEN",
    "DEVICE_AIR_CONDITIONER",
    "DEVICE_AIR_PURIFIER",
    "DEVICE_STYLER",
    "DEVICE_DEHUMIDIFIER"
]

@Field static def countryNameMap = [
    "US": "United States",
    "CA": "Canada", 
    "KR": "South Korea",
    "JP": "Japan",
    "AU": "Australia",
    "GB": "United Kingdom",
    "DE": "Germany",
    "FR": "France",
    "IT": "Italy",
    "ES": "Spain",
    "NL": "Netherlands",
    "SE": "Sweden",
    "NO": "Norway",
    "DK": "Denmark",
    "FI": "Finland"
]

@Field static def regionMapping = [
    // KIC
    "AU": "kic", "BD": "kic", "CN": "kic", "HK": "kic", "ID": "kic", "IN": "kic", "JP": "kic", "KH": "kic", "KR": "kic", "LA": "kic",
    "LK": "kic", "MM": "kic", "MY": "kic", "NP": "kic", "NZ": "kic", "PH": "kic", "SG": "kic", "TH": "kic", "TW": "kic", "VN": "kic",

    // AIC
    "AG": "aic", "AR": "aic", "AW": "aic", "BB": "aic", "BO": "aic", "BR": "aic", "BS": "aic", "BZ": "aic", "CA": "aic", "CL": "aic",
    "CO": "aic", "CR": "aic", "CU": "aic", "DM": "aic", "DO": "aic", "EC": "aic", "GD": "aic", "GT": "aic", "GY": "aic", "HN": "aic",
    "HT": "aic", "JM": "aic", "KN": "aic", "LC": "aic", "MX": "aic", "NI": "aic", "PA": "aic", "PE": "aic", "PR": "aic", "PY": "aic",
    "SR": "aic", "SV": "aic", "TT": "aic", "US": "aic", "UY": "aic", "VC": "aic", "VE": "aic",

    // EIC
    "AE": "eic", "AF": "eic", "AL": "eic", "AM": "eic", "AO": "eic", "AT": "eic", "AZ": "eic", "BA": "eic", "BE": "eic", "BF": "eic",
    "BG": "eic", "BH": "eic", "BJ": "eic", "BY": "eic", "CD": "eic", "CF": "eic", "CG": "eic", "CH": "eic", "CI": "eic", "CM": "eic",
    "CV": "eic", "CY": "eic", "CZ": "eic", "DE": "eic", "DJ": "eic", "DK": "eic", "DZ": "eic", "EE": "eic", "EG": "eic", "ES": "eic",
    "ET": "eic", "FI": "eic", "FR": "eic", "GA": "eic", "GB": "eic", "GE": "eic", "GH": "eic", "GM": "eic", "GN": "eic", "GQ": "eic",
    "GR": "eic", "HR": "eic", "HU": "eic", "IE": "eic", "IL": "eic", "IQ": "eic", "IR": "eic", "IS": "eic", "IT": "eic", "JO": "eic",
    "KE": "eic", "KG": "eic", "KW": "eic", "KZ": "eic", "LB": "eic", "LR": "eic", "LT": "eic", "LU": "eic", "LV": "eic", "LY": "eic",
    "MA": "eic", "MD": "eic", "ME": "eic", "MK": "eic", "ML": "eic", "MR": "eic", "MT": "eic", "MU": "eic", "MW": "eic", "NE": "eic",
    "NG": "eic", "NL": "eic", "NO": "eic", "OM": "eic", "PK": "eic", "PL": "eic", "PS": "eic", "PT": "eic", "QA": "eic", "RO": "eic",
    "RS": "eic", "RU": "eic", "RW": "eic", "SA": "eic", "SD": "eic", "SE": "eic", "SI": "eic", "SK": "eic", "SL": "eic", "SN": "eic",
    "SO": "eic", "ST": "eic", "SY": "eic", "TD": "eic", "TG": "eic", "TN": "eic", "TR": "eic", "TZ": "eic", "UA": "eic", "UG": "eic",
    "UZ": "eic", "XK": "eic", "YE": "eic", "ZA": "eic", "ZM": "eic"
]

@Field static def caCertUrl = "https://www.amazontrust.com/repository/AmazonRootCA1.pem"

def prefMain() {
    if (state.client_id == null)
        state.client_id = "thinq-open-" + (UUID.randomUUID().toString().replaceAll(/-/,""))

    if (state.caCert == null) {
        httpGet([
            uri: caCertUrl,
            textParser: true,
            ignoreSSLIssues: true
        ]) {
            resp ->
                state.caCert = resp.data.text
        }
    }

    return dynamicPage(name: "prefMain", title: "ThinQ Connect Setup", nextPage: "prefAuth", uninstall: false, install: false) {
        section {
            input "logLevel", "enum", title: "Log Level", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            paragraph "This integration uses the official LG ThinQ Connect API with PAT (Personal Access Token) authentication."
            paragraph "You will need:"
            paragraph "1. A PAT token from LG ThinQ Connect portal"
            paragraph "2. MQTT certificates (can be hardcoded or generated once)"
            paragraph "3. Your country/region selection"
        }
    }
}

def prefAuth() {
    return dynamicPage(name: "prefAuth", title: "Authentication Setup", nextPage: "prefMqtt", uninstall: false, install: false) {
        section("ThinQ Connect API") {
            input "patToken", "text", title: "PAT Token", description: "Enter your Personal Access Token from LG ThinQ Connect", required: true
            input "countryCode", "enum", title: "Country", options: getCountryOptions(), required: true, submitOnChange: true
            
            if (countryCode) {
                state.region = regionMapping[countryCode] ?: "aic"
                state.apiBase = "https://api-${state.region}.lgthinq.com"
                paragraph "API Region: ${state.region}"
                paragraph "API Base URL: ${state.apiBase}"
            }
        }
        
        section("Test Connection") {
            if (patToken && countryCode) {
                def testResult = testApiConnection()
                if (testResult.success) {
                    paragraph "✅ Connection successful! Found ${testResult.deviceCount} devices."
                } else {
                    paragraph "❌ Connection failed: ${testResult.error}"
                }
            } else {
                paragraph "Enter PAT token and country to test connection"
            }
        }
    }
}

def getCountryOptions() {
    def options = [:]

    // Show all supported countries from region mapping.
    // Use friendly names where defined, otherwise show ISO code.
    regionMapping.keySet().sort().each { code ->
        options[code] = countryNameMap[code] ?: code
    }

    return options
}

def prefMqtt() {
    return dynamicPage(name: "prefMqtt", title: "MQTT Configuration", nextPage: "prefMQTTClient", uninstall: false, install: false) {
        section("MQTT Server") {
            if (patToken && countryCode) {
                def routeResult = getMqttServerFromApi()
                if (routeResult.success) {
                    paragraph "✅ MQTT Server: ${routeResult.mqttServer}"
                    state.mqttServer = routeResult.mqttServer
                } else {
                    paragraph "❌ Failed to get MQTT server: ${routeResult.error}"
                }
            } else {
                paragraph "Complete authentication setup first to get MQTT server details"
            }
        }
        
        section("Certificate Generation") {
            paragraph "The integration will automatically issue a certificate using LG's API. You only need to provide a CSR (Certificate Signing Request)."
            input "csrData", "textarea", title: "CSR (Certificate Signing Request)", required: true,
                description: "Paste your CSR in PEM format"
            input "privateKey", "textarea", title: "Private Key (PEM format)", required: true
        }
    }
}

def prefMQTTClient() {
    def pk_sig = calculateMD5(csrData + privateKey)
    return dynamicPage(name: "prefMQTTClient", title: "MQTT Client Registration", nextPage: "prefDevices", uninstall: false, install: false) {
        section {
            if(!state.clientCertificate || !state.mqttSubscriptions || state.pk_sig != pk_sig)
                if (patToken && countryCode && state.mqttServer && csrData) {
                    def mqttResult = setupMqttClient()
                    if (mqttResult.success) {
                        paragraph "✅ MQTT client registered and certificate issued successfully"
                        paragraph "📋 Subscriptions: ${mqttResult.subscriptions?.join(', ')}"
                        state.mqttSubscriptions = mqttResult.subscriptions
                        state.clientCertificate = mqttResult.certificate
                        state.pk_sig = pk_sig
                    } else {
                        // paragraph "Try again in 1-2 minutes"
                        paragraph "❌ MQTT setup failed: ${mqttResult.error}"
                    }
                } else {
                    paragraph "Complete all fields above to set up MQTT"
                }
            else
                paragraph "✅ MQTT client already registered"
        }
    }
}

def prefDevices() {
    if (!patToken || !countryCode) {
        return returnErrorPage("Please complete authentication setup first", "prefAuth")
    }
    
    def devices = getDevices()
    if (devices == null) {
        return returnErrorPage("Unable to retrieve devices. Please check your PAT token and try again.", "prefAuth")
    }

    def deviceList = [:]
    state.foundDevices = []
    
    devices.each { device ->
        logger("debug", "${device}")

        if (supportedDeviceTypes.contains(device.deviceInfo.deviceType)) {
            deviceList << ["${device.deviceId}": "${device.deviceInfo.alias} (${device.deviceInfo.deviceType})"]
            state.foundDevices << [
                id: device.deviceId,
                name: device.deviceInfo.alias,
                type: device.deviceInfo.deviceType,
                modelName: device.deviceInfo.modelName,
                reportable: device.deviceInforeportable
            ]
        }
    }

    return dynamicPage(name: "prefDevices", title: "Device Selection", uninstall: false, install: true) {
        section {
            if (deviceList.size() > 0) {
                input "selectedDevices", "enum", title: "Select Devices", required: true, options: deviceList, multiple: true
                paragraph "Found ${deviceList.size()} supported devices"
            } else {
                paragraph "No supported devices found. Supported types: ${supportedDeviceTypes.join(', ')}"
            }
        }
    }
}

def returnErrorPage(message, nextPage) {
    return dynamicPage(name: "prefError", title: "Error Occurred", nextPage: nextPage, uninstall: false, install: false) {
        section {
            paragraph message
        }
    }
}

def installed() {
    logger("debug", "installed()")
    initialize()
}

def updated() {
    logger("debug", "updated()")
    unschedule()
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
    unschedule()
    for (d in getChildDevices()) {
        deleteChildDevice(d.deviceNetworkId)
    }
}

def initialize() {
    logger("debug", "initialize()")
    
    cleanupChildDevices()
    
    for (deviceId in selectedDevices) {
        def deviceDetails = state.foundDevices.find { it.id == deviceId }
        if (!deviceDetails) continue
        
        def driverName = getDriverName(deviceDetails.type)
        if (!driverName) continue
        
        def childDevice = getChildDevice("thinqconnect:${deviceDetails.id}")
        if (childDevice == null) {
            childDevice = addChildDevice("jonozzz", driverName, "thinqconnect:${deviceDetails.id}", 1234, 
                ["name": deviceDetails.name, isComponent: false])
            
            // Set the first device as master for MQTT
            if (!findMasterDevice()) {
                childDevice.updateDataValue("master", "true")
            } else {
                childDevice.updateDataValue("master", "false")
            }
        }
        
        // Initialize device with current status
        getDeviceStatus(deviceDetails, childDevice)
    }
    
    // Register for push notifications
    registerPushNotifications()
    
    // Schedule periodic refresh, every Monday at 3am
    schedule("0 0 3 ? * MON", refreshDevices)
}

def getDriverName(deviceType) {
    switch (deviceType) {
        case "DEVICE_WASHTOWER":
            return "ThinQ Connect WashTower"
        case "DEVICE_WASHER":
        case "DEVICE_WASHTOWER_WASHER":
            return "ThinQ Connect Washer"
        case "DEVICE_DRYER":
        case "DEVICE_WASHTOWER_DRYER":
            return "ThinQ Connect Dryer"
        case "DEVICE_DISH_WASHER":
            return "ThinQ Connect Dishwasher"
        case "DEVICE_REFRIGERATOR":
            return "ThinQ Connect Refrigerator"
        case "DEVICE_OVEN":
            return "ThinQ Connect Oven"
        case "DEVICE_COOKTOP":
            return "ThinQ Connect Cooktop"
        case "DEVICE_MICROWAVE_OVEN":
            return "ThinQ Connect Microwave Oven"
        case "DEVICE_AIR_CONDITIONER":
            return "ThinQ Connect Air Conditioner"
        case "DEVICE_AIR_PURIFIER": 
            return "ThinQ Connect Air Purifier"
        case "DEVICE_STYLER": 
            return "ThinQ Connect Styler"
        case "DEVICE_DEHUMIDIFIER":
            return "ThinQ Connect Dehumidifier"
        default:
            return null
    }
}

def testApiConnection() {
    logger("debug", "testApiConnection()")
    
    try {
        def devices = apiGet("/devices")
        if (devices != null) {
            return [success: true, deviceCount: devices.size()]
        } else {
            return [success: false, error: "No response from API"]
        }
    } catch (Exception e) {
        logger("error", "API connection test failed: ${e}")
        return [success: false, error: e.message]
    }
}

def getMqttServerFromApi() {
    logger("debug", "getMqttServerFromApi()")
    
    try {
        def routeData = apiGet("/route")
        if (routeData != null && routeData.mqttServer) {
            // Clean up the MQTT server URL (remove protocol prefix and extract hostname)
            def mqttServer = routeData.mqttServer.replace("mqtts://", "").split(":")[0]
            return [success: true, mqttServer: "ssl://${mqttServer}:8883"]
        } else {
            return [success: false, error: "No MQTT server in route response"]
        }
    } catch (Exception e) {
        logger("error", "Failed to get MQTT server from API: ${e}")
        return [success: false, error: e.message]
    }
}

def setupMqttClient() {
    logger("debug", "setupMqttClient()")
    
    try {
        // Step 1: Register MQTT client (returns null on success)
        def clientBody = [
            type: "MQTT",
            "service-code": "SVC202",
            "device-type": "607",
            allowExist: true
        ]
        
        def clientResult = apiPost("/client", clientBody)
        // Note: Client registration returns null/empty on success
        logger("debug", "Client registration result: ${clientResult}")
        
        // Ensure csrData has proper PEM headers
        def formattedCsrData = csrData
        if (csrData && !csrData.contains("-----BEGIN CERTIFICATE REQUEST-----")) {
            // Remove any existing whitespace/newlines
            def csrContent = csrData.trim()
            // Add PEM headers
            formattedCsrData = "-----BEGIN CERTIFICATE REQUEST-----\n" + csrContent + "\n-----END CERTIFICATE REQUEST-----"
        }
        
        // Step 2: Issue certificate with CSR to get certificate and subscriptions
        def certBody = [
            "service-code": "SVC202",
            "csr": formattedCsrData
        ]
        
        def certResult = apiPost("/client/certificate", certBody)?.result
        if (certResult != null) {
            def certificate = certResult.certificatePem
            def subscriptions = certResult.subscriptions
            
            if (certificate && subscriptions) {
                return [
                    success: true, 
                    certificate: certificate,
                    subscriptions: subscriptions
                ]
            } else {
                return [success: false, error: "Missing certificate or subscriptions in response"]
            }
        } else {
            return [success: false, error: "Failed to issue certificate"]
        }
    } catch (Exception e) {
        logger("error", "MQTT setup failed: ${e}")
        return [success: false, error: e.message]
    }
}

def getDevices() {
    logger("debug", "getDevices()")
    return apiGet("/devices")
}

def getDeviceProfile(deviceId) {
    logger("debug", "getDeviceProfile(${deviceId})")
    return apiGet("/devices/${deviceId}/profile")
}

def getDeviceStatus(deviceDetails, childDevice) {
    logger("debug", "getDeviceStatus(${deviceDetails.id})")
    
    def status = apiGet("/devices/${deviceDetails.id}/state")

    if (status != null && childDevice != null) {
        childDevice.processStateData(status)
    }
    return status
}

def getDeviceState(deviceId) {
    return apiGet("/devices/${deviceId}/state")
}

def registerPushNotifications() {
    logger("debug", "registerPushNotifications()")
    
    for (deviceId in selectedDevices) {
        try {
            //apiPost("/push/${deviceId}/subscribe", [:])
            apiPost("/event/${deviceId}/subscribe", [expire: [unit: "HOUR", timer: 4464]])
        } catch (Exception e) {
            logger("warn", "Failed to register notifications for device ${deviceId}: ${e}")
        }
    }
}

def refreshDevices() {
    logger("debug", "refreshDevices()")
    
    for (deviceId in selectedDevices) {
        def deviceDetails = state.foundDevices.find { it.id == deviceId }
        def childDevice = getChildDevice("thinqconnect:${deviceId}")
        if (deviceDetails && childDevice) {
            getDeviceStatus(deviceDetails, childDevice)
        }
    }
}

def apiGet(endpoint) {
    logger("debug", "apiGet(${endpoint})")
    
    def headers = getApiHeaders()
    def uri = "${state.apiBase}${endpoint}"
    
    try {
        httpGet([
            uri: uri,
            headers: headers,
            requestContentType: "application/json",
            ignoreSSLIssues: true
        ]) { resp ->
            if (resp.status == 200) {
                result = resp.data?.response
            } else {
                logger("error", "API GET failed: ${resp.status} - ${resp.data}")
                result = null
            }
        }
        logger("debug", "apiGet(${endpoint}, ${result})")
        return result
    } catch (Exception e) {
        logger("error", "API GET exception: ${e}")
        return null
    }
}

def apiPost(endpoint, body) {
    logger("debug", "apiPost(${endpoint}, ${body})")
    
    def headers = getApiHeaders()
    def uri = "${state.apiBase}${endpoint}"
    
    try {
        httpPost([
            uri: uri,
            headers: headers,
            requestContentType: "application/json",
            body: body,
            ignoreSSLIssues: true
        ]) { resp ->
            if (resp.status == 200) {
                logger("debug", "API POST success: ${resp.status} - ${resp.data}")
                result = resp.data?.response
            } else {
                logger("error", "API POST failed: ${resp.status} - ${resp.data}")
                result = null
            }
        }
        return result
    } catch (groovyx.net.http.HttpResponseException e) {
        def resp = e.response
        logger("error", "API POST exception: (${resp.status}) ${resp.data}")
        return null
    }
}

def getApiHeaders() {
    return [
        "Authorization": "Bearer ${patToken}",
        "x-country": countryCode,
        "x-message-id": generateMessageId(),
        "x-client-id": state.client_id,
        "x-api-key": "v6GFvkweNo7DK7yD3ylIZ9w52aKBU0eJ7wLXkSR3",
        "x-service-phase": "OP"
    ]
}

def generateMessageId() {
    return UUID.randomUUID().toString().replaceAll(/-/, "")[0..21]
}

def findMasterDevice() {
    logger("debug", "findMasterDevice()")
    return getChildDevices().find {
        it.hasCapability("Initialize") && it.getDataValue("master") == "true"
    }
}

def cleanupChildDevices() {
    logger("debug", "cleanupChildDevices()")

    for (d in getChildDevices()) {
        def deviceId = d.deviceNetworkId.replace("thinqconnect:", "")

        def deviceFound = false
        for (dev in selectedDevices) {
            if (dev == deviceId) {
                deviceFound = true
                break
            }
        }

        if (!deviceFound) {
            deleteChildDevice(d.deviceNetworkId)
        }
    }
}

def retrieveMqttDetails() {
    logger("debug", "retrieveMqttDetails()")
    
    // Ensure privateKey has proper PEM headers
    def formattedPrivateKey = privateKey
    if (privateKey && !privateKey.contains("-----BEGIN PRIVATE KEY-----")) {
        // Remove any existing whitespace/newlines
        def keyContent = privateKey.trim()
        // Add PEM headers
        formattedPrivateKey = "-----BEGIN PRIVATE KEY-----\n" + keyContent + "\n-----END PRIVATE KEY-----"
    }
    
    return [
        server: state.mqttServer,
        subscriptions: state.mqttSubscriptions ?: [],
        certificate: state.clientCertificate,
        privateKey: formattedPrivateKey,
        caCertificate: state.caCert,
        clientId: state.client_id
    ]
}

def processMqttMessage(dev, payload) {
    logger("debug", "processMqttMessage(${dev}, ${payload})")

    switch (payload.pushType) {
        case "DEVICE_STATUS":
            def targetDevice = getChildDevice("thinqconnect:" + payload.deviceId)
            if (targetDevice) {
                targetDevice.processStateData(payload.report)
            }
            break
        default:
            logger("debug", "Unknown MQTT message type: ${payload.pushType}")
    }
}

def sendDeviceCommand(deviceId, command) {
    logger("debug", "sendDeviceCommand(${deviceId}, ${command})")
    return apiPost("/devices/${deviceId}/control", command)
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
            log."${level}" "${app.name} ${msg}"
        }
    }
}

def calculateMD5(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.bytes)
    digest.collect { String.format("%02x", it) }.join()
}
