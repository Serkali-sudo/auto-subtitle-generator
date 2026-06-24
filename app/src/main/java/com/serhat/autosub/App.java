package com.serhat.autosub;

import com.serhat.autosub.core.NotificationHelper;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {

    private int activeActivities = 0;
    private static boolean isForeground = false;

    public static boolean isAppInForeground() {
        return isForeground;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {
                activeActivities++;
                isForeground = true;
                // Dismiss all background progress notifications since the user returned to the app
                NotificationHelper.cancelAllProgressNotifications(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {}

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {
                activeActivities--;
                if (activeActivities <= 0) {
                    isForeground = false;
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        });
    }
}
