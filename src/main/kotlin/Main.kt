package org.example

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

fun main() {
    (LoggerFactory.getLogger("dev.langchain4j.store.embedding.EmbeddingStoreIngestor") as Logger).level = Level.INFO

    ScienceAssistantServer().start()
}
