package com.androx.app.core.engine

import com.androx.app.core.model.ConcurrencyPolicy
import com.androx.app.core.model.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * Starts runs and enforces per-Flow policies: skip, queue or replace when the Flow is already running,
 * a run limit per hour, and a cap on how many Flows run at once. Any running Flow can be cancelled.
 * Every outcome, including "skipped because it was already running", is reported through [onRecord].
 */
class RunManager(
    private val scope: CoroutineScope,
    private val engine: FlowEngine,
    private val onRecord: suspend (RunRecord) -> Unit,
    maxAtOnce: Int = 4,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val gate = Semaphore(maxAtOnce.coerceIn(1, 16))
    private val lock = Any()
    private val jobs = HashMap<String, MutableList<Job>>()
    private val queueLocks = HashMap<String, Mutex>()
    private val starts = HashMap<String, ArrayDeque<Long>>()
    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of Flows that have an active run (including queued ones). */
    val running: StateFlow<Set<String>> = _running

    fun isRunning(flowId: String): Boolean = synchronized(lock) { jobs[flowId].orEmpty().any { it.isActive } }

    /** Starts a run. Returns the job, or null when the run was skipped (the skip is recorded). */
    fun submit(flow: Flow, trigger: TriggerInfo = TriggerInfo(), options: ExecuteOptions = ExecuteOptions()): Job? {
        val skipReason: String?
        val job: Job
        synchronized(lock) {
            val now = clock()
            val times = starts.getOrPut(flow.id) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() > HOUR_MS) times.removeFirst()
            val limit = flow.run.runLimitPerHour
            val active = jobs[flow.id].orEmpty().filter { it.isActive }
            skipReason = when {
                limit > 0 && times.size >= limit -> "Skipped: this Flow reached its limit of $limit runs per hour."
                flow.run.concurrency == ConcurrencyPolicy.SKIP && active.isNotEmpty() -> "Skipped: it was already running."
                else -> null
            }
            if (skipReason != null) {
                job = Job()
            } else {
                if (flow.run.concurrency == ConcurrencyPolicy.REPLACE) active.forEach { it.cancel() }
                times.addLast(now)
                val mutex = queueLocks.getOrPut(flow.id) { Mutex() }
                val queued = flow.run.concurrency == ConcurrencyPolicy.QUEUE
                val newJob = scope.launch(start = CoroutineStart.LAZY) {
                    val body: suspend () -> Unit = {
                        gate.withPermit {
                            val record = engine.execute(
                                flow, trigger,
                                options.copy(onCancelled = { partial -> scope.launch { onRecord(partial) } }),
                            )
                            onRecord(record)
                        }
                    }
                    if (queued) mutex.withLock { body() } else body()
                }
                jobs.getOrPut(flow.id) { mutableListOf() }.add(newJob)
                newJob.invokeOnCompletion {
                    synchronized(lock) {
                        jobs[flow.id]?.remove(newJob)
                        if (jobs[flow.id].isNullOrEmpty()) jobs.remove(flow.id)
                        publish()
                    }
                }
                job = newJob
            }
        }
        if (skipReason != null) {
            scope.launch { onRecord(skippedRecord(flow, trigger, skipReason)) }
            return null
        }
        synchronized(lock) { publish() }
        job.start()
        return job
    }

    /** Cancels every active run of a Flow. Returns whether anything was running. */
    fun cancel(flowId: String): Boolean = synchronized(lock) {
        val active = jobs[flowId].orEmpty().filter { it.isActive }
        active.forEach { it.cancel() }
        active.isNotEmpty()
    }

    fun cancelAll() {
        val ids = synchronized(lock) { jobs.keys.toList() }
        ids.forEach { cancel(it) }
    }

    private fun publish() {
        _running.value = jobs.filterValues { list -> list.any { it.isActive } }.keys.toSet()
    }

    private fun skippedRecord(flow: Flow, trigger: TriggerInfo, reason: String) = RunRecord(
        id = UUID.randomUUID().toString(),
        flowId = flow.id,
        flowName = flow.name,
        startedAt = clock(),
        durationMs = 0,
        status = RunStatus.SKIPPED,
        triggerTypeId = trigger.typeId,
        triggerId = trigger.triggerId,
        message = reason,
        logs = listOf(reason),
    )

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
