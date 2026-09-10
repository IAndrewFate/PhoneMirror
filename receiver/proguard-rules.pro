# Proguard rules for receiver
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.** { *; }
-keep class com.phonemirror.protocol.control.** { *; }
