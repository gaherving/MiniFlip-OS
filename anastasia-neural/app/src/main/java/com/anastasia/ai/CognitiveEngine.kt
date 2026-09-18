package com.anastasia.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anastasia 6 Cognitive Engine.
 *
 * Uses LiteRT-LM + Gemma 4 E2B as the always-ready conversational/reflex brain.
 * The existing llama.cpp/Qwen brain stays available as the deeper fallback.
 */
class CognitiveEngine(
    private val context: Context,
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        fun onState(state: String, backend: String, detail: String)
        fun onChunk(requestId: String, chunk: String)
        fun onDone(
            requestId: String,
            answer: String,
            firstTokenMs: Long,
            totalMs: Long,
            backend: String,
        )
        fun onError(requestId: String, error: String)
    }

    private val initExecutor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val busy = AtomicBoolean(false)

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var backendName: String = "none"
        private set

    @Volatile
    var lastError: String = ""
        private set

    private val systemInstruction = """
        Eres Anastasia, una IA personal local diseñada para responder con rapidez y precisión.
        Conversa de forma natural, clara y fluida. No conviertas cada mensaje en una orden.
        Para preguntas sencillas responde de inmediato y sin explicaciones innecesarias.
        Para preguntas que requieran análisis, organiza la respuesta con rigor y verifica
        contradicciones antes de contestar. No inventes hechos. Si algo necesita información
        reciente de Internet o una herramienta externa, indícalo con claridad.
        No muestres razonamiento interno ni cadenas privadas de pensamiento.
        Tu respuesta final debe ser útil, concreta y directamente relacionada con la pregunta.
    """.trimIndent()

    @OptIn(ExperimentalApi::class)
    fun initializeAsync(modelPath: String) {
        if (ready || engine != null) return
        initExecutor.execute {
            listener.onState("loading", backendName, "Inicializando Cognitive Engine")
            try {
                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                ExperimentalFlags.enableSpeculativeDecoding = true

                var initialized: Engine? = null
                var selected = "GPU"

                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        maxNumTokens = 4096,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    initialized = Engine(config)
                    initialized.initialize()
                } catch (gpuError: Throwable) {
                    try {
                        initialized?.close()
                    } catch (_: Throwable) {
                    }
                    initialized = null
                    selected = "CPU"
                    listener.onState(
                        "loading",
                        selected,
                        "GPU no disponible, usando CPU optimizada",
                    )
                    val cpuConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU(threadCount = 4),
                        maxNumTokens = 4096,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    initialized = Engine(cpuConfig)
                    initialized.initialize()
                }

                engine = initialized
                backendName = selected

                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = SamplerConfig(
                        topK = 32,
                        topP = 0.92,
                        temperature = 0.62,
                    ),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 384,
                    thinkingConfig = ThinkingConfig(
                        enableThinking = false,
                        thinkingTokenBudget = 0,
                    ),
                )

                conversation = engine!!.createConversation(config)
                ready = true
                lastError = ""
                listener.onState("ready", backendName, "Gemma 4 E2B · LiteRT-LM")
            } catch (t: Throwable) {
                ready = false
                lastError = t.message ?: t.javaClass.simpleName
                try {
                    conversation?.close()
                } catch (_: Throwable) {
                }
                conversation = null
                try {
                    engine?.close()
                } catch (_: Throwable) {
                }
                engine = null
                listener.onState("error", backendName, lastError)
            }
        }
    }

    fun ask(requestId: String, text: String, maxTokens: Int = 320) {
        val c = conversation
        if (!ready || c == null) {
            listener.onError(requestId, "Cognitive Engine todavía no está listo.")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            listener.onError(requestId, "Anastasia todavía está terminando la respuesta anterior.")
            return
        }

        val started = System.nanoTime()
        val output = StringBuilder()
        var firstTokenAt = 0L

        try {
            c.sendMessageAsync(
                text = text,
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.toString()
                        if (chunk.isEmpty()) return
                        if (firstTokenAt == 0L) firstTokenAt = System.nanoTime()
                        output.append(chunk)
                        listener.onChunk(requestId, chunk)
                    }

                    override fun onDone() {
                        val ended = System.nanoTime()
                        val firstMs =
                            if (firstTokenAt == 0L) {
                                (ended - started) / 1_000_000L
                            } else {
                                (firstTokenAt - started) / 1_000_000L
                            }
                        val totalMs = (ended - started) / 1_000_000L
                        busy.set(false)
                        listener.onDone(
                            requestId,
                            output.toString().trim(),
                            firstMs,
                            totalMs,
                            backendName,
                        )
                    }

                    override fun onError(throwable: Throwable) {
                        busy.set(false)
                        listener.onError(
                            requestId,
                            throwable.message ?: throwable.javaClass.simpleName,
                        )
                    }
                },
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(
                    enableThinking = false,
                    thinkingTokenBudget = 0,
                ),
            )
        } catch (t: Throwable) {
            busy.set(false)
            listener.onError(requestId, t.message ?: t.javaClass.simpleName)
        }
    }

    @OptIn(ExperimentalApi::class)
    fun resetConversation() {
        if (!ready || engine == null) return
        initExecutor.execute {
            try {
                conversation?.close()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = SamplerConfig(32, 0.92, 0.62),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 384,
                    thinkingConfig = ThinkingConfig(false, 0),
                )
                conversation = engine!!.createConversation(config)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun status(): String {
        return when {
            ready -> "ready"
            engine != null -> "loading"
            lastError.isNotEmpty() -> "error"
            else -> "idle"
        }
    }

    override fun close() {
        ready = false
        try {
            conversation?.close()
        } catch (_: Throwable) {
        }
        conversation = null
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        initExecutor.shutdownNow()
    }
}
