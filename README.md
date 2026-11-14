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
- **Protocolos** (todos detrás de `RemoteFs`: listar, descargar atómico `.nexus-part`, subir sin sobrescribir salvo petición, mkdir, borrar (recursivo), renombrar/mover sin pisar):
  - **FTP** y **FTPS explícito** (`FtpFs`, JDK puro): pasivo EPSV/PASV, MLSD con LIST de reserva, datos siempre contra el host de control.
    FTPS usa un SSLSocket normal para los datos y, si el servidor rechaza (421/425/426/522 o fallo SSL), pasa a **reutilizar la sesión TLS de control**
    con un `SSLEngine` cuya caché de sesiones usa el puerto de control (`TlsEngineSocket`), lo que exigen vsftpd & co. (`require_ssl_reuse`). Motivo de que sea
    un respaldo y no lo normal: `SSLSocket` busca sesiones por el puerto real del servidor (no se puede falsear; test que lo demuestra). En FTPS hay además la opción «Solo TLS 1.2».
    Probado en JVM (JDK y Conscrypt, TLS 1.2 y 1.3) contra un FTPS que EXIGE la reutilización (pyftpdlib con un manejador que comprueba `SSL_session_reused` y responde 522; vsftpd real
    no se pudo ejecutar: bajar y lanzar su binario fue bloqueado por el clasificador de seguridad). **En el emulador (Conscrypt de Android 16) contra ese servidor estricto falla** con
    `Read error: ssl=0x…: Failure in SSL library, usually a protocol error`, también con «Solo TLS 1.2»; sin esa exigencia (pyftpdlib normal) FTPS funciona en el emulador.
  - **WebDAV** (`WebDavFs`) sobre un cliente HTTP/1.1 mínimo propio (`MiniHttp`; `HttpURLConnection` no admite PROPFIND/MKCOL/MOVE). Sin XXE (se rechaza DOCTYPE/ENTITY).
  - **SFTP** (`SftpFs`, JSch fork mwiede 2.28.8): contraseña o **clave privada** (RSA PEM, OpenSSH ed25519, con o sin frase de paso; se pega o se elige un fichero;
    la clave y la frase se guardan cifradas con Keystore) y **SMB2/3** (`SmbFs`, smbj 0.15.0) con **dominio** opcional.
  - **TLS** (`Tls`): validación del sistema + nombre de host, o huella SHA-256 fijada (autofirmados). **TOFU**: si el certificado (FTPS/WebDAV https) o la
    clave de host SSH (SFTP, `SHA256:…` como `ssh-keygen -l`) no es confiable se lanza `UntrustedHostException` ANTES de enviar credenciales; la app muestra
    la huella y, si el usuario acepta, la guarda en el perfil. Si cambia después, avisa en rojo («¡La identidad del servidor ha cambiado!»).
  - **SAF** (`SafFs`, app): carpetas elegidas con el selector de árbol y permiso persistente; listar, descargar, subir, crear carpeta, renombrar, borrar y
    **mover entre carpetas** (`moveDocument` nativo, o copia+borrado si el proveedor no lo admite). Modo limitado desde la pantalla de permisos (sin «acceso a
    todos los archivos»): solo Remoto + carpetas SAF; descargas a la carpeta propia de la app. «Mover a…» está en el menú de cada elemento remoto.
  - Contraseñas: `ProfileStore` + `KeystoreSecretBox` (AES-256-GCM, Android Keystore).
- **Reanudar descargas** interrumpidas: `Range` (WebDAV), `REST` (FTP/FTPS), offset (SFTP con `RESUME`, SMB). El temporal `.nexus-part` se conserva ante fallos de red
  y se borra ante cancelación o error permanente; si el servidor no admite reanudar o el temporal no encaja, empieza de cero. No hay verificación de que el original no
  cambiara entre intentos más allá del tamaño final. **Verificado en el emulador con los cuatro protocolos** (servidor limitado a ~1,5 MB/s, cortado a mitad y relanzado,
  descarga de 20 MB): WebDAV (corte a 10,2 MB; la 2.ª conexión movió 9,7 MB), FTP (corte a 8,4 MB; el servidor registró `RETR … bytes=11611392`), SFTP (corte a 9,3 MB; 2.ª conexión
  10,8 MB) y SMB (corte a 8,8 MB; 2.ª conexión 11,2 MB): en los cuatro continúa desde el offset y el SHA-256 final coincide con el del origen. Esto destapó un fallo corregido:
  la caída de conexión en SFTP no se consideraba transitoria y borraba el temporal.
- **Dependencias nuevas y por qué** (solo :core; implementar SSH o SMB a mano no es razonable ni seguro): JSch 2.28.8 (~0,5 MB, sin dependencias), smbj 0.15.0 (~0,7 MB;
  arrastra bcprov-jdk18on, asn-one, mbassador y slf4j-api). De pruebas: Conscrypt (para reproducir en JVM el proveedor TLS de Android).
  **Tamaño del APK: debug 41,0 MB; release con R8 y reducción de recursos 10,4 MB** (`app/proguard-rules.pro` mantiene JSch, smbj y BouncyCastle por uso de reflexión).
  El release firmado con la clave de depuración se instaló y funcionó en el emulador (FTPS, SFTP y SMB).
- **Transferencias**: `Transfers` + `TransferService` en primer plano (notificación con «Cancelar»; permiso POST_NOTIFICATIONS pedido una vez al arrancar) para
  descargas/subidas remotas y para copiar, mover, borrar y ZIP locales. **Reintentos** (`RetryingFs`): reconecta y repite la operación ante fallos de red pasajeros,
  4 intentos con espera 2-4-8 s (tope 30 s), cancelable; autenticación, permisos, «ya existe» y huellas no confiadas no se reintentan.
- **Ajustes** (⋮ del explorador): ocultos, cuadrícula, orden, carpetas primero, carpeta de descargas.
- **Verificado**
  - JVM: 94 tests en :core (7 opt-in). Opt-in (`NEXUS_IT=1`, `NEXUS_FTPS_FP`, `NEXUS_SFTP_FP`, `NEXUS_SFTP_KEYDIR`, `NEXUS_SMB=1`) contra servidores REALES en el host: wsgidav (WebDAV), pyftpdlib
    (FTP y FTPS autofirmado), asyncssh (SFTP con TOFU y claves RSA/ed25519/con frase) e impacket (SMB, con y sin dominio): crear, subir, no pisar, listar con tildes,
    renombrar, descargar, **reanudar** y borrar. TLS/TOFU sin servidor externo con un certificado de `keytool`; reutilización de sesión TLS y SSLEngine con JDK y con Conscrypt.
  - Emulador (emulator-5554, por 10.0.2.2, contra esos servidores reales): alta de FTP, FTPS, SFTP (contraseña, clave y clave con frase), SMB (con dominio) y WebDAV desde el
    formulario; diálogo de huella; listar y descargar en todos; descarga WebDAV de 30 MB con SHA-256 idéntico; cancelar una descarga FTP limitada (sin `.nexus-part`); **reintento real**
    (servidor FTP matado a mitad: «Reintentando (4/4)…», terminó con SHA-256 idéntico); subir con el selector del sistema; crear carpeta, renombrar y eliminar por SFTP; notificación del
    servicio; Ajustes; diálogo del permiso de notificaciones («Allow NEXUS to send you notifications?»).
  - **Operaciones locales en el servicio** (emulador): copiar 1,8 GB (`dumpsys` muestra `isForeground=true`, tipo dataSync; el panel de notificaciones «Copiando · big2.bin · 672 MB»; el
    resultado en disco idéntico en tamaño), eliminar, comprimir a ZIP (contenido comprobado) y cortar/pegar en otra carpeta.
  - **SAF** (emulador, modo limitado): selector de árbol, concesión, listar, descargar, crear carpeta, renombrar, mover a otra carpeta, eliminar y subir (contenido comprobado en disco).
  - Visores (JSON, XML, código, PNG, PDF, mp3, mp4) y ZIP con entrada `../` (Fase 4).
- **No verificado**
  - Servidores o certificados reales de Internet (solo loopback).
  - FTPS contra un servidor que exija reutilizar la sesión TLS **en Android** (falla, ver arriba), y vsftpd real.
  - SFTP con claves ECDSA/ssh-agent/certificados de usuario, SMB con firmado/cifrado SMB3 obligatorio o Kerberos.
  - SAF con proveedores distintos del de almacenamiento externo (Drive, etc.) y `moveDocument` nativo (en el emulador no se distingue si movió el proveedor o la copia+borrado).
  - `KeystoreSecretBox` en un dispositivo físico.

Límites asumidos: la identidad SFTP/FTPS se fija por huella (no hay autoridades propias), las descargas SAF→local no se reanudan, SMB no admite Kerberos.

## Probar
`./gradlew :core:test` · `./gradlew :app:assembleDebug` · permiso: `adb shell appops set dev.nexus.files MANAGE_EXTERNAL_STORAGE allow`
