package com.andrerinas.openheadunit.cluster

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

/**
 * The second, instrument-cluster video stream asked of the phone.
 *
 * Android Auto offers only fixed 16:9 codec sizes. Margins tell the phone to draw its map in a
 * centred [contentWidth] x [contentHeight] area of the frame, so a viewport of any shape gets a
 * map laid out for that shape instead of a stretched or cropped 16:9 one.
 */
internal data class ClusterStreamProfile(
    val resolution: VideoCodecResolutionType,
    val codecWidth: Int,
    val codecHeight: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val density: Int = DENSITY_DPI,
) {
    val marginWidth: Int get() = codecWidth - contentWidth
    val marginHeight: Int get() = codecHeight - contentHeight
    /** Where the phone's picture sits in the decoded frame; the margins split evenly. */
    val contentLeft: Int get() = marginWidth / 2
    val contentTop: Int get() = marginHeight / 2

    companion object {
        /** What Android's desktop head unit uses for its cluster example; about a 12.3" 1920x720 panel. */
        const val DENSITY_DPI = 160
        const val FRAME_RATE = 30

        private val SIZES = listOf(
            Triple(VideoCodecResolutionType._800x480, 800, 480),
            Triple(VideoCodecResolutionType._1280x720, 1280, 720),
            Triple(VideoCodecResolutionType._1920x1080, 1920, 1080),
        )

        /**
         * The smallest codec size the viewport fits in. A viewport larger than 1080p is scaled
         * down, keeping its shape, and the cluster scales the picture back up.
         */
        fun forViewport(width: Int, height: Int): ClusterStreamProfile? {
            if (width <= 0 || height <= 0) return null
            val scale = minOf(1.0, 1920.0 / width, 1080.0 / height)
            val contentWidth = even(width * scale).coerceAtMost(1920)
            val contentHeight = even(height * scale).coerceAtMost(1080)
            val (type, codecWidth, codecHeight) = SIZES.first { (_, w, h) -> contentWidth <= w && contentHeight <= h }
            return ClusterStreamProfile(type, codecWidth, codecHeight, contentWidth, contentHeight)
        }

        private fun even(value: Double): Int = (Math.round(value / 2) * 2).toInt().coerceAtLeast(2)
    }
}
