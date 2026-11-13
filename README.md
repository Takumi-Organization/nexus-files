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

Fase 5 (solo :core, sin UI): clientes remotos detrás de `RemoteFs` (listar, descargar atómico `.nexus-part`, subir sin sobrescribir
salvo petición, mkdir, borrar, renombrar sin pisar).
- **WebDAV** (`WebDavFs`, Basic): PROPFIND/GET/PUT/MKCOL/DELETE/MOVE sobre un cliente HTTP/1.1 mínimo propio (`MiniHttp`), porque
  `HttpURLConnection` no admite PROPFIND/MKCOL/MOVE. https con verificación de host; sin redirecciones; XML sin DOCTYPE (sin XXE);
  se ignoran entradas fuera de la carpeta pedida y nombres como `..`.
- **FTP** (`FtpFs`): pasivo EPSV/PASV, MLSD con LIST unix de reserva. Va sin cifrar (FTPS no implementado). Los datos se abren contra el
  host de control, ignorando la IP que anuncie PASV.
- `ProfileStore` + `SecretBox`: perfiles en JSON atómico con la contraseña cifrada; `KeystoreSecretBox` (app) usa AES-256-GCM en Android Keystore.
- Sin dependencias nuevas (decisión: SFTP y SMB exigirían sshj/smbj con BouncyCastle, pesadas y con problemas de compatibilidad en Android;
  se dejan para cuando haya UI y se pueda probar en dispositivo).
- Verificado: 61 tests JVM en :core (11 nuevos de red contra WebDAV y FTP falsos en memoria por loopback, + perfiles); `:app:compileDebugKotlin` compila.
- **No verificado**: nada de red real (servidores reales, TLS/certificados reales, FTP tras NAT, servidores que respondan distinto a los dobles),
  `KeystoreSecretBox` (no se ejecutó en Android), ni UI de ZIP/visores/remotos en pantalla.

Pendiente: pantalla de conexiones y explorador remoto en :app (usar `RemoteFactory`, siempre fuera del hilo principal), transferencias
en servicio en primer plano, SFTP y SMB, FTPS, SAF/MediaStore para ubicaciones sin "todos los archivos", ajustes.

## Probar
`./gradlew :core:test` · `./gradlew :app:assembleDebug` · permiso: `adb shell appops set dev.nexus.files MANAGE_EXTERNAL_STORAGE allow`
