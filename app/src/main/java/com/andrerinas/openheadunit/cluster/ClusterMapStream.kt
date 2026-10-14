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
    // Protocol minor the current connection runs at; cluster displays came with 1.6.
    @Volatile private var protocolMinor = 2
    // A phone that refused the cluster display closed the session before setting up the main
    // picture. Stop offering it for the rest of this run so the next connection works.
    @Volatile var refusedThisRun = false
        private set
    private var offered = false
    private var mainVideoSetUp = false
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
        offered = false
        mainVideoSetUp = false
        if (!available() || !settings.bydClusterMapEnabled) return null
        if (refusedThisRun) {
            AppLog.w("ClusterMap: not offering the cluster display: the phone ended an earlier session that offered it before any video. Restart DiAuto to try again.")
            return null
        }
        if (protocolMinor < MIN_PROTOCOL_MINOR) {
            AppLog.i("ClusterMap: not offering the cluster display: this connection runs protocol 1.$protocolMinor, cluster displays need 1.$MIN_PROTOCOL_MINOR.")
            return null
        }
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
        offered = true
        AppLog.i("ClusterMap: offering a cluster display for ${display.name} ${size.x}x${size.y} theme=$theme " +
            "plan=$plan stream=${profile.codecWidth}x${profile.codecHeight} content=${profile.contentWidth}x${profile.contentHeight}")
        profile
    }

    fun onProtocolNegotiated(minor: Int) {
        protocolMinor = minor
    }

    /** The phone set up the main picture, so it accepted the displays it was offered. */
    fun onMainVideoSetup() = synchronized(lock) { mainVideoSetUp = true }

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

    fun endSession() = synchronized(lock) {
        if (offered && !mainVideoSetUp && !refusedThisRun) {
            refusedThisRun = true
            AppLog.w("ClusterMap: the session that offered the cluster display ended before the phone set up any video. Not offering it again until DiAuto restarts.")
        }
        offered = false
        mainVideoSetUp = false
        endSessionLocked()
    }

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
            "picture=$streamActive protocol=1.$protocolMinor refusedThisRun=$refusedThisRun"
    }

    /**
     * A 1.2 session that offered the cluster was closed by a OnePlus 15 right after service
     * discovery. web-auto, whose cluster video works, declares its displays at 1.6.
     */
    private const val MIN_PROTOCOL_MINOR = 6
}
