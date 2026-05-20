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
import dev.langchain4j.rag.content.retriever.ContentRetriever

class ScienceAssistantServer(
    private val agent: BioResearchAgent,
    private val contentRetriever: ContentRetriever,
    private val embeddingStore: InMemoryEmbeddingStore<TextSegment>,
    private val searcher: PubMedSearcher
) {
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
                    val request = call.receive<AnalyzeRequest>()
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
}

data class AnalyzeRequest(val query: String)
