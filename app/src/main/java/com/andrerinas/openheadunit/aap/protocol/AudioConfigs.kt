package com.andrerinas.openheadunit.aap.protocol

import android.media.AudioManager
import android.util.SparseArray
import com.andrerinas.openheadunit.aap.protocol.proto.Media

import com.andrerinas.openheadunit.decoder.AudioDecoder

object AudioConfigs {
    private val audioTracks = SparseArray<Media.AudioConfiguration>(3)

    // PATEO/Qinggan head units repurpose STREAM_ALARM (4) as their Bluetooth-music stream
    // and the volume knob only drives that stream while BT music is the active source.
    // Playing media there makes Android Auto follow the car's volume controls.
    const val MEDIA_STREAM = AudioManager.STREAM_ALARM

    fun stream(channel: Int, separateAudioStreams: Boolean = true) : Int
    {
        if (separateAudioStreams) {
            return when(channel) {
                Channel.ID_AU1 -> AudioManager.STREAM_VOICE_CALL
                Channel.ID_AU2 -> AudioManager.STREAM_NOTIFICATION
                else -> MEDIA_STREAM
            }
        }
        return MEDIA_STREAM
    }

    fun get(channel: Int): Media.AudioConfiguration {
        return audioTracks.get(channel)
    }

    init {
        val audioConfig0 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_48
            numberOfBits = 16
            numberOfChannels = 2
        }.build()
        audioTracks.put(Channel.ID_AUD, audioConfig0)

        val audioConfig1 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU1, audioConfig1)

        val audioConfig2 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU2, audioConfig2)
    }
}
