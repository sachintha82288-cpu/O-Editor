# O Editor keeps serialization metadata for the project document.
-keepclassmembers class com.oeditor.core.model.** { *; }
-keep,includedescriptorclasses class com.oeditor.core.model.**$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.jetbrains.annotations.**
