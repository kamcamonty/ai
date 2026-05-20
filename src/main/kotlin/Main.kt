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
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

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

    ScienceAssistantServer(agent, contentRetriever, embeddingStore, searcher).start()
}
