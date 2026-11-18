package com.readerengine.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readerengine.core.model.Bookmark
import java.io.File

data class SampleBook(
    val id: String,
    val title: String,
    val assetPath: String,
    val fileName: String,
)

enum class DeviceFileFilter(
    val title: String,
    val subtitle: String,
    val badge: String,
    val defaultExtension: String?,
    val mimeTypes: Array<String>,
) {
    EPUB(
        title = "Abrir arquivo .epub",
        subtitle = "EPUB 2 e EPUB 3 (Reflowable, Fixed-Layout, Mídia)",
        badge = "EPUB",
        defaultExtension = "epub",
        mimeTypes = arrayOf("application/epub+zip", "application/octet-stream", "*/*"),
    ),
    HPUB(
        title = "Abrir arquivo .hpub",
        subtitle = "Pacote HPUB / Baker Framework (book.json + HTML)",
        badge = "HPUB",
        defaultExtension = "hpub",
        mimeTypes = arrayOf("application/zip", "application/octet-stream", "*/*"),
    ),
    JSON(
        title = "Abrir arquivo .json",
        subtitle = "Documento estruturado reader-json",
        badge = "JSON",
        defaultExtension = "json",
        mimeTypes = arrayOf("application/json", "text/plain", "*/*"),
    ),
    ALL(
        title = "Procurar qualquer formato",
        subtitle = "Selecionar .epub, .hpub ou .json do dispositivo",
        badge = "TODOS",
        defaultExtension = null,
        mimeTypes = arrayOf(
            "application/epub+zip",
            "application/json",
            "application/zip",
            "application/octet-stream",
            "*/*",
        ),
    ),
}

@Composable
fun OpenBookDialog(
    importedFiles: List<File>,
    samples: List<SampleBook>,
    activeBookKey: String?,
    onDismiss: () -> Unit,
    onPickDeviceFile: (DeviceFileFilter) -> Unit,
    onOpenImportedFile: (File) -> Unit,
    onDeleteImportedFile: (File) -> Unit,
    onOpenSample: (SampleBook) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Abrir publicação",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Do seu dispositivo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                DeviceFileFilter.entries.forEach { filter ->
                    Surface(
                        onClick = { onPickDeviceFile(filter) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            ) {
                                Text(
                                    text = filter.badge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = filter.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Text(
                                    text = filter.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }

                if (importedFiles.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Recentes do dispositivo",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    importedFiles.forEach { file ->
                        val isSelected = activeBookKey == file.absolutePath
                        val extBadge = file.extension.uppercase().ifBlank { "ARQ" }
                        Surface(
                            onClick = { onOpenImportedFile(file) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = extBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onDeleteImportedFile(file) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Remover arquivo recente",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (samples.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Exemplos inclusos no app",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    samples.forEach { sample ->
                        val isSelected = activeBookKey == sample.id
                        Surface(
                            onClick = { onOpenSample(sample) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = sample.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = sample.fileName.substringAfterLast('.').uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        },
    )
}

@Composable
fun BookmarkChip(
    bookmark: Bookmark,
    isCurrentPage: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = bookmark.label ?: "Página ${bookmark.pageIndex + 1}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remover marcador",
                    modifier = Modifier.size(14.dp),
                )
            }
        },
    )
}
