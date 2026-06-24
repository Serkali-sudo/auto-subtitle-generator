package com.serhat.autosub.core;

import android.util.Log;

import com.serhat.autosub.BuildConfig;

/** Application logger that is completely silent in non-debug builds. */
public final class DebugLog {
    private DebugLog() {}

    public static int v(String tag, String message) {
        return BuildConfig.DEBUG ? Log.v(tag, message) : 0;
    }

    public static int v(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.v(tag, message, error) : 0;
    }

    public static int d(String tag, String message) {
        return BuildConfig.DEBUG ? Log.d(tag, message) : 0;
    }

    public static int d(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.d(tag, message, error) : 0;
    }

    public static int i(String tag, String message) {
        return BuildConfig.DEBUG ? Log.i(tag, message) : 0;
    }

    public static int i(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.i(tag, message, error) : 0;
    }

    public static int w(String tag, String message) {
        return BuildConfig.DEBUG ? Log.w(tag, message) : 0;
    }

    public static int w(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.w(tag, message, error) : 0;
    }

    public static int w(String tag, Throwable error) {
        return BuildConfig.DEBUG ? Log.w(tag, error) : 0;
    }

    public static int e(String tag, String message) {
        return BuildConfig.DEBUG ? Log.e(tag, message) : 0;
    }

    public static int e(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.e(tag, message, error) : 0;
    }

    public static int wtf(String tag, String message) {
        return BuildConfig.DEBUG ? Log.wtf(tag, message) : 0;
    }

    public static int wtf(String tag, String message, Throwable error) {
        return BuildConfig.DEBUG ? Log.wtf(tag, message, error) : 0;
    }
}
