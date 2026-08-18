package com.example.videotranslator.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.VoiceMode

private val BgCard = Color(0xFF0E0E1C)
private val BgElevated = Color(0xFF1A1A32)
private val Gold = Color(0xFFC9A84C)
private val Ivory = Color(0xFFF5F0E8)
private val IvoryDim = Color(0xFFAA9F8E)
private val MutedLabel = Color(0xFF6B6680)
private val BorderGold = Gold.copy(alpha = 0.16f)

@Composable
fun VoiceSettingsDialog(
    selectedMode: VoiceMode,
    onModeSelected: (VoiceMode) -> Unit,
    lowConfFallback: Gender,
    onFallbackSelected: (Gender) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = {
            Text(
                "Voice Synthesis Settings",
                color = Ivory,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "VOICE MODE",
                    color = MutedLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(8.dp))

                VoiceMode.entries.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) BgElevated else Color.Transparent)
                            .border(1.dp, if (isSelected) Gold else BorderGold, RoundedCornerShape(10.dp))
                            .clickable { onModeSelected(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onModeSelected(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = Gold, unselectedColor = IvoryDim)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            mode.displayName,
                            color = if (isSelected) Gold else Ivory,
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "LOW-CONFIDENCE VOICE FALLBACK",
                    color = MutedLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Used when speaker voice cannot be determined with high confidence (>0.60).",
                    color = IvoryDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(Gender.MALE to "Default Male", Gender.FEMALE to "Default Female").forEach { (gender, label) ->
                        val isSelected = lowConfFallback == gender
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) BgElevated else Color.Transparent)
                                .border(1.dp, if (isSelected) Gold else BorderGold, RoundedCornerShape(10.dp))
                                .clickable { onFallbackSelected(gender) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSelected) Gold else IvoryDim,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Done", color = Color(0xFF1A1000), fontWeight = FontWeight.Bold)
            }
        }
    )
}
