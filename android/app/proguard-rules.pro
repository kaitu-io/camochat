# App-level ProGuard rules. The bulk of native-binding keep rules are in
# `shared/consumer-rules.pro` and apply transitively. This file adds rules
# that are specific to the :app module (Compose runtime, kotlinx-serialization
# annotations, and Activity/Application entry points).

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Compose runtime — already preserved by AGP defaults but explicit here.
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# Application + Activity entry points (auto-kept via AndroidManifest but keep
# them explicit to defend against transitive R8 surprises).
-keep class app.chencang.android.CcApp { *; }
-keep class app.chencang.android.MainActivity { *; }

# Repository singletons + data classes (used reflectively by DataStore proto serializer)
-keep class app.chencang.shared.proto.** { *; }
