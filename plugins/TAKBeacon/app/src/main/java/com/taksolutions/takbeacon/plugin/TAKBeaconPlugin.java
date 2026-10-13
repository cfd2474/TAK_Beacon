package com.taksolutions.takbeacon.plugin;

import android.app.Activity;
import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Entry point named in assets/plugin.xml.
 *
 * <p>The bridge and the foreground watcher live for the plugin's lifetime, not
 * inside the pane: the heartbeat that pauses TAK Beacon while ATAK is in front
 * must keep running with the pane closed.
 */
public class TAKBeaconPlugin implements IPlugin {

    private static final String TAG = "TAKBeacon";
    private static final String PREFS_KEY = "takbeacon_preferences";

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;

    private ForegroundWatcher foreground;
    private BeaconBridge bridge;
    private AppInstaller installer;
    private AppUpdateWatcher updates;
    private BeaconPane paneView;
    private Pane pane;

    public TAKBeaconPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        pluginContext = ctxProvider != null ? ctxProvider.getPluginContext() : null;
        if (pluginContext != null)
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        uiService = serviceController.getService(IHostUIService.class);

        // The identifier must be stable, or ATAK loses the button when the user moves it.
        toolbarItem = pluginContext == null ? null : new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        final MapView mapView = MapView.getMapView();
        if (pluginContext == null || uiService == null || mapView == null) {
            Log.w(TAG, "missing plugin context, UI service or MapView; not starting");
            return;
        }
        final Context host = mapView.getContext();
        foreground = new ForegroundWatcher(((Activity) host).getApplication());
        foreground.start();
        bridge = new BeaconBridge(host.getApplicationContext(), foreground);
        bridge.start();
        installer = new AppInstaller(pluginContext, host);
        updates = new AppUpdateWatcher(host, installer, new AppUpdateWatcher.Listener() {
            @Override
            public void onAppChanged() {
                if (paneView != null)
                    paneView.refresh();
            }
        });
        updates.start();
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
    }

    @Override
    public void onStop() {
        unregisterPreferences();
        if (uiService != null && toolbarItem != null) {
            uiService.removeToolbarItem(toolbarItem);
            if (pane != null && uiService.isPaneVisible(pane))
                uiService.closePane(pane);
        }
        if (paneView != null)
            paneView.dispose();
        if (updates != null)
            updates.stop();
        if (installer != null)
            installer.dispose();
        if (bridge != null)
            bridge.stop();
        if (foreground != null)
            foreground.stop();
        paneView = null;
        pane = null;
    }

    private void showPane() {
        if (bridge == null)
            return;
        if (pane == null) {
            paneView = new BeaconPane(pluginContext, MapView.getMapView(), bridge, installer);
            pane = new PaneBuilder(paneView.view())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.4D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        paneView.onShown();
    }

    /** Guarded: a build that lacks the API costs the manual entry, not the plugin. */
    private void registerPreferences() {
        try {
            ToolsPreferenceFragment.register(new ToolsPreferenceFragment.ToolPreference(
                    pluginContext.getString(R.string.app_name),
                    pluginContext.getString(R.string.prefs_summary),
                    PREFS_KEY,
                    // The bare glyph: this row sits on ATAK's dark UI.
                    pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                    new TAKBeaconPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not register preferences: " + e);
        }
    }

    private void unregisterPreferences() {
        try {
            ToolsPreferenceFragment.unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not unregister preferences: " + e);
        }
    }
}
