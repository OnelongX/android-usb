# iOS Reader ProGuard rules

# Keep model and protocol classes (accessed via reflection-like patterns in plist parsing)
-keep class com.androidusb.iosreader.model.** { *; }
-keep class com.androidusb.iosreader.protocol.** { *; }

# Keep USB connection state enum
-keepclassmembers enum com.androidusb.iosreader.usb.ConnectionState {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove debug/verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep readable stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
