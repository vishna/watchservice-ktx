package dev.vishna.watchservice

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should be`
import org.junit.Test
import java.io.File

class WatchServiceTests {
    @Test
    fun `watch current directory for initalization event` () {
        runBlocking {
            val currentDirectory  = File(System.getProperty("user.dir"))

            val watchChannel = currentDirectory.asWatchChannel()

            watchChannel.isClosedForSend `should be equal to` false
            watchChannel.data `should be` null
            watchChannel.path `should be` currentDirectory.toPath()
            watchChannel.subtree `should be` true

            launch {
                watchChannel.consumeEach { event ->
                    // there is always the first event triggered and here we only test that
                    event.type `should be` KWatchEvent.Kind.initalized
                }
            }

            watchChannel.isClosedForSend `should be equal to` false

            watchChannel.close()

            watchChannel.isClosedForSend `should be equal to` true
        }
    }
}