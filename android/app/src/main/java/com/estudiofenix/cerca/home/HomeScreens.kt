package com.estudiofenix.cerca.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.estudiofenix.cerca.calls.CallManager
import com.estudiofenix.cerca.calls.WhatsApp
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.data.Apps
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.Contact
import com.estudiofenix.cerca.data.Favorites
import com.estudiofenix.cerca.data.PhoneBook
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.setup.DeviceChecks
import com.estudiofenix.cerca.ui.Avatar
import com.estudiofenix.cerca.ui.BackButton
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.cardBorder
import com.estudiofenix.cerca.ui.CenteredMessage
import com.estudiofenix.cerca.ui.CercaIcons
import com.estudiofenix.cerca.ui.FitText
import com.estudiofenix.cerca.ui.Palette
import com.estudiofenix.cerca.ui.PhotoHeader
import com.estudiofenix.cerca.ui.rememberTap
import com.estudiofenix.cerca.ui.strongBorder
import com.estudiofenix.cerca.ui.Tap
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val spanish = Locale("es", "ES")

/** Mantener pulsado [millis] para activar. Evita SOS y menús abiertos sin querer. */
private fun Modifier.holdToTrigger(
    millis: Int,
    progress: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    onTrigger: () -> Unit,
) = composed {
    // La lambda cambia en cada recomposición (y la animación recompone): no puede ser la clave de pointerInput,
    // o el gesto se reinicia a mitad de pulsación.
    val trigger by rememberUpdatedState(onTrigger)
    pointerInput(Unit) {
    detectTapGestures(onPress = {
        val job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(millis, easing = LinearEasing))
            trigger()
        }
        try {
            tryAwaitRelease()
        } finally {
            // También si el gesto se cancela: que no se dispare solo
            job.cancel()
            scope.launch { progress.snapTo(0f) }
        }
    })
    }
}

// ====================================================================== INICIO
@Composable
fun HomeScreen(
    cfg: Config,
    paired: Boolean,
    inCall: Boolean,
    onReturnToCall: () -> Unit,
    onContact: (Contact) -> Unit,
    onContacts: () -> Unit,
    onDialer: () -> Unit,
    onMessages: () -> Unit,
    onApps: () -> Unit,
    onSos: () -> Boolean,
    onAdmin: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableStateOf(DeviceChecks.battery(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            battery = DeviceChecks.battery(ctx)
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)).coerceAtLeast(1L))
        }
    }
    val adminHold = remember { Animatable(0f) }
    val sosHold = remember { Animatable(0f) }
    var showPin by remember { mutableStateOf(false) }
    var sosNotice by remember { mutableStateOf(false) }
    val tap = rememberTap()
    // El reloj ya es enorme: con letra grande crece menos que lo demás, para dejar sitio a los contactos
    val fontScale = LocalDensity.current.fontScale
    val clockSize = (84f * minOf(fontScale, 1.25f) / fontScale).sp

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal = 16.dp),
    ) {
        // ---- Reloj (mantener pulsado 3 s = menú de familia) ----
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .holdToTrigger(3000, adminHold, scope) { Tap.strong(ctx); if (cfg.pinHash == null) onAdmin() else showPin = true },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Una sola línea: con letra grande la hora se partía en dos
            FitText(
                SimpleDateFormat("HH:mm", spanish).format(now), clockSize,
                fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onBackground,
            )
            if (cfg.ui.showDate) {
                Text(
                    SimpleDateFormat("EEEE, d 'de' MMMM", spanish).format(now).replaceFirstChar { it.titlecase(spanish) },
                    fontSize = 24.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground,
                )
            }
            // La batería ya sale en la barra de arriba del móvil: aquí solo se avisa cuando queda poca,
            // que es cuando importa (y el iconito de arriba no se ve bien). Así hay más sitio para los contactos
            val (level, charging) = battery
            if (level != null && level <= 20 && !charging) {
                Text(
                    "🔋 Batería baja ($level%): ponlo a cargar",
                    fontSize = 22.sp, textAlign = TextAlign.Center, color = Palette.red, fontWeight = FontWeight.Bold,
                )
            }
            if (adminHold.value > 0f) {
                Box(Modifier.fillMaxWidth(adminHold.value).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Llamada en curso: si se ha salido de la pantalla de llamada (p. ej. con el botón de inicio) ----
        if (inCall) {
            BigButton("Volver a la llamada", Palette.green, height = 72.dp, icon = CercaIcons.Call, onClick = onReturnToCall)
            Spacer(Modifier.height(12.dp))
        }

        // ---- Contactos ----
        Box(Modifier.weight(1f)) {
            if (cfg.contacts.isEmpty()) {
                CenteredMessage(
                    if (paired) "Aún no hay contactos" else "Hola",
                    if (paired) "Tu familia los añadirá desde su panel." else "Pide a tu familia que configure este móvil.",
                    Modifier.verticalScroll(rememberScrollState()),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    // distinctBy: un id repetido desde el servidor cerraría la app (claves duplicadas)
                    items(cfg.contacts.distinctBy { it.id }, key = { it.id }) { c -> ContactTile(c) { onContact(c) } }
                }
            }
        }

        // ---- SOS: mantener pulsado 2 s ----
        // Justo debajo de los contactos, lejos del borde de abajo (donde se apoya la mano) y más bajo que antes:
        // en el borde inferior y tan grande se podía pulsar sin querer
        Box(
            // Altura mínima, no fija: con letra grande el texto ocupa dos líneas y antes se cortaba
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp)).background(Palette.red.copy(alpha = 0.85f))
                .drawBehind { drawRect(Palette.red, size = size.copy(width = size.width * sosHold.value)) }
                .holdToTrigger(2000, sosHold, scope) { Tap.strong(ctx); if (!onSos()) sosNotice = true },
            contentAlignment = Alignment.Center,
        ) {
            FitText(
                if (sosHold.value > 0f) "Sigue pulsando…" else "SOS  ·  mantén pulsado",
                24.sp, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 2,
            )
        }
        Spacer(Modifier.height(16.dp))

        // ---- Botones fijos, de dos en dos ----
        // Abajo del todo, donde mejor llega el dedo, lo que más se usa: Contactos y Teléfono.
        // Encima, Aplicaciones (y Mensajes, si la familia lo activa)
        val upper = buildList<Pair<String, () -> Unit>> {
            if (cfg.ui.showSms) add("Mensajes" to onMessages)
            add("Aplicaciones" to onApps)
        }
        val lower = buildList<Pair<String, () -> Unit>> {
            if (cfg.ui.showContacts) add("Contactos" to onContacts)
            add("Teléfono" to onDialer)
        }
        // Si solo hay dos botones, en una sola fila
        val rows = if (upper.size + lower.size <= 2) listOf(lower + upper) else upper.chunked(2) + lower.chunked(2)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, onClick) ->
                    OutlinedButton(
                        onClick = { tap(); onClick() }, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp),
                        border = strongBorder(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        // Con letra grande, «Aplicaciones» no cabía en media fila y se cortaba: se encoge lo justo
                        FitText(label, 22.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showPin) PinDialog(onDismiss = { showPin = false }, onOk = { showPin = false; onAdmin() })
    if (sosNotice) SosNotice(paired) { sosNotice = false }
}

/** Si el SOS no ha podido llamar a nadie, que al menos se vea que ha hecho algo. */
@Composable
private fun SosNotice(paired: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (paired) "Avisando a tu familia" else "No se ha podido avisar", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                if (paired) "Tu familia recibirá un aviso de emergencia."
                else "Este móvil aún no está conectado con tu familia. Si es una urgencia, llama al 112.",
                fontSize = 22.sp,
            )
        },
        confirmButton = { BigButton("Vale", Palette.brand, height = 72.dp, onClick = onDismiss) },
    )
}

/** Foto de la agenda de cada favorito sin foto de la familia ("" = no tiene): así el inicio no parpadea cada vez. */
private val bookPhotos = mutableMapOf<String, String>()

/** Si la familia no ha puesto foto a un favorito, la de la agenda del móvil (si la tiene). */
@Composable
private fun rememberBookPhoto(c: Contact): String? {
    val ctx = LocalContext.current
    val photo by produceState(if (c.photo == null) bookPhotos[c.phone] else "", c.phone, c.photo) {
        if (c.photo != null) return@produceState
        val found = withContext(Dispatchers.IO) { PhoneBook.lookup(ctx, c.phone)?.photo }.orEmpty()
        bookPhotos[c.phone] = found
        value = found
    }
    return photo?.ifEmpty { null }
}

@Composable
private fun ContactTile(c: Contact, onClick: () -> Unit) {
    val tap = rememberTap()
    Card(
        onClick = { tap(); onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = cardBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(c.photo, c.name, 112.dp, photoUri = rememberBookPhoto(c))
            Spacer(Modifier.height(8.dp))
            // Dos líneas sin partir palabras: con letra grande, «Antonio» se quedaba en «Anton…»
            FitText(
                c.name, 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ====================================================================== CONTACTO
/** La estrella de los favoritos: amarillo oscuro, que se ve sobre blanco y sobre negro. */
private val STAR = Color(0xFFE0A100)

@Composable
fun ContactScreen(c: Contact, showSms: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bookPhoto = rememberBookPhoto(c)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Con letra grande ya no cabía todo y "Volver" se salía por abajo: ahora la foto se encoge
        PhotoHeader(
            maxPhoto = 220.dp,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp),
            photo = { Avatar(c.photo, c.name, it, photoUri = bookPhoto) },
        ) {
            FitText(c.name, 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3,
                color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(16.dp))
        // Llamar es lo principal: el botón más grande
        BigButton("Llamar", Palette.green, height = 120.dp, icon = CercaIcons.Call) {
            CallManager.placeCall(ctx, c.phone)
            onBack()
        }
        MessageButtons(listOf(c.phone), whatsappMarked = c.whatsapp, showSms = showSms)
        Spacer(Modifier.height(16.dp))
        BackButton(onBack)
    }
}

/**
 * Debajo de "Llamar": videollamada y chat de WhatsApp (uno al lado del otro) y, si la familia lo ha activado, SMS.
 * WhatsApp sale si la familia lo ha marcado en el contacto o si la agenda del móvil dice que lo tiene.
 */
@Composable
private fun MessageButtons(numbers: List<String>, whatsappMarked: Boolean, showSms: Boolean, compact: Boolean = false) {
    val ctx = LocalContext.current
    val number = numbers.firstOrNull() ?: return
    val whatsApp by produceState<WhatsApp.Info?>(null, numbers) {
        value = withContext(Dispatchers.IO) { WhatsApp.info(ctx, numbers) }
    }
    val wa = whatsApp
    val gap = if (compact) 12.dp else 16.dp
    val height = if (compact) 72.dp else 84.dp
    // Uno por fila y a lo ancho: de dos en dos, "Videollamada" no cabía y se partía ("Videollamad / a")
    if (wa != null && (whatsappMarked || wa.videoCall != null)) {
        Spacer(Modifier.height(gap))
        BigButton("Videollamada", Palette.video, height = height, icon = CercaIcons.Video) { WhatsApp.videoCall(ctx, wa, number) }
        Spacer(Modifier.height(gap))
        BigButton("WhatsApp", Palette.chat, height = height, icon = CercaIcons.Chat) { WhatsApp.openChat(ctx, number) }
    }
    if (showSms) {
        Spacer(Modifier.height(gap))
        BigButton("SMS", Palette.sms, height = height, icon = CercaIcons.Sms) { CallManager.openSms(ctx, number) }
    }
}

// ====================================================================== AGENDA
/** Lo que la agenda recuerda al abrir una ficha y volver: los contactos ya leídos, lo buscado y por dónde iba la lista. */
@Stable
class PhoneBookState {
    var entries by mutableStateOf<List<PhoneBook.Entry>?>(null)
    var query by mutableStateOf("")
    val list = LazyListState()
}

private val accents = "\\p{Mn}+".toRegex()

/** Para buscar sin distinguir mayúsculas ni tildes: «María» → «maria». */
private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(accents, "").lowercase(spanish)

/** Coincide si tiene todas las palabras buscadas (en cualquier orden) o, si se buscan cifras, alguno de sus números. */
private fun matches(e: PhoneBook.Entry, plainName: String, words: List<String>, digits: String): Boolean =
    words.all { plainName.contains(it) } ||
        (digits.length >= 3 && e.numbers.any { n -> n.number.filter { it.isDigit() }.contains(digits) })

@Composable
fun ContactsScreen(cfg: Config, state: PhoneBookState, onEntry: (PhoneBook.Entry) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var allowed by remember { mutableStateOf(PhoneBook.allowed(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        denied = !it
    }
    // Al volver de los ajustes de Android, por si la familia ha dado el permiso desde allí
    LifecycleResumeEffect(Unit) {
        allowed = PhoneBook.allowed(ctx)
        onPauseOrDispose { }
    }
    LaunchedEffect(allowed) {
        if (allowed && state.entries == null) {
            state.entries = withContext(Dispatchers.IO) { runCatching { PhoneBook.load(ctx) }.getOrDefault(emptyList()) }
        }
    }
    // Los nombres se preparan una vez para buscar: filtrar a cada letra no cuesta nada
    val searchable = remember(state.entries) { state.entries.orEmpty().map { it to plain(it.name) } }
    val results = remember(searchable, state.query) {
        val words = plain(state.query).split(' ').filter { it.isNotBlank() }
        val digits = state.query.filter { it.isDigit() }
        if (words.isEmpty()) searchable.map { it.first }
        else searchable.filter { (e, name) -> matches(e, name, words, digits) }.map { it.first }
    }
    // Al deslizar la lista se esconde el teclado: deja más sitio para ver los resultados
    val scrolling = state.list.isScrollInProgress
    LaunchedEffect(scrolling) { if (scrolling) keyboard?.hide() }

    // safeDrawingPadding incluye el teclado: con él abierto, la lista se acorta en vez de quedar tapada
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        FitText("Contactos", 32.sp, Modifier.padding(bottom = 12.dp), fontWeight = FontWeight.Bold, maxLines = 1,
            color = MaterialTheme.colorScheme.onBackground)
        val list = state.entries
        if (allowed && !list.isNullOrEmpty()) {
            SearchField(
                state.query,
                onChange = {
                    state.query = it
                    state.list.requestScrollToItem(0) // los resultados, desde el principio
                },
                onSearch = { keyboard?.hide() },
            )
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.weight(1f)) {
            when {
                !allowed -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Si Android ya no enseña la ventana del permiso (se ha denegado), solo queda ir a los ajustes
                    CenteredMessage(
                        "Falta un permiso",
                        if (denied) "Abre los ajustes de Cerca, entra en «Permisos» y permite «Contactos»."
                        else "Para ver la agenda, Cerca necesita permiso para leer los contactos del móvil.",
                    )
                    BigButton(if (denied) "Abrir ajustes" else "Permitir", Palette.brand) {
                        if (denied) DeviceChecks.startFirst(ctx, listOf(DeviceChecks.appDetails(ctx)))
                        else askPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
                list == null -> CenteredMessage("Cargando…")
                list.isEmpty() -> CenteredMessage(
                    "La agenda está vacía", "Los contactos que se guarden en este móvil aparecerán aquí.",
                    Modifier.verticalScroll(rememberScrollState()),
                )
                results.isEmpty() -> CenteredMessage(
                    "No hay nadie con «${state.query.trim()}»", "Prueba con otra parte del nombre o con el número.",
                    Modifier.verticalScroll(rememberScrollState()),
                )
                else -> LazyColumn(state = state.list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(results, key = { it.id }) { e ->
                        PhoneBookRow(e, favorite = e.numbers.any { Favorites.isFavorite(cfg, it.number) }) {
                            keyboard?.hide()
                            onEntry(e)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BackButton(onBack)
    }
}

/** Buscador grande: letra grande, lupa y un botón para borrar lo escrito de una vez. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 26.sp),
        shape = RoundedCornerShape(20.dp),
        leadingIcon = { Icon(CercaIcons.Search, contentDescription = null, modifier = Modifier.size(32.dp)) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                val tap = rememberTap()
                IconButton(onClick = { tap(); onChange("") }, modifier = Modifier.size(56.dp)) {
                    Icon(CercaIcons.Clear, contentDescription = "Borrar lo escrito", modifier = Modifier.size(32.dp))
                }
            }
        },
        placeholder = { FitText("Buscar nombre o número", 22.sp, maxLines = 1) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun PhoneBookRow(e: PhoneBook.Entry, favorite: Boolean, onClick: () -> Unit) {
    val tap = rememberTap()
    Card(
        onClick = { tap(); onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = cardBorder(),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(null, e.name, 64.dp, photoUri = e.thumbnail ?: e.photo)
            Spacer(Modifier.size(16.dp))
            FitText(e.name, 26.sp, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface)
            if (favorite) {
                Icon(CercaIcons.Star, contentDescription = "Favorito", tint = STAR, modifier = Modifier.padding(start = 8.dp).size(34.dp))
            }
        }
    }
}

@Composable
fun PhoneBookContactScreen(e: PhoneBook.Entry, cfg: Config, onCalled: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val onBackground = MaterialTheme.colorScheme.onBackground
    // El número que ya es favorito (para quitarlo); si no lo es ninguno, se añade el principal (el primero)
    val favoriteNumber = e.numbers.firstOrNull { Favorites.isFavorite(cfg, it.number) }?.number
    val isFavorite = favoriteNumber != null
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // mensaje y si es un error
    var confirmRemove by remember { mutableStateOf(false) }
    val tap = rememberTap()

    fun save(add: Boolean) {
        val number = (if (add) null else favoriteNumber) ?: e.numbers.first().number
        saving = true
        result = null
        scope.launch {
            // En el ámbito de la app: si se sale de la ficha a medias, el cambio se termina de guardar igual
            val error = CercaApp.scope.async { Favorites.set(ctx.applicationContext, e.name, number, add) }.await()
            saving = false
            result = when {
                error != null -> error to true
                add -> "Añadido a favoritos: saldrá en la pantalla de inicio." to false
                else -> "Quitado de favoritos." to false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhotoHeader(
            maxPhoto = 180.dp,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp),
            photo = { Avatar(null, e.name, it, photoUri = e.photo ?: e.thumbnail) },
        ) {
            FitText(e.name, 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, color = onBackground)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                // Mientras se guarda no se puede pulsar otra vez
                onClick = { tap(); if (isFavorite) confirmRemove = true else save(add = true) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(20.dp),
                border = strongBorder(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = onBackground, strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                } else if (!isFavorite) {
                    Icon(CercaIcons.Star, contentDescription = null, tint = STAR, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                }
                FitText(
                    when {
                        saving -> "Guardando…"
                        isFavorite -> "Quitar de favoritos"
                        else -> "Añadir a favoritos"
                    },
                    22.sp, textAlign = TextAlign.Center, maxLines = 2, color = onBackground,
                )
            }
            result?.let { (message, error) ->
                Text(message, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    color = if (error) MaterialTheme.colorScheme.error else onBackground)
            }
        }
        Spacer(Modifier.height(16.dp))
        // Si tiene varios números (móvil, casa…), un botón por cada uno con su etiqueta
        val numbers = e.numbers.take(4)
        val compact = numbers.size > 2 // con 3 o 4 números, algo más bajos para que quepan con letra grande
        numbers.forEachIndexed { i, n ->
            if (i > 0) Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
            BigButton(if (numbers.size > 1) "Llamar · ${n.label}" else "Llamar", Palette.green, height = if (compact) 88.dp else 110.dp, icon = CercaIcons.Call) {
                CallManager.placeCall(ctx, n.number)
                onCalled()
            }
        }
        // WhatsApp y SMS al número principal (el primero), que suele ser el móvil
        MessageButtons(
            e.numbers.map { it.number },
            whatsappMarked = e.numbers.any { cfg.contactForNumber(it.number)?.whatsapp == true },
            showSms = cfg.ui.showSms,
            compact = compact,
        )
        Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
        BackButton(onBack)
    }

    // Quitar se confirma: un toque sin querer no debe hacer desaparecer a alguien del inicio
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("¿Quitar de favoritos?", fontSize = 26.sp) },
        text = { Text("${e.name} dejará de salir en la pantalla de inicio. Seguirá en la agenda.", fontSize = 20.sp) },
        confirmButton = {
            TextButton(onClick = { tap(); confirmRemove = false; save(add = false) }) { Text("Quitar", fontSize = 20.sp) }
        },
        dismissButton = { TextButton(onClick = { tap(); confirmRemove = false }) { Text("Cancelar", fontSize = 20.sp) } },
    )
}

// ====================================================================== APLICACIONES
/**
 * Por defecto se ven todas las apps del móvil menos las que la familia bloquea desde el panel.
 * [onMenu] (si no es null) añade la rueda ⚙ del "Menú de la familia", que la familia puede quitar desde el panel.
 */
@Composable
fun AppsScreen(
    blocked: List<String>,
    showAll: Boolean,
    onBack: () -> Unit,
    onMenu: (() -> Unit)? = null,
    menuNeedsPin: Boolean = false,
) {
    val ctx = LocalContext.current
    var showPin by remember { mutableStateOf(false) }
    // Leer las apps y sus nombres tarda: fuera del hilo principal, para que la pantalla se abra al momento
    val apps by produceState<List<Pair<String, String>>?>(null, blocked, showAll) {
        value = withContext(Dispatchers.IO) {
            runCatching { Apps.launchable(ctx).filter { showAll || it.pkg !in blocked }.map { it.pkg to it.label } }
                .getOrDefault(emptyList())
        }
    }
    val icons = remember { mutableMapOf<String, ImageBitmap>() }
    val count = apps?.size ?: 0
    // Pocas apps: iconos grandes de dos en dos; muchas: de tres en tres para no tener que bajar tanto.
    // Con letra grande, de dos en dos aunque sean muchas: en tres columnas los nombres no caben
    val big = !showAll && count <= 12
    val twoColumns = big || (!showAll && LocalDensity.current.fontScale >= 1.3f)
    val iconSize = when { big -> 88.dp; showAll -> 56.dp; else -> 64.dp }
    val labelSize = when { big -> 22.sp; showAll -> 14.sp; twoColumns -> 18.sp; else -> 16.sp }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FitText(if (showAll) "Todas las aplicaciones" else "Aplicaciones", 32.sp, Modifier.weight(1f),
                fontWeight = FontWeight.Bold, maxLines = 2, color = MaterialTheme.colorScheme.onBackground)
            // Menú de la familia: una rueda discreta junto al título (antes, un botón grande abajo).
            // Icono de verdad, no un emoji: el emoji cambia de dibujo según el móvil y quedaba mal
            if (onMenu != null) {
                val tap = rememberTap()
                FilledTonalIconButton(
                    onClick = { tap(); if (menuNeedsPin) showPin = true else onMenu() },
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Menú de la familia", modifier = Modifier.size(34.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            val list = apps
            when {
                list == null -> CenteredMessage("Cargando…")
                list.isEmpty() -> CenteredMessage(
                    "No hay aplicaciones", "Tu familia puede elegir cuáles aparecen aquí.",
                    Modifier.verticalScroll(rememberScrollState()),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (twoColumns) 2 else 3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.first }) { (pkg, label) -> AppTile(pkg, label, iconSize, labelSize, icons) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BackButton(onBack)
    }

    if (showPin && onMenu != null) PinDialog(onDismiss = { showPin = false }, onOk = { showPin = false; onMenu() })
}

@Composable
private fun AppTile(pkg: String, label: String, iconSize: Dp, labelSize: TextUnit, cache: MutableMap<String, ImageBitmap>) {
    val ctx = LocalContext.current
    // El icono también se dibuja fuera del hilo principal (antes, al bajar por la lista, iba a saltos)
    val icon by produceState(cache[pkg], pkg) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Apps.icon(ctx, pkg)?.toBitmap(192, 192)?.asImageBitmap() }.getOrNull()
        }?.also { cache[pkg] = it }
    }
    val shape = RoundedCornerShape(20.dp)
    val border = cardBorder()
    Column(
        Modifier.clip(shape).then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable { Tap.feedback(ctx); Apps.launch(ctx, pkg) }
            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, label, Modifier.size(iconSize)) else Spacer(Modifier.size(iconSize))
        // Sin partir palabras («AliExpre / ss»): si el nombre no cabe, la letra se encoge lo justo
        FitText(label, labelSize, textAlign = TextAlign.Center, maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ====================================================================== MENÚ FAMILIA
@Composable
fun AdminScreen(
    revision: Int,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    onAndroidSettings: () -> Unit,
    onChooseLauncher: () -> Unit,
    onAllApps: () -> Unit,
    onSync: () -> Unit,
) {
    val ctx = LocalContext.current
    val checks = remember(revision) {
        listOf(
            "Emparejado" to Store.isPaired,
            "Pantalla de inicio" to DeviceChecks.isDefaultHome(ctx),
            "App de teléfono" to DeviceChecks.isDefaultDialer(ctx),
            "Ubicación siempre" to (DeviceChecks.locationPermission(ctx) == "always"),
            "Batería sin restricciones" to DeviceChecks.ignoringBatteryOptimizations(ctx),
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Menú de la familia", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            if (Store.isPaired) Text("Móvil de ${Store.seniorName} · ${Store.serverUrl}", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        items(checks) { (label, ok) ->
            Text((if (ok) "✅  " else "❌  ") + label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }
        item { AdminButton("Asistente de configuración", onSetup) }
        item { AdminButton("Sincronizar ahora", onSync) }
        item { AdminButton("Todas las aplicaciones", onAllApps) }
        item { AdminButton("Ajustes de Android", onAndroidSettings) }
        item { AdminButton("Cambiar la pantalla de inicio", onChooseLauncher) }
        item {
            Spacer(Modifier.height(8.dp))
            BackButton(onBack)
        }
    }
}

@Composable
private fun AdminButton(text: String, onClick: () -> Unit) {
    val tap = rememberTap()
    // Altura mínima, no fija: con letra grande el texto ocupa dos líneas y antes se cortaba
    OutlinedButton(
        onClick = { tap(); onClick() }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(16.dp),
        border = strongBorder(),
    ) {
        Text(text, fontSize = 18.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val tap = rememberTap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN de la familia") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); error = false },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text("PIN incorrecto") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(onClick = { tap(); if (Store.checkPin(pin)) onOk() else error = true }) { Text("Entrar") } },
        dismissButton = { TextButton(onClick = { tap(); onDismiss() }) { Text("Cancelar") } },
    )
}
