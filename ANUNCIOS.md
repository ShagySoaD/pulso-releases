# Cambiar los anuncios de PULSO

Disponible desde PULSO 0.7.15. No requiere Firebase, GitHub Pages ni publicar otra APK.

1. Abre [anuncio.json](https://github.com/ShagySoaD/pulso-releases/blob/main/anuncio.json).
2. Pulsa el lápiz para editar. Cambia los textos entre comillas, conservando comas y llaves.
3. Guarda con **Commit changes** en `main`.
4. Comprueba el archivo publicado en [esta dirección](https://raw.githubusercontent.com/ShagySoaD/pulso-releases/main/anuncio.json).
5. Inicia PULSO desde cero con internet. Para asegurar un proceso nuevo, usa **Forzar detención** en Ajustes de Android y vuelve a abrirla. Volver desde el fondo o girar la pantalla no repite el aviso.

GitHub puede tardar unos minutos en servir el cambio por su caché. No es un envío instantáneo a todos los teléfonos: cada instalación lo consulta en su próximo inicio completo. El usuario debe tener 0.7.15 o posterior.

## Campos

| Campo | Uso |
|---|---|
| `schema` | Mantener `1`. |
| `enabled` | `true` para mostrar; `false` para desactivar. |
| `id` | Identificador nuevo por anuncio, por ejemplo `novedades-02`. Letras sin tildes, números, guiones o guiones bajos; máximo 80 caracteres. |
| `title` | Título, máximo 120 caracteres. |
| `message` | Texto, máximo 4000 caracteres. Escribir `\n` para separar párrafos y `\"` para una comilla dentro del texto. |
| `imageUrl` | Vacío para sólo texto. También admite imágenes del directorio `anuncios` de este repositorio. |
| `frequency` | `every_launch` para cada inicio completo; `once` para una vez por identificador, después de cerrarlo. |
| `expiresAt` | Vacío sin caducidad, o fecha UTC como `2026-12-31T23:59:59Z`. |

## Imágenes opcionales

Sube una imagen ligera PNG, JPG o WebP a `anuncios/`, con un nombre sin espacios, por ejemplo `bienvenida-02.png`. Usa esta URL en `imageUrl`:

```text
https://raw.githubusercontent.com/ShagySoaD/pulso-releases/main/anuncios/bienvenida-02.png
```

Usa un nombre nuevo si cambias la imagen para evitar una versión anterior en caché. El popup adapta la imagen y permite desplazarse por textos largos. Esta versión muestra texto e imágenes; no ejecuta HTML ni scripts.

## Conexión y privacidad

La consulta se hace en segundo plano y no bloquea el inicio ni la reproducción. Si falla la red o el JSON es inválido, puede reutilizar el último anuncio válido durante un máximo de 24 horas, siempre respetando la caducidad. Sin caché válida no muestra anuncio. Al descargar `enabled: false` se reemplaza también el anuncio guardado. Un teléfono sin conexión puede seguir viendo la copia anterior hasta que caduque la caché.

No hay claves de GitHub dentro de la app ni se envían estadísticas de usuarios. Los anuncios son públicos. La lectura hace una petición normal a GitHub; no se añade un identificador de instalación.
