# NewPipeExtractor usa reflexión y Rhino interpreta JavaScript de YouTube: no se puede recortar.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn org.mozilla.javascript.tools.**
# La página web del motor de reserva llama a estos métodos por nombre.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Partes de Java de escritorio que Rhino referencia pero Android nunca ejecuta.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
