// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.util.ArrayDeque

/**
 * Keeps short button down/up transitions until the network sender has delivered them.
 * Sticks use their newest values. A small bounded queue prevents stale gameplay after a stall.
 * All times are monotonic milliseconds supplied by the caller, keeping this helper testable.
 */
internal class PbsInputQueue(
    private val capacity: Int = 8,
    private val maximumAgeMs: Long = 160,
    private val minimumHoldMs: Long = 24
) {
    internal data class Sample(val state: PbsProtocol.PadState, internal val epoch: Long)
    private data class Transition(val state: PbsProtocol.PadState, val time: Long)
    private val pending = ArrayDeque<Transition>()
    private var latest = PbsProtocol.PadState()
    private var delivered = PbsProtocol.PadState()
    private var holdUntil = 0L
    private var enabled = true
    private var epoch = 0L
    private var overflow = false

    init {
        require(capacity in 1..32 && maximumAgeMs in 32..500 && minimumHoldMs in 1..64)
    }

    /** Returns true for a digital transition, so the sender can wake immediately. */
    @Synchronized
    fun offer(state: PbsProtocol.PadState, now: Long): Boolean {
        if (!enabled) return false
        val changed = state.buttons != latest.buttons
        if (changed && !overflow) {
            if (pending.size == capacity) {
                // Under overload keep only current intent, never replay an old combo.
                pending.clear()
                overflow = true
                holdUntil = 0
                epoch++
            } else {
                pending.addLast(Transition(state, now))
            }
        }
        latest = state
        return changed
    }

    @Synchronized
    fun next(now: Long): Sample {
        if (!enabled) return Sample(PbsProtocol.PadState(), epoch)
        if (overflow || (pending.peekFirst()?.let { now - it.time > maximumAgeMs } == true)) {
            pending.clear()
            overflow = false
            holdUntil = 0
            epoch++
        }
        val digital = when {
            now < holdUntil -> delivered
            pending.isNotEmpty() -> pending.removeFirst().state
            else -> latest
        }
        // Trigger values travel with their digital edges; stick movement is never queued.
        return Sample(latest.copy(buttons = digital.buttons, l2 = digital.l2, r2 = digital.r2), epoch)
    }

    /**
     * Hold each changed digital state after its HTTP acknowledgement so both the payload's
     * input worker and a 60 Hz game get a chance to observe it, including a release between taps.
     */
    @Synchronized
    fun acknowledged(sample: Sample, now: Long) {
        if (!enabled || sample.epoch != epoch) return
        if (sample.state.buttons != delivered.buttons) holdUntil = now + minimumHoldMs
        delivered = sample.state
    }

    @Synchronized
    fun isCurrent(sample: Sample): Boolean = sample.epoch == epoch

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (!value || value != enabled) clear()
        enabled = value
    }

    /** Lifecycle neutralization, distinct from a normal queued button-up event. */
    @Synchronized
    fun clear() {
        pending.clear()
        latest = PbsProtocol.PadState()
        delivered = PbsProtocol.PadState()
        holdUntil = 0
        overflow = false
        epoch++
    }
}
