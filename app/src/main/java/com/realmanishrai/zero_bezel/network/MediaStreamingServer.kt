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
    private val context: android.content.Context
) : NanoHTTPD(MEDIA_HTTP_PORT) {
    private val contentResolver = context.contentResolver

    @Volatile
    private var folderUri: Uri? = null

    fun setFolder(uri: Uri?) {
        folderUri = uri
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/list" -> serveList()
                session.uri == "/test" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Server is working!")
                session.uri == "/viewer.html" -> serveViewerHtml(session)
                session.uri.startsWith("/file/") -> serveFile(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found"
                )
            }.withCors()
        } catch (exception: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                exception.message ?: "Server error"
            ).withCors()
        }
    }

    private fun serveList(): Response {
        val files = listFiles()
        println("📋 Serving file list: ${files.size} files")
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

        val jsonString = json.toString()
        println("📋 File list JSON: $jsonString")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            jsonString
        )
    }

    private fun serveFile(session: IHTTPSession): Response {
        val encodedName = session.uri.removePrefix("/file/")
        val fileName = decode(encodedName)
        val file = listFiles().firstOrNull { it.name == fileName }
        
        println("📁 Serving file: $fileName")
        println("📁 Uri: ${file?.uri}")
        println("📁 Size: ${file?.size}")
        println("📁 File exists: ${file != null}")

        if (file == null) {
            println("❌ File not found: $fileName")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "File not found: $fileName. Available files: ${listFiles().map { it.name }.joinToString()}"
            )
        }

        val rangeHeader = session.headers["range"]
        val range = parseRange(rangeHeader, file.size)
        
        val pfd = try {
            contentResolver.openFileDescriptor(file.uri, "r")
        } catch (e: Exception) {
            println("❌ Failed to open file descriptor: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Unable to open file: ${e.message}"
            )
        } ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            MIME_PLAINTEXT,
            "Unable to open file - null ParcelFileDescriptor"
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
        println("✅ Serving file successfully: $fileName (${range.length} bytes)")
        return response
    }

    private fun serveViewerHtml(session: IHTTPSession): Response {
        val role = session.parameters["role"]?.firstOrNull()
        if (role == null) {
            val remoteIp = session.remoteIpAddress
            val isHost = remoteIp == "127.0.0.1" || remoteIp == "0:0:0:0:0:0:0:1" || remoteIp == "localhost"
            val detectedRole = if (isHost) "host" else "client"
            val queryString = session.queryParameterString
            val redirectUri = if (queryString.isNullOrBlank()) {
                "${session.uri}?role=$detectedRole"
            } else {
                "${session.uri}?$queryString&role=$detectedRole"
            }
            val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "")
            response.addHeader("Location", redirectUri)
            return response
        }

        return try {
            val inputStream = context.assets.open("viewer.html")
            val htmlContent = inputStream.bufferedReader().use { it.readText() }
            println("✅ Serving viewer.html (${htmlContent.length} bytes)")
            newFixedLengthResponse(Response.Status.OK, "text/html", htmlContent)
        } catch (e: Exception) {
            println("❌ Failed to serve viewer.html: ${e.message}")
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "viewer.html not found in assets: ${e.message}"
            )
        }
    }

    private fun Response.withCors(): Response {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
        addHeader("Content-Disposition", "inline")
        return this
    }

    fun listFiles(): List<ServedMediaFile> {
        val treeUri = folderUri ?: run {
            println("⚠️ No folder URI set")
            return emptyList()
        }
        println("📂 Listing files from URI: $treeUri")
        
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
        val cursor = contentResolver.query(childrenUri, projection, null, null, null)
        if (cursor == null) {
            println("❌ Failed to query content resolver - cursor is null")
            return emptyList()
        }
        
        cursor.use { 
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            println("📊 Cursor count: ${it.count}")
            while (it.moveToNext()) {
                val mimeType = it.getString(mimeIndex) ?: "application/octet-stream"
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val displayName = it.getString(nameIndex)
                if (!displayName.isSupportedForMilestone()) {
                    println("⚠️ Skipping unsupported file: $displayName")
                    continue
                }

                val documentId = it.getString(idIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val size = it.getLong(sizeIndex).coerceAtLeast(0L)
                println("📄 Found file: $displayName ($size bytes, $mimeType)")
                files += ServedMediaFile(
                    name = displayName,
                    uri = documentUri,
                    size = size,
                    mimeType = displayName.toMimeType(mimeType)
                )
            }
        }

        println("✅ Total files found: ${files.size}")
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
