package org.example

import io.ktor.server.config.*
import io.ktor.server.config.yaml.*
import java.time.Duration

object Config {
    private val config = YamlConfigLoader().load("application.yml")

    private fun getString(path: String, envName: String): String {
        return System.getenv(envName) ?: config!!.propertyOrNull(path)?.getString() ?: ""
    }

    private fun getInt(path: String, envName: String, default: Int): Int {
        return System.getenv(envName)?.toIntOrNull() 
            ?: config!!.propertyOrNull(path)?.getString()?.toIntOrNull() 
            ?: default
    }

    private fun getDouble(path: String, envName: String, default: Double): Double {
        return System.getenv(envName)?.toDoubleOrNull() 
            ?: config!!.propertyOrNull(path)?.getString()?.toDoubleOrNull() 
            ?: default
    }

    // LLM Settings
    val openRouterApiKey: String = getString("llm.apiKey", "OPENROUTER_API_KEY").ifEmpty { "demo" }
    val openRouterBaseUrl: String = getString("llm.baseUrl", "OPENROUTER_BASE_URL").ifEmpty { "https://openrouter.ai/api/v1" }
    val modelName: String = getString("llm.modelName", "MODEL_NAME").ifEmpty { "openai/gpt-oss-120b:free" }
    val maxTokens: Int = getInt("llm.maxTokens", "MAX_TOKENS", 1000)
    val temperature: Double = getDouble("llm.temperature", "TEMPERATURE", 0.0)
    val timeout: Duration = Duration.ofSeconds(getInt("llm.timeoutSeconds", "TIMEOUT_SECONDS", 60).toLong())

    // RAG Settings
    val chunkSize: Int = getInt("rag.chunkSize", "CHUNK_SIZE", 1000)
    val chunkOverlap: Int = getInt("rag.chunkOverlap", "CHUNK_OVERLAP", 100)
    val maxResults: Int = getInt("rag.maxResults", "MAX_RESULTS", 2)
    val minScore: Double = getDouble("rag.minScore", "MIN_SCORE", 0.4)

    // PubMed Search Settings
    val pubMedRetMax: Int = getInt("pubmed.retMax", "PUBMED_RETMAX", 3)
    val pubMedMaxTurns: Int = getInt("pubmed.maxTurns", "PUBMED_MAX_TURNS", 3)
    val pubMedSnippetSize: Int = getInt("pubmed.snippetSize", "PUBMED_SNIPPET_SIZE", 1500)
    
    // Server Settings
    val serverPort: Int = getInt("server.port", "PORT", 8080)
}
