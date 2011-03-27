/* vim: set sw=4 ts=4: */

package org.nklog.gogoc;

import android.app.Activity;
import android.os.Bundle;

import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import android.view.View;
import android.view.View.OnClickListener;

import android.util.Log;
import android.os.Handler;
import android.os.Message;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;

import android.app.AlertDialog;
import android.content.DialogInterface;

public class GogocActivity extends Activity
{
	private TextView text;
	private TextView logtext;
	private CheckBox checkbox;
	private ImageView status;
	private Receiver receiver = null;

	private final String TAG = "GogocActivity";
	private final int LOG = 1;
	private final int CLEAR = 2;

	private Thread thread = null;
	private boolean showassets;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		text = (TextView) findViewById(R.id.text);
		logtext = (TextView) findViewById(R.id.logtext);
		logtext.setText(R.string.contact);

		showassets = true;
		status = (ImageView) findViewById(R.id.status);
		status.setImageResource(R.drawable.offline);
		status.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (thread == null) {
					displayLog(true);
					Toast.makeText(GogocActivity.this, R.string.click, Toast.LENGTH_LONG).show();
				} else {
					displayLog(false);
					logtext.setText(R.string.logtext);
				}
			}
		});

		checkbox = (CheckBox) findViewById(R.id.checkbox);
		checkbox.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (((CheckBox) v).isChecked()) {
					startService(new Intent(GogocActivity.this, GogocService.class));
					prepareLog();
				} else {
					displayLog(false);
					text.setText(R.string.text);
					logtext.setText(R.string.contact);
					status.setImageResource(R.drawable.offline);
					sendToService("quit");
				}
			}
		});
	}

	private class Receiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String line = intent.getStringExtra("data");
			String tag = line.substring(0, 1);
			String content =  line.substring(1);
			Log.d(TAG, "receive '" + line + "' from service");
			if (tag.compareTo("E") == 0) {
				displayLog(false);
				checkbox.setChecked(false);
				logtext.setText(R.string.contact);
				if (content.compareTo("module") == 0) {
					text.setText(R.string.module_error);
					showdialog();
				} else if (content.compareTo("unpack") == 0) {
					text.setText(R.string.unpack_error);
				} else if (content.compareTo("gogoc") == 0) {
					text.setText(R.string.gogoc_error);
				}
			} else if (tag.compareTo("S") == 0) {
				if (content.compareTo("online") == 0) {
					prepareLog();
					displayAddress();
					checkbox.setChecked(true);
					status.setImageResource(R.drawable.online);
				} else if (content.compareTo("offline") == 0) {
					displayLog(false);
					checkbox.setChecked(false);
					text.setText(R.string.text);
					logtext.setText(R.string.contact);
					status.setImageResource(R.drawable.offline);
				} else if (content.compareTo("running") == 0) {
					prepareLog();
					checkbox.setChecked(true);
					text.setText(R.string.running);
					status.setImageResource(R.drawable.running);
				}
			}
		}
	}

	@Override
	protected void onStart() {
		receiver = new Receiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction("org.nklog.gogoc.GogocActivity");
		registerReceiver(receiver, filter);

		if (displayAddress()) {
			prepareLog();
			checkbox.setChecked(true);
			status.setImageResource(R.drawable.online);
		} else {
			sendToService("status");
		}
		super.onStart();
	}

	@Override
	protected void onStop() {
		showassets = false;
		if (receiver != null) {
			unregisterReceiver(receiver);
		}
		super.onStop();
	}

	@Override
	public void onDestroy() {
		if (!checkbox.isChecked()) {
			stopService(new Intent(GogocActivity.this, GogocService.class));
		}
		displayLog(false);
		super.onDestroy();
	}

	private void sendToService(String command) {
		Log.d(TAG, "try to send command " + command + " to service");
		Intent intent = new Intent();
		intent.setAction("org.nklog.gogoc.GogocService");
		intent.putExtra("command", command);
		sendBroadcast(intent);
	}

	private boolean displayAddress() {
		boolean retcode = false;
		try {
			String line;
			BufferedReader br = new BufferedReader(new FileReader("/proc/net/if_inet6"), 1024);
			while ((line = br.readLine()) != null) {
				if (line.contains("tun")) {
					StringBuilder sb = new StringBuilder("");
					for (int i = 0; i < 8; i++) {
						sb.append(line.substring(i * 4, (i + 1) * 4));
						sb.append(i == 7 ? "" : ":");
					}
					text.setText(sb.toString()
						.replaceAll(":(0)+", ":")
						.replaceFirst("::+", "::"));
					retcode = true;
					break;
				}
			}
			br.close();
		} catch (Exception e) {
			Log.e(TAG, "ipv6 is not supported");
			text.setText(R.string.ipv6_error);
			status.setClickable(false);
			checkbox.setClickable(false);
			return false;
		}
		return retcode;
	}

	private void displayLog(boolean flag) {
		if (flag && thread == null && getFileStreamPath("gogoc.log").exists()) {
			thread = new Thread(new Output(getFileStreamPath("gogoc.log")));
			thread.start();
		} else if (!flag && thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	private void prepareLog() {
		if (thread == null) {
			logtext.setText(R.string.logtext);
		}
	}

	private class Output implements Runnable {
		private final File file;
		public Output(File f) {
			file = f;
		}
		public void run() {
			BufferedReader br = null;
			handler.obtainMessage(CLEAR, "").sendToTarget();
			try{
				br = new BufferedReader(new FileReader(file), 8192);
				while (true) {
					String line;
					if ((line = br.readLine()) != null) {
						handler.obtainMessage(LOG, line).sendToTarget();
					}
				}
			} catch (Exception e1) {
				// Very ugly ...
				try {
					if (br != null) {
						br.close();
					}
				} catch (Exception e2) {
				}
			}
		}
	}

	private final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			if (msg.what == CLEAR) {
				logtext.setText((String)msg.obj);
			} else if (msg.what == LOG) {
				logtext.append((String)msg.obj);
			}
		}
	};

	private void showdialog() {
		if (showassets) {
			new AlertDialog.Builder(this)
				.setTitle(R.string.tun)
				.setMessage(R.string.tunerror)
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener(){
					public void onClick(DialogInterface di, int i) {
						startActivity(new Intent(GogocActivity.this, GogocHelp.class));
					}
				})
			.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener(){
				public void onClick(DialogInterface di, int i) {
				}
			})
			.show();
		}
	}
}
