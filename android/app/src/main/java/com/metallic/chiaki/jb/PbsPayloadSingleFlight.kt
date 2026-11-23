// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes service probes and ELF upload attempts inside this Android app.
 * The caller must re-probe inside the lock; a successful earlier startup
 * means the next caller connects to the existing service.
 *
 * Separate phones cannot share this in-memory lock. Their final server probes
 * reduce collisions; definitive cross-device once-only execution requires
 * an atomic admission guard on a shared gateway or the PS5 loader itself.
 */
internal object PbsPayloadSingleFlight {
    private val mutex = Mutex()
    suspend fun <T> withExclusiveStart(action: suspend () -> T): T =
        mutex.withLock { action() }
}
