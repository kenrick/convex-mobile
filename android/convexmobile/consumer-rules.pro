# Shipped in the AAR and applied to apps that minify with R8.

# JNA, through which the generated bindings reach the native library. It reads Structure fields and
# Callback methods reflectively and binds native methods by name; it also references AWT classes
# that don't exist on Android.
-dontwarn java.awt.**
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# The UniFFI bindings (Structure subclasses, callback vtables, converters) and the client around
# them, which JNA and the Rust library call into.
-keep class dev.convex.android.** { *; }
