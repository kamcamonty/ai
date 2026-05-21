package org.example

import dev.langchain4j.data.document.Document
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mockito.kotlin.*
import kotlin.test.Test
import kotlin.test.assertTrue

class PubMedSearcherTest {
    private val ingestor: EmbeddingStoreIngestor = mock()
    private val client: OkHttpClient = mock()
    private val searcher = PubMedSearcher(ingestor, client)

    @Test
    fun `test downloadAndIndex scoring and indexing`() {
        val topic = "cancer treatment"

        // Mock Search Response
        val searchJsonResponse = """
            {
              "esearchresult": {
                "idlist": ["12345"]
              }
            }
        """.trimIndent()

        val searchCall: Call = mock()
        val searchResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(searchJsonResponse.toResponseBody("application/json".toMediaType()))
            .build()

        whenever(client.newCall(any())).thenReturn(searchCall)
        whenever(searchCall.execute()).thenReturn(searchResponse)

        // Mock Fetch Response (XML)
        val fetchXmlResponse = """
            <pmc-articleset>
              <article>
                <body>
                  <p>In this study, cells were treated with 50 nM of drug for 24 hours.</p>
                  <p>Materials and methods: We incubated the samples.</p>
                </body>
              </article>
            </pmc-articleset>
        """.trimIndent()

        val fetchCall: Call = mock()
        val fetchResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(fetchXmlResponse.toResponseBody("application/xml".toMediaType()))
            .build()

        // Configure mock to return different responses for sequential calls
        whenever(client.newCall(any()))
            .thenReturn(searchCall)
            .thenReturn(fetchCall)

        whenever(searchCall.execute()).thenReturn(searchResponse)
        whenever(fetchCall.execute()).thenReturn(fetchResponse)

        val result = searcher.downloadAndIndex(topic)

        assertTrue(result.contains("SUCCESS: 1 articles indexed"))
        assertTrue(result.contains("PMC12345"))

        // Verify ingest was called
        verify(ingestor, atLeastOnce()).ingest(any<Document>())
    }

    @Test
    fun `test downloadAndIndex idempotency`() {
        val localClient: OkHttpClient = mock()
        val searcherForIdempotency = PubMedSearcher(ingestor, localClient)
        val topic = "test"

        // Mock search response with empty list to stop early
        val emptySearchResponse = """{"esearchresult": {"idlist": []}}""".trimIndent()
        val searchCall: Call = mock()
        val searchResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(emptySearchResponse.toResponseBody("application/json".toMediaType()))
            .build()

        whenever(localClient.newCall(any())).thenReturn(searchCall)
        whenever(searchCall.execute()).thenReturn(searchResponse)

        // First call
        searcherForIdempotency.downloadAndIndex(topic)

        // Second call
        val result = searcherForIdempotency.downloadAndIndex(topic)

        assertTrue(result.contains("ALREADY in memory"))
        verify(localClient, times(1)).newCall(any()) // Only one search call made
    }

    @Test
    fun `test searchPubMed limits`() {
        val searcher = PubMedSearcher(ingestor, client)
        val query = "test"
        val emptySearchResponse = """{"esearchresult": {"idlist": []}}""".trimIndent()
        val searchCall: Call = mock()
        val searchResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(emptySearchResponse.toResponseBody("application/json".toMediaType()))
            .build()

        whenever(client.newCall(any())).thenReturn(searchCall)
        whenever(searchCall.execute()).thenReturn(searchResponse)

        // exhaust turns
        repeat(Config.pubMedMaxTurns) {
            searcher.searchPubMed(query)
        }

        val result = searcher.searchPubMed(query)
        assertTrue(result.contains("LIMIT: Research turns exhausted"))
    }
}