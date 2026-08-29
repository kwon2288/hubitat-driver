/**
 * Homey OnAir Radio (Child Driver)
 *
 * Uses the standard "MusicPlayer" capability so this device types
 * correctly for dashboards, Rule Machine, and voice assistant bridges,
 * instead of a set of ad-hoc custom commands.
 *
 * Only play/pause/next/previous are actually backed by working Homey
 * capabilities for this device (speaker_playing, speaker_next,
 * speaker_prev). MusicPlayer also requires stop/setLevel/mute/unmute/
 * setTrack/resumeTrack/restoreTrack/playTrack to exist, so they're
 * implemented as best-effort or safe no-ops:
 *   - stop()      -> treated as pause()
 *   - setLevel()  -> attempts a write to lge_volume_set, which Homey
 *                    reports as read-only for this device, so it will
 *                    likely fail - kept only for capability completeness
 *   - mute/unmute -> no-op (this device has no volume_mute capability)
 *   - setTrack/resumeTrack/restoreTrack/playTrack -> resume playback
 *
 * Author: kwon2288
 */
metadata {
    definition(name: "Homey OnAir Radio", namespace: "kwon2288", author: "kwon2288") {
        capability "MusicPlayer"
        capability "Refresh"

        attribute "artist", "string"
    }
    preferences {
        input name: "logDebug", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    // placeholder for future per-device setup
}

// ---------- Commands (Hubitat -> Homey) ----------

def play() {
    parent?.sendCommand(device.deviceNetworkId, "speaker_playing", true)
    runIn(2, refresh)
}

def pause() {
    parent?.sendCommand(device.deviceNetworkId, "speaker_playing", false)
    runIn(2, refresh)
}

def stop() {
    // No distinct "stop" for a radio stream on Homey - treat as pause.
    pause()
}

def nextTrack() {
    parent?.sendCommand(device.deviceNetworkId, "speaker_next", true)
    runIn(2, refresh)
}

def previousTrack() {
    parent?.sendCommand(device.deviceNetworkId, "speaker_prev", true)
    runIn(2, refresh)
}

def setLevel(level) {
    // lge_volume_set is reported as setable:false by Homey for this device -
    // this call is expected to fail (check Logs for the sendCommand result).
    parent?.sendCommand(device.deviceNetworkId, "lge_volume_set", level as Integer)
}

def mute() {
    if (logDebug) log.debug "mute() ignored - this device has no volume_mute capability on Homey"
}

def unmute() {
    if (logDebug) log.debug "unmute() ignored - this device has no volume_mute capability on Homey"
}

def setTrack(trackuri) {
    play()
}

def resumeTrack(trackuri) {
    play()
}

def restoreTrack(trackuri) {
    play()
}

def playTrack(trackuri, volumelevel = null) {
    play()
}

def refresh() {
    log.info "Homey OnAir Radio: refresh() called, parent=${parent}"
    parent?.refreshDevice(device.deviceNetworkId)
}

// ---------- State sync (Homey -> Hubitat) ----------

def updateFromHomey(Map capsObj) {
    log.info "updateFromHomey received: ${capsObj}"
    capsObj.each { capId, capData ->
        def v = (capData instanceof Map) ? capData.value : capData
        if (v == null) return
        switch (capId) {
            case "speaker_playing":
                sendEvent(name: "status", value: v ? "playing" : "paused")
                break
            case "speaker_track":
                sendEvent(name: "trackDescription", value: v)
                break
            case "speaker_artist":
                sendEvent(name: "artist", value: v)
                break
            case "lge_volume_set":
            case "volume_set":
                def pct = (capId == "lge_volume_set") ? Math.round(v as Double) : Math.round((v as Double) * 100)
                sendEvent(name: "level", value: pct)
                break
            default:
                log.info "Unmapped Homey capability ${capId} = ${v}"
                break
        }
    }
}
