@file:DependsOn("dev.langchain4j:langchain4j:0.34.0")
@file:DependsOn("dev.langchain4j:langchain4j-ollama:0.34.0")
@file:DependsOn("dev.langchain4j:langchain4j-open-ai:0.34.0")
@file:DependsOn("org.jsoup:jsoup:1.18.1")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.internal.Utils
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.File
import java.time.Duration
import java.util.*


// Variables
val inputFile = File("browser-recording.json")
val outputFile = inputFile.absoluteFile.parentFile.resolve("enriched-${inputFile.name}")

// Shared stuff - should work with @file:Import, but does not
fun <T> withRetry(times: Int, block: () -> T): T {
    var lastThrowable: Throwable? = null
    for (i in 1..times) {
        try {
            return block()
        } catch (e: Throwable) {
            lastThrowable = e
        }
    }

    if (null != lastThrowable) throw lastThrowable else throw IllegalStateException("Failed")
}

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

// Out:
data class EnrichedRecordingEvent(
    val htmlSnapshotId: Int,
    val pageName: String,
    val type: EvtType,
    val nodeId: String,
    val xpath: String,
    val humanReadableNodeName: String,
    val text: String? = null,
    val charCode: Long? = null,
    val currentUrl: String? = null
)
data class EnrichedRecording(val htmlSnapshots: List<String>, val events: List<EnrichedRecordingEvent>)

// 1. ============== Load data ==============
val recordedSequence: Recording = ObjectMapper().findAndRegisterModules().readValue(inputFile, Recording::class.java)
println("Recording loaded")

// 2. ================ Setup LLM ========================
fun openAiModel(): ChatLanguageModel {
    val OPENAI_API_KEY: String = Utils.getOrDefault(System.getenv("OPENAI_API_KEY"), "demo")
    return OpenAiChatModel.builder().modelName("gpt-4o-mini").apiKey(OPENAI_API_KEY).build()
}

fun ollamaAiModel(): ChatLanguageModel {
    val modelName = "llama3.1:8b-instruct-q4_K_S"

    return OllamaChatModel.builder().baseUrl("http://localhost:11434")
        .timeout(Duration.ofMinutes(20))
        .numCtx(16384)
        .modelName(modelName)
        .build()
}
val model = openAiModel()

class SafelistThatAllowsAllTags(safelist: Safelist) : Safelist(safelist) {

    override fun isSafeTag(tag: String): Boolean {
        return true
    }
}

fun extractPageName(html: String, prevName: String?): String {
    val cleanedUp = Jsoup.clean(html, SafelistThatAllowsAllTags(Safelist.basic()))

    val messages = mutableListOf<ChatMessage>(
        UserMessage("You are helpful data extraction agent, with deep HTML/CSS knowledge and expert in Angular and React frameworks"),
        UserMessage("Generate simple and concise page name for the HTML below, provide raw Page name (without quotes, comments, explanation):"),
        UserMessage("If there is a dialog on page, append the dialog name to meaningful page name. ${if (null != prevName) "Check this page name '${prevName}' maybe it fits? If it does, use it!" else ""}"),
        UserMessage("Do not mix business and technical concepts!"),
        UserMessage(cleanedUp)
    )
    val response = model.generate(messages)
    return response.content().text()
}

fun uniquePageName(allPageNames: SortedSet<String>): Set<String> {
    val messages = mutableListOf<ChatMessage>(
        UserMessage("You are helpful data extraction agent and business analyst"),
        UserMessage("""
            Your task is to remove duplicate or similar page names. Page next to each other may be related, order matters!
            Page names are: 
            ${allPageNames.joinToString(", ") { "'$it'" }}
            Do not introduce new names, do not mix technical concepts and business concepts
            Respond with raw JSON array - ["pageName"], without reasoning or references or markdown.
        """.trimIndent()),
    )
    val response = model.generate(messages)

    return withRetry(3) {
        ObjectMapper().readValue(response.content().text())
    }
}

fun refinePageName(pageName: String, targetNames: Set<String>): String {
    if (targetNames.contains(pageName)) {
        return pageName
    }

    var bestMatch = ""
    var bestScore = 0
    for (targetName in targetNames) {
        val rating = withRetry(3) {
            val messages = mutableListOf<ChatMessage>(
                UserMessage("You are helpful data extraction agent, and business analyst"),
                UserMessage("""
            Your task is rate from 0 (not related) to 100 (related) if the following page name '$pageName' is closely related to '$targetName'.
            Do not mix technical concepts and business concepts,
            Respond with raw rating number only!, without reasoning or references          
        """.trimIndent()),
            )
            val response = model.generate(messages)

            response.content().text().toInt()
        }
        if (bestScore < rating) {
            bestScore = rating
            bestMatch = targetName
        }
    }

    return bestMatch
}

data class XpathData(val xpath: String, val humanReadableElementName: String)
fun extractXpathData(html: String, idAttribute: String, idValue: String, evtType: EvtType): XpathData {
    val cleanedUp = Jsoup.clean(html, SafelistThatAllowsAllTags(Safelist.relaxed()).addAttributes(":all", idAttribute, "for", "id"))
    val parsedDocument = Jsoup.parse(html)

    fun findIdAttributeInChildren(element: Element, currentXpath: String, maxDepth: Int, currentDepth: Int = 0): Boolean {
        if (currentDepth + 1 > maxDepth) {
            return false
        }

        if (idValue != element.attr(idAttribute)) {
            return true
        }

        for (elem in element.selectXpath(currentXpath)) {
            if (findIdAttributeInChildren(elem, "${currentXpath}/${elem.tagName()}", maxDepth, currentDepth + 1)) return true
        }

        return false
    }

    val previousMessages = mutableListOf<ChatMessage>()
    for (i in 1..10) {
        val messages = mutableListOf<ChatMessage>(
            UserMessage("You are helpful data extraction agent, XPath professional, with deep HTML/CSS knowledge and expert in Angular and React frameworks"),
            UserMessage("Locate an element having attribute '$idAttribute' with value '$idValue' in the provided HTML, memoize its location"),
        )
        messages += previousMessages
        messages += UserMessage("""Provide only the JSON Array with 10 XPath locators that are as short as possible (in order from shortest to longest) variants for 
            element having '$idAttribute' with value '$idValue' that are short and robust to changes (DO NOT USE '$idAttribute', DO NOT USE 'id' in Xpath, DO NOT USE NUMERIC INDEXES!): 
            [{"xpath": string, "humanReadableElementName": string}] 
            ${if (EvtType.KEYPRESS === evtType) "The XPath should point to the element that accepts input from keyboard (i.e. input)" else ""}
            ${if (EvtType.MOUSE_CLICK === evtType) "The XPath should point to the clickable element" else ""}
            Prefer to use element text and try to normalize spaces in XPath, limit XPath depth to ${if (i < 3) '3' else '5'}
            If there is <label> with 'for' attribute, associated with for input/element - try to utilize them 
            (without quotes, comments, explanation, markdown) to locate it in this HTML:"""
        .trimIndent())
        messages += UserMessage(cleanedUp) as ChatMessage

        val response = model.generate(messages)

        try {
            val xpaths = ObjectMapper().findAndRegisterModules().readValue<List<XpathData>>(response.content().text())
            for (xpath in xpaths) {
                if (xpath.xpath.contains(idValue) || xpath.xpath.contains(idAttribute) || xpath.xpath.contains("@id")) {
                    println("Basic quality gate failed")
                    continue
                }

                println("Trying: ${xpath.xpath}")
                val selected = parsedDocument.selectXpath(xpath.xpath)
                if (selected.isEmpty() || idValue != selected.attr(idAttribute)) {
                    // Afterburner:
                    if (selected.size == 1
                        && (xpath.xpath.contains("text()") || xpath.xpath.contains("normalize-space()")
                        && (!xpath.xpath.contains("=''"))
                        && findIdAttributeInChildren(parsedDocument, xpath.xpath, 2))) {
                        println("Afterburner matches")
                        return xpath
                    }
                    previousMessages += UserMessage("Hint: XPath ${xpath.xpath} is WRONG! ${if (i > 3) ", Try completely different Xpath structure" else ""}")
                    println("Failed Xpath: ${xpath.xpath}, matches ${selected.size}")
                } else {
                    return xpath
                }
            }
        } catch (ex: Throwable) {
            println("Failed")
            continue
        }
    }

    // TODO - XPath simplifier - after XPath found, simplify it to clickable element or input or text

    File("debug-locator-cleaned-up.html").writeText(cleanedUp)
    File("debug-locator-raw.html").writeText(html)
    throw IllegalStateException("Failed to find XPath for $idValue")
}

// 3. ================ Process the data ========================
var prevPageName: String? = null
val cachedPageNames = mutableMapOf<Int, String>()
val xpathCaches = mutableMapOf<Pair<Int, String>, XpathData>()
var enrichedRecording = EnrichedRecording(
    recordedSequence.htmlSnapshots,
    recordedSequence.events.map { evt ->
        val html = recordedSequence.htmlSnapshots[evt.htmlSnapshotId]

        val extractedXpath = xpathCaches.computeIfAbsent(Pair(evt.htmlSnapshotId, evt.nodeId)) {
            return@computeIfAbsent if (evt.type == EvtType.MAJOR_DOM_CHANGE) {
                prevPageName = null
                XpathData("N/A", "")
            } else extractXpathData(html, htmlNodeIdMarker, evt.nodeId, evt.type)
        }

        val pageName = cachedPageNames.computeIfAbsent(evt.htmlSnapshotId) { extractPageName(html, prevPageName) }

        prevPageName = pageName
        EnrichedRecordingEvent(
            evt.htmlSnapshotId,
            pageName.lowercase(),
            evt.type,
            evt.nodeId,
            extractedXpath.xpath,
            extractedXpath.humanReadableElementName,
            evt.text,
            evt.charCode,
            evt.currentUrl
        )
    }
)

val pageNames = enrichedRecording.events.map { it.pageName }.toSortedSet()
val uniqueNames = uniquePageName(pageNames)
val pageNameToUniqueName = pageNames.associateBy ({it}, {refinePageName(it, uniqueNames)})

enrichedRecording = enrichedRecording.copy(events = enrichedRecording.events.map { it.copy(pageName = pageNameToUniqueName[it.pageName]!!) })

// 4. ================= Store result ==================
outputFile.writeText(
    ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(EnrichedRecording(recordedSequence.htmlSnapshots, enrichedRecording.events))
)