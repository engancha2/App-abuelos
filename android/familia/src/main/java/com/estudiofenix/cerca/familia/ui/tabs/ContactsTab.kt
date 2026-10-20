package com.estudiofenix.cerca.familia.ui.tabs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.Contact
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.ui.EmptyState
import com.estudiofenix.cerca.familia.ui.FamiliaColors
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Los mismos límites que sanitizeConfig() en el servidor
private const val MAX_CONTACTS = 12
private const val MAX_NAME = 40

/** Lo que se está escribiendo en el formulario. [id] null = contacto nuevo. */
private data class ContactForm(val id: String? = null, val name: String = "", val phone: String = "", val whatsapp: Boolean = false)

// Para que el formulario a medias sobreviva a girar el móvil
private val ContactFormSaver = Saver<ContactForm?, List<Any?>>(
    save = { f -> f?.let { listOf(it.id, it.name, it.phone, it.whatsapp) } },
    restore = { ContactForm(it[0] as String?, it[1] as String, it[2] as String, it[3] as Boolean) },
)

/**
 * Pestaña «Contactos», como tabContacts del panel: los favoritos que salen en grande en su pantalla de inicio,
 * en este orden. Uno de ellos es el del botón SOS.
 */
@Composable
fun ContactsTab(vm: SeniorViewModel) {
    val draft = vm.draft ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = draft.contacts
    val sosId = draft.sosContact?.id   // sin elegir, el SOS llama al primero (igual que en su móvil)
    val full = contacts.size >= MAX_CONTACTS

    var form by rememberSaveable(stateSaver = ContactFormSaver) { mutableStateOf<ContactForm?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var photoFor by rememberSaveable { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(emptySet<String>()) }

    // Foto: el selector de fotos de Android (no pide permisos). Se recorta aquí y se sube ya reducida.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = photoFor
        photoFor = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        uploading = uploading + id
        vm.launch {
            try {
                val jpeg = contactPhotoJpeg(context.applicationContext, uri)
                val path = FamilyApi.uploadPhoto(vm.seniorId, jpeg)
                vm.edit { cfg -> cfg.copy(contacts = cfg.contacts.map { if (it.id == id) it.copy(photo = path) else it }) }
            } finally {
                uploading = uploading - id
            }
        }
    }

    // "Elegir de mi agenda": el selector de teléfonos de Android. Da permiso para leer solo esa fila,
    // así que no hace falta pedir acceso a toda la agenda. Rellena el formulario para revisarlo.
    val pickFromAgenda = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = readAgendaPhone(context.applicationContext, uri)
            if (picked == null) {
                vm.toast("No se ha podido leer ese contacto")
            } else {
                val (name, phone) = picked
                form = (form ?: ContactForm()).copy(name = name.trim().take(MAX_NAME), phone = phone.trim())
            }
        }
    }

    fun openAgenda() {
        try {
            pickFromAgenda.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        } catch (e: ActivityNotFoundException) {
            vm.toast("No hay ninguna agenda de contactos en este móvil")
        }
    }

    fun saveForm(f: ContactForm) {
        if (f.id == null && contacts.size >= MAX_CONTACTS) {
            vm.toast("Máximo $MAX_CONTACTS contactos")
            return
        }
        val name = f.name.trim().take(MAX_NAME)
        val phone = Phone.dialable(f.phone.trim())   // como lo deja el servidor: solo cifras, +, * y #
        vm.edit { cfg ->
            val list = if (f.id == null) cfg.contacts + Contact(newContactId(cfg), name, phone, null, f.whatsapp)
            else cfg.contacts.map { if (it.id == f.id) it.copy(name = name, phone = phone, whatsapp = f.whatsapp) else it }
            cfg.copy(contacts = list, sosContactId = cfg.sosContactId ?: list.firstOrNull()?.id)
        }
        form = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint("Aparecen como botones grandes en la pantalla de inicio, en este orden. Máximo $MAX_CONTACTS; lo ideal son 4–6.")

        if (contacts.isEmpty()) SectionCard {
            EmptyState("Aún no hay contactos.", "Añade a las personas a las que más llama: hijos, nietos, el médico…")
        }

        contacts.forEachIndexed { i, c ->
            key(c.id) {
                ContactCard(
                    contact = c,
                    isSos = c.id == sosId,
                    canUp = i > 0,
                    canDown = i < contacts.lastIndex,
                    uploading = c.id in uploading,
                    onUp = { vm.edit { moveContact(it, c.id, -1) } },
                    onDown = { vm.edit { moveContact(it, c.id, +1) } },
                    onPhoto = {
                        photoFor = c.id
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onEdit = { form = ContactForm(c.id, c.name, c.phone, c.whatsapp) },
                    onSos = { vm.edit { it.copy(sosContactId = c.id) } },
                    onRemove = { removing = c.id },
                )
            }
        }

        if (full) Text(
            "Ya hay $MAX_CONTACTS contactos, el máximo. Quita alguno para añadir otro.",
            color = FamiliaColors.warning, style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { form = ContactForm() }, enabled = !full, modifier = Modifier.fillMaxWidth()) {
            Text("＋  Añadir contacto")
        }
        OutlinedButton(onClick = { openAgenda() }, enabled = !full, modifier = Modifier.fillMaxWidth()) {
            Text("Elegir de mi agenda")
        }
    }

    form?.let { f ->
        val editing = f.id?.let { id -> contacts.firstOrNull { it.id == id } }
        // Aviso (sin impedirlo) si ese número ya está en la lista
        val number = Phone.matchKey(f.phone)
        val duplicate = number?.let { n -> contacts.firstOrNull { it.id != f.id && Phone.matchKey(it.phone) == n } }
        ContactFormDialog(
            form = f,
            title = when {
                f.id == null -> "Añadir contacto"
                editing != null -> "Editar a ${editing.name}"
                else -> "Editar contacto"
            },
            duplicateOf = duplicate?.name,
            onChange = { form = it },
            onAgenda = if (f.id == null) ({ openAgenda() }) else null,
            onSave = { saveForm(f) },
            onDismiss = { form = null },
        )
    }

    removing?.let { id ->
        val c = contacts.firstOrNull { it.id == id } ?: return@let
        // Si era el del SOS, pasa al primero que quede
        val nextSos = if (id == sosId) contacts.firstOrNull { it.id != id } else null
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("¿Quitar a ${c.name}?") },
            text = {
                Text(
                    "Dejará de salir en su pantalla de inicio." + when {
                        id != sosId -> ""
                        nextSos != null -> " El botón SOS pasará a llamar a ${nextSos.name}."
                        else -> " Es el contacto del SOS y no queda ningún otro."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        vm.edit { cfg ->
                            val list = cfg.contacts.filterNot { it.id == id }
                            cfg.copy(contacts = list, sosContactId = if (cfg.sosContactId == id) list.firstOrNull()?.id else cfg.sosContactId)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class) // FlowRow: los botones bajan de línea si no caben
@Composable
private fun ContactCard(
    contact: Contact,
    isSos: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    uploading: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPhoto: () -> Unit,
    onEdit: () -> Unit,
    onSos: () -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(contact, uploading)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSos) SosTag()
                }
                Text(
                    contact.phone + if (contact.whatsapp) " · WhatsApp" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.semantics { contentDescription = "Subir" }) {
                Text("↑", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.semantics { contentDescription = "Bajar" }) {
                Text("↓", style = MaterialTheme.typography.titleLarge)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContactAction(if (uploading) "Subiendo…" else "Foto", onPhoto, enabled = !uploading)
            ContactAction("Editar", onEdit)
            if (!isSos) ContactAction("Usar para SOS", onSos)
            ContactAction("Quitar", onRemove, danger = true)
        }
    }
}

/** La foto redonda o, si no hay, la inicial (como en su pantalla de inicio). */
@Composable
private fun ContactAvatar(contact: Contact, uploading: Boolean) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial(contact.name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (contact.photo != null) AsyncImage(
            model = FamilyApi.photoUrl(contact.photo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (uploading) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SosTag() {
    Surface(color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, shape = RoundedCornerShape(50)) {
        Text("SOS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun ContactAction(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.outlinedButtonColors(),
    ) { Text(text) }
}

@Composable
private fun ContactFormDialog(
    form: ContactForm,
    title: String,
    duplicateOf: String?,
    onChange: (ContactForm) -> Unit,
    onAgenda: (() -> Unit)?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val valid = form.name.isNotBlank() && Phone.dialable(form.phone).any { it.isDigit() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAgenda != null) OutlinedButton(onClick = onAgenda, modifier = Modifier.fillMaxWidth()) {
                    Text("Elegir de mi agenda")
                }
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onChange(form.copy(name = it.take(MAX_NAME))) },
                    label = { Text("Nombre que verá") },
                    supportingText = { Text("Corto: «Xavi», «Marta hija» · ${form.name.length}/$MAX_NAME") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.phone,
                    onValueChange = { onChange(form.copy(phone = it)) },
                    label = { Text("Teléfono") },
                    placeholder = { Text("+34 600 000 000") },
                    supportingText = if (duplicateOf != null) {
                        { Text("Este número ya está en la lista: $duplicateOf", color = FamiliaColors.warning) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .toggleable(form.whatsapp, role = Role.Checkbox) { onChange(form.copy(whatsapp = it)) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = form.whatsapp, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Tiene WhatsApp", color = MaterialTheme.colorScheme.onSurface)
                        Hint("Muestra también un botón para abrir su chat.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = valid) { Text("Guardar contacto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Sube ([delta] = -1) o baja (+1) un contacto en la lista. */
private fun moveContact(cfg: Config, id: String, delta: Int): Config {
    val list = cfg.contacts.toMutableList()
    val from = list.indexOfFirst { it.id == id }
    val to = from + delta
    if (from < 0 || to !in list.indices) return cfg
    list[from] = list[to].also { list[to] = list[from] }
    return cfg.copy(contacts = list)
}

/** Id corto aleatorio, como uid() del panel ("k3j9x0qa"). */
private fun newContactId(cfg: Config): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    while (true) {
        val id = (1..8).map { alphabet.random() }.joinToString("")
        if (cfg.contacts.none { it.id == id }) return id
    }
}

/** La primera letra (o emoji) en mayúscula; "?" si no hay nombre. */
private fun initial(name: String): String {
    val n = name.trim()
    return if (n.isEmpty()) "?" else n.substring(0, n.offsetByCodePoints(0, 1)).uppercase()
}

/** Nombre y número de la fila que ha devuelto el selector de teléfonos; null si no se puede leer. */
private suspend fun readAgendaPhone(context: Context, uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
    runCatching {
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val number = c.getString(1).orEmpty()
            if (number.isBlank()) null else c.getString(0).orEmpty() to number
        }
    }.getOrNull()
}
