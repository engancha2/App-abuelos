# Cerca — launcher para personas mayores

Launcher gratuito para Android pensado para personas mayores, con tres piezas:

```
android/   App Android (Kotlin + Compose): launcher, app de teléfono, GPS, asistente Xiaomi
server/    Backend Node (Fastify + SQLite) + panel web de la familia, listo para Dokploy
```

## Qué hace

**En el móvil de la persona mayor**
- Pantalla de inicio fija: hora y fecha grandes, batería, contactos con foto en botones enormes, «Teléfono», «Aplicaciones» y **SOS** (mantener pulsado 2 s).
- Es la **app de teléfono predeterminada**: todas las llamadas pasan por Cerca. Pantalla de llamada con botones «Contestar» y «Colgar» gigantes (sin deslizar), altavoz, teclado para menús telefónicos y sensor de proximidad.
- **Filtro de llamadas** configurable: todo el mundo / desconocidos en silencio / solo contactos, con la opción «dejar pasar si insiste» (2 llamadas en 5 min).
- **Ubicación con 4 modos** configurables desde el panel: desactivada, continua (cada X s), cada X minutos, o solo al salir de una zona (y frecuente mientras está fuera). Zonas seguras con geovallas de Google Play Services y comprobación local con margen de precisión.
- Avisos a la familia: SOS con ubicación, salida/vuelta a zona, batería baja, móvil sin conexión, y si alguien cambia el launcher o la app de teléfono.
- Funciona sin conexión con la última configuración; las posiciones se guardan en cola y se suben al volver la cobertura.
- Menú de familia oculto: mantener pulsada la hora 3 s + PIN.
- Asistente de configuración con los pasos específicos de Xiaomi/HyperOS (autoinicio, batería sin restricciones, pantalla de bloqueo, ventanas emergentes).

**Panel web de la familia**
- Mapa con la última posición, recorrido y botón «¿Dónde está ahora?» (respuesta en segundos por WebSocket).
- Contactos con foto (se recortan y reducen en el navegador), orden, WhatsApp y contacto de SOS.
- Filtro de llamadas, modo de ubicación, zonas (tocando el mapa), apps visibles, tamaño de letra, alto contraste, PIN.
- Varios familiares por persona, notificaciones push en el navegador (instalable como PWA).

## 1. Backend en Dokploy

Requisitos: Node 22.13+ (usa el SQLite integrado de Node, sin dependencias nativas).

1. Sube la carpeta `server/` a un repositorio y crea en Dokploy una aplicación con **Dockerfile** (`server/Dockerfile`).
2. Monta un **volumen persistente en `/data`** (base de datos y fotos).
3. Variables de entorno:

| Variable | Ejemplo | Para qué |
|---|---|---|
| `PUBLIC_URL` | `https://cerca.tudominio.com` | URL pública |
| `VAPID_SUBJECT` | `mailto:info@tudominio.com` | Contacto para las notificaciones push |
| `ALLOW_REGISTRATION` | `true` | Ponlo a `false` cuando ya tengáis cuentas, si no quieres registro abierto |
| `COOKIE_SECURE` | `auto` | `auto` detecta HTTPS detrás de Traefik |

4. Dominio con HTTPS en Dokploy (el WebSocket `/api/device/ws` funciona con Traefik sin configuración extra).

En local: `cd server && npm install && npm start` → http://localhost:3000. Tests: `npm test`.

Las claves VAPID de push se generan solas en el primer arranque y se guardan en la base de datos.

## 2. App Android

1. Abre `android/` en Android Studio (Ladybug o posterior). Si propone actualizar AGP/Kotlin, puedes aceptar.
2. En `android/gradle.properties` pon tu servidor: `cerca.serverUrl=https://cerca.tudominio.com` (también se puede cambiar en el asistente).
3. Compila e instala en el Redmi.
4. Se abre el asistente. En el panel web: añade a la persona → Ajustes → «Generar código», e introdúcelo en el móvil. Después completa el resto de pasos hasta que estén todos en ✅.

Requisitos: Android 10 o superior y Google Play Services (geovallas y ubicación).

## 3. Probar en el Redmi antes de dárselo

Lo crítico son las llamadas. Prueba, con la pantalla bloqueada y desbloqueada:
- [ ] Llamada entrante de un contacto: suena, sale la pantalla grande, «Contestar» funciona, altavoz, colgar.
- [ ] Llamada de un número desconocido con cada modo de filtro, y llamar dos veces seguidas con «si insiste».
- [ ] Llamar desde un contacto, desde el teclado y desde el SOS.
- [ ] Reiniciar el móvil: vuelve el launcher, el servicio de ubicación y la conexión (punto verde en el panel).
- [ ] Dejarlo una noche: el panel no debe avisar de «sin conexión» y la batería debe aguantar.
- [ ] Salir de la zona andando: llega el aviso al panel.

Si algo de llamadas falla, desde el menú de familia → «Ajustes de Android» se puede devolver la app de teléfono a la de Xiaomi en un minuto.

## Notas para publicarla en Google Play

- **Teléfono**: al ser app de teléfono predeterminada, se justifica el uso de los permisos de llamadas en el formulario de permisos.
- **Ubicación en segundo plano**: Play exige aviso destacado dentro de la app antes de pedir el permiso, justificación en la ficha y un vídeo mostrando la función. El asistente ya explica para qué es; para la publicación conviene añadir una pantalla de aviso específica antes del paso 5.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**: Play la permite solo en casos concretos; si la rechazan, se puede quitar y dejar solo el paso de ajustes manual.
- **RGPD**: son datos de ubicación de una persona vulnerable. El historial se borra solo según los días configurados (7 por defecto, máx. 90). Hace falta política de privacidad y que el consentimiento lo dé la persona o su representante legal.

## Limitaciones conocidas (v1)

- **No se ha compilado todavía**: el código Android está revisado pero no compilado (el entorno donde se escribió no tenía el SDK de Android). Espera algún ajuste menor en la primera compilación.
- WhatsApp no permite iniciar videollamadas desde otra app: el botón abre el chat del contacto.
- Los ajustes de Xiaomi (autoinicio, etc.) no se pueden leer por código; el asistente los abre y la familia los marca a mano.
- Textos solo en castellano (escritos directamente en el código; para traducir habría que pasarlos a `strings.xml`).
- Si la persona se deja el móvil en casa, la ubicación no sirve: para alzhéimer avanzado conviene complementarlo con un localizador de muñeca.

## Arquitectura rápida

```
Móvil (Cerca)                         Servidor (Dokploy)                 Familia
 ├ HomeActivity (launcher)             ├ /api/device/*  (token móvil)     ├ Panel web (/)
 ├ CercaInCallService + InCallActivity ├ /api/device/ws (WebSocket)  ───► │  mapa, ajustes
 ├ CallFilterService (filtro)          ├ /api/*         (sesión cookie)   └ Notificaciones push
 ├ CercaService (GPS + WebSocket)  ───►├ SQLite en /data
 ├ SyncWorker (respaldo cada 15 min)   └ Avisos: SOS, zona, batería, sin conexión
 └ SetupActivity (asistente)
```

El móvil guarda la configuración en local y la refresca cuando el servidor le avisa por WebSocket o en cada latido (cada 10 min con el servicio activo, 15 min por WorkManager si la ubicación está desactivada).
