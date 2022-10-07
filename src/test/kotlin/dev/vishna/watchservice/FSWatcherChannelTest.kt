package dev.vishna.watchservice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory


@Suppress("OPT_IN_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class FSWatcherChannelTest {

    private lateinit var tmpDir: Path
    private lateinit var channel: FSWatcherChannel

    @BeforeEach
    internal fun setUp() {
        tmpDir = createTempDirectory()
        println(tmpDir)
        channel = tmpDir.toFile().asFSWatcherChannel()
    }

    @AfterEach
    internal fun tearDown() {
        channel.close()
    }

    @Test
    internal fun `should detect file creation`() = runTest {
        channel.startWatch()

        val file = createTextFileInParent(tmpDir, "file-test.txt", "My content")

        assertEquals("CrawledFileEvent(path=${file}, eventType=creation)", channel.receive().toString())
    }

    @Test
    internal fun `should detect file update`() = runTest {
        val file = createTextFileInParent(tmpDir, "file-test.txt", "My content")

        channel.startWatch()

        file.appendText(" more text")

        assertEquals("CrawledFileEvent(path=${file}, eventType=update)", channel.receive().toString())
    }

    @Test
    internal fun `should detect file delete`() = runTest {
        val file = createTextFileInParent(tmpDir, "file-test.txt", "My content")

        channel.startWatch()

        file.delete()

        assertEquals("CrawledFileEvent(path=${file}, eventType=delete)", channel.receive().toString())
    }

    @Test
    internal fun `should detect file creation in child dir`() = runTest {
        val childDir = Paths.get(tmpDir.absolutePathString(), "child").createDirectory()

        channel.startWatch()

        val file = createTextFileInParent(childDir, "file-test.txt", "My content")

        assertEquals("CrawledFileEvent(path=${file}, eventType=creation)", channel.receive().toString())
    }

    @Test
    internal fun `should detect file update in child dir`() = runTest {
        val childDir = Paths.get(tmpDir.absolutePathString(), "child").createDirectory()
        val file = createTextFileInParent(childDir, "file-test.txt", "My content")

        channel.startWatch()

        file.appendText(" more text")

        assertEquals("CrawledFileEvent(path=${file}, eventType=update)", channel.receive().toString())
    }

    @Test
    internal fun `should detect file delete in child dir`() = runTest {
        val childDir = Paths.get(tmpDir.absolutePathString(), "child").createDirectory()
        val file = createTextFileInParent(childDir, "file-test.txt", "My content")

        channel.startWatch()

        file.delete()

        assertEquals("CrawledFileEvent(path=${file}, eventType=delete)", channel.receive().toString())
    }

}

fun createTextFileInParent(parent: Path, filename: String, content: String): File {
    val file = File(parent.toFile(), filename)
    file.printWriter().use { out ->
        out.println(content)
    }
    return file
}