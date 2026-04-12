package org.example

import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.AiServices
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    val apiKey = System.getenv("OPENROUTER_API_KEY") ?: "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"
    
    val model = OpenAiChatModel.builder()
        .apiKey(apiKey)
        .baseUrl("https://openrouter.ai/api/v1")
        .modelName("deepseek/deepseek-r1")
        .maxTokens(1000)
        .build()

    val agent = AiServices.builder(BioResearchAgent::class.java)
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .tools(PubMedSearcher())
        .build()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }

        routing {
            staticResources("/", "static", index = "index.html")

            post("/analyze") {
                val request = call.receive(AnalyzeRequest::class)
                println("Received Scientist Query: ${request.query}")
                
                try {
                    val result = agent.analyze(request.query)
                    call.respond(AnalyzeResponse(result))
                } catch (e: Exception) {
                    call.respond(AnalyzeResponse("Error: ${e.message}"))
                }
            }
        }
    }.start(wait = true)
}

data class AnalyzeRequest(val query: String)
data class AnalyzeResponse(val result: String)