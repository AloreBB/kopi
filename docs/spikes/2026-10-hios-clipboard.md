# Experimento: portapapeles en segundo plano en HiOS

**Fecha:** 2026-10-25 · **Tarea:** Task 7 del plan · **Rama del código:** `spike/hios-clipboard` (no se fusiona)

**Dispositivo:** TECNO CAMON 40 Pro (`TECNO_CM6`), **Android 16** (no 15, como decía la spec), HiOS. Shizuku v13.6.0 iniciado por `adb` (`libshizuku.so`). Mac en el Wi-Fi de casa.

## Resultados

| # | Pregunta | Resultado |
|---|---|---|
| a | ¿Se dispara `OnPrimaryClipChangedListener` en el proceso de la app, en segundo plano? | **No.** Nunca se disparó; el sistema deniega el acceso. |
| b | ¿Funciona el sondeo cada 2 s desde el proceso de la app? | **No.** `getPrimaryClip()` devuelve `null` en segundo plano. |
| c | ¿Se puede conceder `READ_CLIPBOARD_IN_BACKGROUND` a la app con `appops`? | **No.** La operación no existe en esta ROM. `READ_CLIPBOARD` ya estaba en `allow`. |
| c′ | ¿Puede leer el uid `shell` en segundo plano? | **Sí.** `com.android.shell` tiene el permiso concedido; una sonda como shell detectó 3 de 3 copias en ≤ 1 s. |
| d | ¿Sobrevive el proceso de la app en segundo plano? | **No.** HiOS lo congela a los ~8–10 s, aun con servicio en primer plano, lista blanca de `deviceidle` e inicio automático permitido. |
| d′ | ¿Sobrevive un proceso de larga duración como shell? | **Sí.** Un `app_process` de 70 s nunca se congeló y mantuvo una conexión TCP con la Mac todo el tiempo. |
| 3 | ¿Resuelve NSD `_kopi._tcp`? | **Wi-Fi de casa: sí, en 0,2 s.** Hotspot: **sin medir** (pendiente, Task 16). |

## Evidencia

**(a)** Listener en el proceso de la app, con otra app delante:

```
E/ClipboardService: Denying clipboard access to dev.alorebb.kopi, application is not in focus nor is it a system service for user 0
```

**(b)** Sondeo cada 2 s en segundo plano:

```
source=poll mimes=[none] len=-1 clipNull=true
```

**(c)** Conceder el appop:

```
$ adb shell appops set dev.alorebb.kopi READ_CLIPBOARD_IN_BACKGROUND allow
Error: Unknown operation string: READ_CLIPBOARD_IN_BACKGROUND
```

**(c′)** Permisos del uid shell (`dumpsys package com.android.shell`):

```
android.permission.READ_CLIPBOARD_IN_BACKGROUND: granted=true
android.permission.READ_LOGS: granted=true
```

Una sonda Java ejecutada con `app_process` como shell llama por reflexión a `IClipboard.getPrimaryClip` (sobrecarga de 4 argumentos: paquete `"com.android.shell"`, `attributionTag` `null`, `userId` 0, `deviceId` 0). En 60 s detectó las 3 copias del usuario (WhatsApp `text/plain`, Chrome `text/html`, WhatsApp), cada una en ≤ 1 s:

```
16:00:23 clip=5eea63c9/len51 ... desc=text/plain
16:00:36 clip=100e0a02/len11 ... desc=text/html
16:00:41 clip=887b3623/len29 ... desc=text/plain
```

En una ejecución anterior apareció una vez un `clip=null` transitorio; no se reprodujo.

**(d)** Congelación del proceso de la app, con servicio `specialUse` activo, `dumpsys deviceidle whitelist +dev.alorebb.kopi` y Teléfono Maestro → "Gestión de inicio automático" = Permitido:

```
I/Usf_Hiber/stateManager: freeze uid: 10370 dev.alorebb.kopi
```

La pantalla de inicio automático de HiOS no aparece en Ajustes; solo existe como actividad oculta `com.transsion.phonemaster/com.cyin.himgr.autostart.AutoStartActivity`.

**(3)** NSD en el Wi-Fi de casa (`dns-sd -R kopi-test _kopi._tcp local 47474` en la Mac):

```
nsd resolved name=kopi-test host=192.168.1.61 port=47474
```

## Decisiones

1. **La sincronización de Android sale del proceso de la app.** Corre en `KopiDaemon`, un `UserService` de Shizuku en modo daemon (`daemon(true)`, sufijo `daemon`) que se ejecuta como uid shell con el código del propio APK. Ver la enmienda de la spec §5.2.
2. **`ShellClipboard`** usa `IClipboard` por reflexión como `"com.android.shell"`: listener del sistema y, si no se dispara, sondeo cada 1 s. Se elige la sobrecarga según los parámetros que exponga la ROM.
3. **No se usa el appop** `READ_CLIPBOARD_IN_BACKGROUND` (no existe aquí). La comprobación de acceso pasa a ser: Shizuku activo y daemon respondiendo.
4. **Identidad propia del daemon** en `/data/local/tmp/kopi/identity.p12`, porque el AndroidKeyStore de la app es por uid y el daemon es shell.
5. **El fallback a la última IP conocida se mantiene.** NSD funciona en casa, pero el hotspot está sin medir.
6. Los pasos de batería e inicio automático de HiOS siguen en la configuración, pero ya no son críticos.

## Riesgos abiertos

- **El `UserService` del experimento nunca conectó** (no se registró ningún callback). Probablemente porque el proceso de la app estaba congelado, o por un fallo de configuración del bind. La Task 14 debe validar primero esta ruta en el dispositivo.
- **mDNS en el hotspot** sin medir (Task 16).
- **Reinicio:** sin root, Shizuku se detiene y el daemon con él. Hasta reactivar Shizuku no se lee ni se envía nada.
- **Imágenes:** no está comprobado que shell pueda leer los URI de imagen del portapapeles, ni que la app que pega pueda leer un `content://` escrito por el daemon (Task 14).
- **Notificación** publicada como `com.android.shell` vía `INotificationManager`: sin probar; si falla, el estado solo se ve en la app.
- **`clip=null` transitorio** visto una vez: el sondeo de respaldo debe tolerarlo sin enviar nada.
- La reflexión depende de firmas ocultas de `IClipboard` que pueden cambiar con cada versión de Android.
