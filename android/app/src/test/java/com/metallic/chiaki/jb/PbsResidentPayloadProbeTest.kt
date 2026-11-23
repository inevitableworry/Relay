// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.assertEquals
import org.junit.Test

class PbsResidentPayloadProbeTest {
    @Test fun blocksWhenExistingPayloadIsStillResident() {
        val response = """{"processes":[{"pid":100,"name":"payload.elf","is_daemon":true},
                            {"pid":92,"name":"elfldr.elf"}]}"""
        assertEquals(PbsResidentPayloadProbe.Result.PRESENT,PbsResidentPayloadProbe.parse(response))
    }

    @Test fun emptyListIsDistinctFromUnavailableProcessManager() {
        assertEquals(PbsResidentPayloadProbe.Result.ABSENT,
            PbsResidentPayloadProbe.parse("""{"processes":[{"name":"elfldr.elf"}]}"""))
        for(body in listOf("""{}""","""{"processes":null}""",
            """{"processes":[null]}""","""{"processes":[{}]}""",
            """{"processes":{}}""","error")) {
            assertEquals(PbsResidentPayloadProbe.Result.UNAVAILABLE,
                PbsResidentPayloadProbe.parse(body))
        }
    }

    @Test fun recognizesKnownPBSExecutableNamesCaseInsensitively() {
        for (name in listOf("PBS.ELF","pbs-ps5.elf","Payload.elf")) {
            assertEquals(PbsResidentPayloadProbe.Result.PRESENT,
                PbsResidentPayloadProbe.parse("""{"processes":[{"name":"$name"}]}"""))
        }
    }
}
