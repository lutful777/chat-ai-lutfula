package com.example.data

import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

class AppwriteMemoryRepository(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    private data class Config(
        val endpoint: String,
        val projectId: String,
        val databaseId: String,
        val tableId: String
    ) {
        // New Appwrite TablesDB API. This matches the console UI labels: Tables, Rows, Columns.
        val rowsUrl: String
            get() = "$endpoint/tablesdb/${encode(databaseId)}/tables/${encode(tableId)}/rows"

        // Legacy Appwrite Databases API fallback for older projects.
        val legacyDocumentsUrl: String
            get() = "$endpoint/databases/${encode(databaseId)}/collections/${encode(tableId)}/documents"
    }

    private data class RemoteMemory(
        val rowId: String,
        val memory: MemoryEntity
    )

    suspend fun getAllMemories(): List<MemoryEntity> {
        return try {
            getRemoteMemories().map { it.memory }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveMemory(memory: MemoryEntity) {
        try {
            val config = getConfig() ?: return
            val data = JSONObject()
                .put("content", memory.content)
                .put("category", memory.category)
                .put("createdAt", memory.createdAt)
                .put("updatedAt", memory.updatedAt)
                .put("source", memory.source)
                .put("isPinned", memory.isPinned)

            val rowsPayload = JSONObject()
                .put("rowId", "unique()")
                .put("data", data)

            val rowsCreated = postJson(config.rowsUrl, rowsPayload, config)
            if (rowsCreated) return

            // Fallback for older Appwrite projects that still use documents.
            val documentsPayload = JSONObject()
                .put("documentId", "unique()")
                .put("data", data)

            postJson(config.legacyDocumentsUrl, documentsPayload, config)
        } catch (_: Exception) {
            // Cloud memory must never break local chat memory.
        }
    }

    suspend fun deleteAllMemories() {
        try {
            getRemoteMemories().forEach { remote ->
                deleteRow(remote.rowId)
            }
        } catch (_: Exception) {
            // Ignore cloud delete failures so local delete can still complete.
        }
    }

    suspend fun deleteMemoriesByContent(query: String) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return

        try {
            getRemoteMemories()
                .filter { it.memory.content.lowercase().contains(normalizedQuery) }
                .forEach { remote -> deleteRow(remote.rowId) }
        } catch (_: Exception) {
            // Ignore cloud delete failures so local delete can still complete.
        }
    }

    private suspend fun getRemoteMemories(): List<RemoteMemory> {
        val config = getConfig() ?: return emptyList()

        val rowsResult = getRemoteMemoriesFromUrl(
            url = "${config.rowsUrl}?limit=100",
            arrayKey = "rows",
            idKey = "\$id",
            source = "appwrite"
        )
        if (rowsResult != null) return rowsResult

        return getRemoteMemoriesFromUrl(
            url = "${config.legacyDocumentsUrl}?limit=100",
            arrayKey = "documents",
            idKey = "\$id",
            source = "appwrite"
        ).orEmpty()
    }

    private suspend fun getRemoteMemoriesFromUrl(
        url: String,
        arrayKey: String,
        idKey: String,
        source: String
    ): List<RemoteMemory>? {
        val config = getConfig() ?: return emptyList()
        val request = Request.Builder()
            .url(url)
            .addAppwriteHeaders(config)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val rows = JSONObject(body).optJSONArray(arrayKey) ?: return emptyList()
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val content = row.optString("content", "").trim()
                    if (content.isBlank()) continue

                    add(
                        RemoteMemory(
                            rowId = row.optString(idKey, ""),
                            memory = MemoryEntity(
                                content = content,
                                category = row.optString("category", "manual"),
                                createdAt = row.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = row.optLong("updatedAt", System.currentTimeMillis()),
                                source = row.optString("source", source),
                                isPinned = row.optBoolean("isPinned", false)
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun deleteRow(rowId: String) {
        if (rowId.isBlank()) return
        val config = getConfig() ?: return

        val deletedFromRows = deleteUrl("${config.rowsUrl}/${encode(rowId)}", config)
        if (deletedFromRows) return

        deleteUrl("${config.legacyDocumentsUrl}/${encode(rowId)}", config)
    }

    private fun postJson(url: String, payload: JSONObject, config: Config): Boolean {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .addAppwriteHeaders(config)
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun deleteUrl(url: String, config: Config): Boolean {
        val request = Request.Builder()
            .url(url)
            .addAppwriteHeaders(config)
            .delete()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private suspend fun getConfig(): Config? {
        if (!settingsRepository.appwriteMemoryEnabled.first()) return null

        val endpoint = settingsRepository.appwriteEndpoint.first().trim().trimEnd('/')
        val projectId = settingsRepository.appwriteProjectId.first().trim()
        val databaseId = settingsRepository.appwriteDatabaseId.first().trim()
        val tableId = settingsRepository.appwriteMemoryCollectionId.first().trim()

        if (endpoint.isBlank() || projectId.isBlank() || databaseId.isBlank() || tableId.isBlank()) {
            return null
        }

        return Config(
            endpoint = endpoint,
            projectId = projectId,
            databaseId = databaseId,
            tableId = tableId
        )
    }

    private fun Request.Builder.addAppwriteHeaders(config: Config): Request.Builder {
        return addHeader("X-Appwrite-Project", config.projectId)
            .addHeader("X-Appwrite-Response-Format", "1.6.0")
            .addHeader("Content-Type", "application/json")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
