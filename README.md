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

Fase 5: remotos, SAF, transferencias y ajustes.
- **Protocolos** (todos detrás de `RemoteFs`: listar, descargar atómico `.nexus-part`, subir sin sobrescribir salvo petición, mkdir, borrar, renombrar sin pisar):
  - **FTP** y **FTPS explícito** (`FtpFs`, JDK puro): pasivo EPSV/PASV, MLSD con LIST de reserva, datos siempre contra el host de control.
  - **WebDAV** (`WebDavFs`) sobre un cliente HTTP/1.1 mínimo propio (`MiniHttp`; `HttpURLConnection` no admite PROPFIND/MKCOL/MOVE). Sin XXE (se rechaza DOCTYPE/ENTITY).
  - **SFTP** (`SftpFs`) con JSch (fork mwiede 2.28.8) y **SMB2/3** (`SmbFs`) con smbj 0.15.0.
  - **TLS** (`Tls`): validación del sistema + nombre de host, o huella SHA-256 fijada (autofirmados). **TOFU**: si el certificado (FTPS/WebDAV https) o la
    clave de host SSH (SFTP, huella `SHA256:…` como `ssh-keygen -l`) no es confiable se lanza `UntrustedHostException` ANTES de enviar credenciales; la
    app muestra la huella, y si el usuario acepta la guarda en el perfil. Si cambia después, avisa en rojo («¡La identidad del servidor ha cambiado!»).
  - **SAF** (`SafFs`, app): carpetas elegidas con el selector de árbol y permiso persistente; modo limitado (sin «acceso a todos los archivos»)
    desde la pantalla de permisos: solo Remoto + carpetas SAF; descargas a la carpeta propia de la app.
  - Contraseñas: `ProfileStore` + `KeystoreSecretBox` (AES-256-GCM, Android Keystore).
- **Dependencias nuevas y por qué** (únicas en :core; justificadas porque implementar SSH o SMB a mano no es razonable ni seguro):
  JSch 2.28.8 (~0,5 MB, sin dependencias, cifrados modernos), smbj 0.15.0 (~0,7 MB) que arrastra bcprov-jdk18on (~8,6 MB sin reducir), asn-one, mbassador y slf4j-api.
  APK debug resultante: 39,8 MB (sin R8; el release con minificado reduciría BouncyCastle). Probado que arrancan y funcionan en Android (emulador).
  Limitación conocida de smbj con el servidor impacket: se lee por desplazamientos sin pasar del tamaño del fichero.
- **Transferencias**: `Transfers` + `TransferService` en primer plano (notificación con «Cancelar»; permiso POST_NOTIFICATIONS pedido una vez) para
  descargas/subidas remotas Y para copiar, mover, borrar y ZIP locales. **Reintentos** (`RetryingFs`): reconecta y repite la operación ante fallos de red
  pasajeros, 4 intentos con espera 2-4-8 s (tope 30 s), cancelable; autenticación, permisos, «ya existe» y huellas no confiadas nunca se reintentan.
  Las descargas interrumpidas se repiten desde el principio (sin reanudar).
- **Ajustes** (⋮ del explorador): ocultos, cuadrícula, orden, carpetas primero, carpeta de descargas.
- **Verificado**
  - JVM: 80+ tests en :core. Opt-in (`NEXUS_IT=1`, `NEXUS_FTPS_FP`, `NEXUS_SFTP_FP`, `NEXUS_SMB=1`) contra servidores REALES en el host: wsgidav (WebDAV),
    pyftpdlib (FTP y FTPS con certificado autofirmado), asyncssh (SFTP con TOFU) e impacket (SMB): crear, subir, no pisar, listar con tildes, renombrar,
    descargar, borrar. TLS/TOFU probado sin servidor externo con un certificado de `keytool`.
  - Emulador (emulator-5554, por 10.0.2.2, contra esos servidores reales): alta de conexiones FTP, FTPS, SFTP, SMB y WebDAV desde el formulario;
    diálogo de huella (coincide con `openssl`/`ssh-keygen`); listar y descargar en todos; descarga de 30 MB por WebDAV con SHA-256 idéntico; cancelar una
    descarga FTP limitada a 1 MB/s (el `.nexus-part` desaparece); **reintento real**: se mató el servidor FTP a mitad, la pantalla mostró «Reintentando (4/4)…»,
    se reinició y la descarga de 12 MB terminó con SHA-256 idéntico; subir desde el selector del sistema (SFTP, el fichero llegó al servidor); crear carpeta,
    renombrar y eliminar (recursivo) por SFTP; notificación del servicio en primer plano (captura del panel con «Descargar grande.bin · 4,1 MB»); pantalla
    de Ajustes; SAF completo (selector de árbol, concesión, listado, descarga) en modo limitado.
  - Visores (JSON, XML, código, PNG, PDF, mp3, mp4) y ZIP (con entrada `../`) en el emulador; ver Fase 4.
- **No verificado**
  - Copiar/mover/borrar/ZIP locales ejecutándose dentro del servicio (comparten el código de `Transfers.start`, comprobado con transferencias remotas, pero no se
    pulsó «pegar» en pantalla).
  - Servidores o certificados reales de Internet (solo loopback). FTPS contra servidores que exigen reutilizar la sesión TLS en la conexión de datos
    (p. ej. vsftpd por defecto) puede fallar: el JDK no la reutiliza entre puertos; pyftpdlib no lo exige.
  - SFTP con claves privadas (solo contraseña), claves de host solo Ed25519 probadas con asyncssh, SMB con dominio/firmado/cifrado SMB3 obligatorio.
  - SAF: subir, renombrar, borrar y crear carpeta (código escrito, no ejecutado); mover entre carpetas distintas (no soportado).
  - `KeystoreSecretBox` fuera del emulador, tamaño del APK release.
  - El diálogo de permiso de notificaciones (en las pruebas se concedió con `pm grant`).

Pendiente solo lo imposible o fuera de alcance: autenticación SFTP por clave privada, reanudar descargas, dominios SMB, y probar contra hardware/servidores reales.

## Probar
`./gradlew :core:test` · `./gradlew :app:assembleDebug` · permiso: `adb shell appops set dev.nexus.files MANAGE_EXTERNAL_STORAGE allow`
