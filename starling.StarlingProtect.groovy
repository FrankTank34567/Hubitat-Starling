/**
 *  Starling Protect  –  Child Driver
 *  Version: 0.3.0
 *  Author: AI-assisted (Claude/Anthropic)
 *
 *  v0.3.0 Changes:
 *    - healthStatus fires "online" on every successful parse() call
 *    - Added alarmSilenced attribute
 *    - Added coLevel attribute (raw CO sensor reading)
 *    - isOnline -> healthStatus wired in parse()
 *    - updated() stores logLevelExpiry for passive 30-minute log revert
 *
 *  v0.2.0 Changes:
 *    - refresh() now calls parent?.componentRefresh(this.device) per
 *      Hubitat parent/child pattern (public method, device reference passed)
 *    - Five-tier logging: trace, debug, info, warn, error
 */

metadata {
    definition(
        name:      "Starling Protect",
        namespace: "starling",
        author:    "AI-assisted (Claude/Anthropic)"
    ) {
        capability "SmokeDetector"           // attribute: smoke
        capability "CarbonMonoxideDetector"  // attribute: carbonMonoxide
        capability "PresenceSensor"          // attribute: presence (occupancy)
        capability "Battery"                 // attribute: battery (number)

        attribute "smokeStateDetail",  "string"   // "ok" | "warn" | "emergency"
        attribute "coStateDetail",     "string"   // "ok" | "warn" | "emergency"
        attribute "manualTestActive",  "string"   // "true" | "false"
        attribute "alarmSilenced",     "string"   // "true" | "false"
        attribute "coLevel",           "number"   // raw CO level reading from sensor

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
    sendEvent(name: "smoke",            value: "clear")
    sendEvent(name: "carbonMonoxide",   value: "clear")
    sendEvent(name: "presence",         value: "not present")
    sendEvent(name: "battery",          value: 100)
    sendEvent(name: "smokeStateDetail", value: "ok")
    sendEvent(name: "coStateDetail",    value: "ok")
    sendEvent(name: "manualTestActive", value: "false")
    sendEvent(name: "alarmSilenced",    value: "false")
    sendEvent(name: "coLevel",          value: 0)
}

def updated() {
    logInfo "updated()"
    // Store expiry timestamp so logLevelSetting() can passively enforce the
    // 30-minute window without needing its own runIn() scheduler.
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

def parse(Map props) {
    // Always mark online on successful parse - ensures last activity timestamp
    // updates on every poll cycle even when no other values have changed.
    sendEvent(name: "healthStatus", value: "online")
    logTrace "parse() received ${props}"

    if (props.containsKey("smokeStateDetail")) {
        def detail  = props.smokeStateDetail?.toString() ?: "ok"
        def smokeVal = detailToCapabilityValue(detail)
        sendIfChanged("smoke", smokeVal)
        sendIfChanged("smokeStateDetail", detail)
    } else if (props.containsKey("smokeDetected")) {
        sendIfChanged("smoke", props.smokeDetected ? "detected" : "clear")
        sendIfChanged("smokeStateDetail", props.smokeDetected ? "emergency" : "ok")
    }

    if (props.containsKey("coStateDetail")) {
        def detail = props.coStateDetail?.toString() ?: "ok"
        sendIfChanged("carbonMonoxide", detailToCapabilityValue(detail))
        sendIfChanged("coStateDetail", detail)
    } else if (props.containsKey("coDetected")) {
        sendIfChanged("carbonMonoxide", props.coDetected ? "detected" : "clear")
        sendIfChanged("coStateDetail", props.coDetected ? "emergency" : "ok")
    }

    if (props.containsKey("occupancyDetected")) {
        sendIfChanged("presence", props.occupancyDetected ? "present" : "not present")
    }

    if (props.containsKey("batteryStatus")) {
        sendIfChanged("battery", props.batteryStatus == "low" ? 10 : 100)
    }

    if (props.containsKey("manualTestActive")) {
        sendIfChanged("manualTestActive", props.manualTestActive?.toString() ?: "false")
    }
    if (props.containsKey("alarmSilenced")) {
        sendIfChanged("alarmSilenced", props.alarmSilenced?.toString() ?: "false")
    }
    if (props.containsKey("coLevel")) {
        sendIfChanged("coLevel", props.coLevel as Integer)
    }
    if (props.containsKey("isOnline")) {
        sendIfChanged("healthStatus", props.isOnline ? "online" : "offline")
    }
}

private String detailToCapabilityValue(String detail) {
    switch (detail) {
        case "emergency": return "detected"
        case "warn":      return "tested"
        default:          return "clear"
    }
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
