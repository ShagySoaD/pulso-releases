# Actualizaciones de PULSO

Canal: https://github.com/ShagySoaD/pulso-releases/releases
Metadatos: https://github.com/ShagySoaD/pulso-releases/releases/latest/download/update.json

## Para el usuario

Instala manualmente la primera version 0.7.11. A partir de ella, PULSO consulta el canal al abrir si la ultima consulta correcta tiene mas de 12 horas, y programa una consulta periodica con conexion. Android puede retrasar el trabajo en segundo plano.

Una version mas reciente y compatible muestra un aviso con novedades. La notificacion del sistema requiere permiso y se emite una vez por version. El aviso dentro de la app tambien se muestra una vez; Ajustes → Actualizaciones conserva el acceso aunque el usuario pulse Mas tarde o deniegue las notificaciones.

Descargar selecciona la arquitectura compatible. La descarga se ejecuta con WorkManager y notificacion de progreso. Se verifican tamano, SHA-256, paquete, version y certificado antes de abrir el instalador de Android. Si hace falta, Android pide permitir instalaciones desde PULSO: al volver, pulsar Instalar. La instalacion requiere intervencion del usuario y mantiene los datos al actualizar sin desinstalar.

## Para publicar una version

1. Incrementar versionName y versionCode en app/build.gradle.kts.
2. Compilar las tres APK con la firma original y comprobar sus certificados.
3. Colocarlas en una carpeta de entrega con nombres Pulso-VERSION-ARQUITECTURA.apk.
4. Ejecutar tools/Prepare-Release.ps1 con Version, VersionCode, ApkDirectory y Notes. Genera update.json y SHA256.txt a partir de los archivos reales.
5. En GitHub → Releases → Draft a new release, crear etiqueta vVERSION y escribir novedades.
6. Adjuntar las tres APK, update.json, SHA256.txt y el codigo fuente correspondiente con sus licencias cuando corresponda.
7. Publicar como release estable (no prerelease) y establecerla como Latest. Verificar el enlace de metadatos sin iniciar sesion.

Un release publicado debe conservar sus archivos. Una correccion se publica con versionCode superior; no sustituir una APK bajo el mismo numero.

No publicar claves de firma, keystores, contrasenas, local.properties, caches, respaldos personales ni carpetas build. El repositorio es publico; la APK no contiene credenciales de GitHub.

## Limites

Las versiones 0.7.10 y anteriores no tienen el comprobador: requieren instalar 0.7.11 manualmente. Un telefono sin conexion, con notificaciones bloqueadas o con restricciones de bateria puede recibir el aviso mas tarde. No se fuerzan actualizaciones ni se instala silenciosamente.

La comprobacion de hashes y firmas no sustituye la proteccion de la cuenta de GitHub y de la clave de firma. El sistema admite el certificado actual; un cambio futuro de clave requiere disenar una migracion.
