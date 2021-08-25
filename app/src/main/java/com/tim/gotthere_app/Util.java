package com.tim.gotthere_app;

import android.content.Context;

import androidx.preference.PreferenceManager;

public class Util {

	public static final String KEY_REQUESTING_LOCATION_UPDATES = "requesting_location_updates";

	public static boolean requestingLocationUpdates(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_REQUESTING_LOCATION_UPDATES, true);
	}
}
