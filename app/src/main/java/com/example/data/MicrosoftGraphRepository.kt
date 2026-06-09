package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

data class EmailMessage(
    val id: String,
    val subject: String?,
    val bodyPreview: String?,
    val receivedDateTime: String?,
    val sender: EmailSender?
)

data class EmailSender(
    val emailAddress: EmailAddress?
)

data class EmailAddress(
    val address: String?,
    val name: String?
)

data class GraphEmailsResponse(
    val value: List<EmailMessage>
)

interface MicrosoftGraphApi {
    @GET("v1.0/me/messages")
    suspend fun getMessages(
        @Query("\$select") select: String = "sender,subject,receivedDateTime,bodyPreview",
        @Query("\$top") top: Int = 10
    ): GraphEmailsResponse

    @GET("v1.0/me/messages")
    suspend fun searchMessages(
        @Query("\$search") search: String,
        @Query("\$select") select: String = "sender,subject,receivedDateTime,bodyPreview",
        @Query("\$top") top: Int = 20
    ): GraphEmailsResponse
}

class MicrosoftGraphRepository(private val authService: MicrosoftAuthService) {
    private val _emails = MutableStateFlow<List<EmailMessage>>(emptyList())
    val emails: StateFlow<List<EmailMessage>> = _emails.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val graphApi: MicrosoftGraphApi by lazy {
        val authInterceptor = Interceptor { chain ->
            val accessToken = kotlinx.coroutines.runBlocking { authService.acquireTokenSilent() }
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
            if (accessToken != null) {
                newRequest.addHeader("Authorization", "Bearer $accessToken")
            }
            chain.proceed(newRequest.build())
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(MicrosoftGraphApi::class.java)
    }

    suspend fun loadLatestEmails() {
        _isLoading.value = true
        _error.value = null
        try {
            val response = graphApi.getMessages()
            _emails.value = response.value
        } catch (e: Exception) {
            _error.value = "Failed to load emails: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun searchEmails(query: String) {
        _isLoading.value = true
        _error.value = null
        try {
            val response = graphApi.searchMessages(search = "\"$query\"")
            _emails.value = response.value
        } catch (e: Exception) {
            _error.value = "Failed to search emails: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
}
