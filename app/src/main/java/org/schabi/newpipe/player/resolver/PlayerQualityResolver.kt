package org.schabi.newpipe.player.resolver

import android.content.Context
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.ListHelper
import java.util.function.BooleanSupplier

/**
 * Quality selection policy for the video player.
 *
 * The default resolution comes from a different preference depending on the current player mode:
 * normal video playback uses the normal default resolution, while popup playback and system
 * picture-in-picture use the popup default resolution. Instead of capturing the whole
 * [org.schabi.newpipe.player.Player], the current playback mode is queried through
 * [usesMainVideoResolution] only when a resolution is needed.
 */
class PlayerQualityResolver(
    private val context: Context,
    private val usesMainVideoResolution: BooleanSupplier,
) : QualityResolver {

    override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>): Int =
        if (usesMainVideoResolution.asBoolean) {
            ListHelper.getDefaultResolutionIndex(context, sortedVideos)
        } else {
            ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos)
        }

    override fun getOverrideResolutionIndex(
        sortedVideos: List<VideoStream>,
        selectedResolution: String,
        selectedCodec: String?,
    ): Int = ListHelper.getResolutionAndCodecIndex(selectedResolution, selectedCodec, sortedVideos)

    override fun getCurrentAudioQualityIndex(audioStreams: List<AudioStream>): Int =
        ListHelper.getDefaultAudioFormat(context, audioStreams)
}
