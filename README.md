# NEXUS Files — explorador de archivos avanzado para Android

Kotlin + Compose. `:core` (Kotlin puro, probado con JUnit: nombres, listado, copiar/mover/borrar) y `:app` (UI).

## Estado (fases 1 a 3)
Hecho y verificado: explorador (raíces interna/SD, breadcrumbs, lista/cuadrícula, orden, ocultos, selección múltiple con háptico,
copiar/cortar/pegar, mover, renombrar, borrar con confirmación, nueva carpeta, compartir vía FileProvider, abrir con app externa,
progreso y cancelación, skeleton). Copia atómica (`.nexus-part` + rename), sin sobrescribir salvo política explícita, sin seguir symlinks.

Fase 2: dashboard (almacenamiento con StatFs, categorías y recientes vía MediaStore, carpetas importantes), búsqueda por
nombre/extensión/tipo/tamaño/fecha/ubicación (avisa de carpetas ilegibles), favoritos, etiquetas y recientes (JSON atómico en filesDir,
30 tests en :core).
Fase 3: analizador (resumen por categorías con gráfico interactivo, treemap squarified navegable, listas de más grandes / carpetas
pesadas / antiguos / temporales) y duplicados exactos (tamaño → hash parcial → SHA-256). Borrar siempre pide confirmación explícita,
valida que cada grupo conserve una copia y es permanente (sin papelera). 40 tests en :core.
Limitaciones: las categorías dependen del índice de MediaStore; al *mover* con cortar/pegar los favoritos/etiquetas de esa ruta no se
reasignan (se podan al arrancar); renombrar sí los conserva.

Limitación del analizador: los temporales se identifican por nombre/extensión (cachés, .tmp, .bak…), no por análisis de contenido.

Fase 4 (código compilado, UI sin verificar en pantalla): ZIP (listar/extraer sin zip-slip, crear; 8 tests) y visores internos (JSON, XML, código, imagen, PDF, ZIP, audio, vídeo).

Pendiente (fases siguientes):
SAF/MediaStore para ubicaciones sin "todos los archivos", transferencias en servicio en primer plano,
FTP/SFTP/SMB/WebDAV (cada uno con cliente real y credenciales cifradas con Keystore), ajustes.

## Probar
`./gradlew :core:test` · `./gradlew :app:assembleDebug` · permiso: `adb shell appops set dev.nexus.files MANAGE_EXTERNAL_STORAGE allow`
