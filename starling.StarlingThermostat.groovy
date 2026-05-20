/**
 *  Starling Thermostat  –  Child Driver
 *  Version: 0.3.0
 *  Author: AI-assisted (Claude/Anthropic)
 *
 *  v0.3.0 Changes:
 *    - healthStatus fires "online" on every successful parse() call
 *    - updated() stores logLevelExpiry for passive 30-minute log revert
 *
 *  v0.2.0 Changes:
 *    - refresh() now calls parent?.componentRefresh(this.device)
 *    - Five-tier logging
 */

metadata {
    definition(
        name:      "Starling Thermostat",
        namespace: "starling",
        author:    "AI-assisted (Claude/Anthropic)"
    ) {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"

        attribute "ecoMode",              "string"
        attribute "fanRunning",           "string"
        attribute "backplateTemperature", "number"
        attribute "batteryStatus",        "string"

        command "setEcoMode", [[name: "enabled", type: "ENUM", constraints: ["true", "false"]]]
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
    sendEvent(name: "thermostatMode",           value: "off")
    sendEvent(name: "thermostatOperatingState", value: "idle")
    sendEvent(name: "thermostatFanMode",        value: "auto")
    sendEvent(name: "supportedThermostatModes",
              value: ["off", "heat", "cool", "auto"].encodeAsJSON())
    sendEvent(name: "supportedThermostatFanModes",
              value: ["on", "auto"].encodeAsJSON())
    sendEvent(name: "ecoMode",    value: "false")
    sendEvent(name: "fanRunning", value: "false")
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

    def scale = getTemperatureScale()

    if (props.containsKey("currentTemperature")) {
        def t = convertTemp(props.currentTemperature, scale)
        sendIfChanged("temperature", t, "°${scale}")
    }
    if (props.containsKey("backplateTemperature")) {
        sendIfChanged("backplateTemperature", convertTemp(props.backplateTemperature, scale))
    }
    if (props.containsKey("targetTemperature")) {
        def t = convertTemp(props.targetTemperature, scale)
        sendIfChanged("heatingSetpoint", t, "°${scale}")
        sendIfChanged("coolingSetpoint", t, "°${scale}")
    }
    if (props.containsKey("targetHeatingThresholdTemperature")) {
        sendIfChanged("heatingSetpoint", convertTemp(props.targetHeatingThresholdTemperature, scale), "°${scale}")
    }
    if (props.containsKey("targetCoolingThresholdTemperature")) {
        sendIfChanged("coolingSetpoint", convertTemp(props.targetCoolingThresholdTemperature, scale), "°${scale}")
    }
    if (props.containsKey("humidityPercent")) {
        sendIfChanged("humidity", Math.round(props.humidityPercent as Double) as Integer, "%")
    }
    if (props.containsKey("hvacMode")) {
        sendIfChanged("thermostatMode", starlingModeToHubitat(props.hvacMode))
    }
    if (props.containsKey("hvacState")) {
        sendIfChanged("thermostatOperatingState", props.hvacState == "off" ? "idle" : props.hvacState)
    }
    if (props.containsKey("fanRunning")) {
        sendIfChanged("thermostatFanMode", props.fanRunning ? "on" : "auto")
        sendIfChanged("fanRunning", props.fanRunning?.toString() ?: "false")
    }
    if (props.containsKey("ecoMode")) {
        sendIfChanged("ecoMode", props.ecoMode?.toString() ?: "false")
    }
    if (props.containsKey("batteryStatus")) {
        sendIfChanged("batteryStatus", props.batteryStatus?.toString() ?: "normal")
    }
}

def setThermostatMode(String mode) {
    logDebug "setThermostatMode(${mode})"
    sendPropertyToNest([hvacMode: hubitatModeToStarling(mode)])
    sendIfChanged("thermostatMode", mode)
}

def setHeatingSetpoint(BigDecimal temperature) {
    def tempC = toNestCelsius(temperature)
    def props = (device.currentValue("thermostatMode") == "auto")
        ? [targetHeatingThresholdTemperature: tempC]
        : [targetTemperature: tempC]
    logDebug "setHeatingSetpoint(${temperature}) → ${props}"
    sendPropertyToNest(props)
    sendIfChanged("heatingSetpoint", temperature, "°${getTemperatureScale()}")
}

def setCoolingSetpoint(BigDecimal temperature) {
    def tempC = toNestCelsius(temperature)
    logDebug "setCoolingSetpoint(${temperature}) → targetCoolingThresholdTemperature=${tempC}"
    sendPropertyToNest([targetCoolingThresholdTemperature: tempC])
    sendIfChanged("coolingSetpoint", temperature, "°${getTemperatureScale()}")
}

def setThermostatFanMode(String fanMode) {
    logDebug "setThermostatFanMode(${fanMode})"
    sendPropertyToNest([fanRunning: fanMode == "on"])
    sendIfChanged("thermostatFanMode", fanMode)
}

def setEcoMode(String enabled) {
    logDebug "setEcoMode(${enabled})"
    sendPropertyToNest([ecoMode: enabled == "true"])
    sendIfChanged("ecoMode", enabled)
}

def auto()         { setThermostatMode("auto") }
def cool()         { setThermostatMode("cool") }
def heat()         { setThermostatMode("heat") }
def off()          { setThermostatMode("off")  }
def fanAuto()      { setThermostatFanMode("auto") }
def fanOn()        { setThermostatFanMode("on")   }
def fanCirculate() { setThermostatFanMode("on")   }

private void sendPropertyToNest(Map properties) {
    def starlingId = device.getDataValue("starlingId")
    if (!starlingId) { logError "no starlingId – cannot send to Nest"; return }
    parent?.setDeviceProperties(starlingId, properties)
}

private BigDecimal convertTemp(value, String scale) {
    def c = value as BigDecimal
    return (scale == "F") ? celsiusToFahrenheit(c).setScale(1, BigDecimal.ROUND_HALF_UP)
                          : c.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private BigDecimal toNestCelsius(BigDecimal t) {
    return (getTemperatureScale() == "F")
        ? fahrenheitToCelsius(t).setScale(2, BigDecimal.ROUND_HALF_UP)
        : t.setScale(2, BigDecimal.ROUND_HALF_UP)
}

private String starlingModeToHubitat(String m) {
    switch (m) { case "heatCool": return "auto"; case "heat": return "heat"; case "cool": return "cool"; default: return "off" }
}

private String hubitatModeToStarling(String m) {
    switch (m) { case "auto": return "heatCool"; case "heat": return "heat"; case "cool": return "cool"; case "emergency heat": return "heat"; default: return "off" }
}

private void sendIfChanged(String name, value, String unit = null) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        def e = [name: name, value: value]; if (unit) e.unit = unit
        sendEvent(e)
        logDebug "${name} → ${value}${unit ?: ''}"
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
