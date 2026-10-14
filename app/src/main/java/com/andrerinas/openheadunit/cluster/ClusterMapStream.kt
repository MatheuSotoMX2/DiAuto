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
    // A phone that refuses a cluster description closes the session before setting up the main
    // picture. The next connection then tries the next description of the ladder; after the last
    // one the cluster is not offered again in this run, so the phone always gets back in.
    @Volatile private var ladderIndex = 0
    @Volatile private var acceptedProfile: String? = null
    val refusedThisRun: Boolean get() = ladderIndex >= ClusterStreamProfile.LADDER.size
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
            AppLog.w("ClusterMap: not offering the cluster display: the phone refused every description of it. Restart DiAuto to try again.")
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
        val profile = ClusterStreamProfile.LADDER[ladderIndex]
        session = Session(profile, display.name, theme)
        offered = true
        AppLog.i("ClusterMap: offering a cluster display (${profile.name}, description ${ladderIndex + 1} of " +
            "${ClusterStreamProfile.LADDER.size}) for ${display.name} ${size.x}x${size.y} theme=$theme plan=$plan")
        profile
    }

    fun onProtocolNegotiated(minor: Int) {
        protocolMinor = minor
    }

    /** The phone set up the main picture, so it accepted the displays it was offered. */
    fun onMainVideoSetup() = synchronized(lock) {
        mainVideoSetUp = true
        val current = session
        if (offered && current != null && acceptedProfile != current.profile.name) {
            acceptedProfile = current.profile.name
            AppLog.i("ClusterMap: the phone accepted the cluster display (${current.profile.name})")
        }
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
        // A new window shows the spinner until a picture reaches it.
        streamActive = false
        decoder?.setSurface(next)
    }

    /** The phone's answer-awaiting focus request for the cluster, already granted by the caller. */
    fun onFocus(mode: com.andrerinas.openheadunit.aap.protocol.proto.Media.VideoFocusMode) {
        AppLog.i("ClusterMap: the phone asked for cluster focus $mode; granted")
    }

    fun endSession() = synchronized(lock) {
        val current = session
        if (offered && !mainVideoSetUp && current != null && !refusedThisRun) {
            ladderIndex++
            AppLog.w("ClusterMap: the phone ended the session that offered the cluster display (${current.profile.name}) " +
                "before setting up any video. " + if (refusedThisRun) "Not offering it again until DiAuto restarts."
                else "The next connection offers ${ClusterStreamProfile.LADDER[ladderIndex].name}.")
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
            "session=${current?.let { "${it.displayName} ${it.profile.name}" } ?: "none"} " +
            "picture=$streamActive protocol=1.$protocolMinor accepted=${acceptedProfile ?: "none"} " +
            "refused=${ClusterStreamProfile.LADDER.take(ladderIndex).joinToString("/") { it.name }.ifEmpty { "none" }}"
    }

    /**
     * A 1.2 session that offered the cluster was closed by a OnePlus 15 right after service
     * discovery. web-auto, whose cluster video works, declares its displays at 1.6.
     */
    private const val MIN_PROTOCOL_MINOR = 6
}
