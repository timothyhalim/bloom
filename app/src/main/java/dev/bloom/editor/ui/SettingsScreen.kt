package dev.bloom.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.ModelStore
import dev.bloom.editor.Segment
import dev.bloom.editor.ai.AiConfig
import dev.bloom.editor.ai.AiProvider
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.ai.ComfyUi
import dev.bloom.editor.ai.Fal
import dev.bloom.editor.ai.Keys
import dev.bloom.editor.ai.ProviderConfig
import dev.bloom.editor.ai.ProviderType
import dev.bloom.editor.ai.open
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** "Phone · LaMa", "My server · lama". */
fun AiConfig.label(c: Choice) = "${name(c.provider)} · ${c.model}"

/**
 * Settings: which provider and model runs each AI action, the user's providers (add, replace or forget the key,
 * delete), on-device model files, privacy, about. [ai] is saved by the caller.
 */
@Composable
fun SettingsScreen(ai: AiConfig, phone: AiProvider, onChange: (AiConfig) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    var adding by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<ProviderConfig?>(null) }
    var keysTick by remember { mutableIntStateOf(0) } // a key was replaced or forgotten
    // drawn over the editor: the empty pointerInput keeps touches from reaching it
    Column(Modifier.fillMaxSize().background(Bloom.Canvas).pointerInput(Unit) {}.statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Back, "Close settings", tint = Bloom.On) }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Bloom.On)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("AI providers")
            for (cap in Capability.entries) {
                val options = ai.options(cap, phone)
                if (options.isEmpty()) continue
                val chosen = ai.choice(cap, phone)
                Text(cap.label, Modifier.padding(top = 12.dp), color = Bloom.On, fontWeight = FontWeight.Medium)
                for (o in options) Radio(ai.label(o), o == chosen) { onChange(ai.copy(choices = ai.choices + (cap to o))) }
            }
            if (!ai.hideCloud) {
                keysTick
                for (p in ai.providers) ProviderRow(
                    p, Keys.has(context, p.id),
                    onReplace = { replacing = p },
                    onForget = { Keys.forget(context, p.id); keysTick++ },
                    onDelete = { Keys.forget(context, p.id); onChange(ai.remove(p.id)) },
                )
                if (adding) AddProvider(onCancel = { adding = false }) { p, key ->
                    if (key.isNotBlank()) Keys.put(context, p.id, key.trim())
                    onChange(ai.put(p))
                    adding = false
                }
                else TextButton({ adding = true }) { Text("+ Add provider", color = Bloom.Pollen) }
            }

            Section("On-device models")
            OnDeviceModels()

            Section("Privacy")
            TextButton({ onChange(ai.copy(consented = emptySet())) }, enabled = ai.consented.isNotEmpty()) {
                Text("Reset consent choices", color = if (ai.consented.isNotEmpty()) Bloom.Pollen else Bloom.Outline)
            }
            Text("Bloom asks again before a photo crop goes to a provider.", fontSize = 12.sp, color = Bloom.OnVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)
                    .toggleable(ai.hideCloud, role = Role.Switch) { onChange(ai.copy(hideCloud = it)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hide cloud features", color = Bloom.On)
                    Text("Only the phone runs AI; your providers are kept but not offered.", fontSize = 12.sp, color = Bloom.OnVariant)
                }
                Switch(ai.hideCloud, null)
            }

            Section("About")
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            Text("Bloom $version · GPL-3.0", color = Bloom.On)
            for ((what, license) in LICENSES) Text("$what — $license", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Bloom.OnVariant)
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
    replacing?.let { p ->
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("New key for ${p.name}") },
            text = { KeyField(key) { key = it } },
            confirmButton = {
                TextButton({ Keys.put(context, p.id, key.trim()); keysTick++; replacing = null }, enabled = key.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton({ replacing = null }) { Text("Cancel") } },
            containerColor = Bloom.SurfaceHigh,
        )
    }
}

private val LICENSES = listOf(
    "IS-Net (subject masks)" to "Apache-2.0",
    "MobileSAM (tap to select)" to "Apache-2.0",
    "Depth Anything V2 Small (lens blur, depth masks)" to "Apache-2.0",
    "Big LaMa (AI eraser)" to "Apache-2.0",
    "LibRaw 0.22.3 (camera raw)" to "LGPL-2.1",
    "MNN 3.6.2 (on-device AI)" to "Apache-2.0",
)

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 24.dp, bottom = 4.dp), color = Bloom.Pollen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = 12.dp), color = Bloom.On)
    }
}

@Composable
private fun KeyField(key: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        key, onChange, Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

/** One provider: name, type and address; Replace key, Forget key, Delete. */
@Composable
private fun ProviderRow(p: ProviderConfig, hasKey: Boolean, onReplace: () -> Unit, onForget: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Bloom.Surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Bloom.On, fontWeight = FontWeight.Medium)
                Text("${p.type.label} · ${if (p.type == ProviderType.Fal) "fal.ai" else p.url}", fontSize = 12.sp, color = Bloom.OnVariant)
            }
            IconButton(onDelete) { Icon(Icons.Trash, "Delete ${p.name}", tint = Bloom.On) }
        }
        Row {
            TextButton(onReplace) { Text(if (hasKey) "Replace key" else "Add key", color = Bloom.Pollen) }
            if (hasKey) TextButton(onForget) { Text("Forget key", color = Bloom.Pollen) }
        }
    }
}

/**
 * The Add provider form: type, name, address, key (and ComfyUI's workflows). Save is enabled only once Test connection
 * passed for exactly what is entered, and shows what it found.
 */
@Composable
private fun AddProvider(onCancel: () -> Unit, onSave: (ProviderConfig, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var type by remember { mutableStateOf(ProviderType.IOPaint) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var workflows by remember { mutableStateOf(emptyMap<Capability, String>()) }
    var workflowError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var passed by remember { mutableStateOf<ProviderConfig?>(null) } // what passed the test, with the models found
    val draft = ProviderConfig("", type, name.trim().ifEmpty { type.label }, if (type == ProviderType.Fal) Fal.QUEUE else url.trim(), workflows = workflows)
    var picking by remember { mutableStateOf(Capability.ERASE) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cap = picking
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { s -> s.readNBytesCompat(1 shl 20) ?: error("The file is larger than 1 MB") }.decodeToString()
                }
            }
            val err = text.exceptionOrNull()?.message ?: ComfyUi.check(text.getOrThrow(), cap)
            workflowError = err?.let { "${cap.label}: $it" }
            if (err == null) workflows = workflows + (cap to text.getOrThrow())
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Bloom.Surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add provider", color = Bloom.On, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().scrollFade(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in ProviderType.entries) Chip(t.label, t == type) { type = t; result = null }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text(type.label) }, singleLine = true)
        if (type != ProviderType.Fal) OutlinedTextField(
            url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Address") }, singleLine = true,
            placeholder = { Text(if (type == ProviderType.ComfyUI) "http://192.168.1.10:8188" else "http://192.168.1.10:8080") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        KeyField(key) { key = it }
        if (url.trim().startsWith("http://") && type != ProviderType.Fal) {
            Text("This address isn't encrypted: on a shared network others can see the photo crops and the key.", fontSize = 12.sp, color = Bloom.OnVariant)
        }
        if (type == ProviderType.ComfyUI) {
            Text("Workflows, exported with Save (API format); see docs/comfyui", fontSize = 12.sp, color = Bloom.OnVariant)
            for (cap in listOf(Capability.ERASE, Capability.GEN_FILL, Capability.UPSCALE)) {
                TextButton({ picking = cap; pick.launch(arrayOf("application/json", "*/*")) }) {
                    Text("${cap.label} workflow: ${if (cap in workflows) "loaded" else "choose…"}", color = Bloom.Pollen)
                }
            }
            workflowError?.let { Text(it, fontSize = 12.sp, color = Bloom.On) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({
                testing = true
                result = null
                val tested = draft
                scope.launch {
                    val r = runCatching { open(tested, key.trim().ifEmpty { null }).test() }
                    testing = false
                    r.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    val found = r.getOrNull()
                    passed = found?.takeIf { it.isNotEmpty() }?.let { tested.copy(models = it) }
                    result = when {
                        found == null -> "Couldn't connect: ${r.exceptionOrNull()?.message ?: r.exceptionOrNull()}"
                        found.isEmpty() -> "Connected, but it offers nothing Bloom can use"
                        else -> "Found: " + found.entries.joinToString("; ") { (c, m) -> "${c.label} (${m.joinToString(", ")})" }
                    }
                }
            }, enabled = !testing && (type == ProviderType.Fal || url.isNotBlank()) && (type != ProviderType.Fal || key.isNotBlank())) {
                Text("Test connection", color = Bloom.Pollen)
            }
            if (testing) CircularProgressIndicator(Modifier.size(18.dp), color = Bloom.Pollen, strokeWidth = 2.dp)
        }
        result?.let { Text(it, fontSize = 12.sp, color = Bloom.On) }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel", color = Bloom.OnVariant) }
            val ok = passed?.let { it.copy(models = emptyMap()) == draft } == true
            TextButton({ onSave(passed!!.copy(id = UUID.randomUUID().toString()), key) }, enabled = ok) {
                Text("Save", color = if (ok) Bloom.Pollen else Bloom.Outline)
            }
        }
    }
}

/** Reads at most [max] bytes; null if there is more. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1 shl 14)
    while (true) {
        val n = read(buf)
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
}

/** The model files from models.json: name, size, and Delete once downloaded. */
@Composable
private fun ColumnScope.OnDeviceModels() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val models = remember(tick) {
        val specs = ModelStore.specs(context)
        specs.keys().asSequence().map { k ->
            val s = specs.getJSONObject(k)
            Triple(k, s.getString("about").substringBefore(" ("), ModelStore.file(context, k).takeIf { it.exists() }?.length() ?: -s.getLong("size"))
        }.toList()
    }
    for ((k, title, size) in models) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Bloom.On)
            val mb = "${(kotlin.math.abs(size) + 500_000) / 1_000_000} MB"
            Text(if (size >= 0) mb else "Not downloaded · $mb", fontSize = 12.sp, color = Bloom.OnVariant)
        }
        if (size >= 0) IconButton({ ModelStore.delete(context, k); Segment.release(); tick++ }) { Icon(Icons.Trash, "Delete $title", tint = Bloom.On) }
        else Spacer(Modifier.width(48.dp))
    }
}

/**
 * Asked before a photo crop first goes to [choice]'s provider: what is sent ([what]), where ([host]), Send / Not now /
 * a different provider ([others]), and Don't ask again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(ai: AiConfig, choice: Choice, host: String, what: String, others: List<Choice>, onSend: (remember: Boolean) -> Unit, onOther: (Choice) -> Unit, onDismiss: () -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val name = ai.name(choice.provider)
    ModalBottomSheet(onDismiss, containerColor = Bloom.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send to $name?", fontSize = 20.sp, color = Bloom.On, fontWeight = FontWeight.Medium)
            Text("This sends $what to $host. Bloom doesn't store it anywhere else; $name's own terms apply.", color = Bloom.OnVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(dontAsk, role = Role.Checkbox) { dontAsk = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(dontAsk, null)
                Text("Don't ask again for $name", Modifier.padding(start = 8.dp), color = Bloom.On)
            }
            if (choosing) for (o in others) Text(
                ai.label(o), Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOther(o) }.padding(vertical = 14.dp), color = Bloom.On,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (others.isNotEmpty() && !choosing) TextButton({ choosing = true }) { Text("Use a different provider", color = Bloom.OnVariant) }
                TextButton(onDismiss) { Text("Not now", color = Bloom.OnVariant) }
                TextButton({ onSend(dontAsk) }) { Text("Send", color = Bloom.Pollen) }
            }
        }
    }
}
