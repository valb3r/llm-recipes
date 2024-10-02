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
val inputRecording = File("enriched-browser-recording.json")
val inputPageObjects = File("page-objects-for-enriched-browser-recording.json")
val outputDir = inputRecording.absoluteFile.parentFile.resolve("full-test-case-${inputRecording.nameWithoutExtension}")

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
// In:
data class MethodArgument(val name: String, val type: String)
data class MethodRef(val methodName: String, val returnValue: String, val arguments: List<MethodArgument>? = null) {

    fun toJavaCode(): String {
        if (arguments.isNullOrEmpty()) {
            return "$returnValue $methodName()"
        }

        return """
            $returnValue $methodName(${arguments.map { it.type + ' ' + it.name }.joinToString(", ")})
        """.trimIndent()
    }
}
data class PageObject(val className: String, val pageName: String, val javaCode: String, val methods: List<MethodRef> = emptyList())

// 1. ============== Load data ==============
val recordedSequence: EnrichedRecording = ObjectMapper().findAndRegisterModules().readValue(inputRecording)
println("Enriched recording loaded")
val pageObjects: List<PageObject> = ObjectMapper().findAndRegisterModules().readValue(inputPageObjects)
println("Page objects loaded")

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

data class TestCase(val className: String, val javaCode: String)
fun generateTestcases(): List<TestCase> {
    val messages = mutableListOf<ChatMessage>(
        UserMessage("You are Senior Java developer and Automation QA with deep Selenium knowledge"),
        UserMessage("Your task is to generate Junit5 + Selenium Java test case from textual description and PageObject classes with description"),
        UserMessage("Generate JavaDoc as well")
    )
    for (pageObject in pageObjects) {
        messages += UserMessage("""
            There is following PageObject class - '${pageObject.className}' from package 'com.example.tests.pages'
            The class has the following methods:
            ${pageObject.methods.joinToString("\n - ") { it.toJavaCode() }}
        """.trimIndent())
    }
    messages += UserMessage("The test goes the following way:")
    for (event in recordedSequence.events) {
        messages += when (event.type) {
            EvtType.ASSERT_ELEM -> UserMessage("The window has '${event.humanReadableNodeName}' visible (both hasElem... and click... methods can be used)")
            EvtType.MOUSE_CLICK -> UserMessage("The user clicks on '${event.humanReadableNodeName}' with mouse")
            EvtType.KEYPRESS -> UserMessage("The user types text '${event.text ?: ""}' into '${event.humanReadableNodeName}'")
            EvtType.MAJOR_DOM_CHANGE -> UserMessage("The page URL is '${event.currentUrl ?: ""}'")
        }
    }
    messages += UserMessage("Use package: 'com.example.tests'")
    messages += UserMessage("Name test class and test according to most suitable business process fitting the used methods and pages")
    messages += UserMessage("""
        Hint: Do not use: String variable = "TEXT"; page.input(variable);
        Use instead: page.input("TEXT");
    """.trimIndent())
    messages += UserMessage("All PageObject methods are fluent and have return type same as class, they assert things inside, DO NOT ADD ASSERTIONS")
    messages += UserMessage("Use fluent flow for PageObject method calls. Name tests according to business flow not technical flow")
    messages += UserMessage("DO NOT IMAGINE METHODS FROM PageObject THAT DO NOT EXIST! Use existing methods only!")
    messages += UserMessage("Ensure all test steps are in Java code")
    messages += UserMessage("Use @BeforeAll for initialization and store page objects in test class fields, construct WebDriver and pass it to page object constructor, tear down driver in AfterAll")
    messages += UserMessage("""Generate raw JSON having {"className": string, "javaCode": string} (without quotes, references, markdown)""")

    return withRetry(3) {
        val generated = model.generate(messages)

        // TODO - Currently 1 test case
        return@withRetry listOf(ObjectMapper().findAndRegisterModules().readValue(generated.content().text()))
    }
}

val generatedTestcases = generateTestcases()

if (outputDir.exists()) {
    outputDir.deleteRecursively()
}
outputDir.mkdirs()
val pagesPackage = outputDir.resolve("pages")
pagesPackage.mkdirs()

for (pageObject in pageObjects) {
    val classFile = pagesPackage.resolve("${pageObject.className}.java")
    classFile.writeText(pageObject.javaCode)
}

for (test in generatedTestcases) {
    val classFile = outputDir.resolve("${test.className}.java")
    classFile.writeText(test.javaCode)
}