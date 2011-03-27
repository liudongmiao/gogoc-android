/* vim: set sw=4 ts=4: */

package org.nklog.gogoc;

import android.os.IBinder;
import android.app.Service;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

import android.util.Log;

import java.io.FileReader;
import java.io.BufferedReader;

public class GogocService extends Service
{
	private int unpack = 0;
	private Process process = null;
	private Receiver receiver = null;

	private final String TAG = "GogocService";
	private Thread thread = null;

	private final String TUNPATH = "/sdcard/.gogoc/tun.ko";

	@Override
	public void onCreate()
	{
		receiver = new Receiver();

		unpack |= unpackRaw(R.raw.gogoc, "gogoc", true);
		if (!hastun()) {
			unpack |= unpackRaw(R.raw.tun, "tun.ko", false);
		}

		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "on start command");
		IntentFilter filter = new IntentFilter();
		filter.addAction("org.nklog.gogoc.GogocService");
		registerReceiver(receiver, filter);
		if (unpack != 0) {
			sendToActivity("E", "unpack");
		} else {
			Log.d(TAG, "try to startProcess");
			if (thread == null) {
				thread = new Thread(new GogocProcess());
				thread.start();
			}
		}
		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onDestroy() {
		unregisterReceiver(receiver);
		stopProcess();
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void sendToActivity(String tag, String content) {
		Log.d(TAG, "send to activity: " + tag + content);
		Intent intent = new Intent();
		intent.setAction("org.nklog.gogoc.GogocActivity");
		intent.putExtra("data", tag + content);
		sendBroadcast(intent);
	}

	private class Receiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String command = intent.getStringExtra("command");
			Log.d(TAG, "receive " + command + " from activity");
			if (command.compareTo("quit") == 0) {
				stopProcess();
			} else if (command.compareTo("status") == 0) {
				try {
					if (process != null && process.exitValue() == 0) {
						sendToActivity("S", "online");
					} else {
						sendToActivity("S", "offline");
					}
				} catch (IllegalThreadStateException e) {
					sendToActivity("S", "running");
				}
			}
		}
	}

	private boolean startProcess() {
		int retcode = -1;
		Log.d(TAG, "startProcess, process = " + (process != null));
		if (process != null) {
			return true;
		}

		File devtun = new File("/dev/tun");
		if (!devtun.exists()) {
			String path = null;
			Process settun = null;
			if (hastun()) {
				path = TUNPATH;
			} else {
				path = getFileStreamPath("tun.ko").getAbsolutePath();
			}

			Log.d(TAG, "tun.ko path is " + path);
			try {
				settun = new ProcessBuilder()
					.command("su", "-c", "insmod " + path)
					.start();
				retcode = settun.waitFor();
			} catch (Exception e) {
				Log.d(TAG, "insmod tun.ko failed", e);
				if (settun != null) {
					settun.destroy();
				}
			}
			if (retcode != 0) {
				Log.d(TAG, "insmod error, retcode = " + retcode);
				sendToActivity("E", "module");
				return false;
			}
			Log.d(TAG, "insmod ok, retcode = " + retcode);
		}

		retcode = -1;
		sendToActivity("S", "running");
		try {
			Log.d(TAG, "start new process: " + getFileStreamPath("gogoc").getAbsolutePath());
			process = new ProcessBuilder()
				.command("su", "-c", getFileStreamPath("gogoc").getAbsolutePath())
				.redirectErrorStream(true)
				.start();
			retcode = process.waitFor();
		} catch (InterruptedException e) {
			Log.e(TAG, "user interrupt");
			return false;
		} catch (Exception e) {
			Log.e(TAG, "gogoc failed", e);
			if (process != null) {
				process.destroy();
			}
		}
		if (retcode != 0) {
			sendToActivity("E", "gogoc");
			return false;
		} else {
			Process setprop = null;
			sendToActivity("S", "online");
			try {
				setprop = new ProcessBuilder()
					.command("su", "-c", "setprop net.dns1 210.51.191.217")
					.start();
				setprop.waitFor();
			} catch (Exception e) {
				if (setprop != null) {
					setprop.destroy();
				}
			}
			return true;
		}
	}

	private void stopProcess() {
		int retcode;
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}

		// kill the daemon
		String line = null;
		try {
			BufferedReader br = new BufferedReader(new FileReader(getFileStreamPath("gogoc.pid")), 1024);
			line = br.readLine();
			br.close();
		} catch (Exception e) {
			Log.e(TAG, "read gogoc.pid exception", e);
		}
		if (line != null) {
			Log.d(TAG, "try to run kill -9 " + line);
			Process kill = null;
			try {
				kill = new ProcessBuilder()
					.command("su", "-c", "kill -9 " + line)
					.start();
				kill.waitFor();
			} catch (Exception e) {
				Log.e(TAG, "kill -9 gogoc", e);
				if (kill != null) kill.destroy();
			}
		}

		Log.d(TAG, "process = " + (process != null));
		if (process != null) {
			// actually, we have killed -9 the process
			process.destroy();
			Log.d(TAG, "gogoc destroy");
			retcode = process.exitValue();
			Log.e(TAG, "gogoc exitValue: " + retcode);
			process = null;
		}

		// ok, we have killed the process, then remove the pid
		if (getFileStreamPath("gogoc.pid").exists()) {
			getFileStreamPath("gogoc.pid").delete();
		}

		if (getFileStreamPath("gogoc.log").exists()) {
			getFileStreamPath("gogoc.log").delete();
		}

		Log.d(TAG, "try to rmmod tun");
		Process rmmod = null;
		try {
			rmmod = new ProcessBuilder()
				.command("su", "-c", "rmmod tun")
				.start();
			rmmod.waitFor();
		} catch (Exception e) {
			Log.e(TAG, "rmmod tun", e);
			if (rmmod != null) rmmod.destroy();
		}
	}

	private int unpackRaw(int resid, String filename, boolean executable) {
		int retcode = 0;
		try {
			if (getFileStreamPath(filename).exists()) {
				getFileStreamPath(filename).delete();
			}
			InputStream is = getResources().openRawResource(resid);
			OutputStream os = openFileOutput(filename, MODE_WORLD_READABLE);
			int len;
			byte [] buffer = new byte[8192];
			while((len = is.read(buffer)) >= 0) {
				os.write(buffer, 0, len);
			}
			os.close();
		} catch (Exception e) {
			retcode = 1;
			Log.e(TAG, "unpack " + filename, e);
		}
		if (retcode == 0 && executable) {
			Process chmod = null;
			try {
				chmod = Runtime.getRuntime().exec("chmod 555 " + getFileStreamPath(filename).getAbsolutePath());
				chmod.waitFor();
			} catch (Exception e) {
				if (chmod != null) chmod.destroy();
				Log.e(TAG, "chmod 555 " + filename, e);
				retcode = 1;
			}
		}
		Log.d(TAG, "unpacked " + filename + ": " + retcode);
		return retcode;
	}

	private class GogocProcess implements Runnable {
		public GogocProcess() {
		}
		public void run() {
			try{
				startProcess();
			} catch (Exception e) {
				Log.d(TAG, "Process", e);
			}
		}
	}

	private boolean hastun() {
		File tun = new File(TUNPATH);
		return tun.exists();
	}
}
