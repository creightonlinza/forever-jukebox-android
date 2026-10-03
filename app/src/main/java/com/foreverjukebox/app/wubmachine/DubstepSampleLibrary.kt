package com.foreverjukebox.app.wubmachine

import android.content.Context
import android.content.res.AssetManager
import com.foreverjukebox.app.audio.DecodedPcm
import com.foreverjukebox.app.audio.decodePcm

/** Loads a dubstep sample by plan name as interleaved 16-bit PCM at its own rate. */
interface DubstepSampleSource {
    fun load(name: String): DecodedPcm
}

/**
 * The Wub Machine samples (see assets/wubmachine/LICENSE), shipped as Opus in WebM and decoded
 * on demand. Decoded beds stay cached for the next remix; [retainOnly] trims the cache to the
 * samples a remix used so one key's beds do not pile up on another's.
 */
class DubstepSampleLibrary(context: Context) : DubstepSampleSource {
    private val assets: AssetManager = context.applicationContext.assets
    private val cache = LinkedHashMap<String, DecodedPcm>()

    @Synchronized
    override fun load(name: String): DecodedPcm {
        require(name in DUBSTEP_SAMPLE_NAMES) { "Unknown dubstep sample: $name" }
        return cache.getOrPut(name) {
            decodePcm(
                onProgress = null,
                configureDataSource = { extractor ->
                    assets.openFd("$ASSET_ROOT/$name.webm").use { descriptor ->
                        extractor.setDataSource(
                            descriptor.fileDescriptor,
                            descriptor.startOffset,
                            descriptor.length
                        )
                    }
                }
            )
        }
    }

    @Synchronized
    fun retainOnly(names: Collection<String>) {
        cache.keys.retainAll(names.toSet())
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    companion object {
        const val ASSET_ROOT = "wubmachine/dubstep"
    }
}
