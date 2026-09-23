package com.naomi.hoshimusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.playback.AudioPathStatus
import com.naomi.hoshimusic.playback.AudioSignalPathState
import com.naomi.hoshimusic.playback.route.RouteType
import com.naomi.hoshimusic.playback.dsp.DspTelemetry

@Composable
internal fun TechnicalInfoSheet(
    song: Song,
    signalPath: AudioSignalPathState,
    routeType: RouteType,
    dspTelemetry: DspTelemetry?,
    onClose: () -> Unit = {}
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "JALUR AUDIO",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "Status antarmuka hardware dan perjalanan sinyal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics { contentDescription = "Tutup" }
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                AudioPathSummary(signalPath, routeType, dspTelemetry)
                Spacer(Modifier.height(32.dp))
            }

            item {
                MasterSignalRoutingPipeline(song, signalPath, routeType, dspTelemetry, showDiagnostics)
            }

            item {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) { showDiagnostics = !showDiagnostics }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (showDiagnostics) "Sembunyikan diagnostik" else "Tampilkan diagnostik",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Drag Handle
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(36.dp, 4.dp)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun AudioPathSummary(
    path: AudioSignalPathState,
    routeType: RouteType,
    dspTelemetry: DspTelemetry?
) {
    val statusLabel = when (path.status) {
        AudioPathStatus.DSP_ACTIVE -> "DSP aktif"
        AudioPathStatus.DSP_ATTACHED -> "DSP sedang dimulai"
        AudioPathStatus.DSP_BYPASS -> "DSP dilewati"
        AudioPathStatus.DSP_ERROR -> "DSP tidak tersedia"
        AudioPathStatus.UNKNOWN_FORMAT -> "Format sedang dideteksi"
    }

    val deviceName = TechnicalInfoFormatters.cleanDeviceName(path.sink.deviceName, routeType)
    val routeLabel = TechnicalInfoFormatters.formatRouteType(routeType)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = if (path.status == AudioPathStatus.DSP_ACTIVE) Color(0xFF81C784) else Color.White
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
        Text(
            text = "$deviceName • $routeLabel",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Spacer(Modifier.height(12.dp))
        
        // [Issue 3]: Use safe formatters for consistent display
        val sourceRes = TechnicalInfoFormatters.formatResolution(path.source.bitDepth, path.source.sampleRate)
        val sourceMime = TechnicalInfoFormatters.formatMime(path.source.mime)
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$sourceMime $sourceRes",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )
        }
        
        Icon(
            imageVector = Icons.Rounded.South,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.2f),
            modifier = Modifier
                .padding(start = 4.dp)
                .size(16.dp)
        )
        
        // [Issue 3]: Resolution display fallback logic
        // Use telemetry sample rate if active, otherwise use actual path from sink.
        // Bit depth is 32-bit if DSP is active (engine internally processes floats),
        // but it's more accurate to show sink bit depth for "Actual Output".
        val actualSampleRate = dspTelemetry?.sampleRate ?: path.actualSampleRate
        val actualBitDepth = path.actualBitDepth ?: (if (dspTelemetry != null && dspTelemetry.nativeInitialized) 32 else null)
        
        val actualFormat = if (actualSampleRate != null && actualBitDepth != null) {
            TechnicalInfoFormatters.formatResolution(actualBitDepth, actualSampleRate)
        } else if (actualSampleRate != null) {
            TechnicalInfoFormatters.formatKhz(actualSampleRate)
        } else {
            "Output aktual belum tersedia"
        }
        
        Text(
            text = actualFormat,
            style = MaterialTheme.typography.bodySmall,
            color = if (actualSampleRate != null) Color.White else Color.White.copy(alpha = 0.5f),
            fontWeight = if (actualSampleRate != null) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun MasterSignalRoutingPipeline(
    song: Song,
    path: AudioSignalPathState,
    routeType: RouteType,
    dspTelemetry: DspTelemetry?,
    showDiagnostics: Boolean
) {
    val neutralIconColor = Color.White.copy(alpha = 0.4f)
    val neutralTextColor = Color.White

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. SUMBER AUDIO
        PipelineVerticalNode(
            icon = Icons.Rounded.AudioFile,
            title = "SUMBER AUDIO",
            iconColor = neutralIconColor,
            isLast = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = song.displayName.ifBlank { song.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = neutralTextColor,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                val bitDisplay = if ((song.bitDepth ?: 0) > 0) "${song.bitDepth}-bit" else "Belum tersedia"
                val khzDisplay = song.sampleRate?.let { TechnicalInfoFormatters.formatKhz(it) } ?: "Belum tersedia"
                val bitrate = song.bitrate ?: if (song.durationMs > 1000) {
                    ((song.fileSize * 8) / (song.durationMs / 1000f)).toInt()
                } else 0

                ParserDetailRow("Format", song.codec?.uppercase() ?: song.container?.uppercase() ?: "Tidak diketahui", neutralTextColor)
                ParserDetailRow("Resolusi", "$bitDisplay / $khzDisplay", neutralTextColor)
                ParserDetailRow("Bitrate", TechnicalInfoFormatters.formatBitrate(bitrate), neutralTextColor)
                
                if (showDiagnostics) {
                    ParserDetailRow("MIME Type", song.mimeType, neutralTextColor)
                    ParserDetailRow("Verifikasi", if (song.isFormatVerified) "Terverifikasi" else "Belum terverifikasi", neutralTextColor)
                }
            }
        }

        // 2. PEMROSESAN (DSP)
        PipelineVerticalNode(
            icon = Icons.Rounded.GraphicEq,
            title = "PEMROSESAN",
            iconColor = neutralIconColor,
            isLast = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val dspStatus = when (path.status) {
                    AudioPathStatus.DSP_ACTIVE -> "Aktif"
                    AudioPathStatus.DSP_BYPASS -> "Dilewati"
                    AudioPathStatus.DSP_ATTACHED -> "Sedang dimulai"
                    AudioPathStatus.DSP_ERROR -> "Gagal"
                    AudioPathStatus.UNKNOWN_FORMAT -> "Tidak tersedia"
                }
                
                Text(
                    text = "DSP Hoshi • $dspStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neutralTextColor,
                    fontWeight = FontWeight.Bold
                )

                if (path.status == AudioPathStatus.DSP_ACTIVE && dspTelemetry != null) {
                    ParserDetailRow("Sample rate", "${dspTelemetry.sampleRate} Hz", neutralTextColor)
                    if (showDiagnostics) {
                        ParserDetailRow("Engine Init", if (dspTelemetry.nativeInitialized) "Sukses" else "Menunggu", neutralTextColor)
                        ParserDetailRow("Total Frames", dspTelemetry.totalFramesProcessed.toString(), neutralTextColor)
                    }
                } else if (path.status == AudioPathStatus.DSP_ATTACHED) {
                    Text(
                        text = "Menunggu konfirmasi engine...",
                        style = MaterialTheme.typography.bodySmall,
                        color = neutralTextColor.copy(alpha = 0.5f)
                    )
                }

                if (path.btClarityActive) {
                    ParserDetailRow("BT Clarity", "Aktif", neutralTextColor)
                }
            }
        }

        // 3. KELUARAN
        PipelineVerticalNode(
            icon = Icons.Rounded.SurroundSound,
            title = "KELUARAN",
            iconColor = neutralIconColor,
            isLast = true
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val deviceName = TechnicalInfoFormatters.cleanDeviceName(path.sink.deviceName, routeType)
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = neutralTextColor,
                    fontWeight = FontWeight.Bold
                )
                
                val sinkMaxRes = TechnicalInfoFormatters.formatResolution(path.sink.maxBitDepth, path.sink.maxSampleRate)
                ParserDetailRow("Koneksi", TechnicalInfoFormatters.formatRouteType(routeType), neutralTextColor)
                ParserDetailRow("Kemampuan maks", sinkMaxRes, neutralTextColor)
                
                val actualOut = if (path.actualSampleRate != null && path.actualBitDepth != null) {
                    TechnicalInfoFormatters.formatResolution(path.actualBitDepth, path.actualSampleRate)
                } else {
                    "Belum tersedia"
                }
                ParserDetailRow("Output aktual", actualOut, neutralTextColor)

                Spacer(Modifier.height(8.dp))
                
                val bitPerfectStatus = when {
                    path.isBitPerfect -> "Bit-perfect terverifikasi"
                    path.status == AudioPathStatus.DSP_BYPASS && 
                            path.source.sampleRate == path.sink.maxSampleRate &&
                            path.source.bitDepth == path.sink.maxBitDepth -> "Tidak ada konversi format yang terdeteksi"
                    else -> "Status bit-perfect tidak dapat diverifikasi"
                }
                
                Text(
                    text = "[!] $bitPerfectStatus",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = neutralTextColor.copy(alpha = 0.6f)
                )

                if (showDiagnostics) {
                    Spacer(Modifier.height(4.dp))
                    ParserDetailRow("Sink Codec", path.sink.codec, neutralTextColor)
                    ParserDetailRow("Downsampling", if (path.sink.isDownsamplingRequired) "Ya" else "Tidak", neutralTextColor)
                }
            }
        }
    }
}

@Composable
private fun PipelineVerticalNode(
    icon: ImageVector,
    title: String,
    iconColor: Color,
    isLast: Boolean,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .width(1.dp)
                        .weight(1f)
                        .background(iconColor.copy(alpha = 0.2f))
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 32.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = iconColor.copy(alpha = 0.8f),
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ParserDetailRow(label: String, value: String, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
