// Home Smoke and CO Response
// Version: 1.5.5
// Author: AI-assisted (Claude/Anthropic)
//
// v1.5.5 Changes:
//   - activateTier() now guards against re-triggering the same tier that is
//     already active. During a Nest Protect manual test both smoke="tested"
//     (from smokeHandler) and smokeStateDetail="warn" (from smokeDetailHandler)
//     fire for the same device. Without the guard this caused two sets of
//     activate*Lights() handlers to be scheduled, resulting in duplicate
//     flash sequences and double announcements.
//
// v1.5.4 Changes:
//   - Night light state (on/off + level) is now captured before alarm lights
//     activate and restored after alarm clears, with a configurable delay
//     (default 5s) to allow the Hue Bridge to finish processing alarm
//     commands before restore commands arrive.
//   - Night lights are NOT managed by circadian apps so this app must
//     restore them explicitly. Color/CT lights are still handled by the
//     circadian apps on their next scheduled update.
//   - Only the first tier to activate captures the snapshot - escalation
//     from Tier 1 to Tier 2/3 does not overwrite the original pre-alarm state.
//   - Added "How This App Works" documentation section to the main app UI
//     page explaining the full alarm sequence and restore behavior.
//   - Added nightLightRestoreDelay preference (1-30s, default 5s) in Flash
//     Settings section.
//   - restoreNightLights() added to clearAllAlarms() unschedule list.
//
// v1.5.3 Changes:
//   - Fixed variable name conflicts in switch/case blocks: 'msg' renamed to
//     'smokeMsg'/'coMsg'/'smokeRepeatMsg'/'coRepeatMsg' to prevent potential
//     Groovy variable shadowing issues in announceAfterFlash() and
//     repeatAnnouncement()
//   - Fixed stale comments: tier activation sequence, activateWarning delay
//     comment, Flash Settings UI paragraph (3->2 flashes, added Hue group tip)
//   - NOTE: If Hue Bridge bulbs show unreachable after testing, power cycle
//     the Hue Bridge. Rapid repeated commands during testing can overwhelm
//     the bridge's Zigbee radio. Use Hue Groups not individual bulbs to
//     minimize command count.
//
// v1.5.2 Changes:
//   - Flash cycles reduced from 3 to 2 (4 steps instead of 6)
//   - Announcements now fire AFTER lights reach steady state via
//     announceAfterFlash() handler, called 1s after flash completes.
//     Sequence: disable switch -> 2s -> lights flash 2x -> hold ->
//     1s -> TTS + notifications + schedule repeats
//   - clearAllAlarms() now also unschedules announceAfterFlash
//
// v1.5.1 Changes:
//   - Announcements and notifications moved into the delayed light handlers
//     (activateWarningLights, activateSmokeLights, activateCOLights) so TTS
//     and light commands dispatch in the same execution block for near-
//     simultaneous effect. Previously announcements fired 2s before lights.
//   - state.pendingRoom / state.pendingTier store the room name across the
//     2-second delay so the delayed handler has context.
//
// v1.5.0 Changes (full audit and clean rewrite):
//   - clearAllAlarms() now cancels all in-flight delayed handlers
//     (activateWarningLights, activateSmokeLights, activateCOLights,
//     flashStep, clearTestAlarm) so lights don't activate after a clear
//   - updated() now unschedules ALL scheduled methods, not just two
//   - Outside lights (smokeOutsideLights, coOutsideLights) now correctly
//     stay solid - they are no longer included in the flash DNI list
//   - Flash pattern corrected: setColor fires at full brightness, then
//     flash loop starts with dim (step 0=dim, 1=bright, 2=dim, 3=bright,
//     4=dim, 5=bright, then hold bright). Total 6 steps = 3 full flashes.
//   - Removed stale state.pendingSmokeRoom / state.pendingCORoom which
//     were stored but never read
//   - Removed stale "Single pulse" comment from old off/on approach
//   - Test page paragraph corrected to say 60 seconds not 30 seconds
//   - state.flashState cleared on clearAllAlarms() to prevent accumulation
//   - Flash loop correctly ignores outside light DNIs during setLevel steps
//
// v1.4.3 Changes:
//   - Flash pattern: holds bright for one interval after setColor before
//     starting flash loop so color is visible before first dim
//   - setLevelOnDnis searches all preference lists not just color lists
//   - Added duplicate removal in device lookup
//
// v1.4.2 Changes:
//   - pm25Handler ignores below-threshold readings during active test
//
// v1.4.1 Changes:
//   - Test timer extended to 60 seconds
//   - flashAndHold internal delay reduced from 3s to 1s
//
// v1.4.0 Changes:
//   - All three tiers disable circadian apps before sending light commands
//   - 2-second delay between disable switch and light commands
//
// v1.3.0 Changes:
//   - Flash rewritten: setColor once then setLevel for flash loop
//   - Flash interval and dim level configurable
//
// v1.2.0 Changes:
//   - Fixed device lookup for holdColorLights
//   - Light sequencing fixed for Tiers 2/3
//
// v1.1.0 Changes:
//   - Disable switch logic inverted to match user config
//   - Compatible with any Hubitat SmokeDetector/CarbonMonoxideDetector
//   - Added "tested" event handling
//   - smokeStateDetail guarded with hasAttribute()
//
// v1.0.0: Initial release
//
// BEFORE INSTALLING:
//   1. Create a virtual switch (e.g. "Smoke Alarm Active") and add it to
//      each circadian app's disable list. Configure circadian apps to
//      disable when the switch is OFF.
//   2. Add the same switch to each Room Lighting instance's disable
//      conditions, with re-enable set to "switch turns on".
//   3. Ensure AirPlay Integration is configured for HomePod announcements.
//   4. Ensure Hubitat mobile app is installed on phones to notify.

import groovy.transform.Field

@Field static final Integer LOG_REVERT_SECONDS    = 1800
@Field static final Integer LOG_VERBOSE_THRESHOLD = 4
@Field static final Integer PM25_DEFAULT_THRESHOLD = 55

@Field static final String TIER_NONE    = "none"
@Field static final String TIER_WARNING = "warning"
@Field static final String TIER_SMOKE   = "smoke"
@Field static final String TIER_CO      = "co"

// Hue values: 0-100 scale (not 0-360). Red=0, Amber≈8, White=sat:0
@Field static final Map COLOR_RED   = [hue: 0,  saturation: 100, level: 100]
@Field static final Map COLOR_AMBER = [hue: 8,  saturation: 100, level: 100]
@Field static final Map COLOR_WHITE = [hue: 0,  saturation: 0,   level: 100]

definition(
    name:           "Home Smoke and CO Response",
    namespace:      "homeEmergency",
    author:         "AI-assisted (Claude/Anthropic)",
    description:    "Three-tier emergency response to any Hubitat smoke/CO alarm and PM2.5 air quality spike.",
    category:       "Safety",
    iconUrl:        "",
    iconX2Url:      "",
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
    page(name: "warningPage")
    page(name: "smokePage")
    page(name: "coPage")
    page(name: "testPage")
}

// ---------------------------------------------------------------------------
// UI Pages
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "Home Smoke and CO Response", install: true, uninstall: true) {

        section("<h2>Sensors to Monitor</h2>") {
            input name: "protectDevices",
                  type: "capability.smokeDetector",
                  title: "Smoke detector devices (any brand)",
                  multiple: true, required: false

            input name: "coDevices",
                  type: "capability.carbonMonoxideDetector",
                  title: "Carbon monoxide detector devices (any brand)",
                  description: "Can be the same devices as above if your detector reports both",
                  multiple: true, required: false

            input name: "pm25Device",
                  type: "capability.airQuality",
                  title: "Air quality sensor for PM2.5 early warning (optional, any brand)",
                  multiple: false, required: false

            input name: "pm25Threshold",
                  type: "number",
                  title: "PM2.5 early warning threshold (µg/m³, default 55)",
                  defaultValue: PM25_DEFAULT_THRESHOLD,
                  range: "20..500",
                  required: false
        }

        section("<h2>Circadian / Room Lighting Disable Switch</h2>") {
            paragraph "Select the virtual switch(es) that disable your circadian apps and Room Lighting instances. " +
                      "These switches turn OFF when an alarm fires (disabling circadian apps) and back ON when the " +
                      "alarm clears. Configure your circadian apps to disable when this switch is OFF."
            input name: "disableSwitches",
                  type: "capability.switch",
                  title: "Disable switch(es)",
                  multiple: true, required: false
        }

        section("<h2>Flash Settings</h2>") {
            paragraph "Color lights flash 2 times before holding the emergency color. " +
                      "Color is set once via setColor(), then setLevel() alternates between full " +
                      "and dim brightness for the flash. Outside lights always stay solid. " +
                      "For best results, select a single Hue Group rather than individual bulbs " +
                      "to avoid sequential \"popcorn\" delays across the Hue Bridge."
            input name: "flashIntervalSeconds",
                  type: "enum",
                  title: "Time between flash steps",
                  options: ["1": "1 second", "2": "2 seconds", "3": "3 seconds", "4": "4 seconds", "5": "5 seconds"],
                  defaultValue: "2",
                  required: true
            input name: "flashDimLevel",
                  type: "number",
                  title: "Dim level during flash (0-30%)",
                  defaultValue: 10,
                  range: "0..30",
                  required: true
            input name: "nightLightRestoreDelay",
                  type: "number",
                  title: "Night light restore delay after alarm clears (seconds, default 5)",
                  description: "Delay before restoring night lights to pre-alarm state. Allows Hue Bridge to finish processing alarm commands first.",
                  defaultValue: 5,
                  range: "1..30",
                  required: false
        }

        section("<h2>Response Settings</h2>") {
            href name: "toWarningPage", page: "warningPage",
                 title: "Tier 1 - Early Warning (PM2.5)",
                 description: "Amber lights, gentle announcement"
            href name: "toSmokePage", page: "smokePage",
                 title: "Tier 2 - Smoke Alarm",
                 description: "Red lights, evacuate announcement"
            href name: "toCOPage", page: "coPage",
                 title: "Tier 3 - CO Alarm",
                 description: "White lights, leave immediately announcement"
        }

        section("<h2>Test</h2>") {
            href name: "toTestPage", page: "testPage",
                 title: "Test Response",
                 description: "Trigger a test of each alarm tier"
        }

        section("<h2>How This App Works</h2>") {
            paragraph "<b>Real alarm sequence (from a sensor):</b>\n" +
                "1. Smoke/CO detector fires → circadian apps disabled immediately\n" +
                "2. After 2 seconds: outside lights go solid red/white; inside color lights flash " +
                "2 times then hold emergency color; CT-only lights go full white; night lights " +
                "turn on full brightness\n" +
                "3. After flash completes: TTS announcement on all speakers + push notifications\n" +
                "4. Announcements repeat on your configured schedule\n" +
                "5. When ALL selected sensors report clear: night lights restore to their " +
                "pre-alarm state (after a short delay), circadian apps re-enable and restore " +
                "color/CT lights on their next scheduled update\n\n" +
                "<b>Night lights:</b> State (on/off + level) is captured before the alarm fires " +
                "and restored when the alarm clears. Night lights are NOT controlled by circadian " +
                "apps so they must be restored manually by this app.\n\n" +
                "<b>Color/CT lights:</b> NOT restored by this app — the circadian apps handle " +
                "restoration on their next tick (up to 5 minutes after clear).\n\n" +
                "<b>Outside lights:</b> Set to solid red during Tier 2/3. NOT restored by this " +
                "app — the circadian app controlling outside lights will restore them on its next tick.\n\n" +
                "<b>Priority:</b> CO (Tier 3) > Smoke (Tier 2) > PM2.5 Warning (Tier 1). " +
                "A higher-priority alarm cannot be downgraded by a lower-priority event.\n\n" +
                "<b>Test buttons:</b> Run for 60 seconds then auto-clear. Night lights restore " +
                "after the configured restore delay. PM2.5 sensor readings are ignored during tests."
        }

        section("<h2>Logging</h2>") {
            input name: "logLevel",
                  type: "enum",
                  title: "Log level (Debug and Trace revert to Info after 30 minutes)",
                  options: ["0": "None", "1": "Error", "2": "Warn", "3": "Info", "4": "Debug", "5": "Trace"],
                  defaultValue: "3", required: true
        }
    }
}

def warningPage() {
    dynamicPage(name: "warningPage", title: "Tier 1 - Early Warning", install: false, uninstall: false) {

        section("<h2>Lights</h2>") {
            input name: "warnColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash amber then hold)",
                  multiple: true, required: false
            input name: "warnCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on full brightness white)",
                  description: "Select white-only bulbs here. Color-capable bulbs go in the color lights list above.",
                  multiple: true, required: false
            input name: "warnNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on full brightness)",
                  multiple: true, required: false
        }

        section("<h2>Announcements</h2>") {
            input name: "warnSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "warnVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 80, range: "0..100", required: false
            input name: "warnMessage",
                  type: "text",
                  title: "Announcement message",
                  defaultValue: "Attention. Elevated smoke particles detected. Please check for a smoke source.",
                  required: false
            input name: "warnRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "2", required: false
        }

        section("<h2>Notifications</h2>") {
            input name: "warnNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "warnNotifyMessage",
                  type: "text",
                  title: "Push notification message",
                  defaultValue: "Early Warning: Elevated smoke particles detected. Check your home for a smoke source.",
                  required: false
        }
    }
}

def smokePage() {
    dynamicPage(name: "smokePage", title: "Tier 2 - Smoke Alarm", install: false, uninstall: false) {

        section("<h2>Lights</h2>") {
            input name: "smokeColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash red then hold)",
                  multiple: true, required: false
            input name: "smokeCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on full brightness white)",
                  description: "Select white-only bulbs here.",
                  multiple: true, required: false
            input name: "smokeNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on full brightness)",
                  multiple: true, required: false
            input name: "smokeOutsideLights",
                  type: "capability.colorControl",
                  title: "Outside color lights (solid red, no flash)",
                  multiple: true, required: false
        }

        section("<h2>Announcements</h2>") {
            input name: "smokeSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "smokeVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 100, range: "0..100", required: false
            input name: "smokeMessage",
                  type: "text",
                  title: "Announcement message (%room% = room name)",
                  defaultValue: "Smoke alarm. Smoke detected in %room%. Evacuate immediately. Stay low and crawl to the nearest exit.",
                  required: false
            input name: "smokeRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "1", required: false
        }

        section("<h2>Notifications</h2>") {
            input name: "smokeNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "smokeNotifyMessage",
                  type: "text",
                  title: "Push notification message (%room% = room name)",
                  defaultValue: "SMOKE ALARM: Smoke detected in %room%. Evacuate immediately.",
                  required: false
        }
    }
}

def coPage() {
    dynamicPage(name: "coPage", title: "Tier 3 - CO Alarm", install: false, uninstall: false) {

        section("<h2>Lights</h2>") {
            input name: "coColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash bright white then hold)",
                  multiple: true, required: false
            input name: "coCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on full brightness)",
                  description: "Select white-only bulbs here.",
                  multiple: true, required: false
            input name: "coNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on full brightness)",
                  multiple: true, required: false
            input name: "coOutsideLights",
                  type: "capability.colorControl",
                  title: "Outside color lights (solid red, no flash)",
                  multiple: true, required: false
        }

        section("<h2>Announcements</h2>") {
            input name: "coSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "coVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 100, range: "0..100", required: false
            input name: "coMessage",
                  type: "text",
                  title: "Announcement message (%room% = room name)",
                  defaultValue: "Carbon monoxide alarm. Carbon monoxide detected in %room%. Leave the house immediately. Do not stop for anything. Go outside now.",
                  required: false
            input name: "coRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "1", required: false
        }

        section("<h2>Notifications</h2>") {
            input name: "coNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "coNotifyMessage",
                  type: "text",
                  title: "Push notification message (%room% = room name)",
                  defaultValue: "CO ALARM: Carbon monoxide detected in %room%. Leave the house immediately.",
                  required: false
        }
    }
}

def testPage() {
    dynamicPage(name: "testPage", title: "Test Response", install: false, uninstall: false) {
        section {
            paragraph "Use these buttons to test each alarm tier. The response will run for 60 seconds then automatically clear. Press Clear Test at any time to stop early."
            input name: "testWarning", type: "button", title: "Test Tier 1 - Early Warning"
            input name: "testSmoke",   type: "button", title: "Test Tier 2 - Smoke Alarm"
            input name: "testCO",      type: "button", title: "Test Tier 3 - CO Alarm"
            input name: "testClear",   type: "button", title: "Clear Test (restore lights)"
        }
        if (state.testActive) {
            section {
                paragraph "Test in progress: ${state.activeTier?.toUpperCase()} — will auto-clear in 60 seconds, or press Clear Test above."
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logInfo "installed()"
    state.activeTier  = TIER_NONE
    state.activeRooms = []
    state.testActive  = false
    state.flashState         = [:]
    state.nightLightSnapshot = null
    initialize()
}

def updated() {
    logInfo "updated()"
    unschedule()   // cancel ALL scheduled methods
    scheduleLogRevertIfNeeded()
    initialize()
}

def uninstalled() {
    logInfo "uninstalled()"
    clearAllAlarms()
}

private void initialize() {
    unsubscribe()

    if (protectDevices) {
        subscribe(protectDevices, "smoke", "smokeHandler")
        protectDevices.each { dev ->
            if (dev.hasAttribute("smokeStateDetail")) {
                subscribe(dev, "smokeStateDetail", "smokeDetailHandler")
                logDebug "Subscribed to smokeStateDetail for ${dev.displayName}"
            }
        }
    }

    if (coDevices) {
        subscribe(coDevices, "carbonMonoxide", "coHandler")
    }

    if (pm25Device) {
        subscribe(pm25Device, "pm25", "pm25Handler")
    }

    logInfo "Initialized. Monitoring: ${protectDevices?.size() ?: 0} smoke detectors, ${coDevices?.size() ?: 0} CO detectors, ${pm25Device ? 1 : 0} PM2.5 sensor"
}

// ---------------------------------------------------------------------------
// Button handler (test page)
// ---------------------------------------------------------------------------

def appButtonHandler(btn) {
    switch (btn) {
        case "testWarning":
            logInfo "Test: triggering Early Warning"
            state.testActive = true
            activateTier(TIER_WARNING, "Test Location")
            runIn(60, "clearTestAlarm")
            break
        case "testSmoke":
            logInfo "Test: triggering Smoke Alarm"
            state.testActive = true
            activateTier(TIER_SMOKE, "Test Room")
            runIn(60, "clearTestAlarm")
            break
        case "testCO":
            logInfo "Test: triggering CO Alarm"
            state.testActive = true
            activateTier(TIER_CO, "Test Room")
            runIn(60, "clearTestAlarm")
            break
        case "testClear":
            logInfo "Test: manual clear"
            clearTestAlarm()
            break
    }
}

def clearTestAlarm() {
    logInfo "Test cleared"
    state.testActive = false
    clearAllAlarms()
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

def smokeHandler(evt) {
    logDebug "smokeHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "detected" || evt.value == "tested") {
        def roomName = evt.displayName
        addActiveRoom(roomName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, roomName)
        }
    } else if (evt.value == "clear") {
        removeActiveRoom(evt.displayName)
        checkAndClear()
    }
}

def smokeDetailHandler(evt) {
    logDebug "smokeDetailHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "warn" || evt.value == "emergency") {
        def roomName = evt.displayName
        addActiveRoom(roomName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, roomName)
        }
    } else if (evt.value == "ok") {
        removeActiveRoom(evt.displayName)
        checkAndClear()
    }
}

def coHandler(evt) {
    logDebug "coHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "detected" || evt.value == "tested") {
        def roomName = evt.displayName
        addActiveRoom(roomName)
        activateTier(TIER_CO, roomName)
    } else if (evt.value == "clear") {
        removeActiveRoom(evt.displayName)
        checkAndClear()
    }
}

def pm25Handler(evt) {
    logDebug "pm25Handler: ${evt.value} µg/m³"
    def threshold = (pm25Threshold ?: PM25_DEFAULT_THRESHOLD) as Integer
    def value     = (evt.value as BigDecimal).toInteger()

    if (value >= threshold) {
        if (state.activeTier == TIER_NONE) {
            logInfo "PM2.5 threshold exceeded: ${value} µg/m³ (threshold: ${threshold})"
            activateTier(TIER_WARNING, "indoor air")
        }
    } else {
        if (state.activeTier == TIER_WARNING) {
            if (state.testActive) {
                logDebug "pm25Handler: below threshold but test active - ignoring clear"
                return
            }
            logInfo "PM2.5 returned below threshold: ${value} µg/m³"
            clearAllAlarms()
        }
    }
}

// ---------------------------------------------------------------------------
// Tier activation
// ---------------------------------------------------------------------------

private void activateTier(String tier, String roomName) {
    // Prevent downgrade to lower priority
    if (tier == TIER_WARNING && state.activeTier in [TIER_SMOKE, TIER_CO]) {
        logDebug "activateTier: ignoring ${tier} - higher priority alarm active"
        return
    }
    if (tier == TIER_SMOKE && state.activeTier == TIER_CO) {
        logDebug "activateTier: ignoring ${tier} - CO alarm active"
        return
    }
    // Prevent re-triggering the same tier already active.
    // Multiple events from the same alarm (e.g. smoke="tested" AND
    // smokeStateDetail="warn" during a Nest Protect manual test) would
    // otherwise fire duplicate light/announcement sequences.
    if (tier == state.activeTier) {
        logDebug "activateTier: ignoring ${tier} - already active for this tier"
        return
    }

    logInfo "Activating ${tier.toUpperCase()} response for: ${roomName}"
    state.activeTier = tier

    switch (tier) {
        case TIER_WARNING: activateWarning(roomName); break
        case TIER_SMOKE:   activateSmoke(roomName);   break
        case TIER_CO:      activateCO(roomName);      break
    }
}

// All three tiers follow the same sequence:
// 1. Disable circadian apps immediately (they react to light-on events and
//    will overwrite our emergency colors if not stopped first)
// 2. Store room name in state for the delayed handler
// 3. Wait 2 seconds for circadian apps to process the disable switch event
// 4. activate*Lights() fires: outside lights solid, inside lights flash
// 5. After flash completes, announceAfterFlash() fires TTS + notifications

private void activateWarning(String roomName) {
    enableDisableSwitches(false)
    // Store room for the delayed handler
    state.pendingRoom = roomName
    state.pendingTier = TIER_WARNING
    // Delay light commands so circadian apps have time to process the
    // disable switch event before any lights turn on. Announcements fire
    // after the flash completes via announceAfterFlash().
    runIn(2, "activateWarningLights")
}

def activateWarningLights() {
    // Snapshot night light state before overriding so we can restore on clear
    captureNightLightState(warnNightLights)
    // Lights first - announcements fire after flash completes (see announceAfterFlash)
    flashAndHold(warnColorLights, COLOR_AMBER, null)
    turnOnCTLights(warnCTLights)
    turnOnNightLights(warnNightLights)
}

private void activateSmoke(String roomName) {
    enableDisableSwitches(false)
    state.pendingRoom = roomName
    state.pendingTier = TIER_SMOKE
    runIn(2, "activateSmokeLights")
}

def activateSmokeLights() {
    // Snapshot night light state before overriding so we can restore on clear
    captureNightLightState(smokeNightLights)
    // Lights first - announcements fire after flash completes (see announceAfterFlash)
    setOutsideLights(smokeOutsideLights, COLOR_RED)
    flashAndHold(smokeColorLights, COLOR_RED, null)
    turnOnCTLights(smokeCTLights)
    turnOnNightLights(smokeNightLights)
}

private void activateCO(String roomName) {
    enableDisableSwitches(false)
    state.pendingRoom = roomName
    state.pendingTier = TIER_CO
    runIn(2, "activateCOLights")
}

def activateCOLights() {
    // Snapshot night light state before overriding so we can restore on clear
    captureNightLightState(coNightLights)
    // Lights first - announcements fire after flash completes (see announceAfterFlash)
    setOutsideLights(coOutsideLights, COLOR_RED)
    flashAndHold(coColorLights, COLOR_WHITE, null)
    turnOnCTLights(coCTLights)
    turnOnNightLights(coNightLights)
}

// ---------------------------------------------------------------------------
// Repeat announcement scheduler
// ---------------------------------------------------------------------------

def repeatAnnouncement(data) {
    if (state.activeTier == TIER_NONE) return

    def tier     = data?.tier ?: state.activeTier
    def roomName = data?.room ?: (state.activeRooms?.join(", ") ?: "unknown")

    logDebug "repeatAnnouncement: tier=${tier} room=${roomName}"

    switch (tier) {
        case TIER_WARNING:
            announceOnSpeakers(warnSpeakers, warnMessage ?: "Attention. Elevated smoke particles detected.", warnVolume)
            break
        case TIER_SMOKE:
            def smokeRepeatMsg = buildMessage(smokeMessage ?: "Smoke alarm. Smoke detected in %room%. Evacuate immediately.", roomName)
            announceOnSpeakers(smokeSpeakers, smokeRepeatMsg, smokeVolume)
            break
        case TIER_CO:
            def coRepeatMsg = buildMessage(coMessage ?: "Carbon monoxide alarm. Leave the house immediately.", roomName)
            announceOnSpeakers(coSpeakers, coRepeatMsg, coVolume)
            break
    }

    def minutes = getRepeatMinutes(tier)
    if (minutes > 0) {
        runIn(minutes * 60, "repeatAnnouncement", [data: [tier: tier, room: roomName]])
    }
}

private int getRepeatMinutes(String tier) {
    def val
    switch (tier) {
        case TIER_WARNING: val = warnRepeatMinutes;  break
        case TIER_SMOKE:   val = smokeRepeatMinutes; break
        case TIER_CO:      val = coRepeatMinutes;    break
        default:           return 0
    }
    return (val ?: "0").toInteger()
}

// ---------------------------------------------------------------------------
// Clear / restore
// ---------------------------------------------------------------------------

private void checkAndClear() {
    if (state.activeRooms && !state.activeRooms.isEmpty()) {
        logDebug "checkAndClear: still active rooms: ${state.activeRooms}"
        return
    }
    logInfo "All alarms cleared - restoring lights"
    clearAllAlarms()
}

private void clearAllAlarms() {
    // Cancel all in-flight scheduled handlers so no delayed light commands
    // fire after the alarm has been cleared
    unschedule("repeatAnnouncement")
    unschedule("activateWarningLights")
    unschedule("activateSmokeLights")
    unschedule("activateCOLights")
    unschedule("flashStep")
    unschedule("clearTestAlarm")
    unschedule("announceAfterFlash")
    unschedule("restoreNightLights")

    state.activeTier  = TIER_NONE
    state.activeRooms = []
    state.flashState         = [:]
    state.nightLightSnapshot = null   // clear any in-progress flash state

    enableDisableSwitches(true)
    logInfo "Response cleared. Disable switches turned ON - circadian apps will resume on their next scheduled update."

    // Restore night lights to their pre-alarm state after a delay.
    // The delay gives the Hue Bridge time to finish processing alarm commands
    // before restore commands arrive, preventing command collisions.
    if (state.nightLightSnapshot) {
        def delaySecs = (nightLightRestoreDelay ?: 5) as Integer
        logInfo "Night lights will restore to pre-alarm state in ${delaySecs}s"
        runIn(delaySecs, "restoreNightLights")
    }
}

// ---------------------------------------------------------------------------
// Flash logic
//
// Flash sequence for inside color lights:
//   T+0s  : setColor(emergency color at 100%) — lights go to emergency color
//   T+Ns  : flashStep fires — setLevel(dim%)   [step 0]
//   T+2Ns : flashStep fires — setLevel(100%)   [step 1]
//   T+3Ns : flashStep fires — setLevel(dim%)   [step 2]
//   T+4Ns : flashStep fires — setLevel(100%)   [step 3]
//   T+5Ns : flashStep fires — hold at 100%, THEN fire announcements + notifications
//
// N = flashIntervalSeconds (user configurable, default 2s)
// 2 complete dim-bright cycles = 4 steps
// Announcements fire AFTER lights reach steady state so TTS is clear
// Outside lights are set solid separately and NOT included in flash DNIs
// ---------------------------------------------------------------------------

private void flashAndHold(lights, Map color, ignored) {
    if (!lights) return

    if (!state.flashState) state.flashState = [:]
    def flashKey = "flash_${now()}"

    // Store ONLY inside color light DNIs - outside lights excluded
    state.flashState[flashKey] = [
        color     : color,
        dnis      : lights.collect { it.deviceNetworkId },
        flashStep : 0,
        totalSteps: 4    // 2 flashes × 2 steps (dim + bright) each
    ]

    // Step 1: set emergency color at full brightness immediately
    lights.each { light ->
        try {
            light.on()
            light.setColor(color)
            logDebug "setColor: ${light.displayName} → hue:${color.hue} sat:${color.saturation}"
        } catch (Exception e) {
            logWarn "setColor error for ${light.displayName}: ${e.message}"
        }
    }

    // Step 2: wait one interval at full brightness so color is visible,
    // then start the dim-bright flash loop
    def intervalSecs = (flashIntervalSeconds ?: "2").toInteger()
    runIn(intervalSecs, "flashStep", [data: [flashKey: flashKey]])
}

def flashStep(data) {
    def flashKey = data?.flashKey
    if (!flashKey) return

    if (!state.flashState) {
        logDebug "flashStep: no flashState - alarm was cleared"
        return
    }

    def fs = state.flashState[flashKey]
    if (!fs) {
        logDebug "flashStep: flashKey not found - alarm was cleared"
        return
    }

    if (state.activeTier == TIER_NONE) {
        state.flashState.remove(flashKey)
        logDebug "flashStep: alarm cleared - stopping"
        return
    }

    def step         = fs.flashStep as Integer
    def total        = fs.totalSteps as Integer
    def dnis         = fs.dnis
    def color        = fs.color
    def intervalSecs = (flashIntervalSeconds ?: "2").toInteger()
    def dimLevel     = (flashDimLevel ?: 10) as Integer
    def fullLevel    = (color?.level ?: 100) as Integer

    if (step >= total) {
        // All flashes complete - hold at full brightness then fire announcements
        setLevelOnDnis(dnis, fullLevel)
        state.flashState.remove(flashKey)
        logDebug "flashStep: complete - holding at ${fullLevel}%"
        // Fire announcements now that lights are steady - cleaner UX
        runIn(1, "announceAfterFlash")
        return
    }

    // Even steps (0,2,4) = dim; odd steps (1,3,5) = full bright
    def targetLevel = (step % 2 == 0) ? dimLevel : fullLevel
    setLevelOnDnis(dnis, targetLevel)
    logDebug "flashStep: step ${step}/${total} → ${targetLevel}%"

    fs.flashStep = step + 1
    state.flashState[flashKey] = fs

    runIn(intervalSecs, "flashStep", [data: [flashKey: flashKey]])
}

// ---------------------------------------------------------------------------
// Post-flash announcement handler
// Fires after lights reach steady state so TTS is clear and unambiguous
// ---------------------------------------------------------------------------

def announceAfterFlash() {
    if (state.activeTier == TIER_NONE) return   // alarm cleared during flash

    def tier     = state.activeTier
    def roomName = state.pendingRoom ?: (state.activeRooms?.join(", ") ?: "unknown")

    logDebug "announceAfterFlash: tier=${tier} room=${roomName}"

    switch (tier) {
        case TIER_WARNING:
            announceOnSpeakers(warnSpeakers,
                warnMessage ?: "Attention. Elevated smoke particles detected. Please check for a smoke source.",
                warnVolume)
            sendNotifications(warnNotifyDevices,
                warnNotifyMessage ?: "Early Warning: Elevated smoke particles detected. Check your home for a smoke source.")
            scheduleRepeat("repeatAnnouncement", warnRepeatMinutes, [tier: TIER_WARNING, room: roomName])
            break
        case TIER_SMOKE:
            def smokeMsg = buildMessage(smokeMessage ?: "Smoke alarm. Smoke detected in %room%. Evacuate immediately. Stay low and crawl to the nearest exit.", roomName)
            announceOnSpeakers(smokeSpeakers, smokeMsg, smokeVolume)
            sendNotifications(smokeNotifyDevices,
                buildMessage(smokeNotifyMessage ?: "SMOKE ALARM: Smoke detected in %room%. Evacuate immediately.", roomName))
            scheduleRepeat("repeatAnnouncement", smokeRepeatMinutes, [tier: TIER_SMOKE, room: roomName])
            break
        case TIER_CO:
            def coMsg = buildMessage(coMessage ?: "Carbon monoxide alarm. Carbon monoxide detected in %room%. Leave the house immediately. Do not stop for anything. Go outside now.", roomName)
            announceOnSpeakers(coSpeakers, coMsg, coVolume)
            sendNotifications(coNotifyDevices,
                buildMessage(coNotifyMessage ?: "CO ALARM: Carbon monoxide detected in %room%. Leave the house immediately.", roomName))
            scheduleRepeat("repeatAnnouncement", coRepeatMinutes, [tier: TIER_CO, room: roomName])
            break
    }
}

// ---------------------------------------------------------------------------
// Night light state capture / restore
//
// Night lights are not controlled by circadian apps so this app must
// explicitly restore their pre-alarm state when an alarm clears.
// Color/CT lights are handled by the circadian apps on their next tick.
// ---------------------------------------------------------------------------

private void captureNightLightState(lights) {
    if (!lights) return
    // Only capture if no snapshot exists yet - prevents a Tier 2 alarm from
    // overwriting the Tier 1 snapshot (which captured the true pre-alarm state)
    if (state.nightLightSnapshot) {
        logDebug "captureNightLightState: snapshot already exists - keeping original"
        return
    }
    def snapshot = [:]
    lights.each { light ->
        try {
            def sw    = light.currentSwitch ?: "off"
            def level = light.currentLevel  ?: 0
            snapshot[light.deviceNetworkId] = [switch: sw, level: level, label: light.displayName]
            logDebug "captureNightLightState: ${light.displayName} → ${sw} @ ${level}%"
        } catch (Exception e) {
            logWarn "captureNightLightState error for ${light.displayName}: ${e.message}"
        }
    }
    state.nightLightSnapshot = snapshot
    logInfo "Night light state captured for ${snapshot.size()} device(s)"
}

def restoreNightLights() {
    def snapshot = state.nightLightSnapshot
    if (!snapshot) {
        logDebug "restoreNightLights: no snapshot found - nothing to restore"
        return
    }

    // Build full list of all night light devices across all three tiers
    def allNightLights = []
    [warnNightLights, smokeNightLights, coNightLights].each { list ->
        if (list) allNightLights.addAll(list)
    }
    allNightLights = allNightLights.unique { it?.deviceNetworkId }

    snapshot.each { dni, savedState ->
        def device = allNightLights.find { it?.deviceNetworkId == dni }
        if (!device) {
            logWarn "restoreNightLights: device not found for DNI ${dni} (${savedState.label})"
            return
        }
        try {
            if (savedState.switch == "off") {
                device.off()
                logDebug "restoreNightLights: ${device.displayName} → off"
            } else {
                device.on()
                if (device.hasCapability("SwitchLevel") && savedState.level > 0) {
                    device.setLevel(savedState.level as Integer)
                }
                logDebug "restoreNightLights: ${device.displayName} → on @ ${savedState.level}%"
            }
        } catch (Exception e) {
            logWarn "restoreNightLights error for ${device.displayName}: ${e.message}"
        }
    }

    state.nightLightSnapshot = null   // clear snapshot after restore
    logInfo "Night lights restored to pre-alarm state"
}

// ---------------------------------------------------------------------------
// Light command helpers
// ---------------------------------------------------------------------------

// Sets level on a list of device DNIs by looking them up across all
// preference lists. Only searches inside color lights (not outside) since
// outside lights are handled separately as solid color.
private void setLevelOnDnis(List dnis, int level) {
    def allInsideColorDevices = []
    [warnColorLights, smokeColorLights, coColorLights].each { list ->
        if (list) allInsideColorDevices.addAll(list)
    }
    allInsideColorDevices = allInsideColorDevices.unique { it?.deviceNetworkId }

    dnis.each { dni ->
        def device = allInsideColorDevices.find { it?.deviceNetworkId == dni }
        if (device) {
            try {
                device.setLevel(level)
                logDebug "setLevel: ${device.displayName} → ${level}%"
            } catch (Exception e) {
                logWarn "setLevel error for ${device.displayName}: ${e.message}"
            }
        } else {
            logWarn "setLevelOnDnis: device not found for DNI ${dni}"
        }
    }
}

private void turnOnCTLights(lights) {
    if (!lights) return
    lights.each { light ->
        try {
            light.on()
            light.setLevel(100)
        } catch (Exception e) {
            logDebug "turnOnCTLights error for ${light.displayName}: ${e.message}"
        }
    }
}

private void turnOnNightLights(lights) {
    if (!lights) return
    lights.each { light ->
        try {
            light.on()
            if (light.hasCapability("SwitchLevel")) light.setLevel(100)
        } catch (Exception e) {
            logDebug "turnOnNightLights error for ${light.displayName}: ${e.message}"
        }
    }
}

private void setOutsideLights(lights, Map color) {
    if (!lights) return
    lights.each { light ->
        try {
            light.on()
            light.setColor(color)
            logDebug "setOutsideLights: ${light.displayName} → solid hue:${color.hue}"
        } catch (Exception e) {
            logWarn "setOutsideLights error for ${light.displayName}: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
// Disable switch helpers
// ---------------------------------------------------------------------------

private void enableDisableSwitches(boolean on) {
    if (!disableSwitches) return
    disableSwitches.each { sw ->
        try {
            on ? sw.on() : sw.off()
        } catch (Exception e) {
            logWarn "enableDisableSwitches error for ${sw.displayName}: ${e.message}"
        }
    }
    logDebug "Disable switches turned ${on ? 'ON (normal/restored)' : 'OFF (alarm active)'}"
}

// ---------------------------------------------------------------------------
// Speaker helpers
// ---------------------------------------------------------------------------

private void announceOnSpeakers(speakers, String message, volume) {
    if (!speakers || !message) return
    def vol = (volume ?: 80) as Integer
    speakers.each { speaker ->
        try {
            if (speaker.hasCapability("AudioVolume")) speaker.setVolume(vol)
            speaker.speak(message)
            logDebug "Announced on ${speaker.displayName}: ${message}"
        } catch (Exception e) {
            logWarn "Speaker error for ${speaker.displayName}: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
// Notification helpers
// ---------------------------------------------------------------------------

private void sendNotifications(devices, String message) {
    if (!devices || !message) return
    devices.each { device ->
        try {
            device.deviceNotification(message)
            logDebug "Notification sent to ${device.displayName}"
        } catch (Exception e) {
            logWarn "Notification error for ${device.displayName}: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
// Utility helpers
// ---------------------------------------------------------------------------

private void addActiveRoom(String room) {
    if (!state.activeRooms) state.activeRooms = []
    if (!state.activeRooms.contains(room)) state.activeRooms << room
}

private void removeActiveRoom(String room) {
    if (!state.activeRooms) return
    state.activeRooms = state.activeRooms.findAll { it != room }
}

private String buildMessage(String template, String roomName) {
    return template?.replace("%room%", roomName ?: "unknown") ?: ""
}

private void scheduleRepeat(String method, repeatSetting, Map data) {
    def minutes = (repeatSetting ?: "0").toInteger()
    if (minutes > 0) {
        runIn(minutes * 60, method, [data: data])
    }
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
    log.info "Home Smoke and CO Response: 30-minute verbose log window expired - reverting to Info"
    app.updateSetting("logLevel", [type: "enum", value: "3"])
}

private int logLevelSetting() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= LOG_VERBOSE_THRESHOLD) {
        def expiry = state.logLevelExpiry as Long
        if (expiry && now() > expiry) return 3
    }
    return level
}

private void logError(String msg) { if (logLevelSetting() >= 1) log.error "Home Smoke and CO Response: ${msg}" }
private void logWarn (String msg) { if (logLevelSetting() >= 2) log.warn  "Home Smoke and CO Response: ${msg}" }
private void logInfo (String msg) { if (logLevelSetting() >= 3) log.info  "Home Smoke and CO Response: ${msg}" }
private void logDebug(String msg) { if (logLevelSetting() >= 4) log.debug "Home Smoke and CO Response: ${msg}" }
private void logTrace(String msg) { if (logLevelSetting() >= 5) log.trace "Home Smoke and CO Response: ${msg}" }
