package com.taksolutions.takbeacon.plugin;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Tracks whether any ATAK activity is in front, from ATAK's own Application.
 * The TAK Beacon app pauses its reports while ATAK is in front (when the user
 * has turned that on), so the operator isn't on the map twice.
 */
final class ForegroundWatcher implements Application.ActivityLifecycleCallbacks {

    interface Listener {
        void onForegroundChanged(boolean inForeground);
    }

    private final Application application;
    private Listener listener;
    private int resumed;

    ForegroundWatcher(Application application) {
        this.application = application;
        // The plugin is loaded while ATAK's map activity is showing.
        this.resumed = 1;
    }

    void start() {
        application.registerActivityLifecycleCallbacks(this);
    }

    void stop() {
        application.unregisterActivityLifecycleCallbacks(this);
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isForeground() {
        return resumed > 0;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        final boolean was = isForeground();
        resumed++;
        if (!was && listener != null)
            listener.onForegroundChanged(true);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        resumed = Math.max(0, resumed - 1);
        if (!isForeground() && listener != null)
            listener.onForegroundChanged(false);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
