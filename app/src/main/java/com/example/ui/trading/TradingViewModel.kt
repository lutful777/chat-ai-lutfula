package com.example.ui.trading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class TradingUiState(
    val priceData: String = "",
    val balanceData: String = "",
    val positionData: String = "",
    val orderData: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

class TradingViewModel(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradingUiState())
    val uiState: StateFlow<TradingUiState> = _uiState
    
    private val backendUrl = "https://chat-ai-lutfula.vercel.app"

    fun fetchPrice(symbol: String = "BTC-USDT") {
        fetchData("price", "$backendUrl/api/bingx/price?symbol=$symbol") { state, data ->
            state.copy(priceData = data, isLoading = false, error = null)
        }
    }

    fun fetchBalance() {
        fetchData("balance", "$backendUrl/api/bingx/balance") { state, data ->
            state.copy(balanceData = data, isLoading = false, error = null)
        }
    }

    fun fetchPositions() {
        fetchData("positions", "$backendUrl/api/bingx/positions") { state, data ->
            state.copy(positionData = data, isLoading = false, error = null)
        }
    }

    fun openLongDemo(symbol: String = "BTC-USDT", quantity: Double = 0.001) {
        executeDemoOrder("OPEN_LONG", symbol, quantity)
    }

    fun openShortDemo(symbol: String = "BTC-USDT", quantity: Double = 0.001) {
        executeDemoOrder("OPEN_SHORT", symbol, quantity)
    }

    fun closePositionDemo(symbol: String = "BTC-USDT") {
        executeDemoOrder("CLOSE", symbol, 0.0)
    }

    private fun executeDemoOrder(action: String, symbol: String, quantity: Double) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val jsonBody = JSONObject().apply {
                    put("action", action)
                    put("symbol", symbol)
                    put("quantity", quantity)
                }.toString()
                
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$backendUrl/api/bingx/demo-order")
                    .post(requestBody)
                    .build()
                    
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val formatted = try { JSONObject(body).toString(4) } catch (e: Exception) { body }
                    _uiState.update { it.copy(orderData = formatted, isLoading = false, error = null) }
                } else {
                    val errMsg = try { JSONObject(body).optString("error", "Error ${response.code}") } catch (e: Exception) { "Error ${response.code}" }
                    _uiState.update { it.copy(isLoading = false, error = "Failed to demo order: $errMsg") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Network Error: ${e.message}") }
            }
        }
    }

    private fun fetchData(type: String, url: String, updateState: (TradingUiState, String) -> TradingUiState) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val formatted = try {
                        JSONObject(body).toString(4)
                    } catch (e: Exception) {
                        body
                    }
                    _uiState.update { updateState(it, formatted) }
                } else {
                    val errMsg = try {
                        JSONObject(body).optString("error", "Error ${response.code}")
                    } catch (e: Exception) {
                        "Error ${response.code}"
                    }
                    _uiState.update { it.copy(isLoading = false, error = "Failed to fetch $type: $errMsg") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Network Error: ${e.message}") }
            }
        }
    }

    class Factory(private val okHttpClient: OkHttpClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TradingViewModel::class.java)) {
                return TradingViewModel(okHttpClient) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
