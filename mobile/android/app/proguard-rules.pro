# youtubedl-android maps yt-dlp's JSON with Jackson through reflection.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# It unpacks Python and ffmpeg with commons-compress, whose zip reader creates
# its extra-field classes by reflection: R8 must not strip them.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**
