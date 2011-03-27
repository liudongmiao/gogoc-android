/* vim: set sw=4 ts=4: */

package org.nklog.gogoc;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

import android.util.Log;

public class GogocHelp extends Activity {
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.help);

		String url = "file:///android_asset/" + getString(R.string.help);
		Log.d("GogocHelp", url);
		WebView web = (WebView) findViewById(R.id.webview);
		web.loadUrl(url);
		web.computeScroll();
	}
}
