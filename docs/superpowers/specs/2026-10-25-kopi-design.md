# Kopi — Diseño (v1)

**Fecha:** 2026-10-25
**Estado:** Pendiente de revisión
**Repo:** `AloreBB/kopi` (público) · `~/Proyectos/kopi`

## 1. Intención

Replicar la experiencia del Portapapeles Universal de Apple entre un teléfono Android y una Mac: **copias en un dispositivo y pegas en el otro, sin tocar nada**.

Apple no permite usar su implementación (Continuity) desde Android: la descubre por BLE con claves ligadas a la cuenta de iCloud y transfiere por AWDL, un Wi-Fi propietario. Esto se confirmó analizando `useractivityd` en macOS 15 (`UASharedPasteboard*`, `_pasteAdvertiser`, `SFActivityAdvertiser`, `SFPeerDevice`). Kopi reproduce la **misma arquitectura** (aviso → petición bajo demanda → entrega) con un protocolo propio sobre tecnologías abiertas.

### Uso y contexto

- **Para quién:** uso personal del autor. El repositorio es público, así que el código y la documentación deben quedar presentables y no contener secretos.
- **Dispositivos:** Tecno Camon 40 Pro (Android 15, HiOS) y MacBook Air M1 (macOS 15).
- **Redes:** el Wi-Fi de casa, o la Mac conectada al hotspot del teléfono. En ambos casos comparten red local.
- **Caso más frecuente:** copiar un número de tarjeta en el teléfono y pegarlo en la Mac.

## 2. Objetivos, no-objetivos y anti-objetivos

### Objetivos (v1)

- Sincronizar **texto e imágenes** en **ambas direcciones**, de forma **automática**.
- Que el texto esté disponible para pegar en el otro equipo en **menos de 1 segundo** desde que se copia.
- Seguir funcionando tras reiniciar el teléfono y tras horas en segundo plano.
- Emparejar una sola vez, escaneando un QR.
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

- **Android ≥ 10** solo permite leer el portapapeles a la app en primer plano. Kopi obtiene `READ_CLIPBOARD_IN_BACKGROUND` mediante **Shizuku**, sin root.
- **HiOS** cierra servicios en segundo plano con agresividad. Hacen falta un servicio en primer plano y una exclusión del ahorro de batería.
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
| `PeerServer` | `NWListener` con WebSocket y TLS 1.3 mutuo. Anuncia `_kopi._tcp` por Bonjour y acepta solo la huella del teléfono emparejado. | Network.framework, `Pairing` |
| `Pairing` | Genera la identidad (clave P-256 y certificado autofirmado) y la guarda en el Llavero. Crea el QR y el código de un solo uso, y guarda la huella del teléfono. | CryptoKit, swift-certificates, Keychain, CoreImage |
| `SyncEngine` | Lógica pura: versiones, deduplicación por `id`, el último gana, caducidad de 2 minutos, ofertas y peticiones de imagen, y qué enviar al reconectar. | Ninguna (inyectable) |
| `Preview` | Genera el texto de vista previa: recorte del texto, enmascarado de tarjetas y "contenido oculto". | Ninguna |
| `MenuBarUI` | Estado, último elemento, "Vincular teléfono", "Abrir al iniciar sesión", "Ver registro" y "Desvincular". | Los anteriores |

### 5.2 App de Android

- **Pila:** Kotlin, corrutinas, Jetpack Compose, Shizuku API (`dev.rikka.shizuku`), `NsdManager`, OkHttp (WebSocket + TLS), Android Keystore, CameraX, ML Kit Barcode y kotlinx.serialization.
- **Versiones:** `minSdk 31`, `targetSdk 35`.

| Módulo | Responsabilidad | Depende de |
|---|---|---|
| `ClipboardWatcher` | `OnPrimaryClipChangedListener` con el permiso de Shizuku. Lee texto e imagen (URI → bytes) y escribe lo recibido, con las imágenes vía `FileProvider`. Lee `ClipDescription.EXTRA_IS_SENSITIVE`. | `ClipboardManager`, `ShizukuGate` |
| `ShizukuGate` | Comprueba Shizuku, pide el permiso y concede `READ_CLIPBOARD_IN_BACKGROUND` mediante `appops`. Expone el estado. | Shizuku API |
| `PeerClient` | Descubre `_kopi._tcp` y conecta por OkHttp con TLS mutuo y la huella de la Mac fijada. Reintenta con espera creciente (1→30 s) y reintenta de inmediato al cambiar de red. Envía un *ping* cada 30 s. | `NsdManager`, OkHttp, `Pairing` |
| `Pairing` | Escanea el QR. Genera la clave y el certificado en el Android Keystore y guarda la huella de la Mac (DataStore). | CameraX, ML Kit, Keystore |
| `SyncEngine` | La misma lógica que en la Mac (portada, verificada con los mismos fixtures y casos). | Ninguna |
| `Preview` | La misma lógica que en la Mac. | Ninguna |
| `SyncService` | Servicio en primer plano (`specialUse`, `START_STICKY`) que aloja los anteriores y mantiene la notificación de estado. | Todos |
| `SetupUI` | Asistente de primer uso: Shizuku → batería e inicio automático de HiOS → vinculación. Luego, pantalla de estado. | Todos |

## 6. Protocolo

La especificación completa irá en `protocol/PROTOCOL.md`. Resumen:

### 6.1 Emparejamiento

1. **QR de la Mac:** al pulsar "Vincular teléfono", la Mac muestra un QR con:
   `kopi://pair?v=1&host=<ip>&port=<p>&fp=<sha256 cert Mac>&code=<token 128 bits>`.
   El código caduca a los 5 minutos y sirve una sola vez.
2. **Conexión del teléfono:** escanea el QR, se conecta por TLS verificando `fp` y presenta su propio certificado. Envía `{"type":"pair","code":"…","name":"Tecno Camon 40 Pro"}`.
3. **Confirmación:** la Mac valida el código, guarda la huella del certificado del teléfono y responde `{"type":"paired","name":"MacBook Air"}`.
4. **Exclusividad:** a partir de aquí, cada lado acepta solo la huella del otro. Vincular un teléfono nuevo reemplaza al anterior.

### 6.2 Sesión

- Primer mensaje en ambos sentidos: `{"type":"hello","protocolVersion":1,"app":"1.0.0"}`. Si las versiones mayores difieren, se cierra con `{"type":"error","code":"version_mismatch"}`.
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
- **Las claves privadas** viven en el Llavero (Mac) y en el Android Keystore. Nunca se guardan en archivos ni en el repo.

## 8. Mensajes al usuario

Los mensajes siguen la guía de *ux-heuristics*: dicen qué pasó y cómo se resuelve, en lenguaje simple, con botones de verbo + sustantivo y sin jerga técnica.

### Estado permanente

| Estado | Android (notificación fija) | Mac (icono y menú) |
|---|---|---|
| Conectado | "Conectado a MacBook Air" · "Último: …, hace 5 s" | Icono normal · "Conectado a Tecno Camon 40 Pro" |
| Buscando | "Buscando tu Mac…" | Icono atenuado · "Esperando al teléfono…" |
| No disponible | "Mac no encontrada. Se conectará sola cuando estén en la misma red." | Icono atenuado · "Teléfono no conectado" |

### Problemas que requieren acción

| Situación | Mensaje | Acción |
|---|---|---|
| Shizuku inactivo | "Kopi no puede detectar lo que copias. Shizuku se desactivó al reiniciar." | **Reactivar Shizuku** |
| HiOS detuvo el servicio | "Kopi se detuvo por el ahorro de batería." | **Quitar restricción** |
| Huella rechazada | "Este teléfono ya no está vinculado a tu Mac." | **Vincular de nuevo** |
| Versión incompatible | "Kopi está desactualizado en tu Mac (v1.0). Actualízalo para seguir sincronizando." | **Ver cómo actualizar** |
| Imagen demasiado grande | "Imagen no enviada: pesa 34 MB (máximo 20 MB)." | — |
| QR caducado | "El código caducó. Genera uno nuevo en la Mac." | **Escanear de nuevo** |

### Confirmaciones y casos silenciosos

- **Mac:** al recibir algo, el icono hace un pulso breve, sin notificación.
- **Android:** se actualiza la línea "Último: …" de la notificación fija.
- **Sin aviso al usuario:** descartar contenido de más de 2 minutos al reconectar, una imagen interrumpida a mitad de la transferencia y las conversiones de formato. Todo esto solo se registra en el log interno.

## 9. Pruebas

1. **Unitarias:** del `SyncEngine` (bucles, conflictos, caducidad, reconexión) y de `Preview` (Luhn: válidas, inválidas, con espacios y guiones; enmascarado; sensible). Swift Testing en la Mac; JUnit con `kotlinx-coroutines-test` en Android.
2. **De contrato:** cada archivo de `protocol/fixtures/*.json` se decodifica y se vuelve a codificar de forma equivalente en **ambas** apps. Un fixture roto rompe la CI.
3. **De integración en la Mac:** un cliente de prueba en Swift que actúa como teléfono y cubre el emparejamiento, el TLS mutuo, el rechazo de huellas desconocidas, el texto de punta a punta y la imagen con promesa. Usa un `NSPasteboard` con nombre propio, no el general.
4. **Manuales:** una lista de verificación en `docs/manual-testing.md` (texto y tarjeta en ambas direcciones en menos de 1 s; capturas en ambas direcciones; reinicio del teléfono; 3 h en segundo plano; cambio a hotspot; desvincular y volver a vincular).
5. **CI (GitHub Actions):** Android en Ubuntu (compilación y pruebas) y Mac en macOS (compilación y pruebas), cada uno filtrado por ruta; ambos corren si cambia `protocol/`. Con un tag `v*`, se publican en Releases `Kopi.app.zip` sin firmar y el APK.
6. **Fuera de alcance:** pruebas automáticas de interfaz.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| HiOS no entrega `OnPrimaryClipChangedListener` en segundo plano aunque el permiso esté concedido | **Primera tarea del plan: un experimento en el Tecno.** Alternativa: consultar el portapapeles cada pocos segundos desde el servicio, vía Shizuku. |
| mDNS bloqueado en el hotspot de HiOS | Comprobarlo en el mismo experimento. Alternativa: la IP del gateway es conocida (el teléfono es el router), así que se puede probar la conexión directa a la última IP conocida de la Mac. |
| `SMAppService` o los permisos fallan en una app sin firmar | Firma ad-hoc (`codesign -s -`) en el script de bundle; documentar que hay que permitirla en Ajustes la primera vez. |
| Shizuku se desactiva en cada reinicio | Mensaje claro con la acción **Reactivar Shizuku**. Recibir desde la Mac sigue funcionando. |

## 11. Decisiones del diseñador (revisables)

- JSON en lugar de Protobuf.
- La Mac como servidor y el teléfono como cliente.
- Clave P-256 y certificado autofirmado en ambos lados; huella SHA-256.
- Códigos de vinculación de 128 bits que caducan a los 5 minutos.
- Fragmentos de imagen de 256 KB; límite de 20 MB; espera de 5 s al pegar una imagen en la Mac.
- `minSdk 31` (Android 12).
- Vista previa de ~40 caracteres.
- Licencia del repo: **Apache 2.0** (confirmada; `LICENSE` + `NOTICE`).

## 12. Queda para quien implemente

- Nombres internos de clases y archivos dentro de cada módulo.
- Diseño visual del menú de la Mac y de las pantallas de configuración de Android (siguiendo *ux-heuristics*).
- Icono de la app.
- Detalles del script de bundle y del `Makefile`.
