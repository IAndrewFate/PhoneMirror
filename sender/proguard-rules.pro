# Proguard rules for sender
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.** { *; }
-keep class com.phonemirror.protocol.control.** { *; }
