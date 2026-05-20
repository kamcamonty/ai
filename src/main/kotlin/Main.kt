package org.example

import ch.qos.logback.classic.Level
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel
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
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger

fun main() {
    (LoggerFactory.getLogger("dev.langchain4j.store.embedding.EmbeddingStoreIngestor") as Logger).level = Level.INFO

    val model = OpenAiChatModel.builder()
        .apiKey(Config.openRouterApiKey)
        .baseUrl(Config.openRouterBaseUrl)
        .modelName(Config.modelName)
        .maxTokens(Config.maxTokens)
        .temperature(Config.temperature)
        .timeout(Config.timeout)
        .strictTools(true)
        .build()

    // 1. Initialize Vector Store and Embedding Model
    val embeddingStore = InMemoryEmbeddingStore<TextSegment>()
    val embeddingModel = AllMiniLmL6V2QuantizedEmbeddingModel()
    
    // 2. Set up Ingestor
    val ingestor = EmbeddingStoreIngestor.builder()
        .documentSplitter(
            DocumentSplitters.recursive(
            Config.chunkSize,
            Config.chunkOverlap
        ))
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .build()

    val searcher = PubMedSearcher(ingestor)

    // 3. Set up Content Retriever for RAG
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
        .tools(PubMedSearcher(ingestor))
        .retrievalAugmentor(retrievalAugmentor)
        .build()

    embeddedServer(Netty, port = Config.serverPort) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }

        routing {
            staticResources("/", "static", index = "index.html")
            post("/analyze") {

                val request = call.receive(AnalyzeRequest::class)
                searcher.reset()

                embeddingStore.removeAll()
                try {
                    // 1. Let the agent perform its full logic (Search -> Index -> Reflect)
                    val rawResult = agent.analyze(request.query)

                    // 2. NOW, manually trigger the retriever using the original query
                    // This finds the "Selected Chunks" that were just indexed
                    val retrievedContents = contentRetriever.retrieve(
                        dev.langchain4j.rag.query.Query.from(request.query)
                    )

                    // Convert the segments to a clean list of strings for the frontend
                    val chunks = retrievedContents
                        .mapNotNull { it.textSegment()?.text() }
                        .filter { it.isNotBlank() }

                    // 3. Parse headers (with safety fallbacks)
                    val reasoning = rawResult.substringAfter("REASONING:", "Planning...").substringBefore("REFLECTION:").trim()
                    val reflection = rawResult.substringAfter("REFLECTION:", "Analyzing...").substringBefore("FINAL ANSWER:").trim()
                    val finalAnswer = rawResult.substringAfter("FINAL ANSWER:", rawResult).trim()

                    // 4. Send everything to the browser
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

data class AnalyzeRequest(val query: String)
