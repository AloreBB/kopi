# Kopi — Diseño (v1)

**Fecha:** 2026-10-25
**Estado:** Pendiente de revisión
**Repo:** `AloreBB/kopi` (público) · `~/Proyectos/kopi`

## 1. Intención

Replicar la experiencia del Portapapeles Universal de Apple entre un teléfono Android y una Mac: **copias en un dispositivo y pegas en el otro, sin tocar nada**.

Apple no permite usar su implementación (Continuity) desde Android: la descubre por BLE con claves ligadas a la cuenta de iCloud y transfiere por AWDL, un Wi-Fi propietario. Esto se confirmó analizando `useractivityd` en macOS 15 (`UASharedPasteboard*`, `_pasteAdvertiser`, `SFActivityAdvertiser`, `SFPeerDevice`). Kopi reproduce la **misma arquitectura** (aviso → petición bajo demanda → entrega) con un protocolo propio sobre tecnologías abiertas.

### Uso y contexto

- **Para quién:** uso personal del autor. El repositorio es público, así que el código y la documentación deben quedar presentables y no contener secretos.
- **Dispositivos:** Tecno Camon 40 Pro (Android 16, HiOS) y MacBook Air M1 (macOS 15).
- **Redes:** el Wi-Fi de casa, o la Mac conectada al hotspot del teléfono. En ambos casos comparten red local.
- **Caso más frecuente:** copiar un número de tarjeta en el teléfono y pegarlo en la Mac.

## 2. Objetivos, no-objetivos y anti-objetivos

### Objetivos (v1)

- Sincronizar **texto e imágenes** en **ambas direcciones**, de forma **automática**.
- Que el texto esté disponible para pegar en el otro equipo en **menos de 1 segundo** desde que se copia.
- Seguir funcionando tras horas en segundo plano, y tras reiniciar el teléfono una vez reactivado Shizuku.
- Emparejar sin pasos: el primer teléfono que se conecta queda vinculado (con opción de deshacer desde la Mac).
- Mostrar siempre el estado de la conexión.

### No-objetivos (v1)

- Transferir archivos (para eso existen Quick Share/AirDrop y LocalSend).
- Más de un teléfono y una Mac.
- Bluetooth/BLE, previsto para la v2.
- Historial del portapapeles.
- Publicar en Play Store, o firmar y notarizar la app de Mac.
- Funcionar sin una red local compartida.

### Anti-objetivos (sería un fracaso aunque "funcione")

- Que lo copiado reaparezca en bucle entre los dos equipos.
- Que se pegue contenido viejo, de hace más de 2 minutos, al reconectar.
- Que un dispositivo no emparejado pueda enviar o recibir.
- Que un número de tarjeta aparezca completo en una notificación o en un log.
- Que el teléfono pierda batería de forma notable.

## 3. Restricciones

- **Android ≥ 10** solo permite leer el portapapeles a la app en primer plano. En el Tecno (Android 16, HiOS) el appop `READ_CLIPBOARD_IN_BACKGROUND` no existe, así que no se le puede conceder a la app. El uid **shell** sí tiene ese permiso: Kopi lee el portapapeles desde un proceso shell que lanza **Shizuku**, sin root (ver §5.2).
- **HiOS** congela el proceso de la app a los ~8–10 s en segundo plano, aunque tenga un servicio en primer plano, exclusión de batería e inicio automático. Los procesos shell no se congelan. Por eso la sincronización corre en un daemon shell, no en la app.
- **Sin root, Shizuku se detiene al reiniciar** el teléfono, y el daemon con él. Hay que reactivarlo a mano tras cada reinicio.
- **macOS** no notifica los cambios del portapapeles, así que hay que consultar `NSPasteboard.changeCount`.
- **No está instalado Xcode**, solo las Command Line Tools con Swift 6.2. La app de Mac debe compilar con SwiftPM.
- **Android no tiene "promesas" de portapapeles**: el contenido se escribe completo al recibirlo.
- **Repositorio público:** nada de claves, certificados ni datos personales en el repo.

## 4. Enfoques considerados

| Decisión | Opciones | Elegida | Motivo |
|---|---|---|---|
| Reproducir Continuity de Apple | (a) Ingeniería inversa y clonar el protocolo · (b) Protocolo propio con la misma arquitectura | **b** | (a) exige extraer claves del llavero de iCloud (desactivando SIP) y AWDL en Android (imposible sin hardware y root). Sería frágil con cada actualización. |
| Lenguajes | Swift + Kotlin nativos · Flutter · Tauri · Electron | **Swift + Kotlin** | Acceso directo a `NSPasteboard`, Network.framework, Shizuku y servicios; bajo consumo. Flutter igual necesitaría código nativo en ambos lados. |
| Transporte | Wi-Fi local · híbrido BLE + Wi-Fi · solo BLE | **Wi-Fi local** | Cubre ambas redes del usuario. BLE da 50–200 KB/s (una captura tardaría entre 10 y 40 s) y es inestable en segundo plano en HiOS. El híbrido queda para la v2. |
| Orquestación del monorepo | Makefile · Turborepo · just | **Makefile** | Ya está instalado y no añade dependencias. Gradle y SwiftPM ya cachean; Turborepo aporta valor sobre todo en JS/TS. |
| Formato de mensajes | JSON · Protobuf | **JSON** (decisión del diseñador) | Son pocos tipos de mensaje. Los fixtures compartidos garantizan el contrato sin generar código. |

## 5. Arquitectura

```
kopi/
├── apps/macos/          # Paquete Swift (SwiftPM) → Kopi.app
├── apps/android/        # Proyecto Gradle (Kotlin DSL + version catalog)
├── protocol/
│   ├── PROTOCOL.md      # Especificación del protocolo
│   └── fixtures/*.json  # Mensajes de ejemplo verificados por ambas apps
├── docs/
│   ├── superpowers/specs/
│   └── manual-testing.md
├── .github/workflows/   # CI macOS y Android, filtrado por ruta
└── Makefile             # make mac | make android | make test | make release
```

**Roles:** la Mac es el **servidor**, porque es estable y se anuncia por Bonjour. El teléfono es el **cliente**: cambia de red a menudo, así que es él quien busca y reconecta.

### 5.1 App de Mac

- **Pila:** Swift 6, SwiftUI `MenuBarExtra`, Network.framework, CryptoKit, swift-certificates, Keychain, CoreImage y `SMAppService`.
- **Compilación:** SwiftPM más un script que arma el bundle `Kopi.app` con `Info.plist` y `LSUIElement` (sin icono en el Dock).

| Módulo | Responsabilidad | Depende de |
|---|---|---|
| `ClipboardWatcher` | Consulta `changeCount` cada 0,5 s, lee texto e imagen, y escribe lo recibido (el texto directo, la imagen como promesa `NSPasteboardItemDataProvider`). Recuerda el `id` de sus propias escrituras para no reenviarlas. | `NSPasteboard` |
| `PeerServer` | `NWListener` con WebSocket y TLS 1.3 mutuo. Anuncia `_kopi._tcp` por Bonjour. Sin teléfono vinculado acepta cualquier certificado y vincula al primero que envía `hello`; vinculado, acepta solo su huella. | Network.framework, `Pairing` |
| `Pairing` | Genera la identidad (clave P-256 y certificado autofirmado) y la guarda en el Llavero. Guarda la huella y el nombre del teléfono vinculado. | CryptoKit, swift-certificates, Keychain |
| `SyncEngine` | Lógica pura: versiones, deduplicación por `id`, el último gana, caducidad de 2 minutos, ofertas y peticiones de imagen, y qué enviar al reconectar. | Ninguna (inyectable) |
| `Preview` | Genera el texto de vista previa: recorte del texto, enmascarado de tarjetas y "contenido oculto". | Ninguna |
| `MenuBarUI` | Estado, último elemento, "Desvincular", "Abrir al iniciar sesión" y "Ver registro". Al vincularse un teléfono, pulso del icono y notificación "Kopi se vinculó con <nombre>" con la acción **Desvincular** (si macOS la permite). | Los anteriores, UserNotifications |

### 5.2 App de Android

> **Enmienda 2026-10-25 (tras el experimento).** El experimento (`docs/spikes/2026-10-hios-clipboard.md`) mostró que el proceso de la app no puede leer el portapapeles en segundo plano y que HiOS lo congela. La sincronización se mueve a **`KopiDaemon`**, un `UserService` de Shizuku en modo daemon (`daemon(true)`, sufijo de proceso `daemon`) que corre como uid shell con el código del propio APK. La app queda como interfaz: configuración, vinculación y estado, y habla con el daemon por AIDL (`IKopiDaemon`). Ya no hay `SyncService` en primer plano ni identidad en el Android Keystore.

- **Pila:** Kotlin, corrutinas, Jetpack Compose, Shizuku API (`dev.rikka.shizuku`, `UserService`), AIDL, `NsdManager`, OkHttp (WebSocket + TLS), PKCS#12 con BouncyCastle y kotlinx.serialization.
- **Versiones:** `minSdk 31`, `targetSdk 35`.

| Módulo | Proceso | Responsabilidad | Depende de |
|---|---|---|---|
| `KopiDaemon` | daemon (shell) | `UserService` de Shizuku que implementa `IKopiDaemon`. Aloja `SyncEngine` (`EAGER`), `PeerClient` y `ShellClipboard`, y publica la notificación de estado. | Todos los del daemon |
| `ShellClipboard` | daemon | `IClipboard` por reflexión como `"com.android.shell"`: `getPrimaryClip`, `setPrimaryClip` y `addPrimaryClipChangedListener`. Si el listener no se dispara, sondea cada 1 s. Lee texto, imagen (URI → bytes, si shell tiene acceso) y `EXTRA_IS_SENSITIVE`. | `ServiceManager`, `IClipboard` |
| `DaemonIdentity` | daemon | Clave EC P-256 por software y certificado autofirmado en `/data/local/tmp/kopi/identity.p12` (carpeta 700, archivo 600, dueño shell). Se inyecta en `PinnedTls`. | BouncyCastle, `KeyStore` PKCS12 |
| `DaemonState` | daemon | `PeerStore` en `/data/local/tmp/kopi/state.json` (600): huella y nombre de la Mac, último host y puerto. | kotlinx.serialization |
| `PeerClient` | daemon | Descubre `_kopi._tcp` con `NsdManager` (vía un `Context` de sistema obtenido por reflexión, `ActivityThread`) y, si no, usa el último host:puerto. Conecta por OkHttp con TLS mutuo y la huella de la Mac fijada. Reintenta con espera creciente (1→30 s) y de inmediato al cambiar de red. *Ping* cada 30 s. | `NsdManager`, OkHttp, `PinnedTls` |
| `SyncEngine` | daemon | La misma lógica que en la Mac (portada, verificada con los mismos fixtures y casos). | Ninguna |
| `Preview` | daemon y app | La misma lógica que en la Mac. | Ninguna |
| `ShellNotifier` | daemon | Notificación de estado publicada como `com.android.shell` vía `INotificationManager`. Si falla, el estado solo se ve en la app. | `INotificationManager` |
| `ClipsProvider` | app | `ContentProvider` de solo lectura que sirve las imágenes Mac → Android que el daemon escribe en `/sdcard/Android/media/dev.alorebb.kopi/clips/`. | — |
| `SetupUI` | app | Asistente: instalar e iniciar Shizuku → inicio automático de HiOS (si existe la pantalla oculta) y batería, opcionales → iniciar el daemon, que se vincula solo con la Mac (§6.1; sin QR). Pantalla de estado que consulta al daemon mientras está abierta. | Shizuku API, `IKopiDaemon` |

- **Reinicio:** Shizuku se detiene y el daemon también. Al abrir la app se muestra "Reactivar Shizuku". Hasta entonces no se lee ni se envía nada.
- **Imágenes Android → Mac:** el daemon lee el URI con `ContentResolver` como shell. Es *best effort*: si shell no tiene acceso, solo se sincroniza texto desde Android y se registra el motivo.
- **Imágenes Mac → Android:** el daemon escribe el PNG en `/sdcard/Android/media/dev.alorebb.kopi/clips/` (shell puede escribir ahí y la app leer) y pone en el portapapeles un URI `content://dev.alorebb.kopi.clips/<id>` servido por `ClipsProvider`.

## 6. Protocolo

La especificación completa irá en `protocol/PROTOCOL.md`. Resumen:

### 6.1 Emparejamiento

> **Enmienda 2026-10-25.** Se quita el QR y el código de un solo uso: el emparejamiento es automático (*trust on first use*) con opción de deshacer. Desaparecen el mensaje `pair` y el error `invalid_code`; `protocolVersion` sigue en 1.

1. **Descubrimiento:** el teléfono encuentra la Mac por Bonjour (`_kopi._tcp`) o por el último host:puerto, y se conecta por TLS presentando su propio certificado.
2. **Verificación en la Mac:** si **no hay teléfono vinculado**, acepta cualquier certificado de cliente. Si lo hay, solo acepta su huella.
3. **Vinculación automática:** con un `hello` válido y sin teléfono vinculado, la Mac guarda la huella del certificado y `hello.name`, y responde `hello` y luego `{"type":"paired","name":"MacBook Air"}`. El teléfono fija entonces la huella de la Mac.
4. **Exclusividad:** a partir de aquí, cada lado acepta solo la huella del otro. **Desvincular** en la Mac olvida el teléfono y corta la sesión; el siguiente teléfono que se conecte se vincula solo.

### 6.2 Sesión

- Primer mensaje en ambos sentidos: `{"type":"hello","protocolVersion":1,"app":"1.0.0","name":"Tecno Camon 40 Pro"}`. `name` es **obligatorio** (nombre visible del equipo que lo envía). Si las versiones mayores difieren, se cierra con `{"type":"error","code":"version_mismatch"}` y no se vincula nada.
- Respuesta de la Mac: sin teléfono vinculado → `hello` + `paired`; vinculada con esta huella → `hello`; cualquier otro caso (p. ej., otro teléfono se vinculó mientras este conectaba) → `error not_paired` y cierre.
- Transporte: WebSocket. Los mensajes de control van como frames de texto JSON; los datos de imagen, como frames binarios.

### 6.3 Mensajes de portapapeles

| Mensaje | Campos | Uso |
|---|---|---|
| `clip` | `id` (UUID), `ts` (ms epoch del emisor), `kind:"text"`, `text`, `sensitive` (bool) | Texto: se envía completo de inmediato |
| `clip_offer` | `id`, `ts`, `kind:"image"`, `mime` (`image/png`\|`image/jpeg`), `size` | Aviso de imagen disponible |
| `clip_request` | `id` | Petición de los datos de una oferta |
| `clip_data` | `id`, `size` + frames binarios `[16 bytes id][fragmento ≤ 256 KB]` | Entrega de la imagen |
| `clip_unavailable` | `id`, `reason` (`expired`\|`replaced`) | La oferta ya no se puede servir |

- **Imagen de Android → Mac:** la Mac registra una promesa en `NSPasteboard` y pide `clip_request` cuando el usuario pega. Si no llegan datos en 5 s, devuelve vacío.
- **Imagen de Mac → Android:** Android pide `clip_request` en cuanto recibe la oferta, porque no tiene promesas, y escribe la imagen al completarse la descarga.
- **Caducidad:** una oferta es válida 2 minutos desde `ts`. Después, el origen responde `clip_unavailable`.
- **Límite:** las imágenes de más de 20 MB no se ofrecen. HEIC y TIFF se convierten a PNG antes de ofrecerse.

### 6.4 Reglas del `SyncEngine`

1. **Sin bucles:** un `id` recibido y escrito localmente nunca se reenvía.
2. **Conflictos:** gana el `ts` mayor. A igual `ts`, el `id` lexicográficamente mayor.
3. **Reconexión:** solo se envía el último elemento local, y solo si tiene menos de 2 minutos. No hay cola.
4. **Contenido sensible:** el contenido marcado como sensible se sincroniza igual que cualquier otro. Se respeta la marca solo en la vista previa y en los logs.

## 7. Privacidad y vista previa

- **Todo se sincroniza.** No hay bloqueos ni borrado automático.
- La **vista previa** (notificación de Android y menú de la Mac) sigue estas reglas:
  - **Texto normal:** los primeros ~40 caracteres, en una línea.
  - **Número de tarjeta:** `tarjeta •••• 4242`. Un número se detecta si tiene entre 13 y 19 dígitos, tolera espacios y guiones, y cumple la verificación de Luhn.
  - **Marcado como sensible** por la app de origen (`EXTRA_IS_SENSITIVE`, `org.nspasteboard.ConcealedType`): `contenido oculto`.
  - **Imagen:** `imagen`.
- **Los logs** nunca contienen contenido del portapapeles, solo el tipo, el tamaño y el `id`.
- **Las claves privadas** viven en el Llavero (Mac) y, en Android, en `/data/local/tmp/kopi/identity.p12` (carpeta 700, archivo 600, dueño shell: solo adb y shell pueden leerlo), porque el daemon shell no puede usar el Android Keystore de la app. Nunca se guardan en el repo.

## 8. Mensajes al usuario

Los mensajes siguen la guía de *ux-heuristics*: dicen qué pasó y cómo se resuelve, en lenguaje simple, con botones de verbo + sustantivo y sin jerga técnica.

### Estado permanente

| Estado | Android (notificación fija) | Mac (icono y menú) |
|---|---|---|
| Conectado | "Conectado a MacBook Air" · "Último: …, hace 5 s" | Icono normal · "Conectado a Tecno Camon 40 Pro" |
| Sin vincular | "Buscando tu Mac…" | Icono atenuado · "Esperando a tu teléfono… Abre Kopi en tu Android." |
| No disponible (vinculado) | "Mac no encontrada. Se conectará sola cuando estén en la misma red." | Icono atenuado · "Teléfono no conectado" |

### Problemas que requieren acción

| Situación | Mensaje | Acción |
|---|---|---|
| Shizuku inactivo | "Kopi no puede detectar lo que copias. Shizuku se desactivó al reiniciar." | **Reactivar Shizuku** |
| Shizuku activo pero el daemon no responde | "Kopi no se está ejecutando." | **Iniciar Kopi** |
| Huella rechazada | "Este teléfono ya no está vinculado a tu Mac." | **Vincular de nuevo** |
| Versión incompatible | "Kopi está desactualizado en tu Mac (v1.0). Actualízalo para seguir sincronizando." | **Ver cómo actualizar** |
| Imagen demasiado grande | "Imagen no enviada: pesa 34 MB (máximo 20 MB)." | — |

### Confirmaciones y casos silenciosos

- **Mac:** al recibir algo, el icono hace un pulso breve, sin notificación.
- **Mac, vinculación automática:** pulso del icono y notificación "Kopi se vinculó con Tecno Camon 40 Pro" · "Ya sincroniza lo que copias. ¿No es tu teléfono? Desvincúlalo." con la acción **Desvincular**. Con firma ad hoc macOS puede no permitir notificaciones; entonces solo queda el pulso y "Conectado a …" en el menú.
- **Android:** se actualiza la línea "Último: …" de la notificación fija. La publica el daemon como `com.android.shell` (*best effort*); si no se puede, el estado solo se ve en la pantalla de la app.
- **Sin aviso al usuario:** descartar contenido de más de 2 minutos al reconectar, una imagen interrumpida a mitad de la transferencia y las conversiones de formato. Todo esto solo se registra en el log interno.

## 9. Pruebas

1. **Unitarias:** del `SyncEngine` (bucles, conflictos, caducidad, reconexión) y de `Preview` (Luhn: válidas, inválidas, con espacios y guiones; enmascarado; sensible). Swift Testing en la Mac; JUnit con `kotlinx-coroutines-test` en Android.
2. **De contrato:** cada archivo de `protocol/fixtures/*.json` se decodifica y se vuelve a codificar de forma equivalente en **ambas** apps. Un fixture roto rompe la CI.
3. **De integración en la Mac:** un cliente de prueba en Swift que actúa como teléfono y cubre la vinculación automática, el TLS mutuo, el rechazo de huellas desconocidas, el texto de punta a punta y la imagen con promesa. Usa un `NSPasteboard` con nombre propio, no el general.
4. **Manuales:** una lista de verificación en `docs/manual-testing.md` (texto y tarjeta en ambas direcciones en menos de 1 s; capturas en ambas direcciones; reinicio del teléfono; 3 h en segundo plano; cambio a hotspot; desvincular y que el teléfono se vuelva a vincular solo).
5. **CI (GitHub Actions):** Android en Ubuntu (compilación y pruebas) y Mac en macOS (compilación y pruebas), cada uno filtrado por ruta; ambos corren si cambia `protocol/`. Con un tag `v*`, se publican en Releases `Kopi.app.zip` sin firmar y el APK.
6. **Fuera de alcance:** pruebas automáticas de interfaz.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| ~~HiOS no entrega `OnPrimaryClipChangedListener` en segundo plano~~ | **Confirmado** en el experimento: ni el listener ni el sondeo funcionan en la app, y HiOS la congela. Resuelto moviendo la sincronización a `KopiDaemon` (shell). |
| El `UserService` de Shizuku no conecta (en el experimento nunca llegó el callback) | La Task 14 valida primero, en el dispositivo, que el daemon arranca como shell. Si no, se detiene y se replantea. |
| Firmas ocultas de `IClipboard` cambian entre versiones de Android | Elegir la sobrecarga y rellenar argumentos con una función pura probada contra varias firmas; registrar la firma elegida. |
| Shell no puede leer el URI de una imagen copiada en Android | *Best effort*: solo texto de Android → Mac, con el motivo en el log. |
| La app que pega no puede leer el `content://` de una imagen Mac → Android (permiso del URI o app congelada) | Validarlo en el dispositivo en la Task 14; si falla, las imágenes Mac → Android quedan fuera de la v1. |
| La notificación como `com.android.shell` falla | El estado sigue visible en la app. |
| mDNS bloqueado en el hotspot de HiOS | En el Wi-Fi de casa NSD resolvió en 0,2 s; el hotspot se mide en la Task 16. Alternativa: conectar directo a la última IP conocida de la Mac. |
| `SMAppService` o los permisos fallan en una app sin firmar | Firma ad-hoc (`codesign -s -`) en el script de bundle; documentar que hay que permitirla en Ajustes la primera vez. |
| Shizuku se desactiva en cada reinicio | Mensaje claro con la acción **Reactivar Shizuku** al abrir la app. Hasta reactivarlo no se lee ni se envía nada (el daemon también se detiene). |

## 11. Decisiones del diseñador (revisables)

- JSON en lugar de Protobuf.
- La Mac como servidor y el teléfono como cliente.
- Clave P-256 y certificado autofirmado en ambos lados; huella SHA-256.
- Vinculación automática (*trust on first use*) con **Desvincular** como deshacer, en lugar de QR con código de un solo uso: en la red de casa el riesgo de que otro teléfono con Kopi se adelante es bajo y la Mac muestra siempre con quién está vinculada.
- Fragmentos de imagen de 256 KB; límite de 20 MB; espera de 5 s al pegar una imagen en la Mac.
- `minSdk 31` (Android 12).
- Vista previa de ~40 caracteres.
- Licencia del repo: **Apache 2.0** (confirmada; `LICENSE` + `NOTICE`).

## 12. Queda para quien implemente

- Nombres internos de clases y archivos dentro de cada módulo.
- Diseño visual del menú de la Mac y de las pantallas de configuración de Android (siguiendo *ux-heuristics*).
- Icono de la app.
- Detalles del script de bundle y del `Makefile`.
