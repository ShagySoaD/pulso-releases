# Actualizaciones de PULSO

Canal: https://github.com/ShagySoaD/pulso-releases/releases
Metadatos: https://github.com/ShagySoaD/pulso-releases/releases/latest/download/update.json

## Para el usuario

El actualizador existe desde 0.7.11. Desde 0.7.13, PULSO consulta el canal al abrir la actividad y programa ademas una consulta cada 12 horas con conexion. Android puede retrasar el trabajo en segundo plano. Si el actualizador anterior falla, instalar manualmente 0.7.13 sobre la misma firma, sin desinstalar.

Una version mas reciente y compatible mantiene una notificacion con un unico ID, que se restaura al abrir PULSO mientras siga pendiente. Requiere permiso de notificaciones; Android puede permitir al usuario descartarla. Al tocarla se abre Ajustes, con Actualizaciones como primer bloque, sin dialogo automatico. Cuando ya esta actualizado se retira el aviso.

Descargar selecciona la arquitectura compatible. La descarga se ejecuta con WorkManager y notificacion de progreso. Se verifican tamano, SHA-256, paquete, version y certificado antes de abrir el instalador de Android. Si hace falta, Android pide permitir instalaciones desde PULSO: al volver, pulsar Instalar. La instalacion requiere intervencion del usuario y mantiene los datos al actualizar sin desinstalar.

Desde 0.7.13, la APK se guarda en filesDir/app-updates, sin permiso general de almacenamiento. Los errores de E/S de red se reintentan hasta tres veces; las discrepancias de integridad o firma se rechazan. Los mensajes distinguen las etapas del fallo. Logcat usa la etiqueta PulsoUpdater para diagnosticar verificacion en Android.

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
