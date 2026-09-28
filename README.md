<p align="center">
  <img src="https://github.com/ShagySoaD/pulso-releases/releases/download/v0.7.11/Pulso-Telegram-v1.png" width="200" alt="Logo de PULSO">
</p>

# PULSO

Hola, soy **ShagySoaD**. Estoy desarrollando PULSO mientras aprendo a crear aplicaciones para Android. Quería una app para escuchar música, organizar mis playlists y descubrir bandas según mis gustos, y poco a poco el proyecto fue tomando forma.

Lo comparto para que puedan probarlo, darme ideas y aprender conmigo. Es un proyecto personal, sin fines comerciales, y todavía hay cosas por pulir.

**[Descargar PULSO](https://github.com/ShagySoaD/pulso-releases/releases/latest)** · **[Reportar un problema o proponer una idea](https://github.com/ShagySoaD/pulso-releases/issues)**

## Qué incluye

- Búsqueda de canciones, artistas y playlists.
- Radar PULSO para descubrir música a partir de tus favoritas, playlists y descargas.
- Playlists propias, favoritas y reproducción sin conexión de tus descargas.
- Importación de playlists y respaldo de la biblioteca.
- Temas claro y oscuro, con colores personalizables.
- Avisos de nuevas versiones y consulta de actualizaciones desde Ajustes.

## Instalación

Necesitas **Android 10 o posterior**. En [Releases](https://github.com/ShagySoaD/pulso-releases/releases/latest) están las APK y las novedades de cada versión.

- **arm64-v8a:** para la mayoría de teléfonos actuales.
- **armeabi-v7a:** para dispositivos de 32 bits.
- **x86_64:** para emuladores y equipos compatibles.

Si ya tienes PULSO, instala la nueva APK encima de la anterior, sin desinstalar. La versión 0.7.11 incorpora el sistema de actualizaciones; si vienes de una anterior, tendrás que instalarla manualmente una vez.

## Para quienes quieran ver el código

El código está en este repositorio. Puedes explorarlo, clonarlo o hacer un fork para probar tus cambios. Se comparte con la licencia [GPLv3](LICENSE).

```sh
git clone https://github.com/ShagySoaD/pulso-releases.git
cd pulso-releases
```

Abre esa carpeta con **Android Studio** y espera a que Gradle sincronice. Necesitas **JDK 17**, **Android SDK 36** y conexión para descargar las dependencias. Para probarla, conecta un dispositivo con Android 10 o posterior y pulsa Run.

También puedes compilar desde la terminal:

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
```

```sh
# Linux / macOS
sh gradlew :app:assembleDebug
```

Para generar APK por arquitectura, usa `:app:assembleRelease -PperArchitecture=true` con el mismo comando de Gradle. Los archivos se generan en `app/build/outputs/apk/`.

### Dónde empezar

- `app/src/main/java/app/pulso/music/`: código de la aplicación.
- `app/src/main/res/`: imágenes, iconos y recursos.
- `app/src/test/`: pruebas.
- `app/build.gradle.kts`: versión y dependencias.
- [ACTUALIZACIONES.md](ACTUALIZACIONES.md): funcionamiento y publicación de actualizaciones.

Las claves de firma oficiales no se incluyen. Tu compilación usará la clave de depuración de tu equipo y no podrá instalarse encima de la APK oficial. Para desarrollar, usa un emulador o un dispositivo de prueba.

## Referencias y agradecimientos

[Echo Music](https://github.com/EchoMusicApp/Echo-Music) ha sido una referencia para explorar ideas de interfaz y funciones. PULSO también utiliza herramientas y bibliotecas como [youtubedl-android](https://github.com/junkfood02/youtubedl-android), [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org/) y Android Jetpack. Las dependencias conservan sus propias licencias.

## Cómo ayudar

Puedes contarme qué mejorarías o dejar un reporte en [Issues](https://github.com/ShagySoaD/pulso-releases/issues). Si algo falla, incluye la versión de Android, la de PULSO y los pasos para reproducirlo.

Gracias por darle una oportunidad al proyecto.
