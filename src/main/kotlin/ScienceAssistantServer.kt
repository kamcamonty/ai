package org.example

import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.rag.DefaultRetrievalAugmentor
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.http.content.staticResources
import dev.langchain4j.rag.query.Query

class ScienceAssistantServer {
    fun start() {
        embeddedServer(Netty, port = Config.serverPort) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }

            routing {
                staticResources("/", "static", index = "index.html")
                post("/analyze") {
                    val request = try {
                        call.receive<AnalyzeRequest>()
                    } catch (e: Exception) {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid request format"))
                        return@post
                    }

                    // 1. Initialize local instances for thread-safety
                    val model = OpenAiChatModel.builder()
                        .apiKey(Config.openRouterApiKey)
                        .baseUrl(Config.openRouterBaseUrl)
                        .modelName(Config.modelName)
                        .maxTokens(Config.maxTokens)
                        .temperature(Config.temperature)
                        .timeout(Config.timeout)
                        .strictTools(true)
                        .build()

                    val embeddingStore = InMemoryEmbeddingStore<TextSegment>()
                    val embeddingModel = AllMiniLmL6V2QuantizedEmbeddingModel()

                    val ingestor = EmbeddingStoreIngestor.builder()
                        .documentSplitter(
                            DocumentSplitters.recursive(
                                Config.chunkSize,
                                Config.chunkOverlap
                            )
                        )
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .build()

                    val searcher = PubMedSearcher(ingestor)

                    val contentRetriever = EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .maxResults(Config.maxResults)
                        .minScore(Config.minScore)
                        .build()

                    val retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                        .contentRetriever(contentRetriever)
                        .build()

                    val agent = AiServices.builder(BioResearchAgent::class.java)
                        .chatLanguageModel(model)
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(3))
                        .tools(searcher)
                        .retrievalAugmentor(retrievalAugmentor)
                        .build()

                    try {
                        val rawResult = agent.analyze(request.query)

                        // This finds the "Selected Chunks" that were just indexed
                        val retrievedContents = contentRetriever.retrieve(
                            Query.from(request.query)
                        )

                        // Convert the segments to a clean list of strings for the frontend
                        val chunks = retrievedContents
                            .mapNotNull { it.textSegment()?.text() }
                            .filter { it.isNotBlank() }

                        val reasoning = rawResult.substringAfter("REASONING:", "Planning...").substringBefore("REFLECTION:").trim()
                        val reflection = rawResult.substringAfter("REFLECTION:", "Analyzing...").substringBefore("FINAL ANSWER:").trim()
                        val finalAnswer = rawResult.substringAfter("FINAL ANSWER:", rawResult).trim()

                        call.respond(mapOf(
                            "reasoning" to reasoning,
                            "reflection" to reflection,
                            "answer" to finalAnswer,
                            "selectedChunks" to chunks
                        ))
                    } catch (e: Exception) {
                        println("ERROR: ${e.message}")
                        call.respond(mapOf("error" to (e.message ?: "Unknown backend error")))
                    }
                }
            }
        }.start(wait = true)
    }
}

data class AnalyzeRequest(val query: String)
