# Actualizaciones de PULSO

## Para el usuario

Desde 0.7.16, **Descargar actualización** abre en el navegador el enlace oficial de GitHub de la APK compatible con el teléfono. Al terminar, abre el archivo desde Descargas y confirma la instalación de Android, **sin desinstalar PULSO**. Android comprueba la firma y conserva la biblioteca al actualizar sobre la misma aplicación.

El cambio requiere instalar 0.7.16 una vez. Si el actualizador de 0.7.15 o anterior falla, descarga esta versión desde Releases e instálala manualmente. Una actualización nueva no puede cambiar el código del descargador que ya está instalado antes de instalarla.

Se mantienen la consulta al abrir PULSO, la revisión periódica cada 12 horas con conexión y la notificación de actualización pendiente. Al tocarla se abre Ajustes, con Actualizaciones al principio. Las comprobaciones en segundo plano pueden retrasarse por Android. La notificación requiere permiso; el usuario decide cuándo actualizar.

El enlace se selecciona según las arquitecturas soportadas, el Android mínimo y una versión superior a la instalada. Sólo se admiten enlaces HTTPS de APK en Releases del repositorio configurado. Se siguen publicando SHA-256 y tamaños para verificaciones externas, pero PULSO ya no descarga ni verifica el binario internamente. Android puede pedir permitir instalaciones al navegador o gestor de archivos; PULSO ya no solicita ese permiso.

Las tareas del antiguo descargador interno se cancelan al iniciar. No se muestra progreso de descarga dentro de PULSO: lo gestiona el navegador. Los permisos y descargas de canciones no cambian.

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
