package com.realmanishrai.zero_bezel.network

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

class MediaStreamingServer(
    private val context: Context,
    private val treeUriProvider: () -> String?
) : NanoHTTPD(8082) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(response)
            return response
        }

        val response = when (uri) {
            "/list" -> handleList()
            "/pdf_viewer.html" -> serveAsset("pdf_viewer.html", "text/html")
            "/video_player.html" -> serveAsset("video_player.html", "text/html")
            "/image_gallery.html" -> serveAsset("image_gallery.html", "text/html")
            "/file" -> handleFile(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        addCorsHeaders(response)
        return response
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "*")
    }

    private fun serveAsset(fileName: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open(fileName)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error loading asset: ${e.message}")
        }
    }

    private fun handleList(): Response {
        val treeUriStr = treeUriProvider()
        if (treeUriStr.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
        }

        return try {
            val rootUri = Uri.parse(treeUriStr)
            val contentResolver = context.contentResolver
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri,
                DocumentsContract.getTreeDocumentId(rootUri)
            )

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            val jsonArray = JSONArray()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L

                    val type = when {
                        mimeType.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true) || name.endsWith(".gif", true) -> "image"
                        mimeType.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".webm", true) -> "video"
                        mimeType == "application/pdf" || name.endsWith(".pdf", true) -> "pdf"
                        else -> null
                    }

                    if (type != null) {
                        val obj = JSONObject().apply {
                            put("id", docId)
                            put("name", name)
                            put("type", type)
                            put("mime", mimeType)
                            put("size", size)
                        }
                        jsonArray.put(obj)
                    }
                }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "[]")
        }
    }

    private fun handleFile(session: IHTTPSession): Response {
        val params = session.parameters
        val docId = params["id"]?.firstOrNull() ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id parameter"
        )

        val treeUriStr = treeUriProvider() ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No folder selected"
        )

        return try {
            val rootUri = Uri.parse(treeUriStr)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
            val mimeType = context.contentResolver.getType(docUri) ?: getMimeType(docId)

            val rangeHeader = session.headers["range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                serveRangeRequest(docUri, mimeType, rangeHeader)
            } else {
                val inputStream = context.contentResolver.openInputStream(docUri) ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"
                )
                newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveRangeRequest(docUri: Uri, mimeType: String, rangeHeader: String): Response {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(docUri, "r") ?: throw Exception("Failed to open file descriptor")
            val totalLength = pfd.statSize
            val rangeSpec = rangeHeader.substring(6)
            var start = 0L
            var end = totalLength - 1

            val minusIdx = rangeSpec.indexOf('-')
            if (minusIdx > -1) {
                try {
                    val startStr = rangeSpec.substring(0, minusIdx).trim()
                    if (startStr.isNotEmpty()) start = startStr.toLong()
                    val endStr = rangeSpec.substring(minusIdx + 1).trim()
                    if (endStr.isNotEmpty()) end = endStr.toLong()
                } catch (ignored: Exception) {}
            }

            if (start < 0) start = 0
            if (end >= totalLength) end = totalLength - 1
            if (start > end) {
                pfd.close()
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
            }

            val dataLength = end - start + 1
            val fileInputStream = java.io.FileInputStream(pfd.fileDescriptor).apply {
                channel.position(start)
            }

            val autoCloseStream = object : java.io.InputStream() {
                private var closed = false
                override fun read(): Int = fileInputStream.read()
                override fun read(b: ByteArray): Int = fileInputStream.read(b)
                override fun read(b: ByteArray, off: Int, len: Int): Int = fileInputStream.read(b, off, len)
                override fun skip(n: Long): Long = fileInputStream.skip(n)
                override fun available(): Int = fileInputStream.available()
                override fun close() {
                    if (!closed) {
                        closed = true
                        try {
                            fileInputStream.close()
                        } finally {
                            pfd.close()
                        }
                    }
                }
            }

            val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, autoCloseStream, dataLength)
            response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            pfd?.close()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Range Error: ${e.message}")
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
