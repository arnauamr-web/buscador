# Buscador de imágenes

App Android que busca de dónde viene una imagen: páginas, vídeos de YouTube, fechas e imágenes relacionadas.
No reconoce caras. Si le escribes quién es, usa ese dato para afinar la búsqueda.

## Conseguir la APK (sin instalar nada en tu ordenador)

1. Crea una cuenta en github.com y un repositorio nuevo (por ejemplo `buscador-imagenes`).
2. Sube todo el contenido de esta carpeta al repositorio, **incluida la carpeta oculta `.github`**.
   - Si no te deja subir `.github`: en GitHub pulsa **Add file → Create new file**, escribe el nombre
     `.github/workflows/build.yml` y pega el contenido del archivo.
3. Entra en la pestaña **Actions**. La compilación empieza sola (si no, pulsa **Run workflow**). Tarda unos 3-5 minutos.
4. Cuando salga el check verde, abre esa ejecución y descarga **buscador-imagenes-apk** (es un zip con `app-debug.apk`).
5. Pasa la APK al móvil, ábrela y acepta "instalar apps de fuentes desconocidas".

## Claves gratuitas (solo la primera vez, dentro de la app → Ajustes)

- **ImgBB**: https://api.imgbb.com/ → "Get API key". Sirve para subir la imagen unos minutos.
- **SerpAPI**: https://serpapi.com/ → plan gratis con 100 búsquedas al mes.
