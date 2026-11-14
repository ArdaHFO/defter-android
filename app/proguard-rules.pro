# Preserve the original JNI entry points and app API names used by instrumentation.
# R8 can still optimize their bodies and optimize/shrink the dependency graph.
-keep,allowoptimization class dev.defter.app.** { *; }
# AndroidJUnitRunner calls the tracing API from the target's shared classpath.
# Preserve that API in the optimized variant used for native/device checks.
-keep,allowoptimization class androidx.tracing.Trace { *; }
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
