package com.taksolutions.takbeacon.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

/**
 * The plugin's entry under Settings, Tool Preferences: the only place an
 * operator can open the user manual from (assets/usermanual.pdf, compiled by
 * tak.gov from docs/user_manual).
 */
public class TAKBeaconPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "takbeacon"
            + File.separator + "TAK Beacon User Guide.pdf";

    private static Context pluginContext;

    /** Changes with every release and every ATAK target, so PdfHelper re-extracts the PDF. */
    private static long manualVersion() {
        try {
            return pluginContext.getString(R.string.versionName).hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            return System.currentTimeMillis();
        }
    }

    public TAKBeaconPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public TAKBeaconPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "TAK Beacon");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Preference manual = findPreference("manual");
        if (manual == null)
            return;
        // An anonymous class, not a lambda: lambdas break under release proguard.
        manual.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                PdfHelper.extractAndShow(pluginContext, getActivity(), USER_GUIDE,
                        manualVersion(), USER_GUIDE_PATH, true);
                return true;
            }
        });
    }
}
