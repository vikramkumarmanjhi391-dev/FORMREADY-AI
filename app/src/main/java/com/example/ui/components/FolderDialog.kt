package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.database.FolderEntity

val FOLDER_COLORS = listOf(
    "#2563EB", // Blue
    "#10B981", // Emerald Green
    "#F59E0B", // Amber
    "#8B5CF6", // Purple
    "#EC4899", // Pink
    "#06B6D4"  // Cyan
)

@Composable
fun FolderDialog(
    initialFolder: FolderEntity? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, iconName: String) -> Unit
) {
    var name by remember { mutableStateOf(initialFolder?.name ?: "") }
    var selectedColor by remember { mutableStateOf(initialFolder?.colorHex ?: FOLDER_COLORS.first()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialFolder != null) "Edit Folder" else "Create Custom Folder",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) errorMessage = null
                    },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Tax Returns, Job Applications") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_name_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Folder Color",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FOLDER_COLORS.forEach { hex ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hex }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "Folder name cannot be empty"
                    } else {
                        onSave(name.trim(), selectedColor, "folder")
                    }
                },
                modifier = Modifier.testTag("save_folder_button")
            ) {
                Text(if (initialFolder != null) "Update" else "Create")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_folder_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
