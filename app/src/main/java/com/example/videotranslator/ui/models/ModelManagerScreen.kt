package com.example.videotranslator.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videotranslator.model.ModelCategory
import com.example.videotranslator.model.ModelInfo
import com.example.videotranslator.model.ModelStatus
import com.example.videotranslator.models.ModelManager
import com.example.videotranslator.models.ModelRegistry

private val BgDeep = Color(0xFF060610)
private val BgCard = Color(0xFF0E0E1C)
private val BgElevated = Color(0xFF1A1A32)
private val Gold = Color(0xFFC9A84C)
private val GoldLight = Color(0xFFE5C76B)
private val Ivory = Color(0xFFF5F0E8)
private val IvoryDim = Color(0xFFAA9F8E)
private val MutedLabel = Color(0xFF6B6680)
private val SuccessGreen = Color(0xFF2ECC71)
private val ErrorRed = Color(0xFFE74C3C)
private val BorderGold = Gold.copy(alpha = 0.16f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerSheet(
    modelManager: ModelManager,
    onDismiss: () -> Unit
) {
    val statuses by modelManager.modelStatuses.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        contentColor = Ivory
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "On-Device AI Models",
                        color = Ivory,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "100% Offline • No Cloud Required",
                        color = Gold,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = IvoryDim)
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ModelCategory.entries.forEach { category ->
                    val categoryModels = ModelRegistry.ALL_MODELS.filter { it.category == category }
                    if (categoryModels.isNotEmpty()) {
                        item {
                            Text(
                                category.displayName.uppercase(),
                                color = MutedLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }

                        items(categoryModels, key = { it.id }) { model ->
                            val status = statuses[model.id] ?: ModelStatus.NotInstalled
                            ModelItemCard(
                                model = model,
                                status = status,
                                onDownload = { modelManager.downloadModel(model.id) },
                                onDelete = { modelManager.deleteModel(model.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelItemCard(
    model: ModelInfo,
    status: ModelStatus,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, BorderGold, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        color = Ivory,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        model.description,
                        color = IvoryDim,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Size: ${model.formattedSize}",
                        color = Gold,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.width(10.dp))

                when (status) {
                    is ModelStatus.Installed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SuccessGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Installed", color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    is ModelStatus.Downloading -> {
                        CircularProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.size(28.dp),
                            color = Gold,
                            trackColor = BgCard,
                            strokeWidth = 2.5.dp
                        )
                    }

                    is ModelStatus.Error -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Retry", color = ErrorRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    ModelStatus.NotInstalled -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = Gold),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Download", color = Color(0xFF1A1000), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (status is ModelStatus.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Gold,
                    trackColor = BgCard
                )
            }
        }
    }
}
