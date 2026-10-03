package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.AppLog
import com.foreverjukebox.app.audio.BufferedAudioPlayer

/**
 * Renders the loaded track's Wub Machine remix: plans the arrangement, feeds the native renderer
 * the track snapshot and the decoded samples it names, and installs the result on the remix
 * player. Blocks for the whole render, so call it off the main thread.
 */
class WubMachineRemixRenderer(
    private val sourcePlayer: BufferedAudioPlayer,
    private val samples: DubstepSampleLibrary,
    private val controller: WubMachineController,
    private val options: DubstepPlanOptions = WUB_MACHINE_PLAN_OPTIONS
) {
    fun hasRemix(): Boolean = controller.isReady()

    fun currentRemix(): WubRemix? = controller.remix()

    /**
     * Null when the render was cancelled, failed, or the track's audio was replaced meanwhile.
     * [onProgress] receives completed and total slices; returning false cancels.
     */
    @Suppress("TooGenericExceptionCaught")
    fun render(
        analysis: DubstepAnalysis,
        onProgress: (completed: Int, total: Int) -> Boolean
    ): WubRemix? {
        val plan = planDubstepRemix(analysis, options)
        val request = buildWubRenderRequest(
            plan = plan,
            sampleRate = sourcePlayer.getSampleRate(),
            sourceFrames = sourcePlayer.getFrameCount()
        )
        val job = sourcePlayer.beginWubRender(request) ?: return null
        try {
            request.sampleNames.forEachIndexed { index, name ->
                val pcm = samples.load(name)
                val added = job.addSample(index, pcm.data, pcm.dataLength, pcm.sampleRate, pcm.channelCount)
                if (!added) return null
            }
            samples.retainOnly(request.sampleNames)
            if (!job.run(onProgress)) return null
            val installed = controller.install(job) { durationSeconds ->
                layoutWubParts(
                    plan = plan,
                    sampleRate = request.sampleRate,
                    partFrames = request.partFrames,
                    totalFrames = Math.round(durationSeconds * request.sampleRate).toInt()
                )
            }
            return if (installed) controller.remix() else null
        } catch (error: Exception) {
            AppLog.error(TAG, "Wub Machine render failed", error)
            return null
        } finally {
            // Idempotent: a job the controller consumed has nothing left to free.
            job.discard()
        }
    }

    private companion object {
        const val TAG = "WubMachineRemixRenderer"
    }
}
