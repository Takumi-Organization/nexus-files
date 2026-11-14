# JSch carga los algoritmos (cifrados, kex, firmas) por nombre de clase desde cadenas de configuración.
-keep class com.jcraft.jsch.** { *; }
# smbj usa reflexión/ServiceLoader (autenticación NTLM/SPNEGO, transportes) y BouncyCastle por nombre.
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.client.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.hierynomus.asn1.** { *; }
# Dependencias opcionales de esas bibliotecas que no existen en Android.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**
-dontwarn javax.security.**
-dontwarn org.osgi.**
-dontwarn javax.el.**
