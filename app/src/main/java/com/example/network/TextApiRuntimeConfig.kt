package com.example.network

internal data class TextApiConfigSnapshot(
    val apiKey: String,
    val baseUrl: String,
    val path: String,
    val model: String
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    val endpoint: String
        get() {
            val cleanedBaseUrl = baseUrl.trimEnd('/')
            val cleanedPath = if (path.startsWith('/')) path else "/$path"
            return "$cleanedBaseUrl$cleanedPath"
        }
}

internal object TextApiRuntimeConfig {
    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var path: String = "/chat/completions"

    @Volatile
    private var model: String = ""

    fun updateApiKey(value: String) {
        apiKey = value
    }

    fun updateBaseUrl(value: String) {
        baseUrl = value
    }

    fun updatePath(value: String) {
        path = value.ifBlank { "/chat/completions" }
    }

    fun updateModel(value: String) {
        model = value
    }

    fun snapshot(): TextApiConfigSnapshot = TextApiConfigSnapshot(
        apiKey = apiKey,
        baseUrl = baseUrl,
        path = path,
        model = model
    )
}
