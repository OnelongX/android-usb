package com.androidusb.iosreader.util;

import android.util.Log;

/**
 * Centralized logger that respects build type.
 * Debug/verbose logs are compiled in but can be toggled at runtime.
 */
public final class Logger {
    private static volatile boolean debugEnabled = true;

    private Logger() {}

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void d(String tag, String msg) {
        if (debugEnabled) Log.d(tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
