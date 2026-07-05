package com.example.chess.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class StockfishBinaryManager(private val context: Context) {
    suspend fun getExecutablePath(): String? = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return@withContext null
        val preferredName = when {
            abi.contains("arm64-v8a") -> "stockfish-armv8"
            abi.contains("armeabi-v7a") -> "stockfish-armv7"
            abi.contains("x86_64") -> "stockfish-x86_64"
            else -> return@withContext null
        }

        val available = context.assets.list("stockfish").orEmpty()
        val assetName = when {
            preferredName in available -> preferredName
            else -> available.firstOrNull { name ->
                when {
                    abi.contains("arm64-v8a") -> name.contains("armv8") || name.contains("arm64")
                    abi.contains("armeabi-v7a") -> name.contains("armv7")
                    abi.contains("x86_64") -> name.contains("x86_64")
                    else -> false
                }
            }
        } ?: return@withContext null

        val internalFile = File(context.filesDir, "stockfish-$abi")
        try {
            if (!internalFile.exists() || internalFile.length() < MIN_BINARY_BYTES) {
                context.assets.open("stockfish/$assetName").use { input ->
                    FileOutputStream(internalFile, false).use { output -> input.copyTo(output) }
                }
            }

            if (internalFile.length() < MIN_BINARY_BYTES) {
                internalFile.delete()
                return@withContext null
            }
            if (!internalFile.setExecutable(true, true) && !internalFile.canExecute()) {
                return@withContext null
            }
            internalFile.absolutePath
        } catch (_: Exception) {
            internalFile.delete()
            null
        }
    }

    companion object {
        private const val MIN_BINARY_BYTES = 64 * 1024L
    }
}
