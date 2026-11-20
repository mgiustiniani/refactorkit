package org.refactorkit.daemon.requestisolation

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.JsonRpcResponse
import org.refactorkit.daemon.DaemonRequestScheduler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DaemonRequestErrorIsolationSteps {
    private val dispatcherFailed = AtomicBoolean(false)
    private val responses = LinkedBlockingQueue<JsonRpcResponse>()
    private lateinit var scheduler: DaemonRequestScheduler
    private var failedRequestResponse: JsonRpcResponse? = null

    @Given("a daemon request dispatcher raises an assertion error once")
    fun dispatcherRaisesAssertionErrorOnce() {
        scheduler = DaemonRequestScheduler({ method, _, _ ->
            if (method == "intelligence.query" && dispatcherFailed.compareAndSet(false, true)) {
                throw AssertionError("simulated non-fatal query handler failure")
            }
            JsonPrimitive("follow-up-ok")
        }, responses::offer)
    }

    @When("the failing request and a valid follow-up are submitted to one daemon scheduler")
    fun submitFailingRequestAndFollowUp() {
        scheduler.submit(JsonRpcRequest(
            id = JsonPrimitive("query-failure"),
            method = "intelligence.query",
            params = buildJsonObject {
                put("requestId", "failing-query")
                put("priority", "interactive")
            },
        ))
        scheduler.submit(JsonRpcRequest(id = JsonPrimitive("follow-up"), method = "project.summary"))
    }

    @Then("the failing request receives a JSON-RPC internal error response")
    fun failingRequestReceivesInternalError() {
        failedRequestResponse = responses.poll(2, TimeUnit.SECONDS)
        assertNotNull(failedRequestResponse, "the failed request must receive a response")
        assertEquals(JsonPrimitive("query-failure"), failedRequestResponse!!.id)
        assertEquals(JsonRpcErrorCodes.INTERNAL_ERROR, failedRequestResponse!!.error?.code)
    }

    @And("the follow-up receives a success response from the same scheduler")
    fun followUpSucceedsOnSameScheduler() {
        val followUpResponse = responses.poll(2, TimeUnit.SECONDS)
        assertNotNull(followUpResponse, "the same daemon worker must process the follow-up")
        assertEquals(JsonPrimitive("follow-up"), followUpResponse!!.id)
        assertEquals(JsonPrimitive("follow-up-ok"), followUpResponse!!.result)
        assertTrue(dispatcherFailed.get())
    }

    @After
    fun closeScheduler() {
        if (::scheduler.isInitialized) scheduler.close()
    }
}
