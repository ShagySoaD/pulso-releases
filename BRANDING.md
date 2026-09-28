# Identidad PULSO

Emblema aprobado: imagen de Telegram en ../difusion/Pulso-Telegram-v1.png.
Adaptación vectorial de sus barras de sonido y letra P para tamaños pequeños, sin el texto ni los brillos rasterizados.

- ic_pulso_mark.xml: silueta de la interfaz, coloreada con MaterialTheme.colorScheme.primary.
- ic_pulso_launcher.xml: emblema coral/rojo con degradado y margen para máscaras adaptativas.
- ic_pulso_monochrome.xml: silueta con el mismo margen para iconos temáticos de Android 13+ en launchers compatibles.
- PulsoMark: componente compartido en encabezado, vista previa de temas y portada de reserva.

El color personalizado de PULSO se aplica dentro de la app. El launcher normal conserva rojo sobre negro. El launcher temático toma los colores del sistema, no las preferencias internas de PULSO.

Se mantienen los identificadores del icono y paquete para actualizar los accesos existentes. No se agregan dependencias ni imágenes grandes a la APK.
