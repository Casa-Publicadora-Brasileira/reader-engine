package com.readerengine.core.observability

import com.readerengine.core.model.ReadingMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Severidade de logs estruturados emitidos pelo `:reader-core`. */
@Serializable
public enum class ReaderLogLevel {
    @SerialName("debug")
    DEBUG,

    @SerialName("info")
    INFO,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR,
}

/** Registro estruturado de log emitido pelo motor de leitura. */
@Serializable
public data class ReaderLogRecord(
    val level: ReaderLogLevel,
    val tag: String,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
)

/** Span de telemetria/performance medido via relógio monotônico (`TimeSource.Monotonic`). */
@Serializable
public data class ReaderSpan(
    val name: String,
    val durationMs: Double,
    val success: Boolean,
    val attributes: Map<String, String> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
)

/** Métrica numérica (contador, latência ou gauge) emitida pelo SDK. */
@Serializable
public data class ReaderMetric(
    val name: String,
    val value: Double,
    val unit: String = "count",
    val attributes: Map<String, String> = emptyMap(),
)

/** Estatísticas em tempo real do cache LRU de leitura de pacotes ZIP. */
@Serializable
public data class ZipCacheStats(
    val hits: Long = 0L,
    val misses: Long = 0L,
    val evictions: Long = 0L,
    val entryCount: Int = 0,
    val currentBytes: Long = 0L,
    val maxBytes: Long = 0L,
) {
    public val hitRate: Double
        get() {
            val total = hits + misses
            return if (total <= 0L) 0.0 else hits.toDouble() / total.toDouble()
        }
}

/**
 * Snapshot serializável de diagnóstico técnico da sessão ativa, pronto para anexar
 * em tickets de suporte, telas de debug ou relatórios de incidentes.
 */
@Serializable
public data class ReaderDiagnosticsSnapshot(
    val sdkVersion: String,
    val documentId: String,
    val title: String,
    val language: String? = null,
    val pageIndex: Int,
    val spineLen: Int,
    val readingMode: ReadingMode,
    val currentSubPage: Int,
    val totalSubPages: Int,
    val cacheStats: ZipCacheStats? = null,
    val recentSpans: List<ReaderSpan> = emptyList(),
    val recentLogs: List<ReaderLogRecord> = emptyList(),
    val recentMetrics: List<ReaderMetric> = emptyList(),
)

/**
 * SPI (Service Provider Interface) de observabilidade multiplataforma do `:reader-core`.
 *
 * Permite conectar o SDK a qualquer stack de telemetria do app host (Android Logcat,
 * Firebase Crashlytics/Performance, iOS OSLog, Sentry, Datadog, SLF4J, OpenTelemetry)
 * sem introduzir dependências pesadas em `commonMain`.
 */
public interface ReaderObserver {
    public fun onLog(record: ReaderLogRecord) {}

    public fun onSpanFinished(span: ReaderSpan) {}

    public fun onMetric(metric: ReaderMetric) {}

    public companion object {
        /** Implementação vazia de custo zero usada por padrão quando nenhum observer é fornecido. */
        public val NoOp: ReaderObserver = object : ReaderObserver {}
    }
}

/**
 * Encaminha eventos de observabilidade para múltiplos [ReaderObserver]s com isolamento
 * de exceções (uma falha em um coletor nunca derruba o fluxo de leitura).
 */
public class CompositeReaderObserver(
    private val observers: List<ReaderObserver>,
) : ReaderObserver {
    public constructor(vararg observers: ReaderObserver) : this(observers.toList())

    override fun onLog(record: ReaderLogRecord) {
        for (obs in observers) {
            runCatching { obs.onLog(record) }
        }
    }

    override fun onSpanFinished(span: ReaderSpan) {
        for (obs in observers) {
            runCatching { obs.onSpanFinished(span) }
        }
    }

    override fun onMetric(metric: ReaderMetric) {
        for (obs in observers) {
            runCatching { obs.onMetric(metric) }
        }
    }
}

/**
 * Buffer circular (ring buffer) em memória que coleta os últimos [maxEntries] logs, spans
 * e métricas para alimentar [ReaderDiagnosticsSnapshot] sem crescimento ilimitado de RAM.
 */
public class InMemoryDiagnosticsBuffer(
    private val maxEntries: Int = 100,
) : ReaderObserver {
    private val safeMax = maxEntries.coerceAtLeast(1)
    private val logs = ArrayDeque<ReaderLogRecord>()
    private val spans = ArrayDeque<ReaderSpan>()
    private val metrics = ArrayDeque<ReaderMetric>()

    override fun onLog(record: ReaderLogRecord) {
        appendBounded(logs, record)
    }

    override fun onSpanFinished(span: ReaderSpan) {
        appendBounded(spans, span)
    }

    override fun onMetric(metric: ReaderMetric) {
        appendBounded(metrics, metric)
    }

    public fun recentLogs(): List<ReaderLogRecord> = logs.toList()

    public fun recentSpans(): List<ReaderSpan> = spans.toList()

    public fun recentMetrics(): List<ReaderMetric> = metrics.toList()

    public fun clear() {
        logs.clear()
        spans.clear()
        metrics.clear()
    }

    private fun <T> appendBounded(queue: ArrayDeque<T>, item: T) {
        if (queue.size >= safeMax) {
            queue.removeFirst()
        }
        queue.addLast(item)
    }
}

/** Utilitários de instrumentação monotônica e versão do SDK. */
public object ReaderObservability {
    public const val SDK_VERSION: String = "0.9.0"

    /**
     * Executa [block] medindo sua duração com [TimeSource.Monotonic] e reporta um [ReaderSpan]
     * ao [observer] tanto em sucesso quanto em falha (repropagando a exceção original).
     */
    public inline fun <T> measureSpan(
        observer: ReaderObserver,
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: () -> T,
    ): T {
        if (observer === ReaderObserver.NoOp) {
            return block()
        }
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val result = block()
            val durationMs = mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            observer.onSpanFinished(
                ReaderSpan(
                    name = name,
                    durationMs = durationMs,
                    success = true,
                    attributes = attributes,
                ),
            )
            result
        } catch (t: Throwable) {
            val durationMs = mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            observer.onSpanFinished(
                ReaderSpan(
                    name = name,
                    durationMs = durationMs,
                    success = false,
                    attributes = attributes,
                    errorType = t::class.simpleName ?: "Throwable",
                    errorMessage = t.message,
                ),
            )
            observer.onLog(
                ReaderLogRecord(
                    level = ReaderLogLevel.ERROR,
                    tag = "ReaderCore",
                    message = "Span '$name' falhou: ${t.message ?: t::class.simpleName}",
                    attributes = attributes,
                    errorType = t::class.simpleName ?: "Throwable",
                    errorMessage = t.message,
                ),
            )
            throw t
        }
    }
}
