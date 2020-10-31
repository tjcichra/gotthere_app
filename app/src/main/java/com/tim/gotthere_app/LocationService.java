package com.tim.gotthere_app;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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

	private static final int NOTIFICATION_ID = 12345678;

	private boolean mChangingConfiguration = false;

	private FusedLocationProviderClient mFusedLocationClient;
	private LocationCallback mLocationCallback;
	private LocationRequest mLocationRequest;
	private Location mLocation;
	private Handler mServiceHandler;
	private NotificationManager mNotificationManager;

	private Socket socket;

	private BlockingQueue<Location> locationQueue = new ArrayBlockingQueue<>(512);

	private boolean isConnected = false;
	private boolean lostConnection = false;
	private boolean closing = false;

	private Thread socketThread = new Thread(this::connectSocket);
	private Thread locationThread = new Thread(this::readLocationQueue);

	public class LocalBinder extends Binder {
		LocationService getService() {
			return LocationService.this;
		}
	}

	/**
	 * Called when the service is first started. It only runs once.
	 * Used to start the threads that provide connection and send out location data.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate()");

		//Start thread for connection
		this.socketThread.start();
		//Start thread for reading queued locations.
		this.locationThread.start();

		Log.d(TAG, "Ready");

		this.setupFusedLocationClient();
	}

	/**
	 * Called after the service is bound to the main activity (but not when "promoted" to the foreground).
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(TAG, "onStartCommand()");
		boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false);

		if(startedFromNotification) {
			this.removeLocationUpdates();
			this.stopSelf();
		}

		//Returns START_STICKY so that when memory is needed it doesn't kill the service.
		return START_STICKY;
	}

	/**
	 * Called when the main activity binds to the service with bindService() (called before startCommand()).
	 * Stops this service on the foreground and returns an interface used to interact with the service.
	 */
	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "onBind()");
		this.stopForeground(true);
		this.mChangingConfiguration = false;
		return this.M_BINDER;
	}

	/**
	 * Called when the main activity rebinds to the service after unbinding (called before startCommand())
	 */
	@Override
	public void onRebind(Intent intent) {
		// Called when a client (MainActivity in case of this sample) returns to the foreground
		// and binds once again with this service. The service should cease to be a foreground
		// service when that happens.
		Log.i(TAG, "onRebind()");
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
		Log.i(TAG, "onUnbind()");

		// Called when the last client (MainActivity in case of this sample) unbinds from this
		// service. If this method is called due to a configuration change in MainActivity, we
		// do nothing. Otherwise, we make this service a foreground service.
		if (!mChangingConfiguration && Util.requestingLocationUpdates(this)) {
			Log.i(TAG, "Starting foreground service");

			this.startForeground(NOTIFICATION_ID, this.getNotification());
		}
		return true; // Ensures onRebind() is called when a client re-binds.
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		this.mChangingConfiguration = true;
	}

	/**
	 * Called when the foreground service is stopped and there are no bindings (ex. when the app itself is stopped).
	 * This is for cleaning up sockets and threads.
	 */
	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy()");
		this.closing = true;
		this.locationThread.interrupt();
		try {
			this.locationThread.join();
			this.socketThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		if(this.socket != null) {
			try {
				this.socket.getInputStream().close();
				this.socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		mServiceHandler.removeCallbacksAndMessages(null);
	}

	/**
	 * Used for setting up the fused location client, which will start to insert locations into the location queue.
	 */
	public void setupFusedLocationClient() {
		this.mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

		//Set what happens when a new location is retrieved.
		this.mLocationCallback = new LocationCallback() {
			@Override
			public void onLocationResult(LocationResult locationResult) {
				super.onLocationResult(locationResult);
				onNewLocation(locationResult.getLastLocation());
			}
		};

		this.createLocationRequest();
		this.getLastLocation();

		HandlerThread handlerThread = new HandlerThread(TAG);
		handlerThread.start();
		this.mServiceHandler = new Handler(handlerThread.getLooper());
		this.mNotificationManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT);

			mNotificationManager.createNotificationChannel(mChannel);
		}

		/*HandlerThread handlerThread1 = new HandlerThread(TAG + "2");
		handlerThread1.start();
		Handler handler = new Handler(handlerThread1.getLooper());
		handler.post(new Runnable() {
			@Override
			public void run() {
				Log.d(TAG,"Tanner is real");
			}
		});*/
	}

	private void createLocationRequest() {
		this.mLocationRequest = new LocationRequest();
		this.mLocationRequest.setInterval(this.INTERVAL);
		this.mLocationRequest.setFastestInterval(this.FASTEST_INTERVAL);
		this.mLocationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
	}

	private void onNewLocation(Location location) {
		Log.i(TAG, "New location: " + location);

		mLocation = location;

		try {
			this.locationQueue.put(location);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void getLastLocation() {
		try {
			this.mFusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
				@Override
				public void onComplete(@NonNull Task<Location> task) {
					if (task.isSuccessful() && task.getResult() != null) {
						mLocation = task.getResult();
					} else {
						Log.w(TAG, "Failed to get location.");
					}
				}
			});
		} catch (SecurityException unlikely) {
			Log.e(TAG, "Lost location permission." + unlikely);
		}
	}

	/**
	 * Makes a request for location updates. Note that in this sample we merely log the
	 * {@link SecurityException}.
	 */
	public void requestLocationUpdates() {
		Log.i(TAG, "Requesting location updates");
		setRequestingLocationUpdates(this, true);
		this.startService(new Intent(getApplicationContext(), LocationService.class));
		try {
			this.mFusedLocationClient.requestLocationUpdates(this.mLocationRequest, this.mLocationCallback, Looper.myLooper());
		} catch (SecurityException unlikely) {
			setRequestingLocationUpdates(this, false);
			Log.e(TAG, "Lost location permission. Could not request updates. " + unlikely);
		}
	}

	/**
	 * Removes location updates. Note that in this sample we merely log the
	 * {@link SecurityException}.
	 */
	public void removeLocationUpdates() {
		Log.i(TAG, "Removing location updates");
		try {
			mFusedLocationClient.removeLocationUpdates(mLocationCallback);
			setRequestingLocationUpdates(this, false);
			this.stopSelf();
		} catch (SecurityException unlikely) {
			setRequestingLocationUpdates(this, true);
			Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
		}
	}

	/**
	 * Stores the location updates state in SharedPreferences.
	 * @param requestingLocationUpdates The location updates state.
	 */
	static void setRequestingLocationUpdates(Context context, boolean requestingLocationUpdates) {
		PreferenceManager.getDefaultSharedPreferences(context)
				.edit()
				.putBoolean(Util.KEY_REQUESTING_LOCATION_UPDATES, requestingLocationUpdates)
				.apply();
	}

	/**
	 * Used to connect the socket to the GotThere server. It does not stop until connection is established.
	 * Should be ran as a separate thread.
	 */
	public void connectSocket() {
		while(!closing && this.socket == null) {
			try {
				this.socket = new Socket("10.0.0.224", 2810);
				
			} catch (UnknownHostException ex) {
				//This is ran when the host could not be determined.
				ex.printStackTrace();
			} catch(IOException e) {
				//This is ran when the socket fails to connect.
				Log.d(TAG, e.getMessage());

				//Sleeps for 10 seconds before retrying
				try {
					Thread.sleep(10000);
				} catch(InterruptedException ex) {
					ex.printStackTrace();
				}
			} catch(SecurityException ex) {
				//This is ran when the socket runs into security issues.
				ex.printStackTrace();
			} catch(IllegalArgumentException ex) {
				//This is ran when the port number is invalid (not in-between 0 and 65535)
				ex.printStackTrace();
			}
		}

		if(!closing) {
			Log.d(TAG, "Connected");
		}

		this.lostConnection = false;
		this.isConnected = true;

		// Update notification content if running as a foreground service.
		if (!closing && this.serviceIsRunningInForeground(this)) {
			mNotificationManager.notify(NOTIFICATION_ID, getNotification());
		}
	}

	/**
	 * Used to send out locations from the location queue.
	 * Should be ran as a separate thread.
	 */
	public void readLocationQueue() {
		while(!closing) {
			if (this.socket != null && this.socket.isConnected()) {
				try {
					Location location = this.locationQueue.take();

					byte[] buffer = new byte[15];
					this.insertDoubleThree(buffer, 0, location.getBearing());
					this.insertDoubleFive(buffer, 3, location.getLatitude());
					this.insertDoubleFive(buffer, 8, location.getLongitude());
					this.insertDoubleTwo(buffer, 13, location.getSpeed());


					try {
						OutputStream out = this.socket.getOutputStream();
						out.write(buffer);

						// Notify anyone listening for broadcasts about the new location.
						Intent intent = new Intent(ACTION_BROADCAST);
						intent.putExtra(EXTRA_LOCATION, location);
						//LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
					} catch(SocketException e) {
						try {
							this.lostConnection = true;

							this.locationQueue.offer(location);
							this.socket.getOutputStream().close();
							this.socket.close();
							this.socket = null;

							// Update notification content if running as a foreground service.
							if (this.serviceIsRunningInForeground(this)) {
								mNotificationManager.notify(NOTIFICATION_ID, getNotification());
							}

							if(!this.closing) {
								this.socketThread = new Thread(this::connectSocket);
								this.socketThread.start();
							}
						} catch (IOException ex) {
							ex.printStackTrace();
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				} catch (InterruptedException e) {

				}
			}
		}
	}

	public void insertDoubleThree(byte[] buffer, int start, double value) {
		int ivalue = (int) value;
		if(ivalue > Byte.MAX_VALUE) {
			buffer[start] = (byte) (ivalue - Byte.MAX_VALUE);
			buffer[start + 1] = Byte.MAX_VALUE;
		} else if(ivalue < Byte.MIN_VALUE) {
			buffer[start] = (byte) (ivalue - Byte.MIN_VALUE);
			buffer[start + 1] = Byte.MIN_VALUE;
		} else {
			buffer[start] = 0;
			buffer[start + 1] = (byte) value;
		}

		int fvalue = ((int) (Math.abs(value) * 100)) % 100;
		buffer[start + 2] = (byte) fvalue;
	}

	public void insertDoubleFive(byte[] buffer, int start, double value) {
		int ivalue = (int) value;
		if(ivalue > Byte.MAX_VALUE) {
			buffer[start] = (byte) (ivalue - Byte.MAX_VALUE);
			buffer[start + 1] = Byte.MAX_VALUE;
		} else if(ivalue < Byte.MIN_VALUE) {
			buffer[start] = (byte) (ivalue - Byte.MIN_VALUE);
			buffer[start + 1] = Byte.MIN_VALUE;
		} else {
			buffer[start] = 0;
			buffer[start + 1] = (byte) value;
		}

		int fvalue = ((int) (value * 100)) % 100;
		int fvalue2 = ((int) (value * 10000)) % 100;
		int fvalue3 = ((int) (value * 1000000)) % 100;

		buffer[start + 2] = (byte) fvalue;
		buffer[start + 3] = (byte) fvalue2;
		buffer[start + 4] = (byte) fvalue3;
	}

	public void insertDoubleTwo(byte[] buffer, int start, double value) {
		int ivalue = (int) value;
		buffer[start] = (byte) ivalue;

		int fvalue = ((int) (value * 100)) % 100;
		buffer[start + 1] = (byte) fvalue;
	}

	private Notification getNotification() {
		Intent intent = new Intent(this, LocationService.class);
		String message;

		if(this.lostConnection) {
			message = "Lost connection. Trying to reconnect.";
		} else if(this.isConnected) {
			message = "Connected to server.";
		} else {
			message = "Trying to connect to server.";
		}

		// Extra to help us figure out if we arrived in onStartCommand via the notification or not.
		intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

		// The PendingIntent that leads to a call to onStartCommand() in this service.
		PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT);

		// The PendingIntent to launch activity.
		PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), 0);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(message)
				.setOngoing(true)
				.setPriority(Notification.PRIORITY_HIGH)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setTicker("Tim Ticker Text")
				.setWhen(System.currentTimeMillis());

		return builder.build();
	}

	public boolean serviceIsRunningInForeground(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		for(ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if(this.getClass().getName().equals(service.service.getClassName())) {
				if(service.foreground) {
					return true;
				}
			}
		}
		return false;
	}
}