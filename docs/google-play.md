# Google Play: textos y formularios

Todo lo que Play Console pide para publicar las dos apps, ya redactado. Revisa los datos marcados con ⚠️.

---

## 1. Ficha de la app del mayor — «Grandpa»

**Paquete:** `com.estudiofenix.cerca` · **Categoría:** Comunicación · **Etiquetas:** Personas mayores, Launcher

**Título (máx. 30):**
> Grandpa – Móvil fácil mayores

**Descripción breve (máx. 80):**
> Móvil sencillo para mayores: botones grandes, llamadas fáciles, SOS y familia.

**Descripción completa:**
> Grandpa convierte cualquier móvil Android en un teléfono fácil para personas mayores. Y con la app Find My Grandpa, su familia lo configura todo desde su propio móvil y sabe que está bien.
>
> PANTALLA DE INICIO FÁCIL
> • La hora y la fecha en grande.
> • Sus contactos con foto, en botones enormes: un toque y llama.
> • Teléfono, Contactos y Mensajes siempre a mano.
> • Letra grande y alto contraste para quien ve poco.
>
> LLAMADAS SENCILLAS
> • Botones grandes para contestar y colgar, sin deslizar.
> • Manos libres con un toque y teclado para los menús telefónicos.
> • Filtro de llamadas: desconocidos en silencio o bloqueados. Adiós a las llamadas de estafas.
> • «Dejar pasar si insiste», por si es el médico o una urgencia.
> • Timbres fuertes que se oyen bien.
>
> SEGURIDAD
> • Botón SOS: avisa a la familia con su ubicación.
> • Zonas seguras: la familia recibe un aviso si sale de casa o del barrio.
> • «Contestar sola»: si un familiar insiste y no llega al teléfono, la llamada se contesta en manos libres.
> • Avisos de batería baja y de móvil apagado.
>
> CONFIGURADO A DISTANCIA
> La familia cambia contactos, fotos, apps visibles, timbre y ajustes desde la app Find My Grandpa o el panel web, sin tocar su móvil.
>
> PRIMER MES GRATIS, SIN TARJETA
> Lo esencial (inicio fácil, llamadas, SOS y «¿dónde está ahora?») es siempre gratis. Las zonas seguras, el historial, contestar sola y los timbres fuertes forman parte de la suscripción, que se contrata en la app de la familia.
>
> Requisitos: Android 10 o posterior con Google Play. Instálala con el permiso de la persona mayor o de su representante legal.

## 2. Ficha de la app de la familia — «Find My Grandpa»

**Paquete:** `com.estudiofenix.cerca.familia` · **Categoría:** Estilo de vida (o Comunicación)

⚠️ «Find My» es una marca de Apple: Google puede rechazar el nombre. Alternativa propuesta: **«Grandpa Familia»**.

**Título (máx. 30):**
> Find My Grandpa – Familia

**Descripción breve (máx. 80):**
> Cuida del móvil de tus mayores: dónde está, avisos de SOS y ajustes a distancia.

**Descripción completa:**
> Find My Grandpa es la app de la familia de Grandpa, el móvil fácil para personas mayores. Instala Grandpa en su móvil, conéctalo con un código de 6 números y cuida de él desde el tuyo.
>
> SABER QUE ESTÁ BIEN
> • Mapa con dónde está y botón «¿Dónde está ahora?».
> • Zonas seguras: aviso al momento si sale de casa, del barrio o del centro de día.
> • Historial de recorridos.
> • Avisos de SOS, batería baja o móvil apagado.
>
> TODO A DISTANCIA
> • Sus contactos con foto, en el orden que quieras.
> • Filtro de llamadas contra estafas y «contestar sola» si no llega al teléfono.
> • Timbres fuertes y prueba «hacerlo sonar».
> • Qué apps ve, tamaño de letra, alto contraste y PIN.
>
> PARA TODA LA FAMILIA
> Hermanos y nietos con su propia cuenta. Y desde el ordenador o el iPhone, en el panel web.
>
> PRIMER MES GRATIS, SIN TARJETA
> Después, suscripción mensual o anual para toda la familia. Sin suscripción, su móvil sigue funcionando: llamadas, contactos, SOS y «¿dónde está ahora?».

## 3. URLs

| Campo | Valor |
|---|---|
| Sitio web | `https://⚠️TU-DOMINIO/` |
| Política de privacidad | `https://⚠️TU-DOMINIO/privacidad` |
| Borrar la cuenta (app de la familia) | `https://⚠️TU-DOMINIO/borrar-cuenta` |
| Email de contacto | ⚠️ el mismo que `LEGAL_EMAIL` |

## 4. Seguridad de los datos

Respuestas comunes a las dos apps:
- ¿Se cifran en tránsito? **Sí** (HTTPS).
- ¿Pueden pedir que se borren? **Sí** → `https://⚠️TU-DOMINIO/borrar-cuenta`.
- ¿Se comparten con terceros? **No**: los ve la familia que la persona mayor tiene en la app, que es la función que el usuario espera. Los proveedores (servidor, Google Firebase) actúan por encargo, y eso no cuenta como «compartir».

**Grandpa (móvil del mayor)** — datos recogidos:

| Tipo de datos | Recogido | Para qué | ¿Obligatorio? |
|---|---|---|---|
| Ubicación aproximada y precisa | Sí | Funciones de la app | Opcional (la familia elige el modo; puede estar desactivada) |
| Información personal → Nombre | Sí | Funciones de la app | Obligatorio |
| Contactos | Sí (solo los favoritos) | Funciones de la app | Opcional |
| Fotos | Sí (fotos de los contactos que sube la familia) | Funciones de la app | Opcional |
| Actividad en apps → Otras apps instaladas | Sí | Funciones de la app | Obligatorio |
| ID del dispositivo u otros identificadores | Sí (ANDROID_ID resumido) | Prevención de fraudes (un mes gratis por móvil) | Obligatorio |
| Información y rendimiento de la app → Diagnóstico | Sí (batería, permisos, versión) | Funciones de la app | Obligatorio |

**Find My Grandpa (familia)** — datos recogidos:

| Tipo de datos | Recogido | Para qué | ¿Obligatorio? |
|---|---|---|---|
| Información personal → Nombre y email | Sí | Gestión de la cuenta | Obligatorio |
| Información financiera → Historial de compras | Sí (estado de la suscripción) | Funciones de la app | Opcional |
| Fotos | Sí (fotos de contactos) | Funciones de la app | Opcional |
| ID del dispositivo u otros identificadores | Sí (token de notificaciones) | Funciones de la app | Obligatorio |

## 5. Declaraciones de permisos (app del mayor)

**Ubicación en segundo plano** (`ACCESS_BACKGROUND_LOCATION`):
> Grandpa es un launcher para personas mayores. Su familia puede activar las zonas seguras: la app avisa a la familia si la persona sale de casa o del barrio, aunque la app no esté en pantalla. También envía la ubicación al pulsar el botón SOS. Sin la ubicación en segundo plano, los avisos de salida de zona no funcionan, porque la persona mayor no tiene la app abierta mientras camina. Antes de pedir el permiso, la app enseña un aviso destacado que explica qué se recoge, para qué y quién lo ve.

Vídeo (YouTube, oculto, menos de 30 s): asistente → paso 5 → aviso «Grandpa usa la ubicación» → permiso «Permitir siempre» → en la app de la familia, el mapa y el aviso «Ha salido de la zona».

**Servicio en primer plano de tipo ubicación** (`FOREGROUND_SERVICE_LOCATION`):
> Mientras la familia tiene activado el seguimiento (continuo, cada X minutos o al salir de una zona), un servicio con notificación fija envía la ubicación al servidor y mantiene la conexión para «¿Dónde está ahora?». La persona mayor ve la notificación «Grandpa está activo».

**Notificaciones a pantalla completa** (`USE_FULL_SCREEN_INTENT`):
> Grandpa es la app de teléfono predeterminada (rol de marcador): enseña la pantalla de llamada entrante a pantalla completa con la pantalla bloqueada.

**Rol de teléfono** (`CALL_PHONE`, `READ_PHONE_STATE`, `READ_CONTACTS`):
> Grandpa se convierte en la app de teléfono predeterminada para enseñar las llamadas con botones grandes y filtrar las de números desconocidos.

**Ignorar la optimización de batería** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`):
> Las alertas de SOS, de salida de zona y las llamadas entrantes deben funcionar siempre. En los móviles Xiaomi y similares, el ahorro de batería cierra la app y la persona mayor se queda sin avisos ni llamadas.

## 6. Clasificación de contenido y público

- Cuestionario de clasificación: sin violencia, sexo, lenguaje ni apuestas → **PEGI 3 / Para todos**.
- Público objetivo: **18 años o más** (la familia instala y configura; no está dirigida a niños).
- Anuncios: **No contiene anuncios**.
- Acceso a la app (para la revisión): ⚠️ crea una cuenta de prueba en tu servidor con una persona ya emparejada y pon aquí su email y contraseña.

## 7. Lista final antes de enviar

- [ ] Dominio con HTTPS y servidor en Dokploy, con todas las variables del README (`LEGAL_*`, `HOSTING_PROVIDER`, `FIREBASE_SERVICE_ACCOUNT`)
- [ ] `cerca.serverUrl` y `firebase.*` en `android/gradle.properties`, y volver a generar los `.aab`
- [ ] Copia de seguridad de `android/keystore.properties` y `android/keystore/`
- [ ] Suscripción `premium` creada en Play Console (mensual y anual, sin prueba gratis)
- [ ] Icono 512×512, gráfico de 1024×500 y 2–8 capturas de cada app
- [ ] Probar la compra con una cuenta de tester de licencias en la pista de prueba interna
