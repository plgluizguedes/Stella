package com.example

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Drive simulado que GUARDA os arquivos de verdade (por nome e por id), entende o envio simples,
 * o envio em partes (resumable) e a cópia, e pode ser instruído a falhar de formas precisas.
 * Responde por caminho, não pela ordem das chamadas.
 */
internal class FakeDrive(private val server: MockWebServer, val folderId: String) {

    class Entry(
        val id: String,
        val name: String,
        var bytes: ByteArray,
        val createdTime: String,
        var parent: String,
        val mimeType: String = "application/octet-stream"
    )

    private class UploadSession(val name: String, val existingId: String?, val total: Long) {
        val received = ByteArrayOutputStream()
        var finalId: String? = null
    }

    val files: MutableMap<String, Entry> = Collections.synchronizedMap(LinkedHashMap())
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** `Content-Range` (sem o "bytes ") de cada parte de DADOS recebida, na ordem. */
    val chunkRanges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val statusQueries = AtomicInteger()
    val sessionsStarted = AtomicInteger()

    // Falhas instruídas
    @Volatile var failBeforeStoringAtStart: Long? = null
    @Volatile var failAfterStoringAtStart: Long? = null
    @Volatile var expireSessionOnFirstChunk = false
    @Volatile var alwaysFailChunks = false
    @Volatile var failCreatesWhoseNameContains: String? = null
    @Volatile var failCopies = false

    /** Deleting a file whose name contains this answers [failDeleteStatusCode] (default 403: a problem with that file only). */
    @Volatile var failDeletesWhoseNameContains: String? = null
    @Volatile var failDeleteStatusCode = 403

    /** After this many successful deletes the connection drops (like losing the internet). */
    @Volatile var dropConnectionAfterDeletes: Int? = null
    val deletesDone = AtomicInteger()

    /** Força páginas pequenas na listagem da pasta, para exercitar a paginação. */
    @Volatile var listPageSize: Int? = null

    /** Ids de arquivos que estão na lixeira (não aparecem nas listagens). */
    val trashedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val sessions = ConcurrentHashMap<String, UploadSession>()
    private val nextId = AtomicInteger(1)

    val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return try {
                route(request)
            } catch (e: Exception) {
                MockResponse().setResponseCode(500).setBody("""{"error":{"message":"erro no Drive simulado: ${e.message}"}}""")
            }
        }
    }

    /** [createdTime] em ISO-8601 (ex.: 2026-10-01T10:00:00Z); sem ele, usa um horário crescente. */
    fun put(
        name: String,
        bytes: ByteArray,
        createdTime: String? = null,
        parent: String = folderId,
        mimeType: String? = null
    ): String {
        val n = nextId.getAndIncrement()
        val id = "f$n"
        val time = createdTime ?: java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds(n * 60L).toString()
        files[id] = Entry(id, name, bytes, time, parent, mimeType ?: "application/octet-stream")
        return id
    }

    /** Pedidos de apagar arquivo (`DELETE /files/<id>`) recebidos, na ordem. */
    fun deleteRequests(): List<RecordedRequest> = synchronized(requests) { requests.filter { it.method == "DELETE" } }

    fun byName(name: String): Entry? = synchronized(files) { files.values.firstOrNull { it.name == name } }

    fun namesStartingWith(prefix: String): List<String> =
        synchronized(files) { files.values.map { it.name }.filter { it.startsWith(prefix) } }

    fun json(name: String): JSONObject = JSONObject(String(byName(name)!!.bytes, Charsets.UTF_8))

    /** Envios que criam ou trocam arquivo (qualquer `uploadType`). */
    fun uploadRequests(): List<RecordedRequest> = synchronized(requests) {
        requests.filter { (it.method == "POST" || it.method == "PATCH") && it.path.orEmpty().contains("uploadType") }
    }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val pathOnly = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val method = request.method.orEmpty()
        return when {
            method == "GET" && pathOnly == "/files/$folderId" ->
                MockResponse().setResponseCode(200).setBody(
                    """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                )
            method == "GET" && pathOnly == "/files" -> list(query)
            method == "GET" && pathOnly.startsWith("/files/") && query.contains("alt=media") -> download(pathOnly)
            method == "GET" && pathOnly.startsWith("/files/") -> metadata(pathOnly)
            method == "POST" && pathOnly == "/files" && query.contains("uploadType=multipart") -> createMultipart(request)
            method == "POST" && pathOnly == "/files" && query.contains("uploadType=resumable") -> startSession(request, null)
            method == "PATCH" && pathOnly.startsWith("/files/") && query.contains("uploadType=media") ->
                replaceSimple(pathOnly, request)
            method == "PATCH" && pathOnly.startsWith("/files/") && query.contains("uploadType=resumable") ->
                startSession(request, pathOnly.removePrefix("/files/"))
            method == "PUT" && pathOnly.startsWith("/session/") -> handleChunk(request, pathOnly.removePrefix("/session/"))
            method == "POST" && pathOnly.endsWith("/copy") -> copyFile(pathOnly, request)
            method == "DELETE" && pathOnly.startsWith("/files/") -> deleteFile(pathOnly)
            else -> MockResponse().setResponseCode(404).setBody(
                """{"error":{"message":"rota não simulada: $method $path"}}"""
            )
        }
    }

    private fun fileJson(entry: Entry) = JSONObject()
        .put("id", entry.id)
        .put("name", entry.name)
        .put("mimeType", entry.mimeType)
        .put("size", entry.bytes.size.toString())
        .put("createdTime", entry.createdTime)
        .put("modifiedTime", entry.createdTime)

    private fun list(query: String): MockResponse {
        val params = query.split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val q = params["q"].orEmpty()
        val name = Regex("name = '(.*?)' and").find(q)?.groupValues?.get(1)
        val inFolder = synchronized(files) { files.values.filter { it.parent == folderId && it.id !in trashedIds } }
        val body = JSONObject()
        if (name != null) {
            body.put("files", org.json.JSONArray(inFolder.filter { it.name == name }.map { fileJson(it) }))
        } else if (q.contains("'$folderId' in parents")) {
            val pageSize = listPageSize ?: params["pageSize"]?.toIntOrNull() ?: 100
            val start = params["pageToken"]?.toIntOrNull() ?: 0
            val page = inFolder.drop(start).take(pageSize)
            body.put("files", org.json.JSONArray(page.map { fileJson(it) }))
            if (start + pageSize < inFolder.size) body.put("nextPageToken", (start + pageSize).toString())
        } else {
            body.put("files", org.json.JSONArray())
        }
        return MockResponse().setResponseCode(200).setBody(body.toString())
    }

    private fun metadata(pathOnly: String): MockResponse {
        val entry = files[pathOnly.removePrefix("/files/")]
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"File not found"}}""")
        val json = fileJson(entry)
            .put("parents", org.json.JSONArray().put(entry.parent))
            .put("trashed", entry.id in trashedIds)
        return MockResponse().setResponseCode(200).setBody(json.toString())
    }

    private fun download(pathOnly: String): MockResponse {
        val entry = files[pathOnly.removePrefix("/files/")]
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"File not found"}}""")
        return MockResponse().setResponseCode(200).setBody(Buffer().write(entry.bytes))
    }

    private fun parseMultipart(contentType: String, body: ByteArray): Pair<JSONObject, ByteArray> {
        val boundary = "--" + contentType.substringAfter("boundary=").substringBefore(";").trim().trim('"')
        val raw = String(body, Charsets.ISO_8859_1)
        val parts = raw.split(boundary).filter { it.isNotBlank() && it.trim() != "--" }
        fun content(part: String) = part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        val meta = JSONObject(String(content(parts[0]).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        return meta to content(parts[1]).toByteArray(Charsets.ISO_8859_1)
    }

    private fun createMultipart(request: RecordedRequest): MockResponse {
        val (meta, media) = parseMultipart(request.getHeader("Content-Type").orEmpty(), request.body.readByteArray())
        val name = meta.getString("name")
        val failing = failCreatesWhoseNameContains
        if (failing != null && name.contains(failing)) {
            return MockResponse().setResponseCode(500).setBody("""{"error":{"code":500,"message":"falha simulada no envio"}}""")
        }
        val id = put(name, media)
        return MockResponse().setResponseCode(200).setBody("""{"id":"$id","name":"$name"}""")
    }

    private fun replaceSimple(pathOnly: String, request: RecordedRequest): MockResponse {
        val id = pathOnly.removePrefix("/files/")
        val entry = files[id] ?: return MockResponse().setResponseCode(404)
        entry.bytes = request.body.readByteArray()
        return MockResponse().setResponseCode(200).setBody("""{"id":"$id"}""")
    }

    private fun deleteFile(pathOnly: String): MockResponse {
        val id = pathOnly.removePrefix("/files/")
        val entry = files[id]
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"File not found"}}""")
        val failing = failDeletesWhoseNameContains
        if (failing != null && entry.name.contains(failing)) {
            return MockResponse().setResponseCode(failDeleteStatusCode)
                .setBody("""{"error":{"code":$failDeleteStatusCode,"message":"falha simulada ao apagar"}}""")
        }
        val limit = dropConnectionAfterDeletes
        if (limit != null && deletesDone.get() >= limit) {
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        files.remove(id)
        deletesDone.incrementAndGet()
        return MockResponse().setResponseCode(204)
    }

    private fun copyFile(pathOnly: String, request: RecordedRequest): MockResponse {
        if (failCopies) {
            return MockResponse().setResponseCode(500).setBody("""{"error":{"code":500,"message":"falha simulada na cópia"}}""")
        }
        val sourceId = pathOnly.removePrefix("/files/").removeSuffix("/copy")
        val source = files[sourceId] ?: return MockResponse().setResponseCode(404)
        val name = JSONObject(request.body.readUtf8()).getString("name")
        val id = put(name, source.bytes.copyOf())
        return MockResponse().setResponseCode(200).setBody("""{"id":"$id","name":"$name"}""")
    }

    private fun startSession(request: RecordedRequest, existingId: String?): MockResponse {
        val meta = JSONObject(request.body.readUtf8())
        val total = request.getHeader("X-Upload-Content-Length")!!.toLong()
        val sessionId = "s${nextId.getAndIncrement()}"
        sessions[sessionId] = UploadSession(meta.getString("name"), existingId, total)
        sessionsStarted.incrementAndGet()
        return MockResponse().setResponseCode(200).setHeader("Location", server.url("/session/$sessionId").toString())
    }

    private fun progress(session: UploadSession): MockResponse {
        val have = session.received.size().toLong()
        if (have >= session.total) {
            val id = session.finalId ?: run {
                val bytes = session.received.toByteArray()
                val existing = session.existingId?.let { files[it] }
                if (existing != null) {
                    existing.bytes = bytes
                    existing.id
                } else {
                    put(session.name, bytes)
                }
            }
            session.finalId = id
            return MockResponse().setResponseCode(200).setBody("""{"id":"$id","name":"${session.name}"}""")
        }
        val response = MockResponse().setResponseCode(308)
        if (have > 0) response.setHeader("Range", "bytes=0-${have - 1}")
        return response
    }

    private fun handleChunk(request: RecordedRequest, sessionId: String): MockResponse {
        val session = sessions[sessionId] ?: return MockResponse().setResponseCode(404)
        val range = request.getHeader("Content-Range").orEmpty()
        val body = request.body.readByteArray()
        return synchronized(session) {
            if (range.startsWith("bytes */")) {
                statusQueries.incrementAndGet()
                return@synchronized progress(session)
            }
            val spec = range.removePrefix("bytes ")
            chunkRanges.add(spec)
            val start = spec.substringBefore('-').toLong()
            if (expireSessionOnFirstChunk) {
                expireSessionOnFirstChunk = false
                sessions.remove(sessionId)
                return@synchronized MockResponse().setResponseCode(404)
            }
            if (alwaysFailChunks) return@synchronized MockResponse().setResponseCode(503)
            if (failBeforeStoringAtStart == start) {
                failBeforeStoringAtStart = null
                return@synchronized MockResponse().setResponseCode(503)
            }
            val have = session.received.size().toLong()
            if (start > have) {
                return@synchronized MockResponse().setResponseCode(400).setBody("""{"error":{"message":"buraco no envio"}}""")
            }
            val skip = (have - start).toInt()
            if (skip < body.size) session.received.write(body, skip, body.size - skip)
            if (failAfterStoringAtStart == start) {
                failAfterStoringAtStart = null
                return@synchronized MockResponse().setResponseCode(503)
            }
            progress(session)
        }
    }
}

