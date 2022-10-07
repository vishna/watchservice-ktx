package dev.vishna.watchservice

import java.nio.file.Path

data class CrawledFileEvent(val path: Path, val eventType: CrawledEventType)

@Suppress("EnumEntryName")
enum class CrawledEventType {
    creation,
    update,
    delete
}
