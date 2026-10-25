# Kopi

Copia en tu Android, pega en tu Mac (y al revés). Un portapapeles universal entre Android y macOS, inspirado en Continuity de Apple, sobre tu red Wi-Fi local.

Sincroniza texto e imágenes (PNG y JPEG). Sin cuentas, sin nube.

> **Estado:** v1 en desarrollo. Ver [la especificación](docs/superpowers/specs/2026-10-25-kopi-design.md).

## Requisitos

- Una Mac con **macOS 14** o posterior.
- Un teléfono con **Android 12** o posterior.
- Ambos equipos en **la misma red Wi-Fi** (o la Mac conectada al hotspot del teléfono).
- [**Shizuku**](https://shizuku.rikka.app/) instalado en el teléfono. Android solo deja leer el portapapeles a la app en primer plano; Shizuku le da a Kopi ese permiso sin root.

## Instalación

Descarga `Kopi-macOS.zip` y `Kopi-android.apk` desde [Releases](../../releases), o compílalos tú (ver [Compilar](#compilar)).

### En la Mac

1. Descomprime `Kopi-macOS.zip` y mueve `Kopi.app` a **Aplicaciones**.
2. La app no está firmada por un desarrollador identificado, así que la primera vez ábrela con **clic derecho → Abrir** y confirma con **Abrir**. Las siguientes veces se abre con doble clic.
3. Kopi aparece en la barra de menús.

### En el teléfono

1. **Inicia Shizuku** desde su app, con *depuración inalámbrica* (Android 11+) o por USB con `adb`. Sigue las instrucciones de Shizuku.
   Shizuku se detiene al reiniciar el teléfono; tendrás que volver a iniciarlo. Kopi te avisa con **Reactivar Shizuku**.
2. Instala `Kopi-android.apk`, ábrelo y concede el permiso de Shizuku cuando lo pida.
3. **Excluye Kopi del ahorro de batería.** En HiOS (Tecno, Infinix, itel) y otras capas que cierran apps en segundo plano, permite el inicio automático y quita las restricciones de batería. El asistente de Kopi te lleva a esos ajustes.
4. **Vincula los equipos:** en la Mac, abre el menú de Kopi → **Vincular teléfono…** y escanea el código QR con el teléfono. El código caduca a los 5 minutos y sirve una sola vez.

Listo: lo que copies en un equipo queda disponible para pegar en el otro.

## Compilar

Necesitas Swift 6 (Xcode 16 o Command Line Tools) y JDK 21 con el SDK de Android.

| Comando        | Qué hace                                                        |
| -------------- | --------------------------------------------------------------- |
| `make mac`     | Compila la app de Mac en `apps/macos/build/Kopi.app`.            |
| `make android` | Compila el APK de depuración en `apps/android/app/build/outputs/apk/debug/`. |
| `make test`    | Valida los fixtures del protocolo y corre los tests de Mac y Android. |

Otros: `make run-mac` abre la app compilada; `make install-android` instala el APK con `adb`.

## Privacidad

- Todo ocurre en tu red local. Kopi no usa servidores externos ni cuentas.
- La conexión va cifrada con TLS y solo se aceptan equipos vinculados.
- El contenido del portapapeles **nunca** se registra en los logs: solo el tipo, el tamaño y un identificador.
- Lo que una app marca como sensible (por ejemplo, contraseñas) se muestra como «contenido oculto».

## Licencia

[Apache 2.0](LICENSE) © 2026 Kevin Jovy
