package kr.jungdo.video;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void startRecord(String name) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
            File dir = new File(getCacheDir(), "videos");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "tmp_" + stamp + ".mp4");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);

            Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.setClipData(ClipData.newRawUri("video", uri));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : list) {
                grantUriPermission(ri.activityInfo.packageName, uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            prefs.edit().putString("path", f.getAbsolutePath()).putString("name", name).apply();
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            toast("카메라를 열 수 없어요: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VIDEO) return;

        String path = prefs.getString("path", null);
        final String name = prefs.getString("name", "");
        prefs.edit().clear().apply();
        if (path == null) return;
        final File f = new File(path);

        if (f.exists() && f.length() > 0) {
            toast("저장 중... 잠시만 기다려 주세요");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        moveToAlbum(f, name);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                web.evaluateJavascript("window.onNativeSaved(" + JSONObject.quote(name) + ")", null);
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast("앨범 저장 실패: " + e.getMessage());
                            }
                        });
                    }
                }
            }).start();
        } else {
            f.delete();
            web.evaluateJavascript("window.onNativeCanceled()", null);
            if (resultCode != RESULT_CANCELED) {
                toast("촬영 파일이 만들어지지 않았어요 (코드 " + resultCode + ")");
            }
        }
    }

    private void moveToAlbum(File src, String name) throws Exception {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, safe + "_" + stamp + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + ROOT + "/" + safe);
        v.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver cr = getContentResolver();
        Uri out = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("저장 위치를 만들지 못했어요");

        try {
            InputStream in = new FileInputStream(src);
            OutputStream os = cr.openOutputStream(out);
            if (os == null) throw new Exception("파일을 열지 못했어요");
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            os.close();
            in.close();
        } catch (Exception e) {
            cr.delete(out, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        cr.update(out, done, null, null);
        src.delete();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
