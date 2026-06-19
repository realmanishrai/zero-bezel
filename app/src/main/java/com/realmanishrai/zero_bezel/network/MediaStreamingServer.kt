package com.realmanishrai.zero_bezel.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class ServedMediaFile(
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String
)

class MediaStreamingServer(
    private val contentResolver: ContentResolver
) : NanoHTTPD(MEDIA_HTTP_PORT) {
    @Volatile
    private var folderUri: Uri? = null

    fun setFolder(uri: Uri?) {
        folderUri = uri
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/list" -> serveList()
                session.uri.startsWith("/file/") -> serveFile(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found"
                )
            }
        } catch (exception: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                exception.message ?: "Server error"
            )
        }
    }

    private fun serveList(): Response {
        val files = listFiles()
        val json = JSONArray()
        files.forEach { file ->
            json.put(
                JSONObject()
                    .put("name", file.name)
                    .put("encodedName", encode(file.name))
                    .put("size", file.size)
                    .put("type", file.name.toMediaKind())
                    .put("mimeType", file.mimeType)
                    .put("kind", file.name.toMediaKind())
            )
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.toString()
        ).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun serveFile(session: IHTTPSession): Response {
        val encodedName = session.uri.removePrefix("/file/")
        val fileName = decode(encodedName)
        val file = listFiles().firstOrNull { it.name == fileName }
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "File not found"
            )

        val rangeHeader = session.headers["range"]
        val range = parseRange(rangeHeader, file.size)
        val pfd = contentResolver.openFileDescriptor(file.uri, "r")
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Unable to open file"
            )

        val input = ParcelClosingInputStream(pfd)
        input.channel.position(range.start)
        val responseStatus = if (range.isPartial) {
            Response.Status.PARTIAL_CONTENT
        } else {
            Response.Status.OK
        }
        val response = newFixedLengthResponse(
            responseStatus,
            file.name.toMimeType(file.mimeType),
            input,
            range.length
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", range.length.toString())
        if (range.isPartial) {
            response.addHeader(
                "Content-Range",
                "bytes ${range.start}-${range.end}/${file.size}"
            )
        }
        return response
    }

    fun listFiles(): List<ServedMediaFile> {
        val treeUri = folderUri ?: return emptyList()
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            treeDocumentId
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val files = mutableListOf<ServedMediaFile>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeIndex) ?: "application/octet-stream"
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val displayName = cursor.getString(nameIndex)
                if (!displayName.isSupportedForMilestone()) continue

                val documentId = cursor.getString(idIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                files += ServedMediaFile(
                    name = displayName,
                    uri = documentUri,
                    size = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    mimeType = displayName.toMimeType(mimeType)
                )
            }
        }

        return files.sortedBy { it.name.lowercase() }
    }

    private fun parseRange(header: String?, fileSize: Long): ByteRange {
        if (header.isNullOrBlank() || !header.startsWith("bytes=") || fileSize <= 0L) {
            return ByteRange(0L, (fileSize - 1).coerceAtLeast(0L), false)
        }

        val parts = header.removePrefix("bytes=").substringBefore(",").split("-")
        val requestedStart = parts.getOrNull(0)?.toLongOrNull()
        val requestedEnd = parts.getOrNull(1)?.toLongOrNull()

        val start = requestedStart ?: 0L
        val end = (requestedEnd ?: fileSize - 1).coerceAtMost(fileSize - 1)
        return ByteRange(
            start = start.coerceIn(0L, (fileSize - 1).coerceAtLeast(0L)),
            end = end.coerceAtLeast(start),
            isPartial = true
        )
    }

    private fun String.isSupportedForMilestone(): Boolean {
        return lowercase().substringAfterLast('.', "").let { extension ->
            extension in SUPPORTED_EXTENSIONS
        }
    }

    private fun String.toMediaKind(): String {
        return when (lowercase().substringAfterLast('.', "")) {
            "jpg", "jpeg", "png" -> "image"
            "mp4", "mkv", "avi" -> "video"
            "pdf" -> "pdf"
            else -> "other"
        }
    }

    private fun String.toMimeType(fallback: String): String {
        return when (lowercase().substringAfterLast('.', "")) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> fallback
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, Charsets.UTF_8.name())
    }

    private data class ByteRange(
        val start: Long,
        val end: Long,
        val isPartial: Boolean
    ) {
        val length: Long = (end - start + 1).coerceAtLeast(0L)
    }

    private class ParcelClosingInputStream(
        private val pfd: android.os.ParcelFileDescriptor
    ) : FileInputStream(pfd.fileDescriptor) {
        override fun close() {
            super.close()
            pfd.close()
        }
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "mp4", "mkv", "avi")
    }
}
