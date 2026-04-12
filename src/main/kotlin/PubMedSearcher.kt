package org.example

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import java.util.stream.Collectors

class PubMedSearcher {
    private val client = OkHttpClient()

    @Tool("Search PubMed for abstracts of recent research papers given a query")
    fun searchPubMed(@P("the research query") query: String): String {
        println("[DEBUG_LOG] Searching PubMed for: $query")
        
        // 1. ESearch: get IDs
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&term=${query.replace(" ", "+")}&retmax=5&retmode=json"
        val searchRequest = Request.Builder().url(searchUrl).build()
        
        val ids = try {
            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error searching PubMed: ${response.code}"
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                val idList = json.getAsJsonObject("esearchresult").getAsJsonArray("idlist")
                idList.map { it.asString }
            }
        } catch (e: Exception) {
            return "Error searching PubMed: ${e.message}"
        }

        if (ids.isEmpty()) return "No abstracts found for query: $query"

        // 2. EFetch: get abstracts
        val idsString = ids.joinToString(",")
        val fetchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&id=$idsString&retmode=text&rettype=abstract"
        val fetchRequest = Request.Builder().url(fetchUrl).build()

        val abstracts = try {
            client.newCall(fetchRequest).execute().use { response ->
                if (!response.isSuccessful) return "Error fetching abstracts: ${response.code}"
                response.body?.string() ?: "Empty abstracts response"
            }
        } catch (e: Exception) {
            return "Error fetching abstracts: ${e.message}"
        }

        return "Search results for: $query\n\n" + ids.mapIndexed { index, id ->
            "Source [Link: https://pubmed.ncbi.nlm.nih.gov/$id/]\nAbstract:\n$abstracts"
        }.joinToString("\n\n---\n\n")
    }
}
