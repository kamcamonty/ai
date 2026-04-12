package org.example

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.data.document.Document
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser

class PubMedSearcher(private val ingestor: EmbeddingStoreIngestor? = null) {
    private val client = OkHttpClient()

    @Tool("Downloads and indexes recent full-text research articles for a given topic from PMC into the internal database for later retrieval")
    fun downloadAndIndex(@P("the research topic") topic: String): String {
        println("[DEBUG_LOG] Downloading and indexing full-text for topic: $topic")

        // 1. ESearch: get PMC IDs
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pmc&term=${topic.replace(" ", "+")}&retmax=3&retmode=json"
        val searchRequest = Request.Builder().url(searchUrl).build()

        val ids = try {
            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error searching PMC: ${response.code}"
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                val idList = json.getAsJsonObject("esearchresult").getAsJsonArray("idlist")
                idList.map { it.asString }
            }
        } catch (e: Exception) {
            return "Error searching PMC: ${e.message}"
        }

        if (ids.isEmpty()) return "No full-text articles found for topic: $topic in PMC"

        // 2. EFetch: get full texts
        val idsString = ids.joinToString(",")
        val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=$idsString&retmode=text"
        val fetchRequest = Request.Builder().url(fetchUrl).build()

        val fullTextContent = try {
            client.newCall(fetchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error fetching full texts: ${response.code}"
                val body = response.body?.string() ?: ""
                if (body.length > 10000) body.take(10000) + "... [TRUNCATED FOR CONTEXT]" else body
            }
        } catch (e: Exception) {
            return "Error fetching full texts: ${e.message}"
        }

        if (fullTextContent.isBlank()) return "Fetched empty full-text content for topic: $topic"

        // 3. Ingest into Vector DB
        return if (ingestor != null) {
            val doc = Document.from(fullTextContent.take(50000))
            ingestor.ingest(doc)
            "Successfully indexed ${ids.size} full-text papers about $topic into my memory."
        } else {
            "Ingestor not configured. Could not index papers."
        }
    }

    @Tool("Search PMC for full-text of recent research papers given a query")
    fun searchPubMed(@P("the research query") query: String): String {
        println("[DEBUG_LOG] Searching PMC for full-text: $query")

        // 1. ESearch: get PMC IDs
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pmc&term=${query.replace(" ", "+")}&retmax=5&retmode=json"
        val searchRequest = Request.Builder().url(searchUrl).build()

        val ids = try {
            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error searching PMC: ${response.code}"
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                val idList = json.getAsJsonObject("esearchresult").getAsJsonArray("idlist")
                idList.map { it.asString }
            }
        } catch (e: Exception) {
            return "Error searching PMC: ${e.message}"
        }

        if (ids.isEmpty()) return "No full-text articles found in PMC for query: $query"

        // 2. EFetch: get full texts
        val idsString = ids.take(2).joinToString(",")
        val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=$idsString&retmode=text"
        val fetchRequest = Request.Builder().url(fetchUrl).build()

        val rawContent = try {
            client.newCall(fetchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error fetching full texts: ${response.code}"
                response.body?.string() ?: "Empty full-text response"
            }
        } catch (e: Exception) {
            return "Error fetching full texts: ${e.message}"
        }

        val truncatedContent = if (rawContent.length > 15000) {
            rawContent.take(15000) + "... [CONTENT TRUNCATED FOR CONTEXT LIMITS]"
        } else {
            rawContent
        }

        return "Full-text search results for: $query\n\n" + ids.mapIndexed { index, id ->
            "Source [Link: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC$id/]\nFull Text:\n$truncatedContent"
        }.joinToString("\n\n---\n\n")
    }
}
