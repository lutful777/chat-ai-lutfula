package com.example.chess.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StockfishBinaryManager(private val context: Context) {
    suspend fun getExecutablePath(): String? = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return@withContext null
        val binaryName = when {
            abi.contains("arm64-v8a") -> "stockfish-armv8"
            abi.contains("armeabi-v7a") -> "stockfish-armv7"
            abi.contains("x86_64") -> "stockfish-x86_64"
            else -> return@withContext null
        }

        val internalFile = File(context.filesDir, binaryName)
        try {
            if (!internalFile.exists() || internalFile.length() == 0L) {
                val assetManager = context.assets
                val available = assetManager.list("stockfish").orEmpty()
                val selectedName = if (available.contains(binaryName)) {
                    binaryName
                } else {
                    available.firstOrNull() ?: return@withContext null
                }

                assetManager.open("stockfish/$selectedName").use { input ->
                    internalFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            if (!internalFile.setExecutable(true, false) && !internalFile.canExecute()) {
                return@withContext null
            }
            internalFile.absolutePath
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }
}
