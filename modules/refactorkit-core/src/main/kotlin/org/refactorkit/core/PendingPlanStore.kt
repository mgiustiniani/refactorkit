package org.refactorkit.core

import java.util.LinkedHashMap

/**
 * Session-owned, in-memory retention for exact pending-plan payloads.
 *
 * This language-neutral primitive implements only the bounded access-order mechanics required by
 * REQ-PENDING-PLAN-STORE-001 and REQ-PENDING-PLAN-STORE-002. Admission, apply, refusal, rollback,
 * diagnostics, and lifecycle policy remain with the owning integration session. A successful
 * [lookup] refreshes recency. The store is intentionally not synchronized because daemon, LSP,
 * and MCP sessions serialize access.
 */
class PendingPlanStore<T : Any>(
    private val capacity: Int = ProtocolLimits.MAX_PENDING_PLANS,
) {
    init {
        require(capacity in 1..ProtocolLimits.MAX_PENDING_PLANS) {
            "Pending-plan capacity must be between 1 and ${ProtocolLimits.MAX_PENDING_PLANS}"
        }
    }

    private val retained = object : LinkedHashMap<PlanId, T>(minOf(capacity, 64), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlanId, T>?): Boolean =
            size > capacity
    }

    fun insert(planId: PlanId, payload: T) {
        requireValidIdentity(planId)
        retained[planId] = payload
    }

    fun lookup(planId: PlanId): T? {
        requireValidIdentity(planId)
        return retained[planId]
    }

    fun remove(planId: PlanId): T? {
        requireValidIdentity(planId)
        return retained.remove(planId)
    }

    fun clear() {
        retained.clear()
    }

    private fun requireValidIdentity(planId: PlanId) {
        require(planId.value.isNotBlank()) { "Pending-plan ID must not be blank" }
    }
}
