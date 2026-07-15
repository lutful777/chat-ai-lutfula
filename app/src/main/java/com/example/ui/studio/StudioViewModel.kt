package com.example.ui.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.Base64

data class StudioUiState(
    val selectedTab: Int = 0, // 0: Photo, 1: Edit, 2: Video
    val prompt: String = "",
    val generatedMediaUrl: String? = null,
    val generatedVideoUrl: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val selectedImageUri: Uri? = null,
    val videoStatus: String? = null
)

class StudioViewModel(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val applicationContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    fun selectTab(index: Int) {
        _uiState.update {
            it.copy(
                selectedTab = index.coerceIn(0, 2),
                error = null,
                videoStatus = null
            )
        }
    }

    fun updatePrompt(text: String) {
        _uiState.update { it.copy(prompt = text) }
    }

    fun selectImage(uri: Uri?) {
        _uiState.update {
            if (uri != null) {
                // A newly selected image must replace stale generated previews.
                it.copy(
                    selectedImageUri = uri,
                    generatedMediaUrl = null,
                    generatedVideoUrl = null,
                    error = null,
                    videoStatus = null
                )
            } else {
                // Null means "use the generated photo" in the video tab.
                it.copy(
                    selectedImageUri = null,
                    generatedVideoUrl = null,
                    error = null,
                    videoStatus = null
                )
            }
        }
    }

    fun useGeneratedPhoto() {
        _uiState.update {
            it.copy(
                selectedTab = 2,
                selectedImageUri = null,
                generatedVideoUrl = null,
                error = null,
                videoStatus = null
            )
        }
    }

    fun generate() {
        val state = _uiState.value
        if (state.isGenerating) return

        _uiState.update {
            it.copy(
                isGenerating = true,
                error = null,
                videoStatus = null,
                generatedVideoUrl = if (state.selectedTab == 2) null else it.generatedVideoUrl
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (state.selectedTab) {
                    0 -> generateImage(state.prompt)
                    1 -> editImage(state.prompt, state.selectedImageUri)
                    2 -> {
                        val source = state.selectedImageUri
                            ?: state.generatedMediaUrl?.let(Uri::parse)
                        generateVideo(state.prompt, source)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Media generation failed", e)
                val safeMessage = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Network timeout. Please try again."
                    else -> "Generation failed: ${e.localizedMessage ?: "Unknown error"}"
                }
                _uiState.update {
                    it.copy(isGenerating = false, error = safeMessage, videoStatus = null)
                }
            }
        }
    }

    private suspend fun generateImage(prompt: String) {
        if (!validatePrompt(prompt)) return

        val baseUrl = settingsRepository.createPhotoBaseUrl.first().trimEnd('/')
        val endpoint = settingsRepository.createPhotoEndpoint.first().trim('/')
        val apiKey = settingsRepository.createPhotoApiKey.first()
        val model = settingsRepository.createPhotoModel.first()
        val format = settingsRepository.createPhotoFormat.first()
        val economyMode = settingsRepository.economyMode.first()

        if (!validateConfiguration(baseUrl, endpoint, apiKey, "Create Photo")) return

        val requestBody = if (format.equals("multipart", ignoreCase = true)) {
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .apply {
                    if (model.isNotBlank()) addFormDataPart("model", model)
                    if (economyMode) addFormDataPart("n", "1")
                }
                .build()
        } else {
            JSONObject().apply {
                put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                if (economyMode) put("n", 1)
            }.toJsonBody()
        }

        executeImageRequest(
            url = buildUrl(baseUrl, endpoint),
            apiKey = apiKey,
            requestBody = requestBody,
            logLabel = "Create Photo"
        )
    }

    private suspend fun editImage(prompt: String, imageUri: Uri?) {
        if (!validatePrompt(prompt)) return
        if (imageUri == null) {
            fail("Please select a photo first.")
            return
        }

        val baseUrl = settingsRepository.editPhotoBaseUrl.first().trimEnd('/')
        val endpoint = settingsRepository.editPhotoEndpoint.first().trim('/')
        val apiKey = settingsRepository.editPhotoApiKey.first()
        val model = settingsRepository.editPhotoModel.first()
        val format = settingsRepository.editPhotoFormat.first()
        val economyMode = settingsRepository.economyMode.first()

        if (!validateConfiguration(baseUrl, endpoint, apiKey, "Edit Photo")) return

        val requestBody = if (format.equals("multipart", ignoreCase = true)) {
            val tempFile = getFileFromUri(imageUri, economyMode)
            if (tempFile == null) {
                fail("Failed to read selected photo. Please choose another image.")
                return
            }

            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .apply {
                    if (model.isNotBlank()) addFormDataPart("model", model)
                    addFormDataPart(
                        "image",
                        if (economyMode) "image.jpg" else tempFile.name,
                        tempFile.asRequestBody(
                            if (economyMode) JPEG_MEDIA_TYPE else IMAGE_MEDIA_TYPE
                        )
                    )
                }
                .build()
        } else {
            val imageValue = readImageForJson(imageUri, economyMode)
            if (imageValue == null) {
                fail("Failed to read selected photo. Please choose another image.")
                return
            }

            JSONObject().apply {
                put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)

                if (isXai(baseUrl)) {
                    // xAI /v1/images/edits requires application/json and an image object.
                    put(
                        "image",
                        JSONObject()
                            .put("url", imageValue)
                            .put("type", "image_url")
                    )
                } else {
                    put("image", imageValue)
                }
            }.toJsonBody()
        }

        executeImageRequest(
            url = buildUrl(baseUrl, endpoint),
            apiKey = apiKey,
            requestBody = requestBody,
            logLabel = "Edit Photo"
        )
    }

    private suspend fun executeImageRequest(
        url: String,
        apiKey: String,
        requestBody: RequestBody,
        logLabel: String
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "$logLabel API response: ${response.code} $body")
            handleImageResponse(response.code, body)
        }
    }

    private fun handleImageResponse(code: Int, body: String) {
        if (code in 200..299) {
            val imageUrl = extractMediaUrl(body)
            if (imageUrl != null) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generatedMediaUrl = imageUrl,
                        generatedVideoUrl = null,
                        error = null,
                        videoStatus = null
                    )
                }
            } else {
                fail("Failed to extract image from response.")
            }
            return
        }

        fail(mediaHttpError(code, body, isVideo = false))
    }

    private suspend fun generateVideo(prompt: String, imageUri: Uri?) {
        if (!validatePrompt(prompt)) return
        if (imageUri == null) {
            fail("Please select or generate a photo first.")
            return
        }

        val baseUrl = settingsRepository.photoVideoBaseUrl.first().trimEnd('/')
        val createEndpoint = settingsRepository.photoVideoCreateEndpoint.first().trim('/')
        val apiKey = settingsRepository.photoVideoApiKey.first()
        val model = settingsRepository.photoVideoModel.first()
        val format = settingsRepository.photoVideoFormat.first()
        val durationSetting = settingsRepository.photoVideoDuration.first()
        val economyMode = settingsRepository.economyMode.first()
        val duration = (if (economyMode) "5" else durationSetting).toIntOrNull()
            ?.coerceIn(1, 60)

        if (!validateConfiguration(baseUrl, createEndpoint, apiKey, "Photo to Video")) return

        val requestBody = if (format.equals("multipart", ignoreCase = true)) {
            buildVideoMultipartBody(
                imageUri = imageUri,
                prompt = prompt,
                model = model,
                duration = duration,
                economyMode = economyMode
            ) ?: return
        } else {
            val imageValue = readImageForJson(imageUri, economyMode)
            if (imageValue == null) {
                fail("Failed to read selected photo. Please choose another image.")
                return
            }

            JSONObject().apply {
                put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                if (duration != null) put("duration", duration)

                if (isXai(baseUrl)) {
                    put("image", JSONObject().put("url", imageValue))
                } else {
                    put("image", imageValue)
                }
            }.toJsonBody()
        }

        val request = Request.Builder()
            .url(buildUrl(baseUrl, createEndpoint))
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "Photo to Video API response: ${response.code} $body")

            if (response.code !in 200..299) {
                fail(mediaHttpError(response.code, body, isVideo = true))
                return
            }

            val immediateUrl = extractVideoUrl(body)
            if (immediateUrl != null) {
                completeVideo(immediateUrl)
                return
            }

            val requestId = extractRequestId(body)
            val statusEndpoint = settingsRepository.photoVideoStatusEndpoint.first().trim('/')
            if (requestId.isNullOrBlank() || statusEndpoint.isBlank()) {
                fail("Failed to extract Video URL or Request ID from response.")
                return
            }

            _uiState.update { it.copy(videoStatus = "Starting video generation...") }
            pollVideoStatus(requestId, apiKey, baseUrl, statusEndpoint)
        }
    }

    private fun buildVideoMultipartBody(
        imageUri: Uri,
        prompt: String,
        model: String,
        duration: Int?,
        economyMode: Boolean
    ): RequestBody? {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt)

        if (model.isNotBlank()) builder.addFormDataPart("model", model)
        if (duration != null) builder.addFormDataPart("duration", duration.toString())

        val uriString = imageUri.toString()
        if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
            builder.addFormDataPart("image_url", uriString)
        } else {
            val tempFile = getFileFromUri(imageUri, economyMode)
            if (tempFile == null) {
                fail("Failed to read selected photo. Please choose another image.")
                return null
            }
            builder.addFormDataPart(
                "image",
                if (economyMode) "image.jpg" else tempFile.name,
                tempFile.asRequestBody(if (economyMode) JPEG_MEDIA_TYPE else IMAGE_MEDIA_TYPE)
            )
        }

        return builder.build()
    }

    private suspend fun pollVideoStatus(
        requestId: String,
        apiKey: String,
        baseUrl: String,
        statusEndpoint: String
    ) {
        val pollingUrl = endpointWithRequestId(baseUrl, statusEndpoint, requestId)
        _uiState.update { it.copy(videoStatus = "Checking video status...") }

        repeat(MAX_VIDEO_POLL_ATTEMPTS) { index ->
            delay(VIDEO_POLL_INTERVAL_MS)
            val attempt = index + 1
            _uiState.update { it.copy(videoStatus = "Processing video... (Attempt $attempt)") }

            try {
                val request = Request.Builder()
                    .url(pollingUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    Log.d(TAG, "Video status response: ${response.code} $body")

                    if (response.code !in 200..299) {
                        fail("Polling failed: ${response.code}. ${truncate(body)}")
                        return
                    }

                    when (extractStatus(body)) {
                        "completed" -> {
                            val videoUrl = extractVideoUrl(body)
                            if (videoUrl != null) {
                                completeVideo(videoUrl)
                                return
                            }

                            val resultEndpoint =
                                settingsRepository.photoVideoResultEndpoint.first().trim('/')
                            if (resultEndpoint.isNotBlank()) {
                                fetchVideoResult(
                                    requestId,
                                    apiKey,
                                    baseUrl,
                                    resultEndpoint
                                )
                            } else {
                                fail("Completed but no Video URL found.")
                            }
                            return
                        }

                        "failed" -> {
                            fail("Video generation failed. ${extractProviderError(body)}")
                            return
                        }

                        "expired" -> {
                            fail("Video generation request expired. Please generate it again.")
                            return
                        }

                        else -> {
                            val rawStatus = extractRawStatus(body) ?: "processing"
                            _uiState.update {
                                it.copy(videoStatus = "Status: $rawStatus (Attempt $attempt)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Video status polling attempt $attempt failed", e)
                if (attempt == MAX_VIDEO_POLL_ATTEMPTS) {
                    fail("Video status check failed: ${e.localizedMessage ?: "Network error"}")
                    return
                }
            }
        }

        fail("Video is still processing. Please try again later.")
    }

    private suspend fun fetchVideoResult(
        requestId: String,
        apiKey: String,
        baseUrl: String,
        resultEndpoint: String
    ) {
        _uiState.update { it.copy(videoStatus = "Finalizing video...") }
        val resultUrl = endpointWithRequestId(baseUrl, resultEndpoint, requestId)

        try {
            val request = Request.Builder()
                .url(resultUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code !in 200..299) {
                    fail("Failed to fetch result: ${response.code}. ${truncate(body)}")
                    return
                }

                val videoUrl = extractVideoUrl(body)
                if (videoUrl != null) {
                    completeVideo(videoUrl)
                } else {
                    fail("Could not find video URL in result. Provider output: ${truncate(body, 500)}")
                }
            }
        } catch (e: Exception) {
            fail("Failed to fetch result: ${e.localizedMessage ?: "Network error"}")
        }
    }

    fun saveMedia(context: Context, url: String, isVideo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val media = loadMediaBytes(url)
                if (media == null) {
                    showToast(context, "Failed to save: Could not fetch media data")
                    return@launch
                }

                val (bytes, detectedMime) = media
                val mimeType = if (isVideo) {
                    detectedMime.takeIf { it.startsWith("video/") } ?: "video/mp4"
                } else {
                    detectedMime.takeIf { it.startsWith("image/") } ?: "image/png"
                }
                val extension = extensionForMime(mimeType, isVideo)
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "AI_Studio_${System.currentTimeMillis()}.$extension"
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            if (isVideo) Environment.DIRECTORY_MOVIES
                            else Environment.DIRECTORY_PICTURES
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collection = if (isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                }

                val itemUri = resolver.insert(collection, values)
                if (itemUri == null) {
                    showToast(context, "Failed to save: Could not create MediaStore entry")
                    return@launch
                }

                resolver.openOutputStream(itemUri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }

                showToast(context, "Saved to Gallery")
            } catch (e: Exception) {
                showToast(context, "Failed to save: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun getFileFromUri(uri: Uri, economyMode: Boolean): File? {
        return try {
            val tempFile = File.createTempFile(
                "upload_",
                if (economyMode) ".jpg" else ".img",
                applicationContext.cacheDir
            )

            if (economyMode) {
                val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return null
                val scaled = scaleBitmap(bitmap)
                FileOutputStream(tempFile).use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            } else {
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use(input::copyTo)
                } ?: return null
            }

            tempFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create upload file", e)
            null
        }
    }

    private fun getBase64FromUri(uri: Uri, economyMode: Boolean): String? {
        return try {
            if (economyMode) {
                val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return null
                val scaled = scaleBitmap(bitmap)
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                "data:image/jpeg;base64,${
                    Base64.getEncoder().encodeToString(output.toByteArray())
                }"
            } else {
                val mime = applicationContext.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = applicationContext.contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: return null
                "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode selected image", e)
            null
        }
    }

    private fun getBase64FromUrl(url: String): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val mime = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                val bytes = response.body?.bytes() ?: return null
                "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download source image", e)
            null
        }
    }

    private fun readImageForJson(uri: Uri, economyMode: Boolean): String? {
        val value = uri.toString()
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            // Public URLs are supported directly by xAI and most media providers.
            value
        } else {
            getBase64FromUri(uri, economyMode)
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_IMAGE_SIDE && bitmap.height <= MAX_IMAGE_SIDE) {
            return bitmap
        }
        val ratio = minOf(
            MAX_IMAGE_SIDE.toFloat() / bitmap.width,
            MAX_IMAGE_SIDE.toFloat() / bitmap.height
        )
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun extractMediaUrl(json: String): String? {
        return try {
            val obj = JSONObject(json)

            obj.optString("url").takeIf { it.isNotBlank() }?.let { return it }
            obj.optString("image_url").takeIf { it.isNotBlank() }?.let { return it }
            obj.optString("b64_json").takeIf { it.isNotBlank() }?.let {
                return "data:image/png;base64,$it"
            }

            obj.optJSONArray("data")?.firstObject()?.let { item ->
                item.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                item.optString("image_url").takeIf { it.isNotBlank() }?.let { return it }
                item.optString("b64_json").takeIf { it.isNotBlank() }?.let {
                    return "data:image/png;base64,$it"
                }
            }

            when (val output = obj.opt("output")) {
                is String -> output.takeIf { it.isNotBlank() }
                is JSONArray -> output.optString(0).takeIf { it.isNotBlank() }
                is JSONObject -> {
                    output.optString("url").takeIf { it.isNotBlank() }
                        ?: output.optString("image_url").takeIf { it.isNotBlank() }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVideoUrl(json: String): String? {
        return try {
            val obj = JSONObject(json)
            findVideoUrl(obj) ?: findVideoUrlByRegex(json)
        } catch (_: Exception) {
            findVideoUrlByRegex(json)
        }
    }

    private fun findVideoUrl(obj: JSONObject): String? {
        listOf("download_url", "video_url", "url").forEach { key ->
            obj.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }

        when (val video = obj.opt("video")) {
            is String -> video.takeIf { it.isNotBlank() }?.let { return it }
            is JSONObject -> findVideoUrl(video)?.let { return it }
        }

        listOf("assets", "result", "response", "asset").forEach { key ->
            obj.optJSONObject(key)?.let { nested ->
                findVideoUrl(nested)?.let { return it }
            }
        }

        obj.optJSONArray("data")?.firstObject()?.let {
            findVideoUrl(it)?.let { url -> return url }
        }

        when (val output = obj.opt("output")) {
            is String -> output.takeIf { it.isNotBlank() }?.let { return it }
            is JSONArray -> output.optString(0).takeIf { it.isNotBlank() }?.let { return it }
            is JSONObject -> findVideoUrl(output)?.let { return it }
        }

        return null
    }

    private fun findVideoUrlByRegex(text: String): String? {
        val regex = """https?://[^\s\"']+""".toRegex()
        return regex.findAll(text)
            .map { it.value.trimEnd(',', '}', ']') }
            .firstOrNull {
                val lower = it.lowercase()
                lower.contains(".mp4") ||
                    lower.contains("video") ||
                    lower.contains("download") ||
                    lower.contains("asset")
            }
    }

    private fun extractRequestId(json: String): String? {
        return try {
            val obj = JSONObject(json)
            listOf("request_id", "task_id", "id")
                .firstNotNullOfOrNull { key ->
                    obj.optString(key).takeIf { it.isNotBlank() }
                }
                ?: obj.optJSONObject("data")?.let { nested ->
                    listOf("request_id", "task_id", "id")
                        .firstNotNullOfOrNull { key ->
                            nested.optString(key).takeIf { it.isNotBlank() }
                        }
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractRawStatus(json: String): String? {
        return try {
            val obj = JSONObject(json)
            obj.optString("status").takeIf { it.isNotBlank() }
                ?: obj.optString("state").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("data")?.let {
                    it.optString("status").takeIf(String::isNotBlank)
                        ?: it.optString("state").takeIf(String::isNotBlank)
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractStatus(json: String): String? {
        return when (extractRawStatus(json)?.lowercase()) {
            "completed", "complete", "success", "succeeded", "done", "finished" -> "completed"
            "failed", "error", "cancelled", "canceled" -> "failed"
            "expired" -> "expired"
            else -> extractRawStatus(json)?.lowercase()
        }
    }

    private fun extractProviderError(json: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString("error").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("error")?.optString("message")
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun endpointWithRequestId(
        baseUrl: String,
        endpoint: String,
        requestId: String
    ): String {
        val decoded = URLDecoder.decode(endpoint, "UTF-8").trim('/')
        var result = buildUrl(baseUrl, decoded)
            .replace("{id}", requestId)
            .replace("{request_id}", requestId)
            .replace("{task_id}", requestId)

        if (!decoded.contains("{") && !result.endsWith("/$requestId")) {
            result = "${result.trimEnd('/')}/$requestId"
        }
        return result
    }

    private fun buildUrl(baseUrl: String, endpoint: String): String =
        "${baseUrl.trimEnd('/')}/${endpoint.trim('/')}"

    private fun isXai(baseUrl: String): Boolean =
        baseUrl.contains("api.x.ai", ignoreCase = true)

    private fun validatePrompt(prompt: String): Boolean {
        if (prompt.isBlank()) {
            fail("Please enter a prompt first.")
            return false
        }
        return true
    }

    private fun validateConfiguration(
        baseUrl: String,
        endpoint: String,
        apiKey: String,
        section: String
    ): Boolean {
        if (baseUrl.isBlank() || endpoint.isBlank() || apiKey.isBlank()) {
            fail("Please configure $section Settings first.")
            return false
        }
        return true
    }

    private fun completeVideo(url: String) {
        _uiState.update {
            it.copy(
                isGenerating = false,
                generatedVideoUrl = url,
                error = null,
                videoStatus = null
            )
        }
    }

    private fun fail(message: String) {
        _uiState.update {
            it.copy(isGenerating = false, error = message.trim(), videoStatus = null)
        }
    }

    private fun mediaHttpError(code: Int, body: String, isVideo: Boolean): String {
        val providerOutput = truncate(body)
        return when (code) {
            400 -> "Invalid request. Check model, prompt, and media input.\nProvider output: $providerOutput"
            401 -> "Invalid API key."
            402 -> "Provider credit or billing issue."
            404 -> "Endpoint not found. Check Base URL and Path in Settings."
            413 -> "Uploaded image is too large. Enable Economy Mode or choose a smaller image."
            415 -> "Unsupported Media Type. Check Request Format (JSON vs multipart).\nProvider output: $providerOutput"
            422 -> if (isVideo) {
                "Video request failed because the image payload was rejected.\nProvider output: $providerOutput"
            } else {
                "Image request failed because the payload format was rejected.\nProvider output: $providerOutput"
            }
            429 -> "Rate limit exceeded."
            else -> "Error $code: $providerOutput"
        }
    }

    private fun truncate(text: String, limit: Int = 250): String =
        if (text.length > limit) "${text.take(limit)}..." else text

    private fun JSONObject.toJsonBody(): RequestBody =
        toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun JSONArray.firstObject(): JSONObject? =
        if (length() > 0) optJSONObject(0) else null

    private suspend fun loadMediaBytes(url: String): Pair<ByteArray, String>? {
        if (url.startsWith("data:")) {
            val metadata = url.substringBefore(',')
            val mime = metadata.substringAfter("data:", "application/octet-stream")
                .substringBefore(';')
            val bytes = Base64.getDecoder().decode(url.substringAfter(','))
            return bytes to mime
        }

        val request = Request.Builder().url(url).get().build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            val mime = response.header("Content-Type")
                ?.substringBefore(';')
                ?: "application/octet-stream"
            bytes to mime
        }
    }

    private fun extensionForMime(mime: String, isVideo: Boolean): String =
        when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> if (isVideo) "mp4" else "png"
        }

    private fun showToast(context: Context, message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val okHttpClient: OkHttpClient,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(StudioViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            return StudioViewModel(settingsRepository, okHttpClient, context) as T
        }
    }

    companion object {
        private const val TAG = "StudioViewModel"
        private const val MAX_IMAGE_SIDE = 1024
        private const val MAX_VIDEO_POLL_ATTEMPTS = 60
        private const val VIDEO_POLL_INTERVAL_MS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val IMAGE_MEDIA_TYPE = "image/*".toMediaType()
    }
}
