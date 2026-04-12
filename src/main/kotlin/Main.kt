package org.example

import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.rag.DefaultRetrievalAugmentor
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
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
        //.modelName("deepseek/deepseek-r1")
        .modelName("google/gemini-2.0-flash-001")
        .maxTokens(1000)
        .build()

    // 1. Initialize Vector Store and Embedding Model
    val embeddingStore = InMemoryEmbeddingStore<TextSegment>()
    val embeddingModel = OpenAiEmbeddingModel.builder()
        .apiKey(apiKey)
        .baseUrl("https://openrouter.ai/api/v1")
        .modelName("text-embedding-3-small")
        .build()
    
    // 2. Set up Ingestor
    val ingestor = EmbeddingStoreIngestor.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .build()

    // 3. Set up Content Retriever for RAG
    val contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(3)
        .build()

    val retrievalAugmentor = DefaultRetrievalAugmentor.builder()
        .contentRetriever(contentRetriever)
        .build()

    val agent = AiServices.builder(BioResearchAgent::class.java)
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .tools(PubMedSearcher(ingestor))
        .retrievalAugmentor(retrievalAugmentor)
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