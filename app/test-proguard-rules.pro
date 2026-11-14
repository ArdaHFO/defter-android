# AndroidX Test references these compile-time annotations. They have no runtime
# behavior. Scope the generated R8 rules to the instrumentation APK only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
