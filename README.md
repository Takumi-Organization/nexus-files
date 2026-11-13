# NEXUS Files — explorador de archivos avanzado para Android

Kotlin + Compose. `:core` (Kotlin puro, probado con JUnit: nombres, listado, copiar/mover/borrar) y `:app` (UI).

## Estado (fases 1 a 5)
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

Fase 4, verificado en emulador (Android 16-ish, emulator-5554, 2026-11-13): visores de JSON (formateado, con numeración), XML, código, imagen PNG,
PDF (PdfRenderer), audio mp3 (reproductor con barra) y vídeo mp4 (VideoView), y el flujo ZIP (listar, «Extraer todo»). Hallazgo corregido:
Android 14+ rechaza con excepción cualquier ZIP que contenga una entrada con `..` y no dejaba ni listarlo; ahora se desactiva el validador
del sistema (`ZipPathValidator.clearCallback()`) y la protección la da `Zips.safeTarget` (la entrada `../evil.txt` se omite con aviso y
no se escribe fuera; comprobado en el emulador). Para probar visores sin tocar la pantalla, en builds depurables:
`adb shell am start -n dev.nexus.files/.MainActivity --es dev.nexus.files.VIEW_PATH /sdcard/x.json`.

Fase 5: clientes remotos, transferencias y ajustes.
- **:core** detrás de `RemoteFs` (listar, descargar atómico `.nexus-part`, subir sin sobrescribir salvo petición, mkdir, borrar, renombrar sin pisar):
  - **WebDAV** (`WebDavFs`, Basic) sobre un cliente HTTP/1.1 mínimo propio (`MiniHttp`), porque `HttpURLConnection` no admite PROPFIND/MKCOL/MOVE.
    https con verificación de host; sin redirecciones; se rechaza cualquier XML con DOCTYPE/ENTITY (sin XXE; el parser de Android no admite
    `disallow-doctype-decl`, bug hallado en el emulador y corregido); se ignoran entradas fuera de la carpeta pedida y nombres como `..`.
  - **FTP** (`FtpFs`): pasivo EPSV/PASV, MLSD con LIST unix de reserva. Va sin cifrar (FTPS no implementado). Los datos se abren contra el
    host de control, ignorando la IP que anuncie PASV.
  - `RemoteTransfer`: descarga de ficheros y carpetas (sin pisar: «nombre (1)»), subida que renombra si existe, cancelación cooperativa sin dejar parciales.
  - `ProfileStore` + `SecretBox`: perfiles en JSON atómico con la contraseña cifrada; `KeystoreSecretBox` (app) usa AES-256-GCM en Android Keystore.
- **:app**: pestaña «Remoto» (alta/edición/borrado de conexiones, explorador remoto, subir con el selector del sistema, nueva carpeta, renombrar,
  eliminar con confirmación, descargar a `Download/NEXUS`), panel de transferencias con cancelar y `TransferService` en primer plano
  (notificación con «Cancelar»; cada transferencia abre su propia conexión). Ajustes (⋮ del explorador): ocultos, cuadrícula, orden, carpeta de descargas.
- Sin dependencias nuevas en la app. Decisión: SFTP y SMB exigirían sshj/smbj (BouncyCastle, pesadas, problemas en Android); no se hacen.
- **Verificado**: 68 tests JVM en :core (2 opt-in con `NEXUS_IT=1`, que pasaron contra servidores reales wsgidav y pyftpdlib en el host:
  crear, subir, no pisar, listar con tildes, renombrar, descargar, borrar). En el emulador contra esos servidores reales por 10.0.2.2: alta de
  conexión WebDAV y FTP desde el formulario, listado, descarga de fichero (WebDAV: SHA-256 de 30 MB idéntico), progreso, y cancelación de una
  descarga FTP limitada a 1 MB/s (el `.nexus-part` desaparece).
- **No verificado**: subida desde la UI (selector del sistema), renombrar/borrar/carpeta desde la UI, aspecto de la notificación en primer plano
  (solo se comprobó que el servicio arranca sin fallo), servidores o TLS reales de Internet (solo http/ftp en loopback), `KeystoreSecretBox` fuera
  del emulador, ajustes en pantalla.

Pendiente: SFTP, SMB, FTPS; SAF/MediaStore para ubicaciones sin «todos los archivos»; copiar/mover locales en el servicio en primer plano
(hoy solo las transferencias remotas lo usan); reintento automático.

## Probar
`./gradlew :core:test` · `./gradlew :app:assembleDebug` · permiso: `adb shell appops set dev.nexus.files MANAGE_EXTERNAL_STORAGE allow`
