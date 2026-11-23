package dev.bloom.editor.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderType(val label: String) { IOPaint("IOPaint"), ComfyUI("ComfyUI"), Fal("fal"), OpenAiCompatible("OpenAI-compatible") }

/**
 * A provider the user added. [models] is what Test connection found, per capability; [workflows] are ComfyUI's
 * API-format workflow files per capability. The key is never here: see [Keys].
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val url: String,
    val models: Map<Capability, List<String>> = emptyMap(),
    val workflows: Map<Capability, String> = emptyMap(),
)

/** A provider and one of its models. */
@Serializable
data class Choice(val provider: String, val model: String)

/**
 * The AI settings: added providers, the chosen provider + model per capability, providers the user said not to ask
 * about again before sending a photo crop, and whether cloud features are hidden.
 */
@Serializable
data class AiConfig(
    val providers: List<ProviderConfig> = emptyList(),
    val choices: Map<Capability, Choice> = emptyMap(),
    val consented: Set<String> = emptySet(),
    val hideCloud: Boolean = false,
) {
    private val shown get() = if (hideCloud) emptyList() else providers

    /** Every provider + model that can run [cap]: the phone's first, then the user's providers'. */
    fun options(cap: Capability, phone: AiProvider): List<Choice> =
        phone.models(cap).map { Choice(OnDevice.ID, it) } + shown.flatMap { p -> p.models[cap].orEmpty().map { Choice(p.id, it) } }

    /** What runs [cap]: the user's choice while it is still offered, else the first option (the phone's when it can). */
    fun choice(cap: Capability, phone: AiProvider): Choice? = options(cap, phone).let { o -> choices[cap]?.takeIf { it in o } ?: o.firstOrNull() }

    fun name(id: String) = if (id == OnDevice.ID) "Phone" else providers.firstOrNull { it.id == id }?.name ?: id

    /** Adds [p], or replaces the provider with its id. */
    fun put(p: ProviderConfig) = copy(providers = providers.filter { it.id != p.id } + p)

    /** Removes provider [id] with its choices and consent. */
    fun remove(id: String) = copy(providers = providers.filter { it.id != id }, choices = choices.filterValues { it.provider != id }, consented = consented - id)

    fun needsConsent(id: String) = id != OnDevice.ID && id !in consented

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): AiConfig =
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).getString("config", null)?.let { runCatching { json.decodeFromString<AiConfig>(it) }.getOrNull() } ?: AiConfig()

        fun save(context: Context, c: AiConfig) {
            context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putString("config", json.encodeToString(c)).apply()
        }
    }
}

/** The provider [c] describes, with its key. */
fun open(c: ProviderConfig, key: String?): AiProvider = when (c.type) {
    ProviderType.IOPaint -> IoPaint(c.url, key, c.models)
    ProviderType.ComfyUI -> ComfyUi(c.url, key, c.workflows)
    ProviderType.Fal -> Fal(key.orEmpty(), c.models)
    ProviderType.OpenAiCompatible -> OpenAiCompatible(c.url, key, c.models)
}

/**
 * Provider keys, encrypted with an AES-GCM key that lives in the Android Keystore (it never leaves it), in their own
 * prefs file. Never logged, never shown again, never in a project file.
 */
object Keys {
    private const val ALIAS = "bloom_provider_keys"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ai_keys", Context.MODE_PRIVATE)

    fun put(context: Context, id: String, key: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val sealed = c.iv + c.doFinal(key.toByteArray())
        prefs(context).edit().putString(id, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /** The key for provider [id]; null if there is none or it can't be decrypted (e.g. the Keystore key was reset). */
    fun get(context: Context, id: String): String? {
        val sealed = prefs(context).getString(id, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, sealed, 0, 12)) }
            String(c.doFinal(sealed, 12, sealed.size - 12))
        }.getOrNull()
    }

    fun has(context: Context, id: String) = prefs(context).contains(id)

    fun forget(context: Context, id: String) = prefs(context).edit().remove(id).apply()
}
