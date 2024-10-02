@file:DependsOn("org.seleniumhq.selenium:selenium-java:4.24.0")
@file:DependsOn("org.seleniumhq.selenium:selenium-chrome-driver:4.24.0")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

import com.fasterxml.jackson.databind.ObjectMapper
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.devtools.DevTools
import org.openqa.selenium.devtools.v128.dom.DOM
import org.openqa.selenium.devtools.v128.dom.model.NodeId
import org.openqa.selenium.devtools.v128.dom.model.RGBA
import org.openqa.selenium.devtools.v128.overlay.Overlay
import org.openqa.selenium.devtools.v128.overlay.model.HighlightConfig
import org.openqa.selenium.devtools.v128.page.Page
import org.openqa.selenium.devtools.v128.runtime.Runtime
import org.openqa.selenium.devtools.v128.runtime.model.PropertyDescriptor
import org.openqa.selenium.devtools.v128.runtime.model.RemoteObject
import org.openqa.selenium.devtools.v128.runtime.model.RemoteObjectId
import org.openqa.selenium.interactions.Actions
import java.io.File
import java.time.LocalDateTime
import java.util.*
import kotlin.jvm.optionals.getOrNull

val recordingDate: LocalDateTime = LocalDateTime.now()
val targetFile = File("browser-recording.json")

// Shared stuff - should work with @file:Import, but does not
val htmlNodeIdMarker = "node-id-for-tests-only"
enum class EvtType { MOUSE_CLICK, KEYPRESS, ASSERT_ELEM, MAJOR_DOM_CHANGE }
data class RecordingEvent(
    val htmlSnapshotId: Int,
    val type: EvtType,
    val nodeId: String,
    val text: String? = null,
    val charCode: Long? = null,
    val currentUrl: String? = null
)
data class Recording(val htmlSnapshots: List<String>, val events: List<RecordingEvent>)

// Mappers - not shared
fun RawRecordedAction.toRecording(usedHtmlVersions: SortedSet<Int>): RecordingEvent {
    return RecordingEvent(
        usedHtmlVersions.indexOf(this.htmlSnapshotId),
        this.type.map(),
        this.nodeId,
        this.key,
        this.charCode,
        this.currentUrl
    )
}

// ================ 1. Selenium stuff ==================
// Helpers
fun WebDriver.executeScript(script: String, vararg args: Any): Any? {
    return (this as JavascriptExecutor).executeScript(script, *args)
}

fun WebDriver.documentHtml(): String {
    return this.executeScript("return document.body.innerHTML;") as String
}

fun RemoteObjectId.properties(devTools: DevTools): Runtime.GetPropertiesResponse {
    return devTools.send(Runtime.getProperties(this, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
}

fun Runtime.GetPropertiesResponse.asMap(): Map<String, PropertyDescriptor> {
    return this.result.associateBy { it.name }
}

fun List<String>.interleavedToMap(): Map<String, String> {
    return this.zipWithNext().toMap()
}

// ================ 1.1. Selenium setup ==================
val chromeOptions = ChromeOptions().apply {
    addArguments("--disable-notifications", "--user-agent='Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36")
    enableBiDi()
}

val driver: WebDriver = ChromeDriver(chromeOptions).apply {
    manage().window().maximize()
}
val actions = Actions(driver)


// ================ 2. Prepare user interaction recording ==================
val devTools = (driver as ChromeDriver).devTools
devTools.createSession()

devTools.send(Runtime.enable())
devTools.send(Page.enable())
devTools.send(DOM.enable(Optional.empty()))
devTools.send(Overlay.enable())

devTools.send(
    Page.addScriptToEvaluateOnNewDocument(
        "monitorEvents(window, 'mousemove')",
        Optional.empty(),
        Optional.of(true),
        Optional.of(true),
    )
)
devTools.send(
    Page.addScriptToEvaluateOnNewDocument(
        "monitorEvents(window, 'mousedown')",
        Optional.empty(),
        Optional.of(true),
        Optional.of(true),
    )
)
devTools.send(
    Page.addScriptToEvaluateOnNewDocument(
        "monitorEvents(window, 'keydown')",
        Optional.empty(),
        Optional.of(true),
        Optional.of(true),
    )
)

// ================ 3. Prepare mappings of actionable web-element to its XPath ==================


// ================ 4. Record 'raw' events ==================
var pageHtml = "" // TODO - It evolves, maybe we should store versions or preserve html with element
enum class RawEvtType {
    MOUSE_CLICK, KEYDOWN, ASSERT_ELEM, MAJOR_DOM_CHANGE;
    fun map(): EvtType {
        return when (this) {
            MOUSE_CLICK -> EvtType.MOUSE_CLICK
            KEYDOWN -> EvtType.KEYPRESS
            ASSERT_ELEM -> EvtType.ASSERT_ELEM
            MAJOR_DOM_CHANGE -> EvtType.MAJOR_DOM_CHANGE
        }
    }
}
data class RawRecordedAction(
    val posId: Long,
    val htmlSnapshotId: Int,
    val type: RawEvtType,
    val nodeId: String,
    val key: String? = null,
    val charCode: Long? = null,
    val currentUrl: String? = null
)

val events = mutableListOf<RawRecordedAction>()
val htmlSnapshots = mutableListOf<String>()
var isRecording = false
var elementUnderCursor: RemoteObjectId? = null

fun pinNodeWithId(elementUnderCursor: RemoteObjectId): String {
    // Next line is a workaround for https://github.com/puppeteer/puppeteer/issues/9077 :
    devTools.send(DOM.getDocument(Optional.of(0), Optional.empty()))
    val nodeId = devTools.send(DOM.requestNode(elementUnderCursor))
    // nodeId to locate element later
    val attrs = devTools.send(DOM.getAttributes(nodeId)).interleavedToMap()
    if (null != attrs[htmlNodeIdMarker]) return attrs[htmlNodeIdMarker] as String

    val uuid = UUID.randomUUID().toString()
    devTools.send(DOM.setAttributeValue(nodeId, htmlNodeIdMarker, uuid))
    htmlSnapshots += driver.documentHtml()
    return uuid
}

fun onMouseClick(id: Long, eventArg: RemoteObjectId) {
    if (!isRecording) return
    val targetId = (eventArg.properties(devTools).asMap()["target"]!!.value as Optional<RemoteObject>).get().objectId.get()
    val nodeId = pinNodeWithId(targetId)
    // An alternative without DOM changes would be detailed XPath tracing (with CSS, attributes, etc.)
    events += RawRecordedAction(
        id,
        htmlSnapshots.size - 1,
        RawEvtType.MOUSE_CLICK,
        nodeId
    )
}

fun onKeyDown(id: Long, eventArg: RemoteObjectId) {
    val key = (eventArg.properties(devTools).asMap()["code"]!!.value as Optional<RemoteObject>).getOrNull()?.value?.getOrNull() as String?
    // TODO: Macos specific now
    val ctrlKey = (eventArg.properties(devTools).asMap()["ctrlKey"]!!.value as Optional<RemoteObject>).getOrNull()?.value?.getOrNull() as Boolean?
    val shiftKey = (eventArg.properties(devTools).asMap()["shiftKey"]!!.value as Optional<RemoteObject>).getOrNull()?.value?.getOrNull() as Boolean?
    if (true == ctrlKey && true == shiftKey && setOf("KeyR", "KeyS", "KeyW", "KeyC").contains(key)) {
        onFunctionalKeyPress(id, key!!)
    } else {
        onMonitoredKeyPress(id, eventArg)
    }
}

fun highlightElem(nodeId: RemoteObjectId, color: RGBA) {
    devTools.send(
        Overlay.highlightNode(
            HighlightConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(color),
                Optional.empty(),
                Optional.of(color),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            ),
            Optional.empty(),
            Optional.empty(),
            Optional.of(nodeId),
            Optional.empty()
        )
    )
}

fun highlightElem(nodeId: NodeId, color: RGBA) {
    devTools.send(
        Overlay.highlightNode(
            HighlightConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(color),
                Optional.empty(),
                Optional.of(color),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            ),
            Optional.of(nodeId),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )
    )
}

fun onFunctionalKeyPress(id: Long, key: String) {
    when (key) {
        "KeyR" -> {
            if (isRecording) return

            htmlSnapshots += driver.documentHtml()
            isRecording = true

            events += RawRecordedAction(
                id,
                htmlSnapshots.size - 1,
                RawEvtType.MAJOR_DOM_CHANGE,
                "N/A",
                currentUrl = driver.currentUrl
            )

            highlightElem(devTools.send(DOM.getDocument(Optional.of(0), Optional.empty())).nodeId, RGBA(255, 0, 0, Optional.of(0.5)))
            println("Recording")
        }
        "KeyS" -> {
            isRecording = false
            highlightElem(devTools.send(DOM.getDocument(Optional.of(0), Optional.empty())).nodeId, RGBA(255, 255, 0, Optional.of(0.5)))
            println("Stopped recording")
            persistRecording()
        }
        "KeyW" -> {
            val elementUnderCursor = elementUnderCursor
            if (isRecording && null != elementUnderCursor) {
                println("Highlighting possible assertion")
                highlightElem(elementUnderCursor, RGBA(0, 0, 255, Optional.of(0.5)))
            }
        }
        "KeyC" -> {
            val elementUnderCursor = elementUnderCursor
            if (isRecording && null != elementUnderCursor) {
                println("Confirming assertion")
                val nodeId = pinNodeWithId(elementUnderCursor)
                highlightElem(elementUnderCursor, RGBA(0, 255, 0, Optional.of(0.5)))
                events += RawRecordedAction(
                    id,
                    htmlSnapshots.size - 1,
                    RawEvtType.ASSERT_ELEM,
                    nodeId
                )
            }
        }
    }
}

fun onMouseMove(eventArg: RemoteObjectId) {
    val props = eventArg.properties(devTools).asMap()
    elementUnderCursor = props["target"]?.value?.getOrNull()?.objectId?.getOrNull()
}

fun onMonitoredKeyPress(id: Long, eventArg: RemoteObjectId) {
    if (!isRecording) return
    val props = eventArg.properties(devTools).asMap()
    val targetId = (props["target"]!!.value as Optional<RemoteObject>).get().objectId.get()
    highlightElem(targetId, RGBA(0, 125, 0, Optional.of(0.2)))
    val nodeId = pinNodeWithId(targetId)
    // An alternative without DOM changes would be detailed XPath tracing (with CSS, attributes, etc.)
    events += RawRecordedAction(
        id,
        htmlSnapshots.size - 1,
        RawEvtType.KEYDOWN,
        nodeId,
        props["key"]!!.value.get().value.getOrNull() as String?,
        props["keyCode"]!!.value.get().value.getOrNull() as Long?
    )
}

fun persistRecording() {
    val usedHtmlVersions = events.map { it.htmlSnapshotId }.toSortedSet()
    fun collapse(value: MutableList<RawRecordedAction>): List<RawRecordedAction> {
        val resultString = mutableListOf<Char>()
        // TODO: Far from complete, also what happens when cursor pos is changed with mouse
        for (action in value) {
            if ("Backspace" == action.key && resultString.size > 0) {
                resultString.removeLast()
            } else if (action.key?.length == 1) {
                resultString += action.key[0]
            }
        }

        if (resultString.isEmpty()) {
            return listOf()
        }

        return listOf(RawRecordedAction(
            value[0].posId,
            value[0].htmlSnapshotId,
            value[0].type,
            value[0].nodeId,
            String(resultString.toCharArray())
        ));
    }
    // collapse text inputs:
    val collapsedEvents = events.fold(Pair(mutableListOf<RecordingEvent>(), mutableListOf<RawRecordedAction>())) { acc, rawRecordedAction ->
        acc.apply {
            if (RawEvtType.KEYDOWN != rawRecordedAction.type) {
                if (acc.second.isNotEmpty()) {
                    acc.first += collapse(acc.second).map { it.toRecording(usedHtmlVersions) }
                    acc.second.clear()
                }
                acc.first += rawRecordedAction.toRecording(usedHtmlVersions)
            } else {
                if (acc.second.isEmpty()) {
                    acc.second += rawRecordedAction
                } else {
                    if (acc.second[0].nodeId == rawRecordedAction.nodeId) {
                        acc.second += rawRecordedAction
                    } else {
                        acc.first += collapse(acc.second).map { it.toRecording(usedHtmlVersions) }
                        acc.second.clear()
                        acc.second += rawRecordedAction
                    }
                }
            }
        }
    }

    val allEvents = collapsedEvents.first + collapse(collapsedEvents.second).map { it.toRecording(usedHtmlVersions) }
    // It may have some extra html versions due to removed events
    targetFile.writeText(
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Recording(usedHtmlVersions.map { htmlSnapshots[it] }, allEvents))
    )
}

devTools.addListener(Runtime.consoleAPICalled()) { id, event ->
    if (null != event.args && event.args.size == 2) {
        event.args[0].value.ifPresent {
            when (it) {
                "mousedown" -> onMouseClick(id, event.args[1].objectId.get())
                "keydown" -> onKeyDown(id, event.args[1].objectId.get())
                "mousemove" -> onMouseMove(event.args[1].objectId.get())
            }
        }
    }
}

// Handle HTML updates done during page life:
devTools.addListener(DOM.documentUpdated()) { id, event ->
    if (isRecording) {
        htmlSnapshots += driver.documentHtml()
        events += RawRecordedAction(
            id,
            htmlSnapshots.size - 1,
            RawEvtType.MAJOR_DOM_CHANGE,
            "N/A",
            currentUrl = driver.currentUrl
        )
    }
}
devTools.addListener(DOM.childNodeInserted()) { event ->
    if (isRecording) htmlSnapshots += driver.documentHtml()
}
devTools.addListener(DOM.childNodeRemoved()) { event ->
    if (isRecording) htmlSnapshots += driver.documentHtml()
}


// ================ 5. Open the page and wait for user to record the testcase ==================
println("Use Ctrl+Shift+R to start recording") // TODO MacOS specific
println("Use Ctrl+Shift+S to stop recording") // TODO MacOS specific
println("Use Ctrl+Shift+A to add object for assertion") // TODO MacOS specific
println("Use Ctrl+Shift+C to confirm object assertion") // TODO MacOS specific

driver.get("about:blank")
while (true) {
    // Loop forever...
    Thread.sleep(1000)
}