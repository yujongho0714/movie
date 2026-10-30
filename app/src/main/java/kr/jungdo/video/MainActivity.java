package kr.jungdo.video;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VIDEO = 1;
    private static final String ROOT = "정도태권도2관";

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pending", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface
        public void record(final String name) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startRecord(name);
                }
            });
        }
    }

    private void startRecord(String name) {
        try {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());

            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, safe + "_" + stamp + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + ROOT + "/" + safe);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                Toast.makeText(this, "저장 위치를 만들지 못했어요", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("uri", uri.toString()).putString("name", name).apply();

            Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "카메라를 열 수 없어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VIDEO) return;

        String uriStr = prefs.getString("uri", null);
        String name = prefs.getString("name", "");
        if (uriStr == null) return;
        Uri uri = Uri.parse(uriStr);
        prefs.edit().clear().apply();

        if (resultCode == RESULT_OK) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            web.evaluateJavascript("window.onNativeSaved(" + JSONObject.quote(name) + ")", null);
        } else {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
            web.evaluateJavascript("window.onNativeCanceled()", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
