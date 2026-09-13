// gardendless-android

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package com.fct.gardendless

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.LinkedList

/**
 * SAF DocumentsProvider，把游戏目录暴露给系统文件管理器。
 *
 * 暴露目录：filesDir/pvzge_web-master/docs（即 index.html 所在的网页根目录）
 * Document ID 格式： game:相对路径
 *   例：game:index.html → docs/index.html
 *       game:assets/main/index.js → docs/assets/main/index.js
 */
class GameDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "game"
        private const val ALL_MIME_TYPES = "*/*"
        private const val MAX_SEARCH_RESULTS = 50

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )
    }

    /** 游戏网页根目录 */
    private val gameDir: File by lazy {
        File(context!!.filesDir, "pvzge_web-master/docs").apply { mkdirs() }
    }

    // ── docId ↔ File ──────────────────────────────

    private fun docIdToFile(docId: String, mustExist: Boolean = true): File {
        val relative = relativeOf(docId)
        val file = File(gameDir, relative)
        // 防目录穿越：解析后必须仍在 gameDir 内
        if (!isInsideGameDir(file)) throw FileNotFoundException("Invalid docId: $docId")
        if (mustExist && !file.exists()) throw FileNotFoundException(file.absolutePath)
        return file
    }

    private fun fileToDocId(file: File): String =
        "$ROOT_ID:${file.absolutePath.removePrefix(gameDir.absolutePath).removePrefix("/")}"

    private fun relativeOf(docId: String): String {
        val sep = docId.indexOf(':')
        if (sep < 0) throw FileNotFoundException("Invalid docId: $docId")
        return docId.substring(sep + 1)
    }

    private fun isInsideGameDir(file: File): Boolean =
        file.canonicalPath.startsWith(gameDir.canonicalPath + File.separator) ||
                file.canonicalPath == gameDir.canonicalPath

    private fun isGameDirRoot(file: File): Boolean =
        file.canonicalPath == gameDir.canonicalPath

    // ── DocumentsProvider 核心 ─────────────────────

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context!!
        result.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, "$ROOT_ID:")
            .add(Root.COLUMN_TITLE, ctx.getString(R.string.documents_root_title))
            .add(Root.COLUMN_SUMMARY, ctx.getString(R.string.documents_root_summary))
            .add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
            .add(Root.COLUMN_AVAILABLE_BYTES, gameDir.freeSpace)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or
                        Root.FLAG_SUPPORTS_IS_CHILD
            )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, docIdToFile(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = docIdToFile(parentDocumentId)
        parent.listFiles()?.forEach { includeFile(result, it) }
        return result
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            docIdToFile(documentId, mustExist = !mode.contains("w")),
            ParcelFileDescriptor.parseMode(mode)
        )

    override fun getDocumentType(documentId: String): String = getMimeType(docIdToFile(documentId))

    override fun querySearchDocuments(
        rootId: String, query: String, projection: Array<out String>?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val keyword = query.lowercase()
        val pending = LinkedList<File>().apply { add(gameDir) }

        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            if (file.isDirectory) {
                file.listFiles()?.forEach { pending.add(it) }
            } else if (file.name.lowercase().contains(keyword)) {
                includeFile(result, file)
            }
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (!documentId.startsWith(parentDocumentId)) return false
        return documentId.length == parentDocumentId.length ||
                documentId[parentDocumentId.length] == '/'
    }

    override fun createDocument(
        parentDocumentId: String, mimeType: String, displayName: String
    ): String {
        val parent = docIdToFile(parentDocumentId)
        val name = uniquifyName(parent, displayName)
        val newFile = File(parent, name)

        val created = if (Document.MIME_TYPE_DIR == mimeType) {
            newFile.mkdirs()
        } else {
            try {
                newFile.createNewFile()
            } catch (e: IOException) {
                throw FileNotFoundException("Failed to create ${newFile.path}: ${e.message}")
            }
        }
        if (!created) throw FileNotFoundException("Failed to create: ${newFile.path}")
        return fileToDocId(newFile)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = docIdToFile(documentId)
        val newFile = File(file.parentFile!!, displayName)
        if (newFile.exists()) {
            throw FileNotFoundException(context!!.getString(R.string.documents_error_target_exists))
        }
        if (!file.renameTo(newFile)) {
            throw FileNotFoundException(context!!.getString(R.string.documents_error_rename_failed))
        }
        return fileToDocId(newFile)
    }

    override fun deleteDocument(documentId: String) {
        val file = docIdToFile(documentId)
        if (isGameDirRoot(file)) {
            throw UnsupportedOperationException(
                context!!.getString(R.string.documents_error_delete_root)
            )
        }
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete: $documentId")
        }
    }

    // ── 辅助 ──────────────────────────────────────

    private fun includeFile(result: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isGameDirRoot(file)) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, fileToDocId(file))
        row.add(Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { ROOT_ID })
        row.add(Document.COLUMN_SIZE, file.length())
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(file))
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "")
        if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())?.let { return it }
        }
        return "application/octet-stream"
    }

    /** 同名时追加 " (2)"，避免覆盖已有文件 */
    private fun uniquifyName(parent: File, displayName: String): String {
        if (!File(parent, displayName).exists()) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot >= 0) displayName.substring(0, dot) else displayName
        val ext = if (dot >= 0) displayName.substring(dot) else ""
        var suffix = 2
        while (File(parent, "$base ($suffix)$ext").exists()) suffix++
        return "$base ($suffix)$ext"
    }
}
