package org.example

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.data.document.Document
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import dev.langchain4j.data.document.splitter.DocumentSplitters

class PubMedSearcher(private val ingestor: EmbeddingStoreIngestor? = null) {
    private val client = OkHttpClient()
    private var turnCount = 0
    private val MAX_TURNS = 3 //

    @Tool("Downloads and indexes full-text research articles into the internal database")
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

        val fullXml = client.newCall(Request.Builder().url(fetchUrl).build()).execute().use {
            it.body?.string() ?: ""
        }

        // 3. CLEANING: Remove huge XML headers but KEEP the full body
        val bodyStart = fullXml.indexOf("<body>")
        val bodyEnd = fullXml.lastIndexOf("</body>")

        val cleanBody = if (bodyStart != -1 && bodyEnd != -1) {
            fullXml.substring(bodyStart, bodyEnd + 7)
                .replace(Regex("<[^>]*>"), " ") // Strip tags so embeddings focus on science
                .replace(Regex("\\s+"), " ")
        } else {
            fullXml.take(100000) // Fallback for very weird formats
        }

        // 4. INGESTION: Pass the whole body.
        // Your recursive splitter (1000/100) in main() will now slice this into
        // hundreds of small, searchable pieces.
        return if (ingestor != null) {
            val doc = Document.from(cleanBody)
            val splitter = DocumentSplitters.recursive(1000, 100)
            val segments = splitter.split(doc)

            val scientificKeywords = listOf("mM", "μM", "mg/", "concentration", "dose", "Methods", "treated with")

            val relevantSegments = segments.filter { segment ->
                scientificKeywords.any { keyword -> segment.text().contains(keyword, ignoreCase = true) }
            }

            println("[DEBUG_LOG] Filtered ${segments.size} chunks down to ${relevantSegments.size} high-signal chunks.")

            relevantSegments.forEach { segment: dev.langchain4j.data.segment.TextSegment ->
                try {
                    ingestor.ingest(Document.from(segment.text()))
                } catch (e: Exception) {
                    println("Skipping failed segment: ${e.message}")
                }
            }
            "Successfully indexed ${relevantSegments.size} key sections from the research for $topic. " +
                    "Data involving concentrations and methods is now in internal memory. Provide your FINAL ANSWER now."
        } else {
            "Ingestor error."
        }
    }

    @Tool("Search PMC for the beginning of the paper body to identify experimental setups")
    fun searchPubMed(@P("the research query") query: String): String {
        turnCount++
        if (turnCount > MAX_TURNS) throw RuntimeException("STOP_LOOP: Maximum research steps reached.")

        println("[DEBUG_LOG] Searching PMC for body content: $query")

        // 1. ESearch: Get PMC IDs
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pmc&term=${query.replace(" ", "+")}&retmax=2&retmode=json"
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

        // 2. EFetch: Get Full XML but surgically extract the Body
        val idsString = ids.joinToString(",")
        val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=$idsString&retmode=text"
        val fetchRequest = Request.Builder().url(fetchUrl).build()

        val rawContent = try {
            client.newCall(fetchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error fetching full texts: ${response.code}"
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            return "Error fetching full texts: ${e.message}"
        }

        // 3. SURGICAL EXTRACTION: Ignore <front> (authors/metadata) and grab <body>
        val bodySnippet = when {
            rawContent.contains("<body>") -> {
                rawContent.substringAfter("<body>").substringBefore("</body>")
            }
            rawContent.contains("<abstract>") -> {
                rawContent.substringAfter("<abstract>").substringBefore("</abstract>")
            }
            else -> {
                // ULTIMATE FALLBACK: If no tags found, just strip XML and take the first 5000 chars
                rawContent.take(5000)
            }
        }.replace(Regex("<[^>]*>"), " ") // Strip all remaining XML tags
            .replace(Regex("\\s+"), " ")    // Clean up whitespace
            .trim()

        // 4. Return the IDs and the clean Body snippet
        // This goes into the Agent's Chat Memory
        return "Results for $query:\n\n" +
                "Found IDs: ${ids.joinToString(", ")}\n" +
                "Body Snippet: $bodySnippet...\n\n" +
                "--- ACTION: If this snippet looks relevant but lacks specific dosages, " +
                "call 'downloadAndIndex' once to perform a deep Vector RAG search on the full paper. Do not search again"
    }
}
