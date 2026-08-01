#!/bin/bash
sed -i '/val isHolidayQuery/i \
                if (_uiState.value.mode == ChatMode.GITHUB) {\
                    _uiState.update { it.copy(isLoading = true, loadingText = "Fetching GitHub...") }\
                    try {\
                        val encodedQuery = java.net.URLEncoder.encode(messageText, "UTF-8")\
                        val request = Request.Builder()\
                            .url("https://chat-ai-lutfula.vercel.app/api/github?q=$encodedQuery")\
                            .header("X-GitHub-Proxy-Secret", com.example.BuildConfig.APP_GITHUB_PROXY_SECRET)\
                            .get()\
                            .build()\
                        val response = okHttpClient.newCall(request).execute()\
                        val responseStr = response.body?.string()\
                        if (response.isSuccessful && responseStr != null) {\
                            searchContext += "Data dari GitHub API:\\n" + (if (responseStr.length > 5000) responseStr.substring(0, 5000) + "..." else responseStr) + "\\n\\nInstruksi: Jawab berdasarkan data GitHub tersebut. Jangan berasumsi.\\n"\
                        } else {\
                            searchContext += "Pencarian GitHub gagal: ${response.code}\\n\\n"\
                        }\
                    } catch (e: Exception) {\
                        searchContext += "Pencarian GitHub gagal: ${e.message}\\n\\n"\
                    }\
                }\
' app/src/main/java/com/example/ui/chat/ChatViewModel.kt
