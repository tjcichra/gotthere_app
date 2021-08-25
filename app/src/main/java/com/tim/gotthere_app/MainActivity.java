package com.tim.gotthere_app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

/**
 * The main activity for the app. This is what controls the app's interface and deployment of the location service.
 */
public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

	private static final String TAG = MainActivity.class.getSimpleName();
	private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;

	//The instance of the service when it is bound to the app.
	private LocationService mService = null;
	//True when the service is bound to the app, false otherwise.
	private boolean mBound = false;

	/**
	 * Manages when the service is connected and disconnected.
	 */
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		/**
		 * Called when connection to the service has been established.
		 * It sets mService to the instance of the service and starts the location updates. It also sets mBound to true.
		 */
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			Log.d(TAG,"Main onServiceConnected()");
			LocationService.LocalBinder binder = (LocationService.LocalBinder) service;
			mService = binder.getService();
			mService.requestLocationUpdates();
			mBound = true;
		}

		/**
		 * Called when connection to the service has been lost.
		 * It sets mService to null but keeps the location updates running. It also sets mBound to false.
		 */
		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.d(TAG,"Main onServiceDisconnected()");
			mService = null;
			mBound = false;
		}
	};

	private ActivityResultLauncher<String> requestPermissionLauncher = this.registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {});

	/**
	 * Called right when the app is launched.
	 * Used for setting content view and handling permissions.
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.d(TAG,"Main onCreate()");
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.activity_main);
		this.handlePermissions();
	}

	/**
	 * Called after onCreate() when the app is visible (completely opened).
	 * Used for binding the location service to the app.
	 */
	@Override
	protected void onStart() {
		Log.d(TAG,"Main onStart()");
		super.onStart();
		PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

		this.bindService(new Intent(this, LocationService.class), this.mServiceConnection, Context.BIND_AUTO_CREATE);
	}

	/**
	 * Called when the app is no longer visible (not completely opened).
	 * Used for unbinding the location service to the app (if it is bound).
	 */
	@Override
	protected void onStop() {
		Log.d(TAG,"Main onStop()");
		if(mBound) {
			this.unbindService(mServiceConnection);
			mBound = false;
		}

		PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
		super.onStop();
	}

	/**
	 * Called right before the app is destroyed.
	 */
	@Override
	protected void onDestroy() {
		Log.d(TAG,"Main onDestroy()");
		this.stopService(new Intent(this, LocationService.class));
		super.onDestroy();
	}

	/**
	 * Used to handle the permissions of the app. More specifically ACCESS_FINE_LOCATION.
	 */
	public void handlePermissions() {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			//TODO Check for Android 6.0 https://stackoverflow.com/questions/33407250/checkselfpermission-method-is-not-working-in-targetsdkversion-22
			return;
		}

		if(this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
			Log.d(TAG, "Request ACCESS_FINE_LOCATION");
			requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
		}

		if(this.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED) {
			Log.d(TAG, "Request READ_PHONE_STATE");
			requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE);
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
		// Update the buttons state depending on whether location updates are being requested.
		if (s.equals(Util.KEY_REQUESTING_LOCATION_UPDATES)) {
			//setButtonsState(sharedPreferences.getBoolean(Utils.KEY_REQUESTING_LOCATION_UPDATES, false));
		}
	}

	public class MySettingsFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			this.setPreferencesFromResource(R.xml.preferences, rootKey);
		}
	}
}