package com.banga.zip

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Creates and extracts 7z archives using Apache Commons Compress.
 *
 * All operations use **COPY** (no compression) so files are stored
 * verbatim while still benefiting from the 7z container format and
 * AES-256 password encryption.
 */
class ArchiveHelper {

    /**
     * Archive a folder tree into a .7z file (store mode, no compression).
     *
     * @param sourceFolder  absolute path to the directory to archive.
     * @param archivePath   absolute path where the .7z file will be created.
     * @param password      optional AES-256 password (null = no encryption).
     * @param onProgress    called sequentially on the calling thread as
     *                      each file/directory is written. Params:
     *                      (processedCount, totalCount, currentFileName).
     */
    fun archiveFolder(
        sourceFolder: String,
        archivePath: String,
        password: String? = null,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    ) {
        val sourceDir = File(sourceFolder)
        require(sourceDir.exists() && sourceDir.isDirectory) {
            "Source folder does not exist or is not a directory: $sourceFolder"
        }

        // Collect every entry (files + dirs) for accurate progress.
        val allEntries = mutableListOf<File>()
        collectAll(sourceDir, allEntries)
        val totalCount = allEntries.size

        val archiveFile = File(archivePath)

        // Create parent directories automatically.
        archiveFile.parentFile?.mkdirs()

        val pwd = if (password.isNullOrEmpty()) null else password.toCharArray()

        val out = SevenZOutputFile(archiveFile, pwd)
        try {
            // Set default compression to COPY — no compression, just store.
            out.setContentCompression(SevenZMethod.COPY)

            var processed = 0

            // Always place the root directory as the first entry.
            val rootEntry = out.createArchiveEntry(sourceDir, sourceDir.name)
            out.putArchiveEntry(rootEntry)
            out.closeArchiveEntry()
            processed++
            onProgress(processed, totalCount, sourceDir.name)

            for (file in allEntries) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("Archiving cancelled by user")
                }
                val relativePath = sourceDir.toURI().relativize(file.toURI()).path
                val entryPath = "${sourceDir.name}/$relativePath"

                val entry = out.createArchiveEntry(file, entryPath)
                out.putArchiveEntry(entry)

                if (!file.isDirectory) {
                    FileInputStream(file).use { input ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (input.read(buf).also { len = it } >= 0) {
                            if (Thread.currentThread().isInterrupted) {
                                throw CancellationException("Archiving cancelled by user")
                            }
                            out.write(buf, 0, len)
                        }
                    }
                }

                out.closeArchiveEntry()
                processed++
                onProgress(processed, totalCount, file.name)
            }
        } finally {
            out.close()
        }
    }

    /**
     * Extract a .7z archive to a destination folder.
     *
     * @param archivePath       absolute path to the .7z file.
     * @param destinationFolder absolute path of the output directory
     *                          (created if it doesn't exist).
     * @param password          optional AES-256 password.
     * @param onProgress        called sequentially as each entry is
     *                          extracted.
     */
    fun extractArchive(
        archivePath: String,
        destinationFolder: String,
        password: String? = null,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    ) {
        val archiveFile = File(archivePath)
        require(archiveFile.exists()) {
            "Archive file not found: $archivePath"
        }

        val destDir = File(destinationFolder)
        destDir.mkdirs()

        val pwd = if (password.isNullOrEmpty()) null else password.toCharArray()

        // First pass: count entries so we can report accurate progress.
        // This only reads archive metadata, not content — very fast.
        val total = countEntries(archiveFile, pwd)

        // Second pass: actually extract.
        var processed = 0
        openArchive(archiveFile, pwd).use { sevenZFile ->
            var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("Extraction cancelled by user")
                }
                val outputFile = File(destDir, entry.name)

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { out ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (sevenZFile.read(buf).also { len = it } >= 0) {
                            if (Thread.currentThread().isInterrupted) {
                                throw CancellationException("Extraction cancelled by user")
                            }
                            out.write(buf, 0, len)
                        }
                    }
                }

                processed++
                onProgress(processed, total, entry.name)
                entry = sevenZFile.nextEntry
            }
        }
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private fun collectAll(dir: File, result: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            result.add(child)
            if (child.isDirectory) {
                collectAll(child, result)
            }
        }
    }

    private fun countEntries(archiveFile: File, password: CharArray?): Int {
        return try {
            var count = 0
            openArchive(archiveFile, password).use { z ->
                while (z.nextEntry != null) count++
            }
            count
        } catch (_: Exception) {
            // Fallback: unknown total → caller can show indeterminate progress.
            0
        }
    }

    /** Open a 7z file using the (non-deprecated) builder API. */
    private fun openArchive(file: File, password: CharArray?): SevenZFile {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null) {
            builder.setPassword(password)
        }
        return builder.get()
    }
}
