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
 * 通过 SAF 向系统文件管理器暴露两个目录：
 *
 * - `game`：filesDir/pvzge_web-master/docs，即游戏网页根目录；
 * - `gpnext`：filesDir/gp-next，即 gp-next 的数据目录（数据包与 JS 模组）。
 *
 * Document ID 格式为 `<rootId>:<相对路径>`，例如：
 *   game:index.html             → docs/index.html
 *   game:assets/main/index.js   → docs/assets/main/index.js
 *   gpnext:packs/Foo/pack.json  → gp-next/packs/Foo/pack.json
 */
class GameDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "game"

        /** gp-next 数据目录的文档根 id，供 GameActivity 打开该目录时引用 */
        const val GP_NEXT_ROOT_ID = "gpnext"

        private const val GP_NEXT_DIR_NAME = "gp-next"
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

    /** gp-next 数据目录，与 GameActivity.gpNextDir 指向同一位置 */
    private val gpNextDir: File by lazy {
        File(context!!.filesDir, GP_NEXT_DIR_NAME).apply { mkdirs() }
    }

    // ── docId ↔ File ──

    private fun rootDirFor(rootId: String): File =
        if (rootId == GP_NEXT_ROOT_ID) gpNextDir else gameDir

    /** 判断文件位于哪个根下，fileToDocId 据此决定 docId 前缀 */
    private fun rootOf(file: File): File {
        val path = file.canonicalPath
        val gpNextRoot = gpNextDir.canonicalPath
        return if (path == gpNextRoot || path.startsWith(gpNextRoot + File.separator)) {
            gpNextDir
        } else {
            gameDir
        }
    }

    private fun docIdToFile(docId: String, mustExist: Boolean = true): File {
        val sep = docId.indexOf(':')
        if (sep < 0) throw FileNotFoundException("Invalid docId: $docId")
        val root = rootDirFor(docId.substring(0, sep))
        val relative = docId.substring(sep + 1)
        val file = if (relative.isEmpty()) root else File(root, relative)
        // 防止目录穿越：解析结果必须仍位于对应的 root 内
        if (!isInside(root, file)) throw FileNotFoundException("Invalid docId: $docId")
        if (mustExist && !file.exists()) throw FileNotFoundException(file.absolutePath)
        return file
    }

    private fun fileToDocId(file: File): String {
        val root = rootOf(file)
        val rootId = if (root === gpNextDir) GP_NEXT_ROOT_ID else ROOT_ID
        return "$rootId:${file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")}"
    }

    private fun isInside(root: File, file: File): Boolean =
        file.canonicalPath.startsWith(root.canonicalPath + File.separator) ||
                file.canonicalPath == root.canonicalPath

    /** 两个根的根目录都不允许被删除 */
    private fun isRootDir(file: File): Boolean =
        file.canonicalPath == gameDir.canonicalPath ||
                file.canonicalPath == gpNextDir.canonicalPath

    // ── DocumentsProvider 核心 ──

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context!!

        fun addRoot(rootId: String, dir: File, titleRes: Int, summaryRes: Int) {
            result.newRow()
                .add(Root.COLUMN_ROOT_ID, rootId)
                .add(Root.COLUMN_DOCUMENT_ID, "$rootId:")
                .add(Root.COLUMN_TITLE, ctx.getString(titleRes))
                .add(Root.COLUMN_SUMMARY, ctx.getString(summaryRes))
                .add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
                .add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or
                            Root.FLAG_SUPPORTS_IS_CHILD
                )
        }

        addRoot(ROOT_ID, gameDir, R.string.documents_root_title, R.string.documents_root_summary)
        addRoot(
            GP_NEXT_ROOT_ID, gpNextDir,
            R.string.documents_gpnext_root_title, R.string.documents_gpnext_root_summary
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
        val pending = LinkedList<File>().apply { add(rootDirFor(rootId)) }

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

    /** 根目录的 docId 形如 "game:" / "gpnext:"（没有相对路径部分） */
    private fun isRootDocId(docId: String): Boolean =
        docId == "$ROOT_ID:" || docId == "$GP_NEXT_ROOT_ID:"

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (!documentId.startsWith(parentDocumentId)) return false
        // 根目录的 docId 以 ':' 结尾，其后直接跟子项名，没有 '/' 分隔符可匹配
        if (isRootDocId(parentDocumentId)) return true
        // 其余情况父子之间一定有 '/'，用它排除 "game:a" 与 "game:abc" 这类前缀误判
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
        if (isRootDir(file)) {
            throw UnsupportedOperationException(
                context!!.getString(R.string.documents_error_delete_root)
            )
        }
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete: $documentId")
        }
    }

    // ── 辅助 ──

    private fun includeFile(result: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRootDir(file)) {
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
