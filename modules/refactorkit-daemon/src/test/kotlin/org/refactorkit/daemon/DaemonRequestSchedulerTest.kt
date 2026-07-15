package org.refactorkit.daemon

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonRequestSchedulerTest {
    @Test
    fun prioritizesInteractiveQueriesOnlyInsideControlBarriers() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val execution = mutableListOf<String>()
        val responses = LinkedBlockingQueue<org.refactorkit.core.JsonRpcResponse>()
        DaemonRequestScheduler({ method, params, _ ->
            if (method == "block") {
                blocked.countDown()
                release.await(2, TimeUnit.SECONDS)
                execution += "control"
            } else {
                execution += params!!.getValue("requestId").toString().trim('"')
            }
            JsonPrimitive("ok")
        }, responses::add).use { scheduler ->
            scheduler.submit(JsonRpcRequest(id = JsonPrimitive("control"), method = "block"))
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
            scheduler.submit(query("background", "background"))
            scheduler.submit(query("interactive", "interactive"))
            release.countDown()
            repeat(3) { assertTrue(responses.poll(2, TimeUnit.SECONDS) != null) }
        }
        assertEquals(listOf("control", "interactive", "background"), execution)
    }

    @Test
    fun cancellationInterruptsRunningCooperativeQueryAndUsesStandardError() {
        val started = CountDownLatch(1)
        val responses = LinkedBlockingQueue<org.refactorkit.core.JsonRpcResponse>()
        DaemonRequestScheduler({ _, _, cancellation ->
            started.countDown()
            while (!cancellation.isCancellationRequested()) Thread.sleep(5)
            JsonPrimitive("provider-stopped")
        }, responses::add).use { scheduler ->
            scheduler.submit(query("cancel-me", "interactive"))
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(scheduler.cancel("cancel-me"))
            val response = responses.poll(2, TimeUnit.SECONDS)!!
            assertEquals(JsonRpcErrorCodes.REQUEST_CANCELLED, response.error?.code)
        }
    }

    private fun query(requestId: String, priority: String) = JsonRpcRequest(
        id = JsonPrimitive(requestId),
        method = "intelligence.query",
        params = buildJsonObject {
            put("requestId", requestId)
            put("priority", priority)
        },
    )
}
