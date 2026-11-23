// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.util.ArrayDeque

/** Tracks real ordered native acknowledgements, not just bytes handed to a TCP socket. */
internal class PbsRemoteInputWindow(private val capacity: Int = 32, private val timeoutMs: Long = 6000) {
    private data class Pending(val sequence: Long, val sentAt: Long)
    private val pending = ArrayDeque<Pending>()
    init { require(capacity in 1..32 && timeoutMs in 1000..10000) }
    @Synchronized fun hasCapacity(): Boolean = pending.size < capacity
    @Synchronized fun expired(nowMs: Long): Boolean = pending.peekFirst()?.let { nowMs - it.sentAt >= timeoutMs } == true
    @Synchronized fun sent(sequence: Long, nowMs: Long) {
        require(sequence in 0..0xffffffffL && pending.size < capacity)
        pending.addLast(Pending(sequence, nowMs))
    }
    @Synchronized fun acknowledge(sequence: Long): Boolean {
        if (pending.peekFirst()?.sequence != sequence) return false
        pending.removeFirst()
        return true
    }
    @Synchronized fun clear() { pending.clear() }
}
