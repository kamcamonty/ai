package org.example

import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
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

fun main() {
    val apiKey = System.getenv("OPENROUTER_API_KEY") ?: "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"
    
    val model = OpenAiChatModel.builder()
        .apiKey(apiKey)
        .baseUrl("https://openrouter.ai/api/v1")
        //.modelName("deepseek/deepseek-r1")
        //.modelName("google/gemini-2.0-flash-001")
        //.modelName("openrouter/auto:free")
        //.modelName("google/gemma-2-9b-it:free")
        //.modelName("google/gemma-4-26b-a4b-it:free")
        //.modelName("meta-llama/llama-3.3-70b-instruct:free")
        .modelName("openrouter/elephant-alpha")
        //.modelName("mistralai/mistral-7b-instruct:free")
        //.modelName("google/gemma-2-9b-it:free")
        .maxTokens(1000)
        .timeout(java.time.Duration.ofSeconds(60))
        .strictTools(true)
        .build()

    // 1. Initialize Vector Store and Embedding Model
    val embeddingStore = InMemoryEmbeddingStore<TextSegment>()
    val embeddingModel = AllMiniLmL6V2QuantizedEmbeddingModel()
//        .builder()
//        .apiKey(apiKey)
//        .baseUrl("https://openrouter.ai/api/v1")
//        .modelName("text-embedding-3-small")
//        .build()
    
    // 2. Set up Ingestor
    val ingestor = EmbeddingStoreIngestor.builder()
        .documentSplitter(
            DocumentSplitters.recursive(
            1000, // Chunk size: ~250 words
            100   // Overlap: 25 words to keep context between chunks
        ))
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .build()

    // 3. Set up Content Retriever for RAG
    val contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(2)
        .minScore(0.4)
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
