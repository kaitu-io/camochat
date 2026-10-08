# Consumer ProGuard rules for the `:shared` module — applied to `:app`,
# which depends on this one.
#
# These rules MUST be kept in sync with the runtime native-binding contracts:
# - uniffi.chencang.* — generated Kotlin bindings that resolve native symbols via JNA reflection
# - com.sun.jna.** — JNA library entry points
# - kotlinx.serialization.** — uses reflection for @Serializable
#
# Without these rules R8 strips native symbol resolution and the first JNA call crashes.

-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

-keep class uniffi.chencang.** { *; }
-keepclassmembers class uniffi.chencang.** { *; }
-dontwarn uniffi.chencang.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep,includedescriptorclasses class app.chencang.shared.**$$serializer { *; }
-keepclassmembers class app.chencang.shared.** { *** Companion; }
