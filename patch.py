import re

with open('app/src/main/java/com/example/ui/chat/ChatScreen.kt', 'r') as f:
    content = f.read()

# Add the github button
button_code = """                            }
                            
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .background(
                                        color = if (uiState.mode == ChatMode.GITHUB) PrimaryNeon else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        viewModel.setMode(if (uiState.mode == ChatMode.GITHUB) ChatMode.NORMAL else ChatMode.GITHUB)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("GitHub", color = if (uiState.mode == ChatMode.GITHUB) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            DropdownMenu("""

content = content.replace("                            }\n                            DropdownMenu(", button_code)

# Change the placeholder
placeholder_old = """                                if (inputText.isEmpty()) {
                                    Text("Ask anything...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }"""

placeholder_new = """                                if (inputText.isEmpty()) {
                                    Text(if (uiState.mode == ChatMode.GITHUB) "Ask about GitHub..." else "Ask anything...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }"""

content = content.replace(placeholder_old, placeholder_new)

with open('app/src/main/java/com/example/ui/chat/ChatScreen.kt', 'w') as f:
    f.write(content)

