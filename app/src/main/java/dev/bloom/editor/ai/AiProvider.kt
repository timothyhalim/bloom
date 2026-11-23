package dev.bloom.editor.ai

import android.graphics.Bitmap
import dev.bloom.editor.SubjectMask

/** What an AI action does; the user picks a provider and model for each in Settings. */
enum class Capability(val label: String) {
    SEGMENT("Select subject"),
    ERASE("Erase"),
    GEN_FILL("Fill"),
    UPSCALE("Upscale"),
    BG_REPLACE("Replace background"),
    EDIT_COMMAND("Ask"),
}

/**
 * One AI job: [image] (a crop of the photo, or the whole photo for SEGMENT and UPSCALE; none for EDIT_COMMAND), [mask]
 * (image-sized bytes, non-zero = the area to change), the pins for SEGMENT (x, y in 0..1, label), the prompt for
 * GEN_FILL or the whole message for EDIT_COMMAND, and the factor for UPSCALE.
 */
class AiRequest(val image: Bitmap? = null, val mask: ByteArray? = null, val pins: List<Float> = emptyList(), val prompt: String = "", val scale: Int = 2)

sealed interface AiResult {
    /** ERASE, GEN_FILL, UPSCALE: the new image (any size; the caller scales it to the crop). */
    class Image(val bitmap: Bitmap) : AiResult

    /** SEGMENT: the subject's mask over the photo. */
    class Mask(val mask: SubjectMask) : AiResult

    /** EDIT_COMMAND: the model's reply, unchecked; [EditOps.parse] reads it. */
    class Text(val text: String) : AiResult
}

/** Thrown for a failure the user should read as is: the provider's own message, or why a reply was refused. */
class AiError(message: String) : Exception(message)

/** Where AI actions run: the phone, the user's server, or a cloud service with the user's key. */
interface AiProvider {
    /** The models this provider can run [cap] with; empty if it can't. */
    fun models(cap: Capability): List<String>

    /** Asks the provider what it can do now (Test connection): models per capability. Throws if it can't be reached. */
    suspend fun test(): Map<Capability, List<String>>

    /**
     * Runs [cap] with [model]. [status] gets what it is doing ("Uploading 40 %", "Queued, 3 ahead", "Step 12 / 30").
     * Cancelling the coroutine cancels the job and closes its connection.
     */
    suspend fun run(cap: Capability, model: String, req: AiRequest, status: (String) -> Unit): AiResult
}
