package dev.vishna.watchservice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.File
import java.nio.file.*
import java.nio.file.WatchKey
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds.*

/**
 * Watches directory. If file is supplied it will use parent directory. If it's an intent to watch just file,
 * developers must filter for the file related events themselves. 
 *
 * subtree - whether or not changes in recursive directories should be monitored
 * data - any kind of data that should be associated with this channel (e.g tag, marker)
 * scope - coroutine context for the channel
 */
fun File.asWatchChannel(
    subtree: Boolean = true,
    data: Any? = null,
    scope: CoroutineScope = GlobalScope
) = KWatchChannel(
    file = this,
    scope = scope,
    subtree = subtree,
    data = data
)

/**
 * Coroutine channel based wrapper for Java's WatchService
 */
class KWatchChannel(
    val file: File,
    val scope: CoroutineScope = GlobalScope,
    val subtree: Boolean = false,
    val data: Any? = null,
    private var channel: Channel<KWatchEvent> = Channel()
) : Channel<KWatchEvent> by channel {

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private var closed : Boolean = false
    private val registeredKeys = ArrayList<WatchKey>()
    private val path: Path
    private val isSingleFile: Boolean = file.isFile

    /**
     * Registers this channel to watch any changes in path directory and its subdirectories
     * if applicable. Removes any previous subscriptions.
     */
    private fun registerPaths() {
        registeredKeys.apply {
            forEach { it.cancel() }
            clear()
        }
        if (subtree && !isSingleFile) {
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

    init {
        // figure out if we should watch directory and its subtree or a single file
        path = if (isSingleFile) {
            file.parentFile
        } else {
            file
        }.toPath()

        // commence emitting events from channel
        scope.launch(Dispatchers.IO) {

            // sending channel initalization event
            channel.send(
                KWatchEvent(
                    file = path.toFile(),
                    data = data,
                    kind = KWatchEvent.Kind.initalized,
                    isDirectory = true
                ))

            var shouldRegisterPath = true

            while (!closed) {

                if (shouldRegisterPath) {
                    registerPaths()
                    shouldRegisterPath = false
                }

                val monitorKey = watchService.take()
                val dirPath = monitorKey.watchable() as? Path ?: break
                monitorKey.pollEvents().forEach {
                    val eventPath = dirPath.resolve(it.context() as Path)

                    if (isSingleFile && eventPath.toFile().absolutePath != file.absolutePath) {
                        return@forEach
                    }

                    val eventType = when(it.kind()) {
                        ENTRY_CREATE -> KWatchEvent.Kind.created
                        ENTRY_DELETE -> KWatchEvent.Kind.deleted
                        else -> KWatchEvent.Kind.modified
                    }

                    val event = KWatchEvent(
                        file = eventPath.toFile(),
                        data = data,
                        kind = eventType,
                        isDirectory = eventPath.toFile().isDirectory
                    )

                    // if any folder is created or deleted... and we watch subtree we should reregister the whole tree
                    if (subtree && event.isDirectory && event.kind in listOf(KWatchEvent.Kind.created, KWatchEvent.Kind.deleted)) {
                        shouldRegisterPath = true
                    }

                    channel.send(event)
                }

                if (!monitorKey.reset()) {
                    monitorKey.cancel()
                    close()
                    break
                }
                else if (closed) {
                    break
                }
            }
        }
    }

    override fun close(cause: Throwable?): Boolean {
        closed = true

        registeredKeys.apply {
            forEach { it.cancel() }
            clear()
        }

        return channel.close(cause)
    }
}

/**
 * Wrapper around WatchEvent that comes with properly resolved absolute path
 */
data class KWatchEvent(
    /**
     * Abolute path of modified folder/file
     */
    val file: File,

    /**
     * Kind of file system event
     */
    val kind: Kind,

    /**
     * Optional extra data that should be associated with this event
     */
    val data: Any?,

    /**
     * Whether or not this event is associated with a directory
     */
    val isDirectory: Boolean
) {
    /**
     * File system event, wrapper around WatchEvent.Kind
     */
    sealed class Kind(private val name: String) {
        /**
         * Triggered upon initalization of the channel
         */
        object initalized : Kind("initalized")

        /**
         * Triggered when file or directory is created
         */
        object created : Kind("created")

        /**
         * Triggered when file or directory is modified
         */
        object modified : Kind("modified")

        /**
         * Triggered when file or directory is deleted
         */
        object deleted : Kind("deleted")

        override fun toString(): String = name
    }
}