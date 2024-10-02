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
import java.io.File
import java.time.Duration


// Variables
val inputFile = File("enriched-browser-recording.json")
val outputFile = inputFile.absoluteFile.parentFile.resolve("page-objects-for-${inputFile.name}")

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
// In:
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
// Out:
data class MethodArgument(val name: String, val type: String)
data class MethodRef(val methodName: String, val returnValue: String? = null, val arguments: List<MethodArgument>? = null) {

    fun toJavaCode(): String {
        if (arguments.isNullOrEmpty()) {
            return "${returnValue ?: ""} $methodName()"
        }

        return """
            ${returnValue ?: ""} $methodName(${arguments.map { it.type + ' ' + it.name }.joinToString(", ")})
        """.trimIndent()
    }

    fun toFluentJavaCode(retClass: String): String {
        if (arguments.isNullOrEmpty()) {
            return "$retClass $methodName()"
        }

        return """
            $retClass $methodName(${arguments.map { it.type + ' ' + it.name }.joinToString(", ")})
        """.trimIndent()
    }
}
data class PageObject(val className: String, val pageName: String, val javaCode: String, val methods: List<MethodRef> = emptyList())

// 1. ============== Load data ==============
val recordedSequence: EnrichedRecording = ObjectMapper().findAndRegisterModules().readValue(inputFile, EnrichedRecording::class.java)
println("Enriched recording loaded")

// 2. ================ Setup LLM ========================
fun openAiModel(): ChatLanguageModel {
    val OPENAI_API_KEY: String = Utils.getOrDefault(System.getenv("OPENAI_API_KEY"), "demo")
    return OpenAiChatModel.builder().modelName("gpt-4o-mini").temperature(1.0).apiKey(OPENAI_API_KEY).build()
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

// 3. ================ Process the data ========================
fun hasAssert(events: List<EnrichedRecordingEvent>): Boolean {
    return events.any { it.type == EvtType.ASSERT_ELEM }
}
fun hasClick(events: List<EnrichedRecordingEvent>): Boolean {
    return events.any { it.type == EvtType.MOUSE_CLICK }
}
fun hasInput(events: List<EnrichedRecordingEvent>): Boolean {
    return events.any { it.type == EvtType.KEYPRESS }
}
fun isUrlEventsOnly(events: List<EnrichedRecordingEvent>): Boolean {
    return !events.any { it.type != EvtType.MAJOR_DOM_CHANGE }
}

fun generatePageObjectFunctionsSignatures(className: String, pageEventsByElem: Map<String, List<EnrichedRecordingEvent>>): List<MethodRef> {
    println("Phase 1: Function signatures of: $className")
    val messages = mutableListOf<ChatMessage>(
        UserMessage("You are Senior Java developer and Automation QA with deep Selenium knowledge"),
        UserMessage("Your task is to generate page object method signatures for page object class '${className}'"),
    )
    for (element in pageEventsByElem) {
        val elemName = element.value[0].humanReadableNodeName
        val xpaths = element.value.map { it.xpath }

        if (isUrlEventsOnly(element.value)) {
            if (null != element.value[0].currentUrl) {
                messages += UserMessage("Page URL is: '${element.value[0].currentUrl}' that you want to assert using hasPageUrl.. like method")
            }

            continue
        }

        messages += UserMessage(
            """
                    There is an element '${elemName}', that can be located using (
                    ${if (hasAssert(element.value)) "element is used to check if it is on page, use hasElement method name format, " else ""}
                    ${if (hasClick(element.value)) "element is used to click on it, " else ""}
                    ${if (hasInput(element.value)) "element is used to input text, " else ""}):
                    - ${xpaths.toSortedSet().joinToString("\n -")}
                """.trimIndent()
        )
    }
    messages += UserMessage("""
        Never pass WebDriver as method argument!
        Respond with JSON array: [{"methodName": string, "arguments": [{"type": string, "name": string}]}] (no quotes, references, etc.)
    """.trimIndent())

    return withRetry(3) {
        val response = model.generate(messages)

        // TODO: Method purpose and associated locator, what element is clicked on
        return@withRetry ObjectMapper().findAndRegisterModules().readValue(response.content().text())
    }
}

fun generateClassNames(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val byPage = recordedSequence.events.groupBy { it.pageName }
    data class Name(val className: String)
    for (page in byPage) {
        val pageName = page.key

        val messages = mutableListOf<ChatMessage>(
            UserMessage("You are Senior Java developer and Automation QA with deep Selenium knowledge"),
            UserMessage("Generate class name of page object for page name '${pageName}'"),
            UserMessage("""Respond only with JSON {"className": "string"}, nothing else (no quotes, markdown, explanation)""")
        )

        withRetry(3) {
            val response = model.generate(messages)
            val name: Name = ObjectMapper().findAndRegisterModules().readValue(response.content().text())
            result[pageName] = name.className
        }
    }

    return result
}


fun generatePageObjects(): List<PageObject> {
    val result = mutableListOf<PageObject>()
    val classNames = generateClassNames()
    val byClass = recordedSequence.events.map { Pair(classNames[it.pageName], it) }.groupBy ({ it.first!! }, { it.second })

    var currentUrl = ""
    for (page in byClass) {
        val className = page.key
        val pageEvents = page.value.groupBy { it.nodeId }
        pageEvents.flatMap { it.value }.mapNotNull { it.currentUrl }.lastOrNull()?.apply {
            currentUrl = this
        }

        val pageMethods = generatePageObjectFunctionsSignatures(className, pageEvents)

        println("Phase 2: Page object for: $className")

        val messages = mutableListOf<ChatMessage>(
            UserMessage("You are Senior Java developer and Automation QA with deep Selenium knowledge"),
            UserMessage("Your task is to generate page object with class name '${className}'. Use Selenium and Selenium waits and @FindBy."),
            UserMessage("Methods like 'hasElem' are used only to assert certain element is visible, we should only wait for visibility there, they have void type"),
            UserMessage("Use package com.example.tests, FindBy locators should be above constructor. Store WebDriver as class field."),
            UserMessage("Generate JavaDoc as well. Always include JavaDoc for locator or/and FindBy!"),
            UserMessage("Never pass locator as the method argument!"),
            UserMessage("All locators MUST USE XPath! Each locator must be a class field and be backed with FindBy!"),
            UserMessage("In class-level JavaDoc include page URL: $currentUrl"),
            UserMessage("Wait for any element to be visible, for input elements wait for them to be visible and active"),
        )
        for (element in pageEvents) {
            val elemName = element.value[0].humanReadableNodeName
            val xpaths = element.value.map { it.xpath }
            if (isUrlEventsOnly(element.value)) {
                if (null != element.value[0].currentUrl) {
                    messages += UserMessage("Page URL is: '${element.value[0].currentUrl}' that you want to assert  hasPageUrl.. like method")
                }

                continue
            }

            messages += UserMessage(
                """
                    There is an element '${elemName}', that can be located using:
                    - ${xpaths.toSortedSet().joinToString("\n -")}
                """.trimIndent()
            )
        }
        messages += UserMessage("""
            WebDriverWait uses Duration class for timeout, use 5 seconds as timeout.
            WebDriverWait MUST be a PageObject class field.
            Page MUST have the following Java methods with signatures, methods MUST be Fluent - they should return 'this', DO NOT use try-catch: 
            ${pageMethods.map { it.toFluentJavaCode(className)}.joinToString("\n - ") { it }}
        """.trimIndent())
        messages += UserMessage("""
            Respond with JSON: {"className": string, "javaCode": string} (no quotes, references, markdown, etc.)
            """.trimIndent())

        data class GenClass(val className: String, val javaCode: String)
        val genResponse: GenClass = withRetry(3) {
            val response = model.generate(messages)
             return@withRetry ObjectMapper().findAndRegisterModules().readValue(response.content().text())
        }
        result += PageObject(
            genResponse.className,
            className,
            genResponse.javaCode,
            pageMethods.map { it.copy(returnValue = genResponse.className) }
        )
    }

    return result
}

val pages = generatePageObjects()

outputFile.writeText(
    ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(pages)
)