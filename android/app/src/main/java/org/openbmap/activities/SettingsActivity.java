/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.activities;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;

import org.openbmap.Preferences;
import org.openbmap.R;
import org.openbmap.utils.FileUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.GINGERBREAD;

/**
 * Preferences activity.
 */
public class SettingsActivity extends PreferenceActivity {

    private static final String TAG = SettingsActivity.class.getSimpleName();

    private DownloadManager mDownloadManager;

    /*
     * Id of the active catalog download or -1 if no active download
     */
    private long mCurrentCatalogDownloadId = -1;

    /*
     * Id of the active map download or -1 if no active download
     */
    private long mCurrentMapDownloadId = -1;

    private BroadcastReceiver mReceiver = null;

    @SuppressLint("NewApi")
    @Override
    protected final void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences);

        // with versions >= GINGERBREAD use download manager
        if (SDK_INT >= GINGERBREAD) {
            initDownloadManager();
        }

        initActiveCatalogControl();

        initActiveMapControl();

        initGpsLogIntervalControl();

        initAdvancedSettingsButton();

        DialogPreferenceMaps maps = (DialogPreferenceMaps) findPreference(Preferences.KEY_MAPS_DIALOG);
        DialogPreferenceCatalogs catalogs = (DialogPreferenceCatalogs) findPreference(Preferences.KEY_CATALOGS_DIALOG);
    }

    /**
     * Initialises download manager for GINGERBREAD and newer
     */
    private void initDownloadManager() {
        mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        mReceiver = new BroadcastReceiver() {
            @SuppressLint("NewApi")
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final String action = intent.getAction();
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                    final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                    final Query query = new Query();
                    query.setFilterById(downloadId);
                    final Cursor c = mDownloadManager.query(query);
                    if (c.moveToFirst()) {
                        final int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        final int reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                        Log.i(TAG, "Download status:" + c.getInt(statusCol));
                        if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(statusCol)) {
                            // we're not checking download id here, that is done in handleDownloads
                            final String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                            handleDownloads(uriString);
                        } else if (DownloadManager.STATUS_FAILED == c.getInt(statusCol)) {
                            Log.e(TAG, "Download failed:" + c.getString(reasonCol));
                        }
                    }
                }
            }
        };

        registerReceiver(mReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    @Override
    protected final void onDestroy() {
        try {
            if (mReceiver != null) {
                Log.i(TAG, "Deregister broadcast receivers");
                unregisterReceiver(mReceiver);
            }
        } catch (final IllegalArgumentException e) {
            // do nothing here {@see http://stackoverflow.com/questions/2682043/how-to-check-if-receiver-is-registered-in-android}
            super.onDestroy();
            return;
        }

        super.onDestroy();
    }

    /**
     * Starts advanced setting activity
     */
    private void initAdvancedSettingsButton() {
        final Preference pref = findPreference(Preferences.KEY_ADVANCED_SETTINGS);
        pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                startActivity(new Intent(pref.getContext(), AdvancedSettingsActivity.class));
                return true;
            }
        });
    }

    /**
     * Initializes gps logging interval.
     */
    private void initGpsLogIntervalControl() {
        // Update GPS logging interval summary to the current value
        final Preference pref = findPreference(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL);
        pref.setSummary(
                PreferenceManager.getDefaultSharedPreferences(this).getString(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL, org.openbmap.Preferences.VAL_GPS_LOGGING_INTERVAL)
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
        pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                // Set summary with the interval and "seconds"
                preference.setSummary(newValue
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
                return true;
            }
        });
    }

    /**
     * Populates the active map list preference by scanning available map files.
     */
    private void initActiveMapControl() {
        List<String> entries = new ArrayList<>();
        List<String> values = new ArrayList<>();

        // Create default / none entry
        entries.add(getResources().getString(R.string.prefs_map_none));
        values.add(Preferences.VAL_MAP_NONE);

        entries.add(getResources().getString(R.string.prefs_map_online));
        values.add(Preferences.VAL_MAP_ONLINE);

        // Check for presence of maps directory
        final File folder = FileUtils.getMapFolder(SettingsActivity.this);
        Log.d(TAG, "Listing maps in " + folder.getAbsolutePath());

        // List each map file
        if (folder.exists() && folder.canRead() && folder.listFiles().length > 0) {
            final String[] mapFiles = folder.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename.endsWith(org.openbmap.Preferences.MAP_FILE_EXTENSION);
                }
            });

            for (int i = 0; i < mapFiles.length; i++) {
                entries.add(mapFiles[i].substring(0, mapFiles[i].length() - Preferences.MAP_FILE_EXTENSION.length()));
                values.add(mapFiles[i]);
            }
        }

        Log.d(TAG, "Found " + entries.size() + " files");

        final ListPreference lf = (ListPreference) findPreference(Preferences.KEY_MAP_FILE);
        lf.setEntries(entries.toArray(new String[entries.size()]));
        lf.setEntryValues(values.toArray(new String[values.size()]));
    }

    /**
     * Gets a file reference for wifi catalog folder
     * @return map folder
     */
    @NonNull
    private File getCatalogFolder() {
        return new File(PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(
                Preferences.KEY_WIFI_CATALOG_FOLDER,
                SettingsActivity.this.getExternalFilesDir(null) + File.separator + Preferences.CATALOG_SUBDIR));
    }

    /**
     * Checks the local wifi catalog version
     * @return version number
     */
    private String getLocalCatalogVersion() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_CATALOG_VERSION, Preferences.CATALOG_VERSION_NONE);
    }

    /**
     * Saves local catalog version
     * @param version
     */
    private void setLocalCatalogVersion(String version) {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(Preferences.KEY_CATALOG_VERSION, version).commit();
    }

    /**
     * Populates the wifi catalog list preference by scanning catalog folder.
     */
    private void initActiveCatalogControl() {
        String[] entries;
        String[] values;

        // Check for presence of database directory
        final File folder = FileUtils.getCatalogFolder(SettingsActivity.this);
        Log.d(TAG, "Listing catalogs in " + folder.getAbsolutePath());

        if (folder.exists() && folder.canRead()) {
            // List each map file
            final String[] dbFiles = folder.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename.endsWith(
                            org.openbmap.Preferences.CATALOG_FILE_EXTENSION);
                }
            });

            // Create array of values for each map file + one for not selected
            entries = new String[dbFiles.length + 1];
            values = new String[dbFiles.length + 1];

            // Create default / none entry
            entries[0] = getResources().getString(R.string.prefs_map_none);
            values[0] = org.openbmap.Preferences.VAL_CATALOG_NONE;

            for (int i = 0; i < dbFiles.length; i++) {
                entries[i + 1] = dbFiles[i].substring(0, dbFiles[i].length() - org.openbmap.Preferences.CATALOG_FILE_EXTENSION.length());
                values[i + 1] = dbFiles[i];
            }
        } else {
            // No wifi catalog found, populate values with just the default entry.
            entries = new String[]{getResources().getString(R.string.prefs_map_none)};
            values = new String[]{org.openbmap.Preferences.VAL_CATALOG_NONE};
        }

        Log.d(TAG, "Found " + entries.length + " files");
        final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_CATALOG_FILE);
        lf.setEntries(entries);
        lf.setEntryValues(values);
    }

    /**
     * Changes catalog preference item to given filename.
     * Helper method to activate wifi catalog following successful download
     *
     * @param absoluteFile absolute filename (including path)
     */
    private void activateWifiCatalog(final String absoluteFile) {
        final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_CATALOG_FILE);

        // get filename
        final String[] filenameArray = absoluteFile.split("\\/");
        final String file = filenameArray[filenameArray.length - 1];

        final CharSequence[] values = lf.getEntryValues();
        for (int i = 0; i < values.length; i++) {
            if (file.equals(values[i].toString())) {
                lf.setValueIndex(i);
            }
        }
    }

    /**
     * Selects downloaded file either as wifi catalog / active map (based on file extension).
     * @param file
     */
    public final void handleDownloads(String file) {
        Log.i(TAG, "Download completed " + file);
        // get current file extension
        final String[] filenameArray = file.split("\\.");
        final String extension = "." + filenameArray[filenameArray.length - 1];

        // replace prefix file:// in filename string
        file = file.replace("file://", "");

        if (extension.equals(org.openbmap.Preferences.CATALOG_FILE_EXTENSION)) {
            mCurrentCatalogDownloadId = -1;
            if (file.contains(SettingsActivity.this.getExternalCacheDir().getPath())) {
                Log.i(TAG, "Moving file to " + getCatalogFolder().getAbsolutePath());
                file = moveToFolder(file, getCatalogFolder().getAbsolutePath());
            }

            String version = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            setLocalCatalogVersion(version);

            initActiveCatalogControl();
            // handling wifi catalog files
            activateWifiCatalog(file);
        }
    }

    /**
     * Moves file to specified folder
     * @param file
     * @param folder
     * @return new file name
     */
    private String moveToFolder(final String file, final String folder) {
        // file path contains external cache dir, so we have to move..
        final File source = new File(file);
        final File destination = new File(folder + File.separator + source.getName());
        Log.i(TAG, file + " stored in temp folder. Moving to " + destination.getAbsolutePath());

        try {
            FileUtils.moveFile(source, destination);
        } catch (final IOException e) {
            Log.e(TAG, "I/O error while moving file " + e.getMessage(), e);
        }
        return destination.getAbsolutePath();
    }
}
