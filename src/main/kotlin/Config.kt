package org.example

import io.ktor.server.config.*
import java.time.Duration
import java.io.InputStream
import org.yaml.snakeyaml.Yaml

object Config {
    private val config: Map<String, Any>? = try {
        val inputStream: InputStream? = javaClass.classLoader.getResourceAsStream("application.yml")
        if (inputStream != null) {
            Yaml().load<Map<String, Any>>(inputStream)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun getNestedValue(map: Map<String, Any>?, path: String): String? {
        var current: Any? = map
        val keys = path.split(".")
        for (key in keys) {
            if (current !is Map<*, *>) return null
            current = current[key]
        }
        return current?.toString()
    }

    private fun getString(path: String, envName: String): String {
        return System.getenv(envName) ?: getNestedValue(config, path) ?: ""
    }

    private fun getInt(path: String, envName: String, default: Int): Int {
        return System.getenv(envName)?.toIntOrNull() 
            ?: getNestedValue(config, path)?.toIntOrNull() 
            ?: default
    }

    private fun getDouble(path: String, envName: String, default: Double): Double {
        return System.getenv(envName)?.toDoubleOrNull() 
            ?: getNestedValue(config, path)?.toDoubleOrNull() 
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
