package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.JsonRpcResponse
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.errorResponse
import org.refactorkit.core.isNotification
import org.refactorkit.core.successResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-session scheduler. Stateful/control requests are FIFO barriers; only
 * interactive queries between the same barriers are reordered by priority.
 */
internal class DaemonRequestScheduler(
    private val dispatch: (String, JsonObject?, SemanticCancellationToken) -> kotlinx.serialization.json.JsonElement,
    private val respond: (JsonRpcResponse) -> Unit,
) : AutoCloseable {
    constructor(session: DaemonSession, respond: (JsonRpcResponse) -> Unit) : this(
        { method, params, cancellation -> session.dispatch(method, params, cancellation) },
        respond,
    )
    private val sequence = AtomicLong()
    private val queue = PriorityBlockingQueue<ScheduledRequest>()
    private val registrations = ConcurrentHashMap<String, ScheduledRequest>()
    private val running = AtomicBoolean(true)
    private var barrierGroup = 0L
    private val worker = Thread(::runLoop, "refactorkit-daemon-dispatch").apply {
        isDaemon = true
        start()
    }

    @Synchronized
    fun submit(request: JsonRpcRequest) {
        check(running.get()) { "daemon request scheduler is closed" }
        if (queue.size >= ProtocolLimits.MAX_DAEMON_QUEUED_REQUESTS) {
            if (!isNotification(request)) respond(errorResponse(
                request.id, JsonRpcErrorCodes.INVALID_REQUEST, "Daemon request queue capacity exceeded",
            ))
            return
        }
        val params = request.params as? JsonObject
        val query = request.method == "intelligence.query"
        val requestId = if (query) params?.string("requestId") else null
        val cancellation = CancellationSource()
        val task = ScheduledRequest(
            request = request,
            params = params,
            cancellation = cancellation,
            semanticRequestId = requestId,
            group = barrierGroup,
            priority = if (query) priority(params?.string("priority")) else CONTROL_PRIORITY,
            sequence = sequence.getAndIncrement(),
        )
        if (!query) barrierGroup++
        if (requestId != null && registrations.putIfAbsent(requestId, task) != null) {
            if (!isNotification(request)) respond(errorResponse(
                request.id, JsonRpcErrorCodes.INVALID_REQUEST, "Duplicate active intelligence requestId: $requestId",
            ))
            return
        }
        queue.put(task)
    }

    fun cancel(requestId: String): Boolean {
        val task = registrations[requestId] ?: return false
        task.cancellation.cancel()
        if (queue.remove(task)) {
            registrations.remove(requestId, task)
            if (!isNotification(task.request)) respond(cancelledResponse(task.request))
        }
        return true
    }

    private fun runLoop() {
        while (running.get() || queue.isNotEmpty()) {
            val task = try { queue.take() } catch (_: InterruptedException) {
                if (!running.get()) break else continue
            }
            val request = task.request
            val response = if (task.cancellation.isCancellationRequested()) {
                cancelledResponse(request)
            } else try {
                val result = dispatch(request.method, task.params, task.cancellation)
                if (task.cancellation.isCancellationRequested()) cancelledResponse(request)
                else successResponse(request.id, result)
            } catch (failure: JsonRpcException) {
                errorResponse(request.id, failure.code, failure.message, failure.data)
            } catch (_: Exception) {
                errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, "Internal error")
            }
            task.semanticRequestId?.let { registrations.remove(it, task) }
            if (!isNotification(request)) respond(response)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        registrations.values.forEach { it.cancellation.cancel() }
        worker.interrupt()
        worker.join(2_000)
        queue.clear()
        registrations.clear()
    }

    private fun priority(value: String?): Int = when (value ?: "interactive") {
        "interactive" -> 0
        "normal" -> 1
        "background" -> 2
        else -> 1 // DaemonSession performs typed protocol validation.
    }

    private fun cancelledResponse(request: JsonRpcRequest) = errorResponse(
        request.id, JsonRpcErrorCodes.REQUEST_CANCELLED, "Request cancelled",
    )

    private data class ScheduledRequest(
        val request: JsonRpcRequest,
        val params: JsonObject?,
        val cancellation: CancellationSource,
        val semanticRequestId: String?,
        val group: Long,
        val priority: Int,
        val sequence: Long,
    ) : Comparable<ScheduledRequest> {
        override fun compareTo(other: ScheduledRequest): Int =
            compareValuesBy(this, other, ScheduledRequest::group, ScheduledRequest::priority, ScheduledRequest::sequence)
    }

    private class CancellationSource : SemanticCancellationToken {
        private val cancelled = AtomicBoolean()
        override fun isCancellationRequested(): Boolean = cancelled.get()
        fun cancel() = cancelled.set(true)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private companion object {
        const val CONTROL_PRIORITY = 3
    }
}
