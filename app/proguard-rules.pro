# LibVLC uses JNI; preserve its public bridge classes when R8 is enabled.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.util.** { *; }
