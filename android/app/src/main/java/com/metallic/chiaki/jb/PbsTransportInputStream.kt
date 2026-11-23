// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException

/** Only failures of the actual network read are transport errors; parsing happens outside this wrapper. */
internal class PbsTransportInputStream(input: InputStream, private val stage: PbsFailureStage): FilterInputStream(input) {
    override fun read(): Int = try { `in`.read() }
        catch(error: IOException) { throw PbsFailures.atIoBoundary(error, stage) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try { `in`.read(bytes, offset, length) }
        catch(error: IOException) { throw PbsFailures.atIoBoundary(error, stage) }
}
