package com.estudiofenix.cerca.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.estudiofenix.cerca.data.Store
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Palette {
    val brand = Color(0xFF1F6F5C)
    // Un color por acción, el mismo en toda la app: así se reconocen sin leer (antes casi todo era verde).
    // Todos con letra blanca y contraste de al menos 4,5:1
    val green = Color(0xFF1E7E34)  // llamar, contestar
    val video = Color(0xFF6A1B9A)  // videollamada (morado)
    val chat = Color(0xFF1565C0)   // escribir por WhatsApp (azul)
    val sms = Color(0xFFB45309)    // SMS (naranja)
    val back = Color(0xFF455A64)   // volver (gris oscuro)
    val red = Color(0xFFC62828)    // SOS, colgar
    val bg = Color(0xFFF6F4EF)
    val card = Color(0xFFFFFFFF)
    val ink = Color(0xFF1D2421)
    val line = Color(0xFF52605A)   // bordes: contraste 6:1 sobre el fondo claro
}

/** true con "alto contraste": las tarjetas llevan borde, porque sobre el fondo negro no se ve su sombra. */
val LocalHighContrast = staticCompositionLocalOf { false }

// Material fija la altura de línea en sp (24 sp en el texto normal, 20 en los botones): con letra grande,
// un texto de dos líneas se montaba una encima de otra. Proporcional al tamaño de la letra, siempre cabe.
private val typography = Typography().run {
    fun TextStyle.proportional() = copy(lineHeight = 1.25.em)
    copy(
        displayLarge = displayLarge.proportional(), displayMedium = displayMedium.proportional(),
        displaySmall = displaySmall.proportional(), headlineLarge = headlineLarge.proportional(),
        headlineMedium = headlineMedium.proportional(), headlineSmall = headlineSmall.proportional(),
        titleLarge = titleLarge.proportional(), titleMedium = titleMedium.proportional(),
        titleSmall = titleSmall.proportional(), bodyLarge = bodyLarge.proportional(),
        bodyMedium = bodyMedium.proportional(), bodySmall = bodySmall.proportional(),
        labelLarge = labelLarge.proportional(), labelMedium = labelMedium.proportional(),
        labelSmall = labelSmall.proportional(),
    )
}

@Composable
fun CercaTheme(
    highContrast: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors = if (highContrast) {
        darkColorScheme(
            primary = Color(0xFFFFEB3B), onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color(0xFF111111), onSurface = Color.White,
            surfaceVariant = Color(0xFF222222), onSurfaceVariant = Color.White,
            outline = Color.White,
        )
    } else {
        lightColorScheme(
            primary = Palette.brand, onPrimary = Color.White,
            background = Palette.bg, onBackground = Palette.ink,
            surface = Palette.card, onSurface = Palette.ink,
            // Gris claro neutro (antes verde clarito: con tanto verde, todo parecía lo mismo)
            surfaceVariant = Color(0xFFE8EBEE), onSurfaceVariant = Palette.ink,
            outline = Palette.line,
        )
    }
    val density = LocalDensity.current
    // Tope a la letra del sistema por la de la familia: con 1,3 × 1,6 = 2,1 ya no cabría ni la hora
    val scale = (density.fontScale * fontScale).coerceAtMost(2f)
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, scale),
        LocalHighContrast provides highContrast,
    ) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            // Sin esto, los textos sueltos (fuera de botones y tarjetas) eran negros: invisibles con alto contraste
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

/** Barra de estado legible sobre nuestro fondo: iconos claros con alto contraste (fondo negro), oscuros si no. */
fun ComponentActivity.cercaSystemBars(highContrast: Boolean) {
    val style = if (highContrast) {
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** Borde bien visible para botones secundarios y casillas. */
@Composable
fun strongBorder() = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)

/** Borde de las tarjetas: solo con alto contraste, donde la sombra no se ve. */
@Composable
fun cardBorder(): BorderStroke? = if (LocalHighContrast.current) strongBorder() else null

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** Botón enorme, pensado para dedos que no aciertan. Con [icon], el dibujo va a la izquierda del texto. */
@Composable
fun BigButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 88.dp,
    textColor: Color = Color.White,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    // Si el botón casi no se distingue del fondo (p. ej. "Volver"), lleva borde para que se vea dónde pulsar
    val faint = contrast(color, MaterialTheme.colorScheme.background) < 3f
    val tap = rememberTap()
    Button(
        onClick = { tap(); onClick() }, // vibra y hace clic: que se note que se ha pulsado
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = textColor,
            disabledContainerColor = color.copy(alpha = 0.6f), disabledContentColor = textColor.copy(alpha = 0.8f),
        ),
        border = if (faint) strongBorder() else null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
        }
        // weight sin rellenar: el texto usa el sitio que deja el icono y el conjunto queda centrado
        FitText(text, 30.sp, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3)
    }
}

/** "Volver", igual en todas las pantallas (gris oscuro con flecha): se reconoce sin leerlo. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 72.dp) {
    BigButton("Volver", Palette.back, modifier, height = height, icon = CercaIcons.Back, onClick = onClick)
}

/**
 * Texto que nunca parte una palabra por la mitad ni se corta: si con la letra grande no cabe en
 * [maxLines] líneas, se encoge lo justo (como mucho, a la mitad).
 */
@Composable
fun FitText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 2,
) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(
        TextStyle(fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign ?: TextAlign.Unspecified),
    )
    BoxWithConstraints(modifier, contentAlignment = if (textAlign == TextAlign.Center) Alignment.TopCenter else Alignment.TopStart) {
        val width = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val size = remember(text, style, width, maxLines) {
            if (bounded) fittingSize(measurer, text, style, width, maxLines) else fontSize
        }
        Text(
            text, color = color, fontSize = size, fontWeight = fontWeight, textAlign = textAlign,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fittingSize(measurer: TextMeasurer, text: String, style: TextStyle, width: Int, maxLines: Int): TextUnit {
    val words = text.split(' ', '\n').filter { it.isNotBlank() }
    val start = style.fontSize.value
    var size = start
    while (true) {
        val s = style.copy(fontSize = size.sp)
        val widest = words.maxOfOrNull { measurer.measure(it, s, softWrap = false, maxLines = 1).size.width } ?: 0
        val fits = widest <= width &&
            !measurer.measure(text, s, maxLines = maxLines, constraints = Constraints(maxWidth = width)).hasVisualOverflow
        if (fits || size <= start * 0.5f) return size.sp
        // El ancho crece casi en proporción a la letra: se salta directamente cerca del tamaño que cabe
        val ratio = if (widest > width) width.toFloat() / widest else 0.9f
        size = max(start * 0.5f, size * min(ratio, 0.95f))
    }
}

/**
 * Cabecera de las fichas (contacto, llamada): la foto arriba y, debajo, el nombre y lo demás. Si con letra
 * grande no cabe todo, la foto se encoge (o se quita) para que el texto se lea entero sin tapar los botones.
 */
@Composable
fun PhotoHeader(
    maxPhoto: Dp,
    modifier: Modifier = Modifier,
    photo: @Composable (Dp) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val text = subcompose("text") {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }.map { it.measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight)) }
        val textHeight = text.maxOfOrNull { it.height } ?: 0
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight - textHeight - gap else Int.MAX_VALUE
        val side = minOf(maxPhoto.roundToPx(), room, width)
        val photos = if (side >= 64.dp.roundToPx()) {
            subcompose("photo") { photo(side.toDp()) }.map { it.measure(Constraints.fixed(side, side)) }
        } else emptyList()
        val top = if (photos.isEmpty()) 0 else side + gap
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else top + textHeight
        layout(width, height) {
            photos.forEach { it.place((width - it.width) / 2, 0) }
            text.forEach { it.place(0, top) }
        }
    }
}

/** Fotos ya leídas, para no decodificarlas otra vez cada vez que se vuelve a una pantalla. */
private val photoCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Resultado de leer la foto ([bitmap] null = no tiene). Mientras se lee, no hay Loaded. */
private class Loaded(val bitmap: ImageBitmap?)

/**
 * Foto del contacto o su inicial. [photo] es el archivo descargado del servidor (contactos de la familia)
 * y [photoUri], la foto de la agenda de Android (content://…), que se usa si no hay la otra.
 * Se leen fuera del hilo principal.
 */
@Composable
fun Avatar(photo: String?, name: String, size: Dp, modifier: Modifier = Modifier, photoUri: String? = null) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    // Dos tamaños de lectura: la misma foto sirve aunque la ficha la encoja un poco
    val target = if (with(density) { size.roundToPx() } <= 320) 320 else 720
    val none = photo == null && photoUri == null
    // Otra persona: se empieza de cero (que no se vea un momento la foto de la anterior)
    val loaded = key(photo, photoUri) {
        val cacheKey = "$photo|$photoUri|$target"
        val state by produceState(if (none) Loaded(null) else photoCache.get(cacheKey)?.let(::Loaded), cacheKey) {
            if (none) return@produceState
            photoCache.get(cacheKey)?.let { value = Loaded(it); return@produceState }
            val bitmap = withContext(Dispatchers.IO) { readPhoto(ctx, photo, photoUri, target) }
            bitmap?.let { photoCache.put(cacheKey, it) }
            value = Loaded(bitmap)
        }
        state
    }
    val bitmap = loaded?.bitmap
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        // Leyendo: un círculo vacío, mejor que enseñar la inicial y cambiarla enseguida por la foto
        loaded == null -> Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        else -> Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            // La inicial; si el nombre no tiene letras (un número: "+34 600…"), la silueta de una persona
            val initial = name.firstOrNull { it.isLetter() }?.uppercase()
            if (initial != null) {
                Text(
                    initial,
                    color = MaterialTheme.colorScheme.onPrimary,
                    // Proporcional al círculo, no a la letra elegida: con 1,6× se salía
                    fontSize = with(density) { (size * 0.42f).toSp() },
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(size * 0.6f))
            }
        }
    }
}

private fun readPhoto(ctx: Context, photo: String?, photoUri: String?, target: Int): ImageBitmap? {
    Store.photoFile(photo)?.let { file -> decode(ImageDecoder.createSource(file), target)?.let { return it } }
    val uri = photoUri?.let(Uri::parse) ?: return null
    return decode(ImageDecoder.createSource(ctx.contentResolver, uri), target)
}

private fun decode(source: ImageDecoder.Source, target: Int): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // Se lee ya reducida al tamaño de pantalla: una foto de cámara ocuparía decenas de MB
        val shortSide = min(info.size.width, info.size.height)
        if (shortSide > target) {
            val scale = target.toFloat() / shortSide
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }.asImageBitmap()
}.getOrNull()

@Composable
fun CenteredMessage(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FitText(title, 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3,
            color = MaterialTheme.colorScheme.onBackground)
        if (body != null) Text(body, fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 12.dp))
    }
}
