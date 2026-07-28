/**
 * =============================================================================
 *  Samsung Soundbar (Local IP, 2024+ Wi-Fi models) - Hubitat Driver
 * =============================================================================
 *  Talks directly to the soundbar over LAN via JSON-RPC 2.0 on TCP 1516
 *  (HTTPS, self-signed cert) - the same local API the SmartThings app uses.
 *
 *  Protocol reverse-engineered by ZtF for Home Assistant:
 *  https://github.com/ZtF/hass-samsung-soundbar-local
 *
 *  Supported models (per upstream project, YMMV by firmware):
 *  HW-Q990D, HW-Q930D, HW-Q800D, HW-QS730D, HW-S800D, HW-S801D,
 *  HW-S700D, HW-S60D, HW-S61D, HW-LS60D  (some 2025/F-series models reported
 *  working too; 2023-and-older / C-series bars do NOT implement this API)
 *
 *  Requirements:
 *  - Soundbar registered in the Samsung SmartThings app, connected to Wi-Fi,
 *    with "IP control" enabled in the device's network settings.
 *
 *  Notes:
 *  - The soundbar has NO "set absolute volume" RPC call - only VOL_UP/VOL_DOWN
 *    remote-key steps exist. setVolume() therefore reads the current volume
 *    and steps toward the target, same approach the upstream integration uses.
 *  - The AccessToken is cached in device state and only re-requested if a
 *    call fails with what looks like an auth error, or after resetAccessToken().
 *
 *  Author: 권민 (kwon2288)
 *  License: MIT
 * =============================================================================
 */
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final List<String> SOURCE_LIST = ["HDMI_IN1", "HDMI_IN2", "E_ARC", "ARC", "D_IN", "BT", "WIFI_IDLE"]
@Field static final List<String> SOUND_MODE_LIST = ["STANDARD", "SURROUND", "GAME", "MOVIE", "MUSIC", "CLEARVOICE", "DTS_VIRTUAL_X", "ADAPTIVE"]
@Field static final Integer SOUNDBAR_PORT = 1516
@Field static final Integer STEP_DELAY_MS = 150

metadata {
    definition(
        name: "Samsung Soundbar Local",
        namespace: "kwon2288",
        author: "권민",
        importUrl: "https://raw.githubusercontent.com/kwon2288/hubitat-driver/main/samsung-soundbar-local/samsung_soundbar_local.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Switch"
        capability "AudioVolume"
        capability "MediaInputSource"

        attribute "soundMode", "string"
        attribute "codec", "string"
        attribute "identifier", "string"
        attribute "commStatus", "string"

        command "setInputSource", [[name: "inputName*", type: "ENUM", constraints: SOURCE_LIST]]
        command "setSoundMode", [[name: "soundMode*", type: "ENUM", constraints: SOUND_MODE_LIST]]
        command "subwooferUp"
        command "subwooferDown"
        command "resetAccessToken"
    }

    preferences {
        input name: "ipAddress", type: "text", title: "Soundbar IP address", required: true
        input name: "pollIntervalMin", type: "enum", title: "Auto-refresh interval",
              options: ["0": "Disabled", "1": "Every 1 minute", "5": "Every 5 minutes", "10": "Every 10 minutes", "30": "Every 30 minutes"],
              defaultValue: "5"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

// -----------------------------------------------------------------------
// Lifecycle
// -----------------------------------------------------------------------
def installed() {
    log.info "${device.displayName}: installed"
    sendEvent(name: "supportedInputSources", value: JsonOutput.toJson(SOURCE_LIST))
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences updated"
    unschedule()
    sendEvent(name: "supportedInputSources", value: JsonOutput.toJson(SOURCE_LIST))
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

def initialize() {
    unschedule(refresh)
    Integer mins = (pollIntervalMin ?: "5") as Integer
    if (mins > 0) {
        schedule("0 0/${mins} * * * ?", refresh)
    }
    runIn(2, refresh)
}

def logsOff() {
    log.warn "${device.displayName}: debug logging auto-disabled after 30 min"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// -----------------------------------------------------------------------
// Switch
// -----------------------------------------------------------------------
def on() {
    rpcCall("powerControl", [power: "powerOn"])
    runInMillis(600, refresh)
}

def off() {
    rpcCall("powerControl", [power: "powerOff"])
    runInMillis(600, refresh)
}

// -----------------------------------------------------------------------
// AudioVolume
// -----------------------------------------------------------------------
def volumeUp() {
    rpcCall("remoteKeyControl", [remoteKey: "VOL_UP"])
    runInMillis(400, getVolumeState)
}

def volumeDown() {
    rpcCall("remoteKeyControl", [remoteKey: "VOL_DOWN"])
    runInMillis(400, getVolumeState)
}

def setVolume(level) {
    Integer target = Math.max(0, Math.min(100, (level as Integer)))
    Map cur = rpcCall("getVolume")
    if (cur?.volume == null) {
        log.warn "${device.displayName}: could not read current volume, aborting setVolume"
        return
    }
    Integer current = cur.volume as Integer
    Integer steps = Math.abs(target - current)
    if (steps == 0) return

    String key = (target > current) ? "VOL_UP" : "VOL_DOWN"
    if (txtEnable) log.info "${device.displayName}: stepping volume ${current} -> ${target} (${steps} steps, no absolute-set API exists)"

    steps.times {
        rpcCall("remoteKeyControl", [remoteKey: key])
        pauseExecution(STEP_DELAY_MS)
    }
    getVolumeState()
}

def mute() {
    Map cur = rpcCall("getMute")
    if (cur?.mute == false) rpcCall("remoteKeyControl", [remoteKey: "MUTE"])
    getMuteState()
}

def unmute() {
    Map cur = rpcCall("getMute")
    if (cur?.mute == true) rpcCall("remoteKeyControl", [remoteKey: "MUTE"])
    getMuteState()
}

// -----------------------------------------------------------------------
// MediaInputSource
// -----------------------------------------------------------------------
def setInputSource(source) {
    if (!(source in SOURCE_LIST)) {
        log.warn "${device.displayName}: '${source}' isn't in the known source list, sending as-is: ${SOURCE_LIST}"
    }
    rpcCall("inputSelectControl", [inputSource: source])
    getInputState()
}

// -----------------------------------------------------------------------
// Custom commands
// -----------------------------------------------------------------------
def setSoundMode(mode) {
    rpcCall("soundModeControl", [soundMode: mode])
    getSoundModeState()
}

def subwooferUp() {
    rpcCall("remoteKeyControl", [remoteKey: "WOOFER_PLUS"])
}

def subwooferDown() {
    rpcCall("remoteKeyControl", [remoteKey: "WOOFER_MINUS"])
}

def resetAccessToken() {
    log.info "${device.displayName}: clearing cached AccessToken, will re-pair on next call"
    state.remove("accessToken")
}

// -----------------------------------------------------------------------
// Refresh / state polling
// -----------------------------------------------------------------------
def refresh() {
    getPowerState()
    getVolumeState()
    getMuteState()
    getInputState()
    getSoundModeState()

    Map c = rpcCall("getCodec")
    if (c?.codec) sendEvent(name: "codec", value: c.codec)

    Map i = rpcCall("getIdentifier")
    if (i?.identifier) sendEvent(name: "identifier", value: i.identifier)
}

def getPowerState() {
    Map r = rpcCall("powerControl")
    if (r?.power != null) {
        String sw = (r.power == "powerOn") ? "on" : "off"
        if (device.currentValue("switch") != sw) sendEvent(name: "switch", value: sw)
    }
}

def getVolumeState() {
    Map r = rpcCall("getVolume")
    if (r?.volume != null) sendEvent(name: "volume", value: r.volume as Integer)
}

def getMuteState() {
    Map r = rpcCall("getMute")
    if (r?.mute != null) sendEvent(name: "mute", value: (r.mute ? "muted" : "unmuted"))
}

def getInputState() {
    Map r = rpcCall("inputSelectControl")
    if (r?.inputSource != null) sendEvent(name: "mediaInputSource", value: r.inputSource)
}

def getSoundModeState() {
    Map r = rpcCall("soundModeControl")
    if (r?.soundMode != null) sendEvent(name: "soundMode", value: r.soundMode)
}

// -----------------------------------------------------------------------
// Transport - JSON-RPC 2.0 over HTTPS (self-signed cert, port 1516)
// -----------------------------------------------------------------------
private Map rpcCall(String method, Map params = [:], boolean allowRetry = true) {
    if (!ipAddress) {
        log.warn "${device.displayName}: no IP address configured"
        return null
    }
    if (method != "createAccessToken" && !state.accessToken) {
        if (!requestAccessToken()) return null
    }

    Map fullParams = [:]
    if (method != "createAccessToken") fullParams["AccessToken"] = state.accessToken
    fullParams.putAll(params)

    Map body = [jsonrpc: "2.0", method: method, id: 1]
    if (fullParams) body.params = fullParams

    Map reqParams = [
        uri              : "https://${ipAddress}:${SOUNDBAR_PORT}/",
        contentType       : "application/json",
        requestContentType: "application/json",
        body              : JsonOutput.toJson(body),
        ignoreSSLIssues   : true,
        timeout           : 8
    ]

    try {
        Map result = null
        httpPost(reqParams) { resp ->
            def data = resp?.data
            if (logEnable) log.debug "${device.displayName}: ${method} -> ${data}"
            if (data?.error) throw new Exception("soundbar returned error: ${data.error}")
            result = data?.result
        }
        sendEvent(name: "commStatus", value: "online")
        return result
    } catch (Exception e) {
        String msg = e.message ?: e.toString()
        if (logEnable) log.debug "${device.displayName}: ${method} failed: ${msg}"
        boolean looksLikeAuthError = (msg =~ /(?i)(token|auth|unauthor|forbidden|401|403)/)
        if (allowRetry && method != "createAccessToken" && looksLikeAuthError) {
            state.remove("accessToken")
            return rpcCall(method, params, false)
        }
        sendEvent(name: "commStatus", value: "error")
        return null
    }
}

private boolean requestAccessToken() {
    Map result = rpcCall("createAccessToken", [:], false)
    if (result?.AccessToken) {
        state.accessToken = result.AccessToken
        if (txtEnable) log.info "${device.displayName}: obtained AccessToken from ${ipAddress}"
        return true
    }
    log.warn "${device.displayName}: failed to obtain AccessToken from ${ipAddress} - check IP, that the soundbar is on Wi-Fi, and that IP control is enabled in SmartThings"
    return false
}
