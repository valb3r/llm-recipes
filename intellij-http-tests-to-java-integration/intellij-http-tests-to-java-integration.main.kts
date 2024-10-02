/**
 * This script how to translate IntelliJ http client test file into Java Integration test using JGiven framework.
 * It will use the test-template example you provide in 'test-template' folder.
 * Available models:
 * 1. openAiModel() - ChatGPT API
 * 2. ollamaAiModel() - Local LLM via Ollama
 * 4. groqAiModel() - Groq API
 */
@file:DependsOn("dev.langchain4j:langchain4j:0.34.0")
@file:DependsOn("dev.langchain4j:langchain4j-ollama:0.34.0")
@file:DependsOn("dev.langchain4j:langchain4j-jlama:0.34.0")
@file:DependsOn("dev.langchain4j:langchain4j-open-ai:0.34.0")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.internal.Utils
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import java.io.File
import java.time.Duration


// ================ 1. Select LLM ==================
fun openAiModel(): ChatLanguageModel {
    val OPENAI_API_KEY: String = Utils.getOrDefault(System.getenv("OPENAI_API_KEY"), "demo")
    return OpenAiChatModel.builder().modelName("gpt-4o").apiKey(OPENAI_API_KEY).build()
}

fun groqAiModel(): ChatLanguageModel {
    val GROQ_API_KEY: String = Utils.getOrDefault(System.getenv("GROQ_API_KEY"), "demo")
    return OpenAiChatModel.builder()
        .baseUrl("https://api.groq.com/openai/v1")
        .apiKey(GROQ_API_KEY)
        .modelName("llama-3.1-8b-instant")
        .temperature(0.0)
        .build()
}

fun ollamaAiModel(): ChatLanguageModel {
    val modelName = "llama3.1:8b-instruct-q4_K_S"

    return OllamaChatModel.builder().baseUrl("http://localhost:11434")
        .timeout(Duration.ofMinutes(20))
        .modelName(modelName)
        .temperature(0.0)
        .numCtx(8096)
        .build()

}

// ================ 2. OpenAI Model selected ==================
val model = openAiModel()

var aggregatedTemplate = ""
File("test-template").walk().filter { it.isFile }.forEach { template ->
    aggregatedTemplate += "${template.name}: \n\n"
    aggregatedTemplate += template.readText()
}

var aggregatedTestCase = ""
File("intellij-test-case").walk().filter { it.isFile }.forEach { template ->
    aggregatedTestCase += "${template.name}: \n\n"
    aggregatedTestCase += template.readText()
}

println("============ Generating ===============")
val response = model.generate(
    UserMessage("You are an expert Java developer with deep IntelliJ HTTP client test knowledge"),
    UserMessage("Your task is to convert IntelliJ HTTP client test to Java integration test with Spring Boot Test, Testcontainers, JGiven"),
    UserMessage("""
        Here is an example Java testcase template you should follow:
        $aggregatedTemplate
    """.trimIndent()),
    UserMessage("""
        Convert the following IntelliJ HTTP client test(s) to Java, generate all necessary Steps and Test files:
        $aggregatedTestCase
    """.trimIndent()),
    UserMessage("""Return result as raw JSON array of this format, follow it strictly!: [{"fileName": "string", "javaCode": "string"}]. Ensure JSON is properly quoted, do not use markdown!""")
)
data class ResponseDto(val fileName: String, val javaCode: String)

println("====== Generated: ${response.tokenUsage()} ======")

val parsed: List<ResponseDto> = ObjectMapper().findAndRegisterModules().readValue(response.content().text())

val output = File("output")
if (!output.exists()) {
    output.deleteRecursively()
}
output.mkdirs()

parsed.forEach { outFile ->
    output.resolve(outFile.fileName).writeText(outFile.javaCode)
}