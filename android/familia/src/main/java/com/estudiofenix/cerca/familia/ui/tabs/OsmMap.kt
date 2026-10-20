package com.estudiofenix.cerca.familia.ui.tabs

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.estudiofenix.cerca.data.Zone
import com.estudiofenix.cerca.familia.ui.FamiliaColors
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

// Lo común de los mapas de las pestañas «Mapa» y «Ubicación»: OpenStreetMap (osmdroid) dentro de Compose,
// con las zonas, el recorrido y la última posición, y los mismos colores que el panel web.

/** Dónde se abre el mapa cuando aún no hay nada que enseñar: España entera, como en el panel. */
object OsmMapDefaults {
    const val LAT = 40.4168
    const val LNG = -3.7038
    const val ZOOM = 6.0
}

private val ZONE_COLOR = FamiliaColors.brand
private val TRACK_COLOR = Color(0xFFD9822B)
private val ACCURACY_COLOR = Color(0xFF3B82F6)

/** Un punto marcado en el mapa (su última posición). Con [accuracy] se dibuja también el círculo de precisión. */
data class MapPin(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val title: String = "",
    val snippet: String? = null,
)

/**
 * Para mover el mapa desde la pestaña (centrar en su posición…) y saber si el usuario lo ha tocado:
 * en cuanto lo mueve o lo amplía, el mapa ya no se recoloca solo.
 */
@Stable
class OsmMapState internal constructor(
    internal val startLat: Double,
    internal val startLng: Double,
    internal val startZoom: Double,
) {
    private var view: MapView? = null
    private var pending: ((MapView) -> Unit)? = null

    /** El usuario ha tocado el mapa. Se pone a false para que vuelva a seguir su posición. */
    var touched by mutableStateOf(false)

    /** Centra el mapa en un punto; con [zoom], además lo acerca o lo aleja. */
    fun moveTo(lat: Double, lng: Double, zoom: Double? = null, animate: Boolean = false) {
        val go: (MapView) -> Unit = { map ->
            val point = GeoPoint(lat, lng)
            if (animate) {
                map.controller.animateTo(point, zoom ?: map.zoomLevelDouble, null)
            } else {
                zoom?.let { map.controller.setZoom(it) }
                map.controller.setCenter(point)
            }
        }
        // Si el mapa aún no está creado, se hace en cuanto lo esté
        view?.let(go) ?: run { pending = go }
    }

    internal fun attach(map: MapView) {
        view = map
        pending?.invoke(map)
        pending = null
    }

    internal fun detach(map: MapView) {
        if (view === map) view = null
    }
}

@Composable
fun rememberOsmMapState(
    lat: Double = OsmMapDefaults.LAT,
    lng: Double = OsmMapDefaults.LNG,
    zoom: Double = OsmMapDefaults.ZOOM,
): OsmMapState = remember { OsmMapState(lat, lng, zoom) }

/**
 * Mapa de OpenStreetMap con las [zones] (círculos verdes), el [track] (línea naranja) y el [pin] de su última posición.
 * Con [onTap], tocar el mapa (o su marcador) devuelve ese punto, p. ej. para añadir una zona; sin él, tocar una zona
 * o el marcador enseña su nombre. Se puede meter en una columna con scroll: al arrastrar dentro, se mueve el mapa.
 */
@SuppressLint("ClickableViewAccessibility") // el toque solo se observa; lo sigue gestionando el propio mapa
@Composable
fun OsmMap(
    state: OsmMapState,
    modifier: Modifier = Modifier,
    zones: List<Zone> = emptyList(),
    track: List<GeoPoint> = emptyList(),
    pin: MapPin? = null,
    onTap: ((lat: Double, lng: Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentState by rememberUpdatedState(state)

    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true) // letras del mapa legibles en pantallas de mucha densidad
            setMinZoomLevel(3.0)
            setMaxZoomLevel(19.0)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            // Lo cerramos nosotros al salir de la pestaña (onDetach), no cada vez que se quita de la ventana
            setDestroyMode(false)
            controller.setZoom(state.startZoom)
            controller.setCenter(GeoPoint(state.startLat, state.startLng))
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        currentState.touched = true
                        // Que la columna con scroll no se quede el gesto: dentro del mapa, se mueve el mapa
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }
    val layers = remember(map) {
        MapLayers(map) { point -> currentOnTap?.let { it(point.latitude, point.longitude); true } ?: false }
    }

    // Ciclo de vida del mapa: descarga de teselas al volver a la app, pausa al salir, y se libera al cerrar la pestaña
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(state, map) {
        state.attach(map)
        onDispose { state.detach(map) }
    }
    DisposableEffect(map) {
        onDispose { map.onDetach() }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { layers.update(zones, track, pin, tappable = onTap != null) },
    )
}

/**
 * Las capas del mapa, siempre en el mismo orden (de abajo arriba): toques, zonas, precisión, recorrido, marcador.
 * Se crean una vez y se actualizan en su sitio, para que el mapa no parpadee al refrescar.
 */
private class MapLayers(private val map: MapView, onTap: (GeoPoint) -> Boolean) {
    private val dp = map.resources.displayMetrics.density

    private val zoneLayer = FolderOverlay()

    private val accuracy = Polygon().apply {
        fillPaint.color = ACCURACY_COLOR.copy(alpha = 0.1f).toArgb()
        outlinePaint.color = ACCURACY_COLOR.toArgb()
        outlinePaint.strokeWidth = 1 * dp
        setOnClickListener { _, _, _ -> false } // no se queda los toques
        isEnabled = false
    }

    private val route = Polyline().apply {
        outlinePaint.color = TRACK_COLOR.copy(alpha = 0.7f).toArgb()
        outlinePaint.strokeWidth = 3 * dp
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
        isEnabled = false
    }

    private val marker = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        isEnabled = false
    }

    private val markerTap = Marker.OnMarkerClickListener { m, _ -> onTap(m.position) }

    private var shownZones: List<Zone>? = null
    private var shownTrack: List<GeoPoint>? = null
    private var shownPin: MapPin? = null
    private var tappable: Boolean? = null

    init {
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = onTap(p)
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        map.overlays.add(zoneLayer)
        map.overlays.add(accuracy)
        map.overlays.add(route)
        map.overlays.add(marker)
        map.overlays.add(CopyrightOverlay(map.context)) // "© OpenStreetMap contributors", lo pide su licencia
    }

    fun update(zones: List<Zone>, track: List<GeoPoint>, pin: MapPin?, tappable: Boolean) {
        var changed = false
        if (zones != shownZones || tappable != this.tappable) {
            zoneLayer.items.toList().forEach { zoneLayer.remove(it); it.onDetach(map) }
            zones.forEach { zoneLayer.add(zoneCircle(it, tappable)) }
            shownZones = zones
            changed = true
        }
        if (track != shownTrack) {
            route.setPoints(track)
            route.isEnabled = track.size > 1
            shownTrack = track
            changed = true
        }
        if (pin != shownPin) {
            if (pin == null) {
                marker.closeInfoWindow()
                marker.isEnabled = false
                accuracy.isEnabled = false
            } else {
                val point = GeoPoint(pin.lat, pin.lng)
                marker.position = point
                marker.title = pin.title
                marker.snippet = pin.snippet
                marker.isEnabled = true
                val radius = pin.accuracy?.takeIf { it > 0 }
                if (radius != null) accuracy.setPoints(Polygon.pointsAsCircle(point, radius))
                accuracy.isEnabled = radius != null
            }
            shownPin = pin
            changed = true
        }
        if (tappable != this.tappable) {
            // Si el mapa sirve para elegir un punto, tocar su marcador también lo elige (p. ej. "Casa", donde está ahora)
            marker.setOnMarkerClickListener(if (tappable) markerTap else null)
            this.tappable = tappable
        }
        if (changed) map.invalidate()
    }

    private fun zoneCircle(zone: Zone, tappable: Boolean) = Polygon(map).apply {
        setPoints(Polygon.pointsAsCircle(GeoPoint(zone.lat, zone.lng), zone.radius))
        fillPaint.color = ZONE_COLOR.copy(alpha = 0.12f).toArgb()
        outlinePaint.color = ZONE_COLOR.toArgb()
        outlinePaint.strokeWidth = 2 * dp
        title = zone.name
        // Para añadir zonas, el toque pasa al mapa (como en el panel); si no, enseña el nombre de la zona
        if (tappable) setOnClickListener { _, _, _ -> false }
    }
}
