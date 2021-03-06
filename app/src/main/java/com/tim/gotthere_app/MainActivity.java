package com.tim.gotthere_app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;

/**
 * The main activity for the app. This is what controls the app's interface and deployment of the location service.
 */
public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

	private static final String TAG = MainActivity.class.getSimpleName();
	private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;


	private MyReceiver myReceiver;

	//The instance of the service when it is bound to the app.
	private LocationService mService = null;
	//True when the service is bound to the app, false otherwise.
	private boolean mBound = false;

	private class MyReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Location location = intent.getParcelableExtra(LocationService.EXTRA_LOCATION);
			if (location != null) {
				Toast.makeText(MainActivity.this, getLocationText(location), Toast.LENGTH_SHORT).show();
			}
		}
	}

	public static String getLocationText(Location location) {
		return location == null ? "Unknown location" :
				"(" + location.getLatitude() + ", " + location.getLongitude() + ")";
	}

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
			//
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
			mService = null;
			mBound = false;
		}
	};

	private ActivityResultLauncher<String> requestPermissionLauncher = this.registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
		if(isGranted) {
			mService.requestLocationUpdates();
		} else {

		}
	});

	/**
	 * Called right when the app is launched.
	 * Used for setting content view and handling permissions.
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.myReceiver = new MyReceiver();
		this.setContentView(R.layout.activity_main);
		//this.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, new MySettingsFragment()).commit();

		//if(Util.requestingLocationUpdates(this)) {
			this.handlePermissions();
		//}
	}

	/**
	 * Called after onCreate() when the app is visible (completely opened).
	 * Used for binding the location service to the app.
	 */
	@Override
	protected void onStart() {
		super.onStart();
		//PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

		this.bindService(new Intent(this, LocationService.class), this.mServiceConnection, Context.BIND_AUTO_CREATE);

		//LocalBroadcastManager.getInstance(this).registerReceiver(this.myReceiver, new IntentFilter(LocationService.ACTION_BROADCAST)); //Look at this
	}

	/**
	 * Called when the app is no longer visible (not completely opened).
	 * Used for unbinding the location service to the app (if it is bound).
	 */
	@Override
	protected void onStop() {
		//LocalBroadcastManager.getInstance(this).unregisterReceiver(this.myReceiver);

		if(mBound) {
			this.unbindService(mServiceConnection);
			mBound = false;
		}

		//PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
		super.onStop();
	}

	/**
	 * Called right before the app is destroyed.
	 */
	@Override
	protected void onDestroy() {
		this.stopService(new Intent(this, LocationService.class));
		super.onDestroy();
	}

	public void handlePermissions() {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			//TODO Check for Android 6.0 https://stackoverflow.com/questions/33407250/checkselfpermission-method-is-not-working-in-targetsdkversion-22
			return;
		}

		if(this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
			Log.d("Timmy", "Request ACCESS_FINE_LOCATION");
			requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
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