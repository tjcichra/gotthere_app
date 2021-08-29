package com.tim.gotthere_app;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.JobIntentService;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LocationService extends Service {

	private final String TAG = LocationService.class.getSimpleName();
	private final IBinder M_BINDER = new LocalBinder();
	private final String CHANNEL_ID = "channel_01";

	public static final String PACKAGE_NAME = "com.tim.gotthere_app";
	public static final String ACTION_BROADCAST = PACKAGE_NAME + ".broadcast";
	public static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";
	public static final String EXTRA_STARTED_FROM_NOTIFICATION = PACKAGE_NAME + ".started_from_notification";

	private final long INTERVAL = 30000;
	private final long FASTEST_INTERVAL = 15000;
	// private final long INTERVAL = 5000;
	// private final long FASTEST_INTERVAL = 2500;

	private static final int NOTIFICATION_ID = 1;

	private boolean mChangingConfiguration = false;

	private Location mLocation;
	private Handler mServiceHandler;
	private NotificationManager mNotificationManager;

	protected LocationManager locationManager;
	private long MIN_TIME_BW_UPDATES = 30 * 1000; // n seconds
	private float MIN_DISTANCE_CHANGE_FOR_UPDATES = 0.0f;

	private BlockingQueue<Location> locationQueue = new ArrayBlockingQueue<>(512);

	private boolean closing = false;

	private Thread locationThread = new Thread(this::readLocationQueue);
	private Location lastLocation;

	public class LocalBinder extends Binder {
		LocationService getService() {
			return LocationService.this;
		}
	}

	/**
	 * Called when the service is first started. It only runs once. Used to start
	 * the thread that handles location data.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate()");
		this.locationThread.start();
		timer.start();
	}

	/**
	 * Called after the service is bound to the main activity (but not when
	 * "promoted" to the foreground).
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand()");
		boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false);

		if (startedFromNotification) {
//			this.removeLocationUpdates();
			this.stopSelf();
		}

		// Returns START_STICKY so that when memory is needed it doesn't kill the
		// service.
		return START_STICKY;
	}

	/**
	 * Called when the main activity binds to the service with bindService() (called
	 * before startCommand()). Stops this service on the foreground and returns an
	 * interface used to interact with the service.
	 */
	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		Log.d(TAG, "onBind()");
		this.stopForeground(true);
		this.mChangingConfiguration = false;
		return this.M_BINDER;
	}

	/**
	 * Called when the main activity rebinds to the service after unbinding (called
	 * before startCommand())
	 */
	@Override
	public void onRebind(Intent intent) {
		// Called when a client (MainActivity in case of this sample) returns to the
		// foreground
		// and binds once again with this service. The service should cease to be a
		// foreground
		// service when that happens.
		Log.d(TAG, "onRebind()");
		this.stopForeground(true);
		mChangingConfiguration = false;
		super.onRebind(intent);
	}

	/**
	 *
	 * @param intent
	 * @return
	 */
	@Override
	public boolean onUnbind(Intent intent) {
		Log.d(TAG, "onUnbind()");

		// Called when the last client (MainActivity in case of this sample) unbinds
		// from this
		// service. If this method is called due to a configuration change in
		// MainActivity, we
		// do nothing. Otherwise, we make this service a foreground service.
		if (!mChangingConfiguration && Util.requestingLocationUpdates(this)) {
			Log.d(TAG, "Starting foreground service");

			this.startForeground(NOTIFICATION_ID, this.getNotification());
		}
		return true; // Ensures onRebind() is called when a client re-binds.
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		Log.d(TAG, "Configuration changed");
		this.mChangingConfiguration = true;
	}

	/**
	 * Called when the foreground service is stopped and there are no bindings (ex.
	 * when the app itself is stopped). This is for cleaning up sockets and threads.
	 */
	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy()");
		this.closing = true;
		this.locationThread.interrupt();
		try {
			this.locationThread.join();
		} catch (InterruptedException e) {
			Log.d(TAG, Log.getStackTraceString(e));
		}
		stopService(new Intent(getApplicationContext(), LocationService.class));
//		 mServiceHandler.removeCallbacksAndMessages(null);
	}

	public void requestLocationUpdates() {
		Log.d(TAG, "requestLocationUpdates()");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			this.startForegroundService(new Intent(getApplicationContext(), LocationService.class));
		} else {
			this.startService(new Intent(getApplicationContext(), LocationService.class));
		}

		try {
			locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
			boolean isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
				if (isGPSEnabled) {
					if (locationManager != null) {
						locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_BW_UPDATES,
								MIN_DISTANCE_CHANGE_FOR_UPDATES, locationProviderListener);
					}
			}
		} catch (SecurityException e) {
			Log.d(TAG, Log.getStackTraceString(e));
		} catch (Exception e) {
			Log.d(TAG, Log.getStackTraceString(e));
		}
	}

	public CountDownTimer timer = new CountDownTimer(45 * 1000,1 * 1000) {
		@Override
		public void onTick(long millisUntilFinished) {
			// do nothing
		}

		@Override
		public void onFinish() {
			if (lastLocation != null) {
				try {
					Log.d(TAG, "lastLocation inserted by timer");
					locationQueue.put(lastLocation);
				} catch (InterruptedException e) {
					Log.d(TAG, Log.getStackTraceString(e));
				}
			} else {
				Log.d(TAG, "lastLocation was null so no insert happened");
			}
			//start the timer again
			timer.start();
		}
	};

	public LocationListener locationProviderListener = new LocationListener() {

		@Override
		public void onLocationChanged(Location location) {
			Log.d(TAG, "Location inserted by onLocationChanged");
			lastLocation = location;
			try {
				locationQueue.put(location);
				//cycle the timer
				timer.cancel();
				timer.start();
			} catch (Exception e) {
				Log.d(TAG, Log.getStackTraceString(e));
			}
		}

		@Override
		public void onStatusChanged(String s, int i, Bundle bundle) {

		}

		@Override
		public void onProviderEnabled(String s) {

		}

		@Override
		public void onProviderDisabled(String s) {

		}
	};

	/**
	 * Used to send out locations from the location queue. Should be ran as a
	 * separate thread.
	 */
	public void readLocationQueue() {
		while (!closing) {
			try {
				Location location = this.locationQueue.take();

				TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

				OkHttpClient client = new OkHttpClient();

				// calculate the proper gps time
				long gps_time_since_boot_in_milliseconds = location.getElapsedRealtimeNanos() / 1000000;
				long boot_time_in_milliseconds = (java.lang.System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime());
				long gps_time = (boot_time_in_milliseconds + gps_time_since_boot_in_milliseconds) / 1000;

				// latitude
				// longitude
				// gps_time
				// provider
				// accuracy
				// speed
				// altitude
				// bearing
				// ip_address
				// imei
				JSONObject json = new JSONObject();
				try {
					json.put("latitude", location.getLatitude());
					json.put("longitude", location.getLongitude());
					json.put("gps_time", gps_time);
					json.put("provider", location.getProvider());
					json.put("accuracy", location.getAccuracy());
					json.put("speed", location.getSpeed());
					json.put("altitude", location.getAltitude());
					json.put("bearing", location.getBearing());

					if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
						Log.d(TAG, "IMEI is off. Setting it to a funny number");
						json.put("imei", "1234567890");
					} else {
						String id = null;
						try {
							id = tm.getDeviceId();
						}catch (SecurityException e) {
							// permission for imei is blocked in newer versions of android. Use the ANDROID_ID instead if we have to use a unique id
							id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
						}
						json.put("imei", id);
					}

				} catch (JSONException e) {
					e.printStackTrace();
				}
				String jsonString = json.toString();
				Log.d(TAG, jsonString);

				RequestBody body = RequestBody.create(jsonString, MediaType.parse("application/json"));
				Request request = new Request.Builder().url("https://madeit.jrcichra.dev/phone_location").post(body)
						.build();
				// Request request = new
				// Request.Builder().url("http://10.0.0.40:3000/phone_location").post(body).build();

				Call call = client.newCall(request);
				Response response = call.execute();

				if (!response.isSuccessful()) {
					Log.d(TAG, "HTTP response error");
					this.locationQueue.put(location);
				}

				// Notify anyone listening for broadcasts about the new location.
				Intent intent = new Intent(ACTION_BROADCAST);
				intent.putExtra(EXTRA_LOCATION, location);
			} catch (InterruptedException | MalformedURLException e) {
				Log.e(TAG, "Location thread interrupted", e);
			} catch (IOException e) {
				Log.e(TAG, "Input/Output error", e);
			}
		}
	}

	private Notification getNotification() {
		Intent intent = new Intent(this, LocationService.class);
		String message = "MadeIt (GT) is Running";

		// Extra to help us figure out if we arrived in onStartCommand via the
		// notification or not.
		intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel();
		}

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(message)
				.setOngoing(true).setPriority(Notification.PRIORITY_HIGH).setSmallIcon(R.mipmap.ic_launcher)
				.setTicker("MadeIt (GT) Text").setWhen(System.currentTimeMillis());

		return builder.build();
	}

	private void createNotificationChannel() {
		// Create the NotificationChannel, but only on API 26+ because
		// the NotificationChannel class is new and not in the support library
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.app_name);
			String description = "MadeIt (GT) is Running";
			int importance = NotificationManager.IMPORTANCE_DEFAULT;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			// Register the channel with the system; you can't change the importance
			// or other notification behaviors after this
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}

}