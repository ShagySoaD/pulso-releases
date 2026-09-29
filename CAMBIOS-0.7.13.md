# PULSO 0.7.13 / codigo 25

Notificacion persistente de version pendiente, restaurada al abrir la actividad y tras conceder notificaciones. Un unico ID evita duplicados. Se cancela cuando la version instalada ya esta actualizada. Android y el usuario mantienen el control del permiso y de la posibilidad de descartar avisos.

El intent de la notificacion abre Ajustes y vuelve al inicio de su lista independiente, donde Actualizaciones es el primer bloque. Se elimina el dialogo automatico de actualizaciones; descarga e instalacion se ofrecen dentro del bloque.

Descargador: conexiones sin cache, codificacion identity, espera de lectura de 90 segundos, tres intentos ante errores de E/S de red, directorio privado filesDir con comprobacion previa de espacio, errores concretos y registro en logcat PulsoUpdater. No se relajan hashes, firma, version, paquete ni servidores permitidos.

El mensaje generico de 0.7.11/12 no permite deducir la causa reportada sin datos del dispositivo. No hay telefono conectado para reproducir el fallo de Android; la prueba de red en JVM no sustituye la validacion en telefono. Una compilacion hecha en otro equipo puede tener una firma distinta: se informa y se rechaza correctamente, sin borrar datos ni pedir desinstalar.

Si el actualizador anterior no funciona, esta correccion necesita una instalacion manual sobre la misma firma. La prueba completa del aviso e instalacion exige una version posterior a esta instalada.
