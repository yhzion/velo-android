# JNI entry points and callbacks are looked up by name from the Zig core.
-keep class io.github.yhzion.velo.core.VeloCore { *; }
-keep interface io.github.yhzion.velo.core.CoreListener { *; }
-keep class * implements io.github.yhzion.velo.core.CoreListener { *; }
-keep class io.github.yhzion.velo.TailnetNative { *; }
-keep class io.github.yhzion.velo.SshNative { *; }
