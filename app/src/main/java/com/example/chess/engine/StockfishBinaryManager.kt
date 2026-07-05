package com.example.chess.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StockfishBinaryManager(private val context: Context) {
    suspend fun getExecutablePath(): String? = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return@withContext null
        val binaryName = when {
            abi.contains("arm64-v8a") -> "stockfish-armv8"
            abi.contains("armeabi-v7a") -> "stockfish-armv7"
            abi.contains("x86_64") -> "stockfish-x86_64"
            else -> "stockfish-armv8" // Fallback
        }

        val internalFile = File(context.filesDir, "stockfish_bin")
        try {
            if (!internalFile.exists() || internalFile.length() == 0L) {
                // Try to copy from assets
                val assetManager = context.assets
                var assetInput: InputStream? = null
                try {
                    assetInput = assetManager.open("stockfish/\$binaryName")
                } catch (e: Exception) {
                    // Fallback to whatever is available
                    val available = assetManager.list("stockfish")
                    if (!available.isNullOrEmpty()) {
                        assetInput = assetManager.open("stockfish/\${available[0]}")
                    }
                }
                
                if (assetInput != null) {
                    FileOutputStream(internalFile).use { out ->
                        assetInput.copyTo(out)
                    }
                    assetInput.close()
                } else {
                    return@withContext null // Binary not found
                }
            }
            
            // Set executable permissions
            internalFile.setExecutable(true, false)
            return@withContext internalFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
