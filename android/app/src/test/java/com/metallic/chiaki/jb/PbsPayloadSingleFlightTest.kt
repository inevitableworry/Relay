// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PbsPayloadSingleFlightTest {
    @Test fun overlappingConnectsProbeAgainAndLoadOnlyOnce() = runBlocking {
        val launch = CompletableDeferred<Unit>()
        var serviceAvailable = false
        var uploads = 0
        val outcomes = coroutineScope {
            (1..4).map {
                async(Dispatchers.Default) {
                    launch.await()
                    PbsPayloadSingleFlight.withExclusiveStart {
                        if(serviceAvailable) "already running"
                        else {
                            delay(30)
                            uploads++
                            serviceAvailable = true
                            "started"
                        }
                    }
                }
            }.also {launch.complete(Unit)}.awaitAll()
        }
        assertEquals(1, uploads)
        assertEquals(1, outcomes.count {it == "started"})
        assertEquals(3, outcomes.count {it == "already running"})
    }
}
