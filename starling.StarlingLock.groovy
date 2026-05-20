/**
 *  Starling Lock  –  Child Driver
 *  Version: 0.3.0
 *  Author: AI-assisted (Claude/Anthropic)
 *
 *  v0.3.0 Changes:
 *    - healthStatus fires "online" on every successful parse() call so last
 *      activity timestamp updates each poll cycle even when no values change
 *    - updated() stores logLevelExpiry for passive 30-minute log revert
 *
 *  v0.2.0 Changes:
 *    - refresh() calls parent?.componentRefresh(this.device)
 *    - Five-tier logging
 */

metadata {
    definition(name: "Starling Lock", namespace: "starling", author: "AI-assisted (Claude/Anthropic)") {
        capability "Lock"
        capability "Battery"

        attribute "autoRelockEnabled",    "string"
        attribute "oneTouchLockEnabled",  "string"
        attribute "privacyModeEnabled",   "string"
        attribute "isTampered",           "string"
        attribute "batteryStatus",        "string"
        attribute "lastLockUnlockMethod", "string"

        attribute "healthStatus", "string"   // "online" | "offline"
        command "setHealthStatus", [[name: "status", type: "ENUM", constraints: ["online", "offline"]]]
        command "refresh"
    }

    preferences {
        input name: "logLevel",
              type: "enum",
              title: "Log level (Debug and Trace revert to Info after 30 minutes)",
              options: ["0": "None", "1": "Error", "2": "Warn", "3": "Info", "4": "Debug", "5": "Trace"],
              defaultValue: "3",
              required: true
    }
}

def installed() {
    logInfo "installed()"
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "lock",                  value: "unknown")
    sendEvent(name: "battery",               value: 100)
    sendEvent(name: "autoRelockEnabled",     value: "false")
    sendEvent(name: "oneTouchLockEnabled",   value: "false")
    sendEvent(name: "privacyModeEnabled",    value: "false")
    sendEvent(name: "isTampered",            value: "false")
    sendEvent(name: "batteryStatus",         value: "normal")
    sendEvent(name: "lastLockUnlockMethod",  value: "other")
}

def updated() {
    logInfo "updated()"
    def level = (logLevel ?: "3").toInteger()
    if (level >= 4) {
        device.updateDataValue("logLevelExpiry", (now() + 1800000).toString())
        logInfo "Verbose logging active - will revert to Info in 30 minutes"
    } else {
        device.updateDataValue("logLevelExpiry", null)
    }
}


def setHealthStatus(String status) {
    sendIfChanged("healthStatus", status)
}
def refresh() {
    logDebug "refresh() called"
    parent?.componentRefresh(this.device)
}

def lock() {
    logDebug "lock()"
    sendPropertyToNest([targetState: "locked"])
    sendIfChanged("lock", "locked")
}

def unlock() {
    logDebug "unlock()"
    sendPropertyToNest([targetState: "unlocked"])
    sendIfChanged("lock", "unlocked")
}

def parse(Map props) {
    // Always mark online on successful parse - ensures last activity timestamp
    // updates on every poll cycle even when no other values have changed.
    sendEvent(name: "healthStatus", value: "online")
    logTrace "parse() received ${props}"

    if (props.containsKey("currentState")) {
        def lockVal = props.currentState == "jammed" ? "unknown" : props.currentState?.toString() ?: "unknown"
        sendIfChanged("lock", lockVal)
    }
    if (props.containsKey("batteryStatus")) {
        sendIfChanged("batteryStatus", props.batteryStatus?.toString() ?: "normal")
        sendIfChanged("battery", props.batteryStatus == "low" ? 10 : 100)
    }
    updateBoolAttr("autoRelockEnabled",   props)
    updateBoolAttr("oneTouchLockEnabled", props)
    updateBoolAttr("isTampered",          props)
    updateBoolAttr("privacyModeEnabled",  props)
    if (props.containsKey("lastLockUnlockMethod")) {
        sendIfChanged("lastLockUnlockMethod", props.lastLockUnlockMethod?.toString() ?: "other")
    }
}

private void sendPropertyToNest(Map properties) {
    def starlingId = device.getDataValue("starlingId")
    if (!starlingId) { logError "no starlingId – cannot send to Nest"; return }
    parent?.setDeviceProperties(starlingId, properties)
}

private void updateBoolAttr(String attrName, Map props) {
    if (props.containsKey(attrName)) sendIfChanged(attrName, props[attrName]?.toString() ?: "false")
}

private void sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
        logDebug "${name} → ${value}"
    } else {
        logTrace "${name} unchanged (${value})"
    }
}

// Returns effective log level.
// If Debug (4) or Trace (5) was selected, the level is treated as Info (3)
// once 30 minutes have elapsed since the preference was saved.
// The parent app's active revert will also write the preference back to Info,
// but this check ensures correct behaviour even if that fires slightly late.
private int logLevelSetting() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= 4) {
        def expiry = device.getDataValue("logLevelExpiry")
        if (expiry && now() > expiry.toLong()) return 3
    }
    return level
}

private void logError(String msg) { if (logLevelSetting() >= 1) log.error "${device.displayName}: ${msg}" }
private void logWarn (String msg) { if (logLevelSetting() >= 2) log.warn  "${device.displayName}: ${msg}" }
private void logInfo (String msg) { if (logLevelSetting() >= 3) log.info  "${device.displayName}: ${msg}" }
private void logDebug(String msg) { if (logLevelSetting() >= 4) log.debug "${device.displayName}: ${msg}" }
private void logTrace(String msg) { if (logLevelSetting() >= 5) log.trace "${device.displayName}: ${msg}" }
