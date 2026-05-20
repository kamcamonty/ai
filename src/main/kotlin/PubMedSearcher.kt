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
    private var isAlreadyIndexed = false
    private var turnCount = 0
    private val MAX_TURNS = Config.pubMedMaxTurns

    @Tool("Downloads and indexes full-text research articles for multiple sources into internal memory")
    fun downloadAndIndex(@P("the research topic") topic: String): String {
        // 1. Idempotency Guard: prevent re-indexing
        if (isAlreadyIndexed) {
            return "NOTICE: Full-text data is ALREADY in memory. Stop calling this tool and analyze your context now."
        }

        println("[DEBUG_LOG] Method-focused indexing started for: $topic")

        // 1. ESearch: Fetch top IDs
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pmc&term=${topic.replace(" ", "+")}+AND+(dosage+OR+concentration)&retmax=${Config.pubMedRetMax}&retmode=json"
        val idList = try {
            client.newCall(Request.Builder().url(searchUrl).build()).execute().use { response ->
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                json.getAsJsonObject("esearchresult").getAsJsonArray("idlist").map { it.asString }
            }
        } catch (e: Exception) { return "Search failed: ${e.message}" }

        if (idList.isEmpty()) return "No methodological full-text articles found for '$topic'."

        val indexedLinks = mutableListOf<String>()
        val splitter = DocumentSplitters.recursive(Config.chunkSize, Config.chunkOverlap)

        // Key signal words for scoring
        val highSignalUnits = listOf("nM", "μM", "uM", "mM", "mg/kg", "hours", "hrs", "duration")
        val experimentalVerbs = listOf("treated with", "incubated", "supplemented", "administered")

        idList.forEach { id ->
            try {
                val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=$id&retmode=text"
                val rawXml = client.newCall(Request.Builder().url(fetchUrl).build()).execute().use { it.body?.string() ?: "" }

                val cleanText = when {
                    rawXml.contains("<body>") -> rawXml.substringAfter("<body>").substringBefore("</body>")
                    rawXml.contains("<abstract>") -> rawXml.substringAfter("<abstract>").substringBefore("</abstract>")
                    else -> rawXml.take(15000)
                }.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

                if (cleanText.isNotBlank()) {
                    val doc = Document.from(cleanText)
                    val allSegments = splitter.split(doc)

                    // 2. SCORING FILTER: Prioritize chunks with units AND verbs
                    val relevantSegments = allSegments.filter { seg ->
                        val text = seg.text()
                        highSignalUnits.any { unit -> text.contains(unit, ignoreCase = false) } ||
                                text.contains("Materials and methods", ignoreCase = true)
                    }.sortedByDescending { seg ->
                        val text = seg.text()
                        // Score +2 for units, +1 for verbs
                        highSignalUnits.count { u -> text.contains(u) } * 2 +
                                experimentalVerbs.count { v -> text.contains(v, ignoreCase = true) }
                    }.take(15) // Take top 15 highest scoring segments per paper

                    relevantSegments.forEach { segment ->
                        ingestor?.ingest(Document.from(segment.text()))
                    }

                    indexedLinks.add("https://pmc.ncbi.nlm.nih.gov/articles/PMC$id/")
                    println("[DEBUG_LOG] Indexed PMC$id with ${relevantSegments.size} signal-rich segments")
                }
            } catch (e: Exception) { println("[ERROR] PMC$id failed") }
        }

        isAlreadyIndexed = true
        return "SUCCESS: ${indexedLinks.size} articles indexed. LINKS: ${indexedLinks.joinToString(", ")}. " +
                "CRITICAL: Data is now in memory. Find the numeric concentrations and treatment times. Provide the FINAL ANSWER table now."
    }

    @Tool("Search PMC for body snippets to find candidate IDs")
    fun searchPubMed(@P("the research query") query: String): String {
        if (isAlreadyIndexed) {
            return "STOP: Database is already populated. Analyze the current context for the answer."
        }

        turnCount++
        if (turnCount > MAX_TURNS) return "LIMIT: Research turns exhausted. Synthesize FINAL ANSWER from current memory."

        println("[DEBUG_LOG] Searching PMC for snippets: $query")

        // Add methodology bias to the query
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pmc&term=${query.replace(" ", "+")}+AND+(dosage+OR+concentration)&retmax=${Config.pubMedRetMax}&retmode=json"

        val ids = try {
            client.newCall(Request.Builder().url(searchUrl).build()).execute().use { response ->
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                json.getAsJsonObject("esearchresult").getAsJsonArray("idlist").map { it.asString }
            }
        } catch (e: Exception) { return "Search failed" }

        if (ids.isEmpty()) return "No relevant papers found for that specific query."

        val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=${ids.joinToString(",")}&retmode=text"
        val rawContent = client.newCall(Request.Builder().url(fetchUrl).build()).execute().use { it.body?.string() ?: "" }

        val bodySnippet = when {
            rawContent.contains("<body>") -> rawContent.substringAfter("<body>").substringBefore("</body>")
            rawContent.contains("<abstract>") -> rawContent.substringAfter("<abstract>").substringBefore("</abstract>")
            else -> rawContent.take(5000)
        }.replace(Regex("<[^>]*>"), " ").trim().take(Config.pubMedSnippetSize)

        return "IDs Found: ${ids.joinToString(", ")}\nSnippet: $bodySnippet...\n" +
                "INSTRUCTION: If dosage is unclear, call 'downloadAndIndex' for these IDs."
    }

    fun reset() {
        this.turnCount = 0
        this.isAlreadyIndexed = false
    }
}