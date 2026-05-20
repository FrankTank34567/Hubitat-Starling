// Starling Connect - Parent App v0.7.0
// Author: AI-assisted (Claude/Anthropic)
//
// Changes from 0.6.0:
//   - CRITICAL FIX: Added circuit breaker to fast poll loop.
//     When the Starling Hub is unreachable, the loop backs off exponentially
//     and stops firing async requests until the hub recovers.
//   - Added pending request guard: the fast loop will not fire a new request
//     for a camera if a prior request for that camera is still pending.
//   - Reduced async timeout from 8s to 5s to resolve faster when unreachable.
//   - checkStarlingStatus() now gates all polling - if the hub is not reachable
//     polling is suspended until /status returns OK.
//   - Fast loop backoff: 1x, 2x, 4x, 8x, 16x interval up to 5 minutes max
//     on consecutive hub-level failures, then resets on recovery.

import groovy.transform.Field

@Field static final Integer LOG_REVERT_SECONDS      = 1800
@Field static final Integer LOG_VERBOSE_THRESHOLD   = 4
@Field static final Integer FAILURE_LOG_INTERVAL    = 10
@Field static final Integer CAMERA_POLL_GAP_SECONDS = 2
@Field static final Integer MAX_BACKOFF_SECONDS     = 300   // 5 minutes max backoff
@Field static final Integer ASYNC_TIMEOUT_SECONDS   = 5     // reduced from 8

@Field static final Map DRIVER_MAP = [
    "thermostat"        : ["starling", "Starling Thermostat"],
    "temp_sensor"       : ["starling", "Starling Temperature Sensor"],
    "protect"           : ["starling", "Starling Protect"],
    "cam"               : ["starling", "Starling Camera"],
    "lock"              : ["starling", "Starling Lock"],
    "home_away_control" : ["starling", "Starling Home Away Control"],
    "weather"           : ["starling", "Starling Weather"],
]

@Field static final Map SLOW_SCHEDULE_MAP = [
    "1 minute"  : "0 */1  * ? * *",
    "5 minutes" : "0 */5  * ? * *",
    "10 minutes": "0 */10 * ? * *",
    "15 minutes": "0 */15 * ? * *",
    "30 minutes": "0 */30 * ? * *",
]

@Field static final List FAST_POLL_OPTIONS = ["5", "10", "15", "30"]

definition(
    name:           "Starling Connect",
    namespace:      "starling",
    author:         "AI-assisted (Claude/Anthropic)",
    description:    "Discovers Nest devices via the Starling Home Hub SDC API and keeps child device attributes current.",
    category:       "Integrations",
    iconUrl:        "",
    iconX2Url:      "",
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
}

// ---------------------------------------------------------------------------
// UI Pages
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "Starling Connect", install: true, uninstall: true) {

        section("Starling Hub Connection") {
            input name: "hubIp",    type: "text",     title: "Starling Hub IP address",  required: true
            input name: "apiKey",   type: "password", title: "API key (12-character)",   required: true
            input name: "useHttps", type: "bool",     title: "Use HTTPS (port 3443)?",   defaultValue: false
        }

        section("Slow Poll - Protects, Thermostats, Sensors") {
            input name: "slowPollSchedule",
                  type: "enum",
                  title: "Poll interval",
                  options: SLOW_SCHEDULE_MAP.keySet().collect { it },
                  defaultValue: "5 minutes",
                  required: true
        }

        section("Fast Poll - Cameras") {
            input name: "fastPollSeconds",
                  type: "enum",
                  title: "Poll interval (seconds)",
                  options: FAST_POLL_OPTIONS,
                  defaultValue: "10",
                  required: true
            paragraph "Each camera is polled sequentially. If the Starling Hub is unreachable the loop backs off automatically up to 5 minutes between attempts."
        }

        section("Device Discovery") {
            href name: "toDiscoveryPage", page: "discoveryPage",
                 title: "Discover / Sync Devices Now",
                 description: "Tap to query your Starling Hub and create or update child devices"
        }

        section("Logging") {
            input name: "logLevel",
                  type: "enum",
                  title: "Log level (Debug and Trace revert to Info after 30 minutes)",
                  options: ["0": "None", "1": "Error", "2": "Warn", "3": "Info", "4": "Debug", "5": "Trace"],
                  defaultValue: "3",
                  required: true
        }
    }
}

def discoveryPage() {
    def result = discoverDevices()
    dynamicPage(name: "discoveryPage", title: "Discovery Results", install: false, uninstall: false) {
        section { paragraph result }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logInfo "installed()"
    initState()
    initialize()
}

def updated() {
    logInfo "updated()"
    unschedule("revertLogLevel")
    unschedule("pollSlowDevices")
    unschedule("checkStarlingStatus")
    unschedule("fastPollStep")
    initState()
    if (apiKey) state.encryptedApiKey = encrypt(apiKey)
    scheduleLogRevertIfNeeded()
    initialize()
}

def uninstalled() {
    logInfo "uninstalled() - removing child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private void initState() {
    state.cameraIndex       = 0
    state.fastLoopRunning   = false
    state.hubOnline         = true    // assume online until proven otherwise
    state.hubFailureCount   = 0       // consecutive hub-level failures
    state.pendingRequests   = [:]     // DNI -> timestamp of in-flight request
    if (!state.failureCounts) state.failureCounts = [:]
}

def initialize() {
    def slowExpr = SLOW_SCHEDULE_MAP[slowPollSchedule ?: "5 minutes"]
    schedule(slowExpr, "pollSlowDevices")
    schedule(slowExpr, "checkStarlingStatus")
    logInfo "Slow poll scheduled every ${slowPollSchedule}"
    discoverDevices()
    checkStarlingStatus()
    pollSlowDevices()
    startFastLoop()
}

// ---------------------------------------------------------------------------
// Log level auto-revert
// ---------------------------------------------------------------------------

private void scheduleLogRevertIfNeeded() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= LOG_VERBOSE_THRESHOLD) {
        state.logLevelExpiry = now() + (LOG_REVERT_SECONDS * 1000)
        runIn(LOG_REVERT_SECONDS, "revertLogLevel")
        logInfo "Verbose logging active - will revert to Info in 30 minutes"
    } else {
        state.logLevelExpiry = null
    }
}

def revertLogLevel() {
    log.info "Starling Connect: 30-minute verbose log window expired - reverting to Info"
    app.updateSetting("logLevel", [type: "enum", value: "3"])
}

// ---------------------------------------------------------------------------
// Starling Hub status check - gates all polling
// ---------------------------------------------------------------------------

def checkStarlingStatus() {
    def params = [
        uri            : "${buildBaseUrl()}/api/connect/v1/status",
        query          : [key: getApiKey()],
        timeout        : ASYNC_TIMEOUT_SECONDS,
        ignoreSSLIssues: (useHttps == true),
        contentType    : "application/json"
    ]
    asynchttpGet("starlingStatusCallback", params)
}

def starlingStatusCallback(resp, data) {
    if (resp.hasError()) {
        def wasOnline = state.hubOnline
        state.hubOnline = false
        state.hubFailureCount = (state.hubFailureCount ?: 0) + 1
        if (wasOnline) logWarn "Starling Hub unreachable - polling suspended (${resp.getErrorMessage()})"
        return
    }
    try {
        def json = resp.json
        if (json?.apiReady == false || json?.connectedToNest == false) {
            state.hubOnline = false
            logWarn "Starling Hub not ready (apiReady=${json?.apiReady}, connectedToNest=${json?.connectedToNest})"
        } else {
            // Hub recovered
            if (!state.hubOnline) {
                logInfo "Starling Hub back online - resuming polling"
                state.hubFailureCount = 0
                state.failureCounts   = [:]
                state.pendingRequests = [:]
                // Mark all devices online again
                getChildDevices().each { child ->
                    try { child.setHealthStatus("online") } catch (Exception e) { }
                }
            }
            state.hubOnline = true
            logDebug "Starling Hub status OK (apiReady=${json?.apiReady}, connectedToNest=${json?.connectedToNest})"
        }
    } catch (Exception e) {
        logWarn "GET /status parse error: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------

def discoverDevices() {
    def params = [
        uri            : "${buildBaseUrl()}/api/connect/v1/devices",
        query          : [key: getApiKey()],
        timeout        : 10,
        ignoreSSLIssues: (useHttps == true),
        contentType    : "application/json"
    ]
    def summary = ""
    try {
        httpGet(params) { resp ->
            if (resp.status != 200) {
                summary = "Error: HTTP ${resp.status} from Starling Hub"
                logError summary; return
            }
            def data = resp.data
            logTrace "GET /devices: ${groovy.json.JsonOutput.toJson(data)}"
            def devices = data?.devices
            if (!devices) {
                summary = "No 'devices' key in response. Check API key and firmware version."
                logWarn summary; return
            }
            int created = 0, existing = 0, skipped = 0
            devices.each { dev ->
                switch (ensureChildDevice(dev)) {
                    case "created":  created++;  break
                    case "existing": existing++; break
                    case "skipped":  skipped++;  break
                }
            }
            summary = "Discovery complete - Created: ${created}, Already existed: ${existing}, Skipped: ${skipped}"
            logInfo summary
        }
    } catch (Exception e) {
        summary = "Error contacting Starling Hub: ${e.message}"
        logError summary
    }
    return summary
}

private String ensureChildDevice(Map dev) {
    def deviceId   = dev.id?.toString()
    def deviceType = dev.type?.toString()?.toLowerCase()
    def where      = dev.where ?: ""
    def name       = dev.name  ?: ""
    def label      = (where && name) ? "${where} ${name}".trim() : (where ?: name ?: deviceType)

    if (!deviceId || !deviceType) { logWarn "Skipping device with missing id/type: ${dev}"; return "skipped" }

    def driverEntry = DRIVER_MAP[deviceType]
    if (!driverEntry) { logWarn "No driver mapped for type '${deviceType}' (${label})"; return "skipped" }

    def (driverNamespace, driverName) = driverEntry
    def dni   = "starling-${app.id}-${deviceId}"
    def child = getChildDevice(dni)
    if (child) { logDebug "Child exists: ${label}"; return "existing" }

    try {
        def cd = addChildDevice(driverNamespace, driverName, dni, [
            name: "Starling: ${label}", label: label, isComponent: false
        ])
        cd.updateDataValue("starlingId",   deviceId)
        cd.updateDataValue("starlingType", deviceType)
        logInfo "Created '${label}' (${driverName}) DNI=${dni}"
        return "created"
    } catch (Exception e) {
        logError "Failed to create '${label}' with '${driverName}': ${e.message}"
        return "skipped"
    }
}

// ---------------------------------------------------------------------------
// Slow poll loop
// ---------------------------------------------------------------------------

def pollSlowDevices() {
    logDebug "pollSlowDevices()"
    if (!state.hubOnline) { logDebug "Hub offline - skipping slow poll"; return }

    getChildDevices().each { child ->
        if (child.getDataValue("starlingType") == "cam") return
        def starlingId = child.getDataValue("starlingId")
        if (!starlingId) { logWarn "${child.label} has no starlingId"; return }
        asyncPollDevice(child.deviceNetworkId, starlingId)
    }
}

// ---------------------------------------------------------------------------
// Fast poll loop - cameras only, with circuit breaker
// ---------------------------------------------------------------------------

def startFastLoop() {
    def cameras = getCameraChildren()
    if (!cameras) {
        logInfo "No cameras - fast poll loop not started"
        state.fastLoopRunning = false
        return
    }
    state.cameraDnis      = cameras.collect { it.deviceNetworkId }
    state.cameraIndex     = 0
    state.fastLoopRunning = true
    state.fastLoopStartMs = now()
    logInfo "Fast poll loop started (${cameras.size()} cameras, every ${fastPollSeconds}s)"
    runIn(1, "fastPollStep")
}

def fastPollStep() {
    if (!state.fastLoopRunning) {
        logDebug "fastPollStep() - loop stopped"
        return
    }

    // Circuit breaker: if hub is offline, back off exponentially
    if (!state.hubOnline) {
        def failures = (state.hubFailureCount ?: 1) as Integer
        // Backoff: 2^(failures-1) * base interval, capped at MAX_BACKOFF_SECONDS
        def baseSecs = (fastPollSeconds ?: "10").toInteger()
        def backoff  = Math.min(MAX_BACKOFF_SECONDS, baseSecs * (int)Math.pow(2, Math.min(failures - 1, 7)))
        logDebug "Hub offline - fast poll backing off ${backoff}s (failure count: ${failures})"
        runIn(backoff, "fastPollStep")
        return
    }

    // Refresh camera list at start of each cycle
    if (state.cameraIndex == 0) {
        def cameras = getCameraChildren()
        if (!cameras) { state.fastLoopRunning = false; return }
        state.cameraDnis      = cameras.collect { it.deviceNetworkId }
        state.fastLoopStartMs = now()
    }

    def dnis  = state.cameraDnis
    def index = state.cameraIndex as Integer

    if (index >= dnis.size()) {
        state.cameraIndex = 0
        runIn(1, "fastPollStep")
        return
    }

    def dni   = dnis[index]
    def child = getChildDevice(dni)
    if (child) {
        def starlingId = child.getDataValue("starlingId")
        if (starlingId) {
            // Pending request guard: don't fire a new request if one is already
            // in flight for this device. A request is considered stale after
            // 3x the timeout period.
            def pending   = state.pendingRequests ?: [:]
            def pendingSince = pending[dni] as Long
            def staleMs   = ASYNC_TIMEOUT_SECONDS * 3 * 1000
            if (pendingSince && (now() - pendingSince) < staleMs) {
                logDebug "Skipping ${child.label} - prior request still pending"
            } else {
                if (!state.pendingRequests) state.pendingRequests = [:]
                state.pendingRequests[dni] = now()
                asyncPollDevice(dni, starlingId)
            }
        } else {
            logWarn "Camera ${child.label} has no starlingId"
        }
    }

    def nextIndex = index + 1
    state.cameraIndex = nextIndex

    if (nextIndex < dnis.size()) {
        runIn(CAMERA_POLL_GAP_SECONDS, "fastPollStep")
    } else {
        def targetMs  = (fastPollSeconds ?: "10").toInteger() * 1000
        def elapsedMs = now() - (state.fastLoopStartMs as Long)
        def delaySecs = Math.max(1, ((targetMs - elapsedMs) / 1000).toInteger())
        state.cameraIndex = 0
        logTrace "Camera cycle complete - next in ${delaySecs}s"
        runIn(delaySecs, "fastPollStep")
    }
}

private List getCameraChildren() {
    return getChildDevices().findAll { it.getDataValue("starlingType") == "cam" }
}

// ---------------------------------------------------------------------------
// Async poll
// ---------------------------------------------------------------------------

private void asyncPollDevice(String dni, String starlingId) {
    def params = [
        uri            : "${buildBaseUrl()}/api/connect/v1/devices/${starlingId}",
        query          : [key: getApiKey()],
        timeout        : ASYNC_TIMEOUT_SECONDS,
        ignoreSSLIssues: (useHttps == true),
        contentType    : "application/json"
    ]
    asynchttpGet("asyncPollCallback", params, [dni: dni, starlingId: starlingId])
}

def asyncPollCallback(resp, Map data) {
    def dni        = data?.dni
    def starlingId = data?.starlingId

    // Clear pending request marker regardless of outcome
    if (state.pendingRequests && dni) state.pendingRequests.remove(dni)

    if (resp.hasError()) {
        handlePollFailure(dni, resp.getErrorMessage())
        return
    }
    if (resp.status != 200) {
        handlePollFailure(dni, "HTTP ${resp.status}")
        return
    }

    def child = getChildDevice(dni)
    if (!child) { logWarn "asyncPollCallback: no child for DNI ${dni}"; return }

    try {
        def props = resp.json?.properties
        if (!props) { logWarn "No 'properties' in response for ${child.label}"; return }
        logTrace "Poll [${child.label}]: ${groovy.json.JsonOutput.toJson(props)}"

        // Clear per-device failure count on success
        if (state.failureCounts) state.failureCounts.remove(dni)

        // Mark device online on recovery
        def wasOffline = (state.failureCounts?.get(dni) ?: 0) > 0
        if (wasOffline) {
            try { child.setHealthStatus("online") } catch (Exception e) { }
        }

        child.parse(props)
    } catch (Exception e) {
        logError "asyncPollCallback parse error for ${child?.label}: ${e.message}"
    }
}

private void handlePollFailure(String dni, String reason) {
    if (!state.failureCounts) state.failureCounts = [:]
    def count = (state.failureCounts[dni] ?: 0) + 1
    state.failureCounts[dni] = count

    def child = getChildDevice(dni)
    def label = child?.label ?: dni

    // Log first failure and every FAILURE_LOG_INTERVAL thereafter
    if (count == 1 || count % FAILURE_LOG_INTERVAL == 0) {
        logWarn "Poll failed for ${label} (${reason}) - consecutive failures: ${count}"
    }

    // Mark device offline on first failure
    if (count == 1 && child) {
        try { child.setHealthStatus("offline") } catch (Exception e) { }
    }
}

// ---------------------------------------------------------------------------
// componentRefresh and setDeviceProperties (called by child drivers)
// ---------------------------------------------------------------------------

void componentRefresh(cd) {
    def starlingId = cd.getDataValue("starlingId")
    if (!starlingId) { logWarn "${cd.displayName} has no starlingId"; return }
    if (!state.hubOnline) { logWarn "Hub offline - skipping refresh for ${cd.displayName}"; return }
    logDebug "componentRefresh(${cd.displayName})"
    asyncPollDevice(cd.deviceNetworkId, starlingId)
}

void setDeviceProperties(String starlingId, Map properties) {
    if (!state.hubOnline) { logWarn "Hub offline - cannot send command for ${starlingId}"; return }
    def params = [
        uri                : "${buildBaseUrl()}/api/connect/v1/devices/${starlingId}",
        query              : [key: getApiKey()],
        requestContentType : "application/json",
        contentType        : "application/json",
        body               : groovy.json.JsonOutput.toJson(properties),
        timeout            : ASYNC_TIMEOUT_SECONDS,
        ignoreSSLIssues    : (useHttps == true)
    ]
    asynchttpPost("asyncPostCallback", params, [starlingId: starlingId])
}

def asyncPostCallback(resp, Map data) {
    if (resp.hasError()) {
        logError "POST error for ${data?.starlingId}: ${resp.getErrorMessage()}"
        return
    }
    resp.status == 200 ? logDebug("POST OK for ${data?.starlingId}") : logWarn("POST HTTP ${resp.status} for ${data?.starlingId}")
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private String buildBaseUrl() {
    return "${useHttps ? 'https' : 'http'}://${hubIp}:${useHttps ? 3443 : 3080}"
}

private String getApiKey() {
    if (state.encryptedApiKey) {
        try { return decrypt(state.encryptedApiKey) } catch (Exception e) { }
    }
    return apiKey ?: ""
}

private int logLevelSetting() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= LOG_VERBOSE_THRESHOLD) {
        def expiry = state.logLevelExpiry as Long
        if (expiry && now() > expiry) return 3
    }
    return level
}

private void logError(String msg) { if (logLevelSetting() >= 1) log.error "Starling Connect: ${msg}" }
private void logWarn (String msg) { if (logLevelSetting() >= 2) log.warn  "Starling Connect: ${msg}" }
private void logInfo (String msg) { if (logLevelSetting() >= 3) log.info  "Starling Connect: ${msg}" }
private void logDebug(String msg) { if (logLevelSetting() >= 4) log.debug "Starling Connect: ${msg}" }
private void logTrace(String msg) { if (logLevelSetting() >= 5) log.trace "Starling Connect: ${msg}" }
