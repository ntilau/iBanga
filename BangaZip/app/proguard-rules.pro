# BangaZip ProGuard Rules
# Keep Apache Commons Compress — it uses reflection
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
