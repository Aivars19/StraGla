package info.dvkr.screenstream.rtsp.ui.main.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeIntRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.EncoderUtils
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.getBitRateInKbits
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.getFrameRates
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.media.EncoderItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val RESIZE_STOPS_PERCENT: List<Float> = listOf(25f, 50f, 75f, 100f)
private val FPS_STOPS: List<Int> = listOf(2, 4, 8, 12, 16, 24, 30)
private val BITRATE_STOPS_KBITS: List<Int> = listOf(400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2500, 3000, 3500, 4000, 4500, 5000)

private fun nearestIndex(values: List<Int>, target: Int): Int =
    values.indices.minByOrNull { index -> abs(values[index] - target) } ?: 0

private fun nearestResizeIndex(target: Float): Int =
    RESIZE_STOPS_PERCENT.indices.minByOrNull { index -> abs(RESIZE_STOPS_PERCENT[index] - target) } ?: 0

@Composable
internal fun VideoCard(
    isStreaming: Boolean,
    selectedVideoEncoder: VideoCodecInfo?,
    settings: RtspSettings.Data,
    updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = rememberSaveable { mutableStateOf(false) }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_video_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        if (selectedVideoEncoder?.capabilities?.videoCapabilities == null) return@ExpandableCard

        val videoCapabilities = remember(selectedVideoEncoder) {
            selectedVideoEncoder.capabilities.videoCapabilities!!
        }

        VideoEncoder(
            isAutoSelect = settings.videoCodecAutoSelect,
            onAutoSelectChange = { updateSettings { copy(videoCodecAutoSelect = videoCodecAutoSelect.not()) } },
            selectedEncoder = selectedVideoEncoder,
            availableEncoders = EncoderUtils.availableVideoEncoders,
            onCodecSelected = { updateSettings { copy(videoCodec = it) } },
            enabled = isStreaming.not(),
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )

        val context = LocalContext.current
        val screenSize = remember(context) {
            WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds.toComposeIntRect().size
        }

        val (resizeFactor, resizedWidth, resizedHeight) = remember(
            screenSize, videoCapabilities, settings.videoResizeFactor
        ) {
            videoCapabilities.adjustResizeFactor(screenSize.width, screenSize.height, settings.videoResizeFactor / 100)
        }

        val fpsRange = remember(videoCapabilities, resizedWidth, resizedHeight) {
            videoCapabilities.getFrameRates(resizedWidth, resizedHeight)
        }

        val bitrateRangeKbits = remember(videoCapabilities, resizedWidth, resizedHeight) { videoCapabilities.getBitRateInKbits() }

        ImageSize(
            screenSize = screenSize,
            resultSize = IntSize(resizedWidth, resizedHeight),
            resizeFactor = resizeFactor * 100,
            onValueChange = { newResizeFactor ->
                val (resizeFactor, resizedWidth, resizedHeight) =
                    videoCapabilities.adjustResizeFactor(screenSize.width, screenSize.height, newResizeFactor / 100)
                val fpsRange = videoCapabilities.getFrameRates(resizedWidth, resizedHeight)
                updateSettings { copy(videoResizeFactor = resizeFactor * 100, videoFps = videoFps.coerceIn(fpsRange)) }
            },
            enabled = isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )

        Fps(
            fpsRange = fpsRange,
            fps = settings.videoFps,
            onValueChange = { updateSettings { copy(videoFps = it) } },
            enabled = isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )

        Bitrate(
            bitrateRangeKbits = bitrateRangeKbits,
            bitrateBits = settings.videoBitrateBits,
            onValueChange = { updateSettings { copy(videoBitrateBits = it) } },
            enabled = isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun VideoEncoder(
    isAutoSelect: Boolean,
    onAutoSelectChange: (Boolean) -> Unit,
    selectedEncoder: VideoCodecInfo?,
    availableEncoders: List<VideoCodecInfo>,
    onCodecSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .conditional(enabled) { toggleable(value = isAutoSelect, onValueChange = onAutoSelectChange) }
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.rtsp_video_encoder))
            Spacer(modifier = Modifier.weight(1f))
            Row {
                Text(text = stringResource(R.string.rtsp_video_encoder_auto), modifier = Modifier.align(Alignment.CenterVertically))
                Switch(checked = isAutoSelect, enabled = enabled, onCheckedChange = null, modifier = Modifier.scale(0.7F))
            }
        }

        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .conditional(isAutoSelect.not()) { clickable(enabled = enabled) { expanded = true } }
                .alpha(if (isAutoSelect || enabled.not()) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EncoderItem(
                codecName = "${selectedEncoder?.codec?.name} ${selectedEncoder?.vendorName}",
                encoderName = "[${selectedEncoder?.name}]",
                isHardwareAccelerated = selectedEncoder?.isHardwareAccelerated == true,
                isCBRModeSupported = selectedEncoder?.isCBRModeSupported == true
            )
            Spacer(Modifier.weight(1f))
            val iconRotation = remember { Animatable(0F) }
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                contentDescription = null,
                modifier = Modifier.graphicsLayer {
                    rotationZ = iconRotation.value
                }
            )
            LaunchedEffect(expanded) { iconRotation.animateTo(targetValue = if (expanded) 180F else 0F, animationSpec = tween(500)) }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableEncoders.forEachIndexed { index, encoder ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                if (index != 0) HorizontalDivider()
                                Row {
                                    if (selectedEncoder?.name == encoder.name) {
                                        Icon(
                                            painter = painterResource(R.drawable.check_small_24px),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(start = 16.dp)
                                                .align(Alignment.CenterVertically)
                                        )
                                    }
                                    EncoderItem(
                                        codecName = "${encoder.codec.name} ${encoder.vendorName}",
                                        encoderName = "[${encoder.name}]",
                                        isHardwareAccelerated = encoder.isHardwareAccelerated,
                                        isCBRModeSupported = encoder.isCBRModeSupported,
                                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onCodecSelected(encoder.name)
                        },
                        contentPadding = PaddingValues()
                    )
                }

            }
        }
    }
}

@Composable
private fun ImageSize(
    screenSize: IntSize,
    resultSize: IntSize,
    resizeFactor: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var counter by remember { mutableLongStateOf(0) }
        var sliderPosition by remember(resizeFactor, counter) {
            mutableFloatStateOf(nearestResizeIndex(resizeFactor).toFloat())
        }
        val selectedResizePercent = RESIZE_STOPS_PERCENT[sliderPosition.roundToInt().coerceIn(0, RESIZE_STOPS_PERCENT.lastIndex)]
        var currentResultSize by remember(resultSize, counter) { mutableStateOf(resultSize) }
        val scope = rememberCoroutineScope()

        Text(text = stringResource(R.string.rtsp_video_resize_image, selectedResizePercent))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "${RESIZE_STOPS_PERCENT.first().roundToInt()}%", modifier = Modifier.align(Alignment.CenterVertically))
            Slider(
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    val selected = RESIZE_STOPS_PERCENT[it.roundToInt().coerceIn(0, RESIZE_STOPS_PERCENT.lastIndex)]
                    currentResultSize = IntSize((screenSize.width * selected / 100F).roundToInt(), (screenSize.height * selected / 100F).roundToInt())
                },
                enabled = enabled,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                valueRange = 0f..RESIZE_STOPS_PERCENT.lastIndex.toFloat(),
                steps = (RESIZE_STOPS_PERCENT.size - 2).coerceAtLeast(0),
                onValueChangeFinished = {
                    val selected = RESIZE_STOPS_PERCENT[sliderPosition.roundToInt().coerceIn(0, RESIZE_STOPS_PERCENT.lastIndex)]
                    onValueChange.invoke(selected)
                    scope.launch { delay(250); counter += 1 }
                }
            )
            Text(text = "${RESIZE_STOPS_PERCENT.last().roundToInt()}%", modifier = Modifier.align(Alignment.CenterVertically))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(top = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.rtsp_video_screen_size, screenSize.width, screenSize.height),
                style = MaterialTheme.typography.bodySmall
            )

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxHeight()
            )

            Text(
                text = stringResource(R.string.rtsp_video_video_size, currentResultSize.width, currentResultSize.height),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Fps(
    fpsRange: ClosedRange<Int>,
    fps: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val allowedFpsStops = remember(fpsRange) {
        FPS_STOPS.filter { it in fpsRange }.ifEmpty { listOf(fps.coerceIn(fpsRange)) }
    }

    Column(modifier = modifier) {
        var sliderPosition by remember(allowedFpsStops, fps) {
            mutableFloatStateOf(nearestIndex(allowedFpsStops, fps.coerceIn(fpsRange)).toFloat())
        }
        val selectedFps = allowedFpsStops[sliderPosition.roundToInt().coerceIn(0, allowedFpsStops.lastIndex)]

        Text(text = stringResource(R.string.rtsp_video_frame_rate, selectedFps))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = allowedFpsStops.first().toString(), modifier = Modifier.align(Alignment.CenterVertically))
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                enabled = enabled,
                valueRange = 0f..allowedFpsStops.lastIndex.toFloat(),
                steps = (allowedFpsStops.size - 2).coerceAtLeast(0),
                onValueChangeFinished = {
                    val selected = allowedFpsStops[sliderPosition.roundToInt().coerceIn(0, allowedFpsStops.lastIndex)]
                    onValueChange.invoke(selected)
                }
            )
            Text(text = allowedFpsStops.last().toString(), modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun Bitrate(
    bitrateRangeKbits: ClosedRange<Int>,
    bitrateBits: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val allowedBitrateStops = remember(bitrateRangeKbits) {
        BITRATE_STOPS_KBITS.filter { it in bitrateRangeKbits }.ifEmpty { listOf((bitrateBits / 1000).coerceIn(bitrateRangeKbits)) }
    }

    Column(modifier = modifier) {
        var isDragging by remember { mutableStateOf(false) }
        var sliderPosition by remember(allowedBitrateStops, bitrateBits, bitrateRangeKbits) {
            mutableFloatStateOf(nearestIndex(allowedBitrateStops, (bitrateBits / 1000).coerceIn(bitrateRangeKbits)).toFloat())
        }

        LaunchedEffect(bitrateBits, bitrateRangeKbits, allowedBitrateStops) {
            if (!isDragging) {
                sliderPosition = nearestIndex(allowedBitrateStops, (bitrateBits / 1000).coerceIn(bitrateRangeKbits)).toFloat()
            }
        }

        val selectedBitrateKbits = allowedBitrateStops[sliderPosition.roundToInt().coerceIn(0, allowedBitrateStops.lastIndex)]

        Text(
            text = stringResource(R.string.rtsp_video_bitrate, selectedBitrateKbits.toKOrMBitString()),
            modifier = Modifier
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = allowedBitrateStops.first().toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Slider(
                value = sliderPosition,
                onValueChange = {
                    isDragging = true
                    sliderPosition = it
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                enabled = enabled,
                valueRange = 0f..allowedBitrateStops.lastIndex.toFloat(),
                steps = (allowedBitrateStops.size - 2).coerceAtLeast(0),
                onValueChangeFinished = {
                    isDragging = false
                    val selected = allowedBitrateStops[sliderPosition.roundToInt().coerceIn(0, allowedBitrateStops.lastIndex)]
                    onValueChange.invoke(selected * 1000)
                }
            )
            Text(
                text = allowedBitrateStops.last().toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
internal fun Int.toKOrMBitString(): String =
    if (this >= 1000) stringResource(R.string.rtsp_video_bitrate_mbit, this / 1000f)
    else stringResource(R.string.rtsp_video_bitrate_kbit, this)
