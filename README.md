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
| `FIREBASE_SERVICE_ACCOUNT` | *(el JSON, en base64 o la ruta)* | Clave de la cuenta de servicio de Google: notificaciones (FCM) **y** comprobar los pagos de Google Play. También vale dejarla en `/data/firebase-service-account.json` |
| `BILLING` | `on` | `off` = todo gratis para todos (sin mes gratis ni suscripción) |
| `PLAY_PACKAGE_NAME` | `com.estudiofenix.cerca.familia` | La app de la familia, donde se compra la suscripción |
| `PLAY_PRODUCT_IDS` | `premium` | Id de la suscripción en Play Console (varios separados por comas) |
| `PLAY_SENIOR_PACKAGE` | `com.estudiofenix.cerca` | La app del mayor (para el botón de Google Play de la web) |
| `LEGAL_NAME` | `Estudio Fénix S.L.` | Titular (empresa o autónomo) en la web y la política de privacidad |
| `LEGAL_ID` | `B12345678` | NIF / CIF |
| `LEGAL_ADDRESS` | `C/ Mayor 1, 43001 Tarragona` | Domicilio |
| `LEGAL_EMAIL` | `privacidad@tudominio.com` | Contacto y derechos RGPD |
| `HOSTING_PROVIDER` | `Hetzner Online GmbH (Alemania)` | Dónde está el servidor (sale en la política de privacidad) |

Sin los `LEGAL_*` y `HOSTING_PROVIDER`, las páginas enseñan `[falta …]` en su lugar (y el servidor lo avisa al arrancar): ponlos antes de publicar.

4. Dominio con HTTPS en Dokploy (el WebSocket `/api/device/ws` funciona con Traefik sin configuración extra).

Páginas del servidor:

| Dirección | Qué es |
|---|---|
| `/` | Presentación de la app, con los botones de Google Play (`server/pages/index.html`) |
| `/panel` | Panel de la familia (instalable como app desde el navegador) |
| `/privacidad` | Política de privacidad: la URL que pide Google Play |
| `/borrar-cuenta` | Borrar la cuenta sin la app: la otra URL que pide Google Play |

En local: `cd server && npm install && npm start` → http://localhost:3000. Tests: `npm test`.

Las claves VAPID de push se generan solas en el primer arranque y se guardan en la base de datos.

## 2. App Android

1. Abre `android/` en Android Studio (una versión reciente: el proyecto usa AGP 8.13 y Android 16, API 36).
2. En `android/gradle.properties` pon tu servidor: `cerca.serverUrl=https://cerca.tudominio.com` (también se puede cambiar en el asistente).
3. Compila e instala en el Redmi.
4. Se abre el asistente. En el panel web: añade a la persona → Ajustes → «Generar código», e introdúcelo en el móvil. Después completa el resto de pasos hasta que estén todos en ✅.

Requisitos: Android 10 o superior y Google Play Services (geovallas y ubicación).

**Avisos de la app de la familia (Firebase).** En la consola de Firebase, el mismo proyecto de la cuenta de servicio: añade una app Android con el paquete `com.estudiofenix.cerca.familia` y copia sus 4 datos en `android/gradle.properties` (`firebase.appId`, `firebase.apiKey`, `firebase.projectId`, `firebase.senderId`). No hace falta `google-services.json`. Sin ellos, la app funciona igual pero los avisos solo se ven al abrirla.

**Versión para Google Play.** La firma está en `android/keystore.properties` y `android/keystore/` (fuera de git). **Guarda una copia de los dos en un sitio seguro** (gestor de contraseñas o nube privada): es la clave de subida. Con la *firma de apps de Google Play* activada, si se pierde se puede pedir otra a Google, pero tarda días.

```
cd android
./gradlew :app:bundleRelease :familia:bundleRelease
```

Los paquetes quedan en `app/build/outputs/bundle/release/app-release.aab` y `familia/build/outputs/bundle/release/familia-release.aab`. Antes de cada subida, sube `versionCode` en `app/build.gradle.kts` y `familia/build.gradle.kts`.

## 3. Probar en el Redmi antes de dárselo

Lo crítico son las llamadas. Prueba, con la pantalla bloqueada y desbloqueada:
- [ ] Llamada entrante de un contacto: suena, sale la pantalla grande, «Contestar» funciona, altavoz, colgar.
- [ ] Llamada de un número desconocido con cada modo de filtro, y llamar dos veces seguidas con «si insiste».
- [ ] Llamar desde un contacto, desde el teclado y desde el SOS.
- [ ] Reiniciar el móvil: vuelve el launcher, el servicio de ubicación y la conexión (punto verde en el panel).
- [ ] Dejarlo una noche: el panel no debe avisar de «sin conexión» y la batería debe aguantar.
- [ ] Salir de la zona andando: llega el aviso al panel.

Si algo de llamadas falla, desde el menú de familia → «Ajustes de Android» se puede devolver la app de teléfono a la de Xiaomi en un minuto.

## Cobrar: mes gratis + suscripción

- **Mes gratis sin tarjeta.** Empieza al emparejar el móvil del mayor y lo cuenta el servidor (`server/src/billing.js`). Va ligado al móvil: borrar a la persona y volver a crearla no da otro mes. Avisa a la familia 5 días antes y el día que se acaba.
- **Suscripción en Google Play**, desde la app de la familia (mensual y anual). La paga una persona y vale para toda la familia de ese mayor, también en el panel web (en el panel no se compra).
- **Sin suscripción su móvil sigue funcionando**: llamadas, contactos, SOS, «¿dónde está ahora?» y la última posición. Se desactivan las zonas seguras, el historial de recorridos, contestar sola y los timbres fuertes. La configuración se guarda: al suscribirse vuelve tal cual.
- Qué es de pago está en un solo sitio: `effectiveConfig` y `PREMIUM_FEATURES` en `server/src/billing.js` (y los textos de la app y el panel).

Para activarlo:

1. **Play Console → Configuración → Perfil de pagos**: dar de alta el perfil de pagos de la empresa (datos fiscales y cuenta bancaria).
2. **Play Console → la app de la familia → Monetizar → Suscripciones**: crear la suscripción con id `premium` y dos planes básicos, uno mensual y otro anual, con renovación automática y **sin prueba gratis** (la prueba es la nuestra, sin tarjeta).
3. **Google Cloud** (el mismo proyecto que Firebase): activar *Google Play Android Developer API* y crear una cuenta de servicio con su clave JSON. Esa clave es `FIREBASE_SERVICE_ACCOUNT`.
4. **Play Console → Usuarios y permisos**: invitar el email de esa cuenta de servicio con permiso para *ver datos financieros* y *gestionar pedidos y suscripciones*.
5. Para probar sin pagar: añadir vuestras cuentas como **testers de licencias** (Play Console → Configuración → Pruebas de licencias) y subir la app a una pista de prueba interna. Los pagos de prueba se renuevan cada pocos minutos.

## Notas para publicarla en Google Play

- **Teléfono**: al ser app de teléfono predeterminada, se justifica el uso de los permisos de llamadas en el formulario de permisos.
- **Ubicación en segundo plano**: el asistente ya enseña el aviso destacado antes de pedir el permiso (paso 5). En Play Console hay que justificarlo y subir un vídeo corto (YouTube, oculto) que enseñe ese aviso y la función: el mapa y el aviso de salida de zona en la app de la familia.
- **Servicio en primer plano de ubicación** y **notificaciones a pantalla completa** (llamadas): se declaran en Play Console → Contenido de la app.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**: Play la permite solo en casos concretos; si la rechazan, se puede quitar y dejar solo el paso de ajustes manual.
- **Privacidad y borrar la cuenta**: las URL son `https://tu-dominio/privacidad` y `https://tu-dominio/borrar-cuenta`. Dentro de la app de la familia: menú ⋮ → Privacidad / Borrar mi cuenta.
- **RGPD**: son datos de ubicación de una persona vulnerable. El historial se borra solo según los días configurados (7 por defecto, máx. 90). El consentimiento lo da la persona o su representante legal (el aviso de ubicación lo recuerda). Conviene que un abogado revise la política de privacidad (`server/pages/privacidad.html`).

## Limitaciones conocidas (v1)

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
