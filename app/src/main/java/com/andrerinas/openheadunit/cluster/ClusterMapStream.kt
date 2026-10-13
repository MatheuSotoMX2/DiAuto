package com.andrerinas.openheadunit.cluster

import android.content.Context
import android.os.Build
import android.view.Surface
import androidx.annotation.ChecksSdkIntAtLeast
import com.andrerinas.openheadunit.hud.BydGuidance
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The Android Auto instrument-cluster stream for one session: what was offered to the phone, the
 * decoder, the surface it draws on and the turn card's guidance.
 *
 * The phone draws this map itself (Google Maps and Waze do on cars that declare a cluster display).
 * DiAuto only declares the display, decodes the stream and shows it on the BYD cluster.
 */
internal object ClusterMapStream {
    /** Session state, written on the transport thread and read by the projection activity. */
    data class Session(val profile: ClusterStreamProfile, val displayName: String, val theme: ClusterLayout.Theme)

    @Volatile var session: Session? = null
        private set
    @Volatile var streamActive = false
        private set
    /** The latest mapped guidance for the turn card; null when there is no route. */
    @Volatile var guidance: BydGuidance? = null

    private val lock = Any()
    private var decoder: ClusterVideoDecoder? = null
    private var surface: Surface? = null
    @Volatile private var sendKeyframeRequest: (() -> Unit)? = null

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Decides, while the service discovery response is built, whether this session gets a cluster
     * display. It needs the setting and a BYD cluster projection display visible to the app.
     */
    fun prepareSession(context: Context, settings: Settings): ClusterStreamProfile? = synchronized(lock) {
        endSessionLocked()
        if (!available() || !settings.bydClusterMapEnabled) return null
        val theme = ClusterLayout.theme(settings.bydClusterLayout)
        val display = ClusterLayout.findDisplay(context, theme) ?: run {
            AppLog.i("ClusterMap: enabled, but no cluster projection display among ${ClusterLayout.describeDisplays(context)}")
            return null
        }
        val size = ClusterLayout.sizeOf(display)
        val plan = ClusterLayout.plan(size.x, size.y, theme)
        val profile = (if (plan != null) ClusterStreamProfile.forViewport(plan.width, plan.height)
            else ClusterStreamProfile.forViewport(size.x, size.y)) ?: return null
        session = Session(profile, display.name, theme)
        AppLog.i("ClusterMap: offering a cluster display for ${display.name} ${size.x}x${size.y} theme=$theme " +
            "plan=$plan stream=${profile.codecWidth}x${profile.codecHeight} content=${profile.contentWidth}x${profile.contentHeight}")
        profile
    }

    /** The phone set up the cluster stream; [requestKeyframe] sends it a video focus nudge. */
    fun onSetup(requestKeyframe: () -> Unit) {
        sendKeyframeRequest = requestKeyframe
        synchronized(lock) { ensureDecoderLocked() }
    }

    fun onFrame(buf: ByteArray, offset: Int, length: Int) {
        val target = synchronized(lock) { ensureDecoderLocked() } ?: return
        target.decode(buf, offset, length)
    }

    fun setSurface(next: Surface?) = synchronized(lock) {
        surface = next
        if (next == null) streamActive = false
        decoder?.setSurface(next)
    }

    fun endSession() = synchronized(lock) { endSessionLocked() }

    private fun endSessionLocked() {
        decoder?.release()
        decoder = null
        session = null
        streamActive = false
        guidance = null
        sendKeyframeRequest = null
    }

    private fun ensureDecoderLocked(): ClusterVideoDecoder? {
        decoder?.let { return it }
        val current = session ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return ClusterVideoDecoder(
            current.profile.codecWidth, current.profile.codecHeight,
            onPicture = { streamActive = true },
            requestKeyframe = { runCatching { sendKeyframeRequest?.invoke() } },
        ).also { created ->
            decoder = created
            surface?.let(created::setSurface)
        }
    }

    fun diagnosticLine(context: Context, settings: Settings): String {
        val current = session
        val theme = ClusterLayout.theme(settings.bydClusterLayout)
        return "Dashboard map: enabled=${settings.bydClusterMapEnabled} turnCard=${settings.bydClusterTurnCardEnabled} " +
            "theme=$theme measuredFirmware=${ClusterLayout.supported()} " +
            "display=${if (available()) ClusterLayout.findDisplay(context, theme)?.name ?: "none" else "unsupported"} " +
            "session=${current?.let { "${it.displayName} ${it.profile.codecWidth}x${it.profile.codecHeight} content=${it.profile.contentWidth}x${it.profile.contentHeight}" } ?: "none"} " +
            "picture=$streamActive"
    }
}
