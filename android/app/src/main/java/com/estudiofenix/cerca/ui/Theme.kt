package com.estudiofenix.cerca.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estudiofenix.cerca.data.Store
import java.io.File

object Palette {
    val brand = Color(0xFF1F6F5C)
    val green = Color(0xFF1E8E3E)
    val red = Color(0xFFC62828)
    val bg = Color(0xFFF6F4EF)
    val card = Color(0xFFFFFFFF)
    val ink = Color(0xFF1D2421)
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
        )
    } else {
        lightColorScheme(
            primary = Palette.brand, onPrimary = Color.White,
            background = Palette.bg, onBackground = Palette.ink,
            surface = Palette.card, onSurface = Palette.ink,
            surfaceVariant = Color(0xFFE3F0EB), onSurfaceVariant = Palette.ink,
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** Botón enorme, pensado para dedos que no aciertan. */
@Composable
fun BigButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 88.dp,
    textColor: Color = Color.White,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = textColor),
    ) {
        Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Foto del contacto (descargada en local) o su inicial. */
@Composable
fun Avatar(photo: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val file: File? = remember(photo) { Store.photoFile(photo) }
    val bitmap = remember(file) { file?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun CenteredMessage(title: String, body: String? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (body != null) Text(body, fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp))
    }
}
