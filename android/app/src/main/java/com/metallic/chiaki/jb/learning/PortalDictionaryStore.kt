// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Explicit import into app-private storage; reads and writes should run off the UI thread. */
class PortalDictionaryStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "relay_word_learning")

    fun load(language: String): PortalWordDictionary {
        val path = target(language)
        if (!path.isFile) return PortalWordDictionary.empty(language)
        return path.inputStream().reader(StandardCharsets.UTF_8).use {
            PortalWordDictionary.importTsv(language, it).dictionary
        }
    }

    /** Imports only after a user selects a local, licensed TSV file via their app UI. */
    fun importTsv(language: String, input: InputStream): PortalDictionaryImport {
        val imported = input.reader(StandardCharsets.UTF_8).use {
            PortalWordDictionary.importTsv(language, it)
        }
        require(imported.dictionary.entries.isNotEmpty()) { "Dictionary contains no valid entries" }
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create local dictionary directory" }
        val temp = File.createTempFile("import-", ".tsv", dir)
        try {
            temp.bufferedWriter(StandardCharsets.UTF_8).use { output ->
                output.appendLine("word\treading\tdefinition")
                imported.dictionary.entries.values.forEach { row ->
                    output.append(row.word).append('\t')
                        .append(row.reading.orEmpty()).append('\t').appendLine(row.meaning)
                }
            }
            check(temp.renameTo(target(language))) { "Cannot finish dictionary import" }
        } finally {
            temp.delete()
        }
        return imported
    }

    /** Explicit deletion of the selected local dictionary only. */
    fun delete(language: String): Boolean = target(language).let { !it.exists() || it.delete() }

    private fun target(language: String): File =
        File(dir, PortalWordDictionary.normalizeLanguage(language) + ".tsv")
}
