package com.serhat.autosub.shorts

import android.content.Context
import android.os.Debug
import com.serhat.autosub.core.DebugLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Text-only Gemma runtime. This class must never be loaded below API 31. */
class LiteRtShortsLlmEngine(
    private val context: Context,
    private val preferGpu: Boolean = true,
) : ShortsLlmEngine {
    companion object { private const val TAG = "AutoSubShorts" }
    private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var backendLabel: String = "not-initialized"
    @Volatile private var thinkingEnabled: Boolean = false

    private var maxContextTokens: Int = 8192

    override fun initialize(modelFile: File, maxContextTokens: Int) {
        this.maxContextTokens = maxContextTokens
        val startedAt = System.currentTimeMillis()
        DebugLog.i(TAG, "Engine initialization started: fileBytes=${modelFile.length()}, " +
            "preferredBackend=${if (preferGpu) "GPU" else "CPU"}, maxContextTokens=$maxContextTokens")
        close()
        if (!preferGpu) {
            val cpuConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = maxContextTokens,
                cacheDir = context.cacheDir.absolutePath,
            )
            engine = Engine(cpuConfig).also { it.initialize() }
            backendLabel = "CPU"
            DebugLog.i(TAG, "Engine initialized directly on CPU in ${System.currentTimeMillis() - startedAt}ms")
            return
        }
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = null,
            audioBackend = null,
            // Keep the mobile KV cache bounded. Longer transcripts are chunked by the analyzer.
            maxNumTokens = maxContextTokens,
            cacheDir = context.cacheDir.absolutePath,
        )
        try {
            engine = Engine(config).also { it.initialize() }
            backendLabel = "GPU"
            DebugLog.i(TAG, "Engine initialized on GPU in ${System.currentTimeMillis() - startedAt}ms")
        } catch (gpuError: Throwable) {
            DebugLog.w(TAG, "GPU initialization failed after ${System.currentTimeMillis() - startedAt}ms; retrying on CPU: ${gpuError.message}")
            engine?.close()
            val cpuConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = maxContextTokens,
                cacheDir = context.cacheDir.absolutePath,
            )
            engine = Engine(cpuConfig).also { it.initialize() }
            backendLabel = "CPU"
            DebugLog.i(TAG, "Engine initialized on CPU in ${System.currentTimeMillis() - startedAt}ms total")
        }
    }

    override fun getMaxContextTokens(): Int {
        return maxContextTokens
    }

    override fun setThinkingEnabled(enabled: Boolean) {
        thinkingEnabled = enabled
    }

    override fun generate(systemInstruction: String, prompt: String): String {
        val activeEngine = engine ?: error("Gemma engine is not initialized")
        val startedAt = System.currentTimeMillis()
        val estimatedInputTokens = (prompt.toByteArray(Charsets.UTF_8).size + 2) / 3
        DebugLog.i(TAG, "Inference started: backend=$backendLabel, thinking=$thinkingEnabled, promptChars=${prompt.length}, estimatedInputTokens=$estimatedInputTokens, systemChars=${systemInstruction.length}")
        val phase = AtomicReference("closing previous conversation")
        val firstTokenSeen = AtomicBoolean(false)
        val cancelledIntentionally = AtomicBoolean(false)
        val messageCount = AtomicInteger(0)
        val outputChars = AtomicInteger(0)
        val heartbeatCount = AtomicInteger(0)
        val initialProcessCpuMs = android.os.Process.getElapsedCpuTime()
        val lastProcessCpuMs = AtomicLong(initialProcessCpuMs)
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "shorts-inference-watchdog").apply { isDaemon = true }
        }
        watchdog.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            val processCpuMs = android.os.Process.getElapsedCpuTime()
            val cpuDeltaMs = processCpuMs - lastProcessCpuMs.getAndSet(processCpuMs)
            val javaUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
            val nativeUsedMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            val heartbeat = heartbeatCount.incrementAndGet()
            val message = "Inference heartbeat: phase=${phase.get()}, backend=$backendLabel, elapsedMs=${now - startedAt}, " +
                "processCpuMs=${processCpuMs - initialProcessCpuMs}, cpuMsLast5s=$cpuDeltaMs, callbacks=${messageCount.get()}, " +
                "outputChars=${outputChars.get()}, javaHeapMb=$javaUsedMb, nativeHeapMb=$nativeUsedMb"
            if (!firstTokenSeen.get() && heartbeat % 6 == 0) {
                DebugLog.w(TAG, "$message; CPU prefill can take several minutes for long prompts")
            } else {
                DebugLog.i(TAG, message)
            }
        }, 5, 5, TimeUnit.SECONDS)

        val current = try {
            conversation?.close()
            val system = Contents.of(listOf(Content.Text(systemInstruction)))
            phase.set("creating conversation")
            activeEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    systemInstruction = system,
                )
            )
        } catch (throwable: Throwable) {
            watchdog.shutdownNow()
            DebugLog.e(TAG, "Conversation creation failed after ${System.currentTimeMillis() - startedAt}ms: ${throwable.message}", throwable)
            throw throwable
        }
        conversation = current

        val result = StringBuilder()
        val failure = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val lastProgressLogAt = AtomicLong(startedAt)
        try {
            phase.set("submitting prompt / native prefill")
            current.sendMessageAsync(
                Contents.of(listOf(Content.Text(prompt))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val length = synchronized(result) {
                            result.append(message.toString())
                            result.length
                        }
                        outputChars.set(length)
                        val callbacks = messageCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (firstTokenSeen.compareAndSet(false, true)) {
                            phase.set("generating output")
                            DebugLog.i(TAG, "First output token received after ${now - startedAt}ms (prefill complete)")
                        }
                        if (now - lastProgressLogAt.get() >= 2_000 && lastProgressLogAt.compareAndSet(lastProgressLogAt.get(), now)) {
                            DebugLog.i(TAG, "Inference generating: elapsedMs=${now - startedAt}, callbacks=$callbacks, outputChars=$length")
                        }
                        if (callbacks > 1200 || length > 5000) {
                            if (cancelledIntentionally.compareAndSet(false, true)) {
                                DebugLog.w(TAG, "Inference output limit exceeded (callbacks=$callbacks, chars=$length); cancelling process to prevent infinite loop.")
                                current.cancelProcess()
                            }
                        }
                    }

                    override fun onDone() {
                        phase.set("completed")
                        DebugLog.i(TAG, "Inference completed: elapsedMs=${System.currentTimeMillis() - startedAt}, callbacks=${messageCount.get()}, outputChars=${outputChars.get()}")
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        phase.set("failed")
                        if (cancelledIntentionally.get()) {
                            val limitExceeded = IllegalStateException("Gemma output limit exceeded (possible loop)", throwable)
                            DebugLog.e(TAG, "Inference cancelled intentionally: limit exceeded", limitExceeded)
                            failure.set(limitExceeded)
                        } else {
                            DebugLog.e(TAG, "Inference failed after ${System.currentTimeMillis() - startedAt}ms: ${throwable.message}", throwable)
                            failure.set(throwable)
                        }
                        latch.countDown()
                    }
                },
                if (thinkingEnabled) mapOf("enable_thinking" to "true") else emptyMap(),
            )
            if (latch.count > 0 && !firstTokenSeen.get()) phase.set("native prefill / waiting for first token")
            if (!latch.await(5, TimeUnit.MINUTES)) {
                phase.set("timed out")
                DebugLog.e(TAG, "Inference timed out after ${System.currentTimeMillis() - startedAt}ms")
                current.cancelProcess()
                throw IllegalStateException("Gemma response timed out")
            }
            failure.get()?.let { throw it }
            return synchronized(result) { result.toString() }
        } finally {
            watchdog.shutdownNow()
        }
    }

    override fun cancel() {
        DebugLog.i(TAG, "Inference cancellation requested")
        conversation?.cancelProcess()
    }

    override fun close() {
        if (engine != null || conversation != null) DebugLog.i(TAG, "Closing Gemma engine and conversation")
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        backendLabel = "not-initialized"
    }
}
