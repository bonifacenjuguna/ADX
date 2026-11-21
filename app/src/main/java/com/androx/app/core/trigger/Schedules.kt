package com.androx.app.core.trigger

import com.androx.app.core.model.Event
import com.androx.app.core.model.Flow
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerEntry
import com.androx.app.core.model.TriggerIds
import com.androx.app.model.MIN_INTERVAL_MINUTES
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** Next-fire maths for the time-driven triggers. Pure, so it is tested without a device. */
object Schedules {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    val timedTypes: Set<String> = setOf(TriggerIds.SCHEDULE, TriggerIds.INTERVAL, TriggerIds.ONCE, TriggerIds.MONTHLY)

    fun isTimed(typeId: String) = typeId in timedTypes

    /** The next time [event] should fire after [after], or null when it is not time-based or has expired. */
    fun nextFire(event: Event, after: Long, zone: ZoneId): Long? {
        val p = P(event.params)
        return when (event.typeId) {
            TriggerIds.SCHEDULE -> {
                val days = p.ints("days")
                val time = LocalTime.of(p.int("hour", 8).coerceIn(0, 23), p.int("minute", 0).coerceIn(0, 59))
                val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
                for (offset in 0L..7L) {
                    val date = start.plusDays(offset)
                    if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
                    val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
                    if (candidate > after) return candidate
                }
                after + DAY_MS
            }
            TriggerIds.INTERVAL -> {
                val step = p.int("minutes", 60).coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
                val startAt = p.long("startAt")
                val first = startAt + step
                if (after < first) first else startAt + ((after - startAt) / step + 1) * step
            }
            TriggerIds.ONCE -> p.long("atMillis").takeIf { it > after }
            TriggerIds.MONTHLY -> {
                val wanted = p.int("day", 1).coerceIn(1, 31)
                val time = LocalTime.of(p.int("hour", 8).coerceIn(0, 23), p.int("minute", 0).coerceIn(0, 59))
                val first = YearMonth.from(Instant.ofEpochMilli(after).atZone(zone))
                for (m in 0L..13L) {
                    val ym = first.plusMonths(m)
                    val date = ym.atDay(minOf(wanted, ym.lengthOfMonth()))
                    val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
                    if (candidate > after) return candidate
                }
                null
            }
            else -> null
        }
    }

    /** One upcoming time per enabled time-based trigger of a Flow. */
    fun upcoming(flow: Flow, after: Long, zone: ZoneId): List<Pair<TriggerEntry, Long>> =
        if (!flow.enabled || flow.deletedAt != null) emptyList()
        else flow.triggers.filter { it.enabled }.mapNotNull { t -> nextFire(t.event, after, zone)?.let { t to it } }

    /** The earliest upcoming time over all Flows, with its Flow and trigger. Used by "Up next" and the alarm. */
    fun next(flows: List<Flow>, after: Long, zone: ZoneId): Triple<Flow, TriggerEntry, Long>? =
        flows.flatMap { f -> upcoming(f, after, zone).map { (t, at) -> Triple(f, t, at) } }.minByOrNull { it.third }
}
