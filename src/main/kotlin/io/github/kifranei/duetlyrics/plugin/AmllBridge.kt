package io.github.kifranei.duetlyrics.plugin

import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.management.ObjectName

/**
 * Optional hand-off to "AMLL into SPW" (https://github.com/GaBoron/AMLL-into-SPW), a separate
 * community plugin that replaces SPW's lyric view and renders duets left/right.
 * Uses its documented in-process JMX protocol 1 (no network, no shared jar). If AMLL is not
 * installed/enabled every call is a cheap no-op.
 */
internal class AmllBridge : AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "duet-lyrics-amll-bridge").apply { isDaemon = true }
    }
    private val generation = AtomicLong()

    /** Submit TTML for the current song; retried briefly because the receiver may not have seen the track change yet. */
    fun submit(trackId: String, path: String, ttml: String) {
        val gen = generation.incrementAndGet()
        fun attempt(i: Int) {
            if (gen != generation.get()) return
            if (!isAvailable()) return
            if (invoke("submitTtml", arrayOf(trackId, path, ttml, 0L),
                    arrayOf("java.lang.String", "java.lang.String", "java.lang.String", "long"))) return
            if (i + 1 < DELAYS.size) executor.schedule({ attempt(i + 1) }, DELAYS[i + 1], TimeUnit.MILLISECONDS)
        }
        executor.schedule({ attempt(0) }, DELAYS[0], TimeUnit.MILLISECONDS)
    }

    /** New song without plugin-provided TTML: stop pending retries. */
    fun cancelPending() { generation.incrementAndGet() }

    private fun isAvailable(): Boolean = runCatching {
        val server = ManagementFactory.getPlatformMBeanServer()
        server.isRegistered(NAME) &&
            server.getAttribute(NAME, "ProtocolVersion") == 1 &&
            server.getAttribute(NAME, "Active") == true
    }.getOrDefault(false)

    private fun invoke(op: String, args: Array<Any>, sig: Array<String>): Boolean = runCatching {
        ManagementFactory.getPlatformMBeanServer().invoke(NAME, op, args, sig) == true
    }.getOrDefault(false)

    override fun close() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    companion object {
        val NAME = ObjectName("io.github.gaboron.amllspw:type=TtmlReceiver,version=1")
        private val DELAYS = longArrayOf(150, 600, 1_500, 3_500)
    }
}
