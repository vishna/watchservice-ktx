package dev.vishna.watchservice

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.nio.file.WatchKey
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds.*


import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlin.io.path.isDirectory

fun File.asFSWatcherChannel(
    mode: FSWatcherChannel.Mode? = null,
) = FSWatcherChannel(
    file = this,
    mode = mode ?: if (isFile) FSWatcherChannel.Mode.SingleFile else FSWatcherChannel.Mode.Recursive,
)

class FSWatcherChannel(
    val file: File,
    val mode: Mode,
    private val channel: Channel<CrawledFileEvent> = Channel(),
    scope: CoroutineScope? = null
) : Channel<CrawledFileEvent> by channel {

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val scope: CoroutineScope = getOrCreateCoroutineScope(scope)
    private val registeredKeys = ArrayList<WatchKey>()
    private val path = if (file.isFile) {
        file.parentFile
    } else {
        file
    }.toPath()


    private fun registerPaths() {
        disableWatchRegistration()
        if (mode == Mode.Recursive) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(subPath: Path, attrs: BasicFileAttributes): FileVisitResult {
                    registeredKeys += subPath.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            registeredKeys += path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        }
    }

    fun startWatch() {
        registerPaths()

        scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                flowFSEvent().filter { !it.path.isDirectory() }.collect {
                    if (mode == Mode.Recursive && it.eventType in listOf(CrawledEventType.creation, CrawledEventType.delete) && it.path.isDirectory()) {
                        registerPaths()
                    }
                    send(it)
                }
            }
        }
    }

    private fun flowFSEvent(): Flow<CrawledFileEvent> = flow {
        @Suppress("BlockingMethodInNonBlockingContext") val monitorKey = watchService.take()
        val dirPath = monitorKey.watchable() as? Path
        if (dirPath != null) {
            monitorKey.pollEvents().forEach {
                val eventPath = dirPath.resolve(it.context() as Path)

                if (mode == Mode.SingleFile && eventPath.toFile().absolutePath != file.absolutePath) {
                    return@forEach
                }

                emit(CrawledFileEvent(path = eventPath, eventType = it.kind().toCrawledFileEvent()))
            }

            if (!monitorKey.reset()) {
                monitorKey.cancel()
            }
        }
    }


    override fun close(cause: Throwable?): Boolean {
        disableWatchRegistration()
        scope.cancel()
        return channel.close(cause)
    }

    private fun disableWatchRegistration() {
        registeredKeys.apply {
            forEach { it.cancel() }
            clear()
        }
    }

    enum class Mode {
        SingleFile,
        @Suppress("unused")
        SingleDirectory,
        Recursive
    }
}

private fun <T> WatchEvent.Kind<T>.toCrawledFileEvent(): CrawledEventType = when (this) {
    ENTRY_CREATE -> CrawledEventType.creation
    ENTRY_DELETE -> CrawledEventType.delete
    else -> CrawledEventType.update
}

fun getOrCreateCoroutineScope(scope: CoroutineScope?, dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope {
    return if (scope == null) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> throwable.printStackTrace(); println("$throwable") }
        val context = dispatcher + SupervisorJob() + exceptionHandler
        CoroutineScope(context)
    } else {
        scope
    }
}