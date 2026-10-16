# LSPosed Module Proguard Rules
-keep class pochita.hook.** { *; }
-keep class pochita.model.** { *; }
-keep class pochita.crypto.** { *; }
-keep class pochita.source.** { *; }

# Keep LibXposed API & Service
-keep class io.github.libxposed.** { *; }

# Keep QuickJS native bindings
-keep class com.dokar.quickjs.** { *; }

