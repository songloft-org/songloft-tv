# Gson: data models are (de)serialized by field name via reflection - keep fields
-keep,allowobfuscation class com.songloft.tv.data.model.** { <fields>; }
-keep,allowobfuscation class com.songloft.tv.data.api.** { <fields>; }
-keep,allowobfuscation class com.songloft.tv.data.repository.AuthRepository$LoginErrorBody { <fields>; }
-keep,allowobfuscation class com.songloft.tv.data.storage.ResumeSnapshot { <fields>; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
