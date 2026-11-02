package kr.jungdo.video;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_VIDEO = 1;
    private static final int REQ_PICK = 2;
    private static final String ROOT = "정도태권도2관";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FaceDetector detector;

    // 분류용으로 가져온 영상들 (id -> uri)
    private final Map<String, Uri> picked = new HashMap<>();
    private final Map<String, Long> pickedDate = new HashMap<>();
    private int pickSeq = 0;

    // 날짜를 넣어서 저장하는 작업 줄 (한 번에 하나씩)
    private static class Job {
        Uri src; File temp; String name; long dateMs; String id;
    }
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private boolean working = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pending", MODE_PRIVATE);

        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.06f)
                .build());

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void js(final String code) {
        main.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void toast(final String m) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void record(final String name) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    startRecord(name);
                }
            });
        }

        @JavascriptInterface
        public void pickVideos() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("video/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(i, REQ_PICK);
                    } catch (Exception e) {
                        toast("영상 고르기를 열 수 없어요: " + e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void play(final String id) {
            final Uri u = picked.get(id);
            if (u == null) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "video/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("영상을 열 수 없어요");
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveTo(final String idsJson, final String name) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONArray a = new JSONArray(idsJson);
                        for (int k = 0; k < a.length(); k++) {
                            String id = a.getString(k);
                            Uri u = picked.get(id);
                            if (u == null) continue;
                            Job j = new Job();
                            j.src = u;
                            j.name = name;
                            j.id = id;
                            Long d = pickedDate.get(id);
                            j.dateMs = d != null ? d : System.currentTimeMillis();
                            jobs.add(j);
                        }
                        nextJob();
                    } catch (Exception e) {
                        toast("저장 준비 실패: " + e.getMessage());
                    }
                }
            });
        }
    }

    /* ====================== 촬영 ====================== */

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

            prefs.edit().putString("path", f.getAbsolutePath()).putString("name", name)
                    .putLong("at", System.currentTimeMillis()).apply();
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            toast("카메라를 열 수 없어요: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VIDEO) onVideoResult(resultCode, data);
        else if (requestCode == REQ_PICK) onPickResult(resultCode, data);
    }

    private void onVideoResult(int resultCode, Intent data) {
        String path = prefs.getString("path", null);
        final String name = prefs.getString("name", "");
        long at = prefs.getLong("at", System.currentTimeMillis());
        prefs.edit().clear().apply();
        if (path == null) return;
        final File f = new File(path);

        Job j = new Job();
        j.name = name;
        j.dateMs = at;
        if (f.exists() && f.length() > 0) {
            j.src = Uri.fromFile(f);
            j.temp = f;
        } else if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            // 카메라 앱이 우리 파일을 무시하고 자기 위치에 저장한 경우 → 그 영상을 가져와서 이름 붙여 저장
            f.delete();
            j.src = data.getData();
        } else {
            f.delete();
            web.evaluateJavascript("window.onNativeCanceled()", null);
            if (resultCode != RESULT_CANCELED) toast("촬영 파일이 만들어지지 않았어요 (코드 " + resultCode + ")");
            return;
        }
        toast("저장 중... (날짜 표시 넣는 중)");
        jobs.add(j);
        nextJob();
    }

    /* ====================== 날짜 넣어서 앨범에 저장 ====================== */

    private void nextJob() {
        if (working) return;
        final Job j = jobs.poll();
        if (j == null) return;
        working = true;
        try {
            stampAndSave(j);
        } catch (Throwable t) {
            // 날짜 넣기를 못 하면 원본 그대로라도 저장
            plainSave(j, "날짜 표시 실패: " + t.getMessage());
        }
    }

    private void jobDone(final Job j, final boolean ok) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (j.temp != null) j.temp.delete();
                if (j.id == null) {
                    if (ok) web.evaluateJavascript("window.onNativeSaved(" + JSONObject.quote(j.name) + ")", null);
                } else {
                    web.evaluateJavascript("window.onSortSaved(" + JSONObject.quote(j.id) + "," + ok + "," + jobs.size() + ")", null);
                }
                working = false;
                nextJob();
            }
        });
    }

    private void plainSave(final Job j, final String why) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(j.src);
                    copyToAlbum(in, j.name, j.dateMs);
                    if (why != null) toast(why + " → 원본 그대로 저장했어요");
                    jobDone(j, true);
                } catch (Exception e) {
                    toast("앨범 저장 실패: " + e.getMessage());
                    jobDone(j, false);
                }
            }
        });
    }

    private void stampAndSave(final Job j) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        int w = 1920, h = 1080, rot = 0, br = 0;
        try {
            r.setDataSource(this, j.src);
            w = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1920);
            h = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 1080);
            rot = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            br = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE), 0);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        int dispH = (rot % 180 == 0) ? h : w;
        int bitrate = br > 0 ? Math.max(4_000_000, Math.min(40_000_000, br)) : 12_000_000;

        String date = new SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREA).format(new Date(j.dateMs));
        Bitmap stamp = stampBitmap(date, dispH);
        OverlaySettings os = new OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0.96f, 0.95f)
                .setOverlayFrameAnchor(1f, 1f)
                .build();
        ImmutableList<TextureOverlay> overlays = ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(stamp, os));
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(j.src))
                .setEffects(new Effects(ImmutableList.of(), ImmutableList.of(new OverlayEffect(overlays))))
                .build();

        File dir = new File(getCacheDir(), "videos");
        if (!dir.exists()) dir.mkdirs();
        final File out = new File(dir, "stamp_" + System.nanoTime() + ".mp4");

        Transformer t = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    copyToAlbum(new FileInputStream(out), j.name, j.dateMs);
                                    out.delete();
                                    jobDone(j, true);
                                } catch (Exception e) {
                                    out.delete();
                                    toast("앨범 저장 실패: " + e.getMessage());
                                    jobDone(j, false);
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult, ExportException e) {
                        out.delete();
                        plainSave(j, "날짜 표시를 못 넣었어요");
                    }
                })
                .build();
        t.start(item, out.getAbsolutePath());
    }

    private Bitmap stampBitmap(String text, int frameH) {
        float size = Math.max(22f, frameH * 0.042f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Rect b = new Rect();
        p.getTextBounds(text, 0, text.length(), b);
        int pad = (int) (size * 0.35f);
        int bw = b.width() + pad * 2, bh = (int) (size * 1.35f) + pad;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgp.setColor(Color.argb(110, 0, 0, 0));
        c.drawRoundRect(new RectF(0, 0, bw, bh), pad, pad, bgp);
        float y = bh / 2f - (p.descent() + p.ascent()) / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.12f);
        p.setColor(Color.BLACK);
        c.drawText(text, pad - b.left, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawText(text, pad - b.left, y, p);
        return bmp;
    }

    private void copyToAlbum(InputStream in, String name, long dateMs) throws Exception {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date(dateMs));

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, safe + "_" + stamp + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + ROOT + "/" + safe);
        v.put(MediaStore.Video.Media.DATE_TAKEN, dateMs);
        v.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver cr = getContentResolver();
        Uri out = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("저장 위치를 만들지 못했어요");
        try {
            OutputStream os = cr.openOutputStream(out);
            if (os == null) throw new Exception("파일을 열지 못했어요");
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
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
    }

    /* ====================== 영상 분류: 가져오기 + 얼굴 찾기 ====================== */

    private void onPickResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final List<String> ids = new ArrayList<>();
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String id = "v" + (++pickSeq);
            picked.put(id, u);
            ids.add(id);
        }
        web.evaluateJavascript("window.onSortPicked(" + ids.size() + ")", null);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                for (int k = 0; k < ids.size(); k++) {
                    String id = ids.get(k);
                    String json;
                    try {
                        json = analyze(id, picked.get(id));
                    } catch (Throwable t) {
                        json = "{\"id\":" + JSONObject.quote(id) + ",\"err\":true}";
                    }
                    js("window.onSortInfo(" + json + ")");
                }
            }
        });
    }

    private String analyze(String id, Uri u) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("file", displayName(u));
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, u);
            long dur = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            int rot = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            int vw = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int vh = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            long date = parseVideoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            if (date <= 0) date = lastModified(u);
            if (date <= 0) date = System.currentTimeMillis();
            pickedDate.put(id, date);
            o.put("ms", date);
            o.put("date", new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(new Date(date)));
            o.put("dur", dur);

            JSONArray faces = new JSONArray();
            Bitmap firstFrame = null;
            double[] at = {0.12, 0.35, 0.6, 0.85};
            for (double f : at) {
                long t = (long) (dur * f * 1000);
                Bitmap fr = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720);
                if (fr == null) continue;
                fr = upright(fr, rot, vw, vh);
                if (firstFrame == null) firstFrame = fr;
                List<Face> fs = Tasks.await(detector.process(InputImage.fromBitmap(fr, 0)));
                Face big = null;
                for (Face fc : fs) {
                    if (big == null || area(fc.getBoundingBox()) > area(big.getBoundingBox())) big = fc;
                }
                if (big != null) faces.put(b64(cropFace(fr, big.getBoundingBox(), 220)));
                if (faces.length() >= 3) break;
            }
            o.put("faces", faces);
            if (faces.length() == 0 && firstFrame != null) o.put("thumb", b64(scaleTo(firstFrame, 260)));
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    private Bitmap upright(Bitmap fr, int rot, int vw, int vh) {
        if (rot % 360 == 0 || vw == 0 || vh == 0) return fr;
        boolean frameLandscape = fr.getWidth() > fr.getHeight();
        boolean rawLandscape = vw > vh;
        if ((rot % 180 != 0) && frameLandscape == rawLandscape) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            return Bitmap.createBitmap(fr, 0, 0, fr.getWidth(), fr.getHeight(), m, true);
        }
        return fr;
    }

    private static int area(Rect r) {
        return Math.max(0, r.width()) * Math.max(0, r.height());
    }

    private Bitmap cropFace(Bitmap src, Rect box, int out) {
        int cx = box.centerX(), cy = box.centerY();
        int s = (int) (Math.max(box.width(), box.height()) * 1.7f);
        int x0 = Math.max(0, cx - s / 2), y0 = Math.max(0, cy - s / 2);
        int x1 = Math.min(src.getWidth(), x0 + s), y1 = Math.min(src.getHeight(), y0 + s);
        if (x1 - x0 < 8 || y1 - y0 < 8) return scaleTo(src, out);
        Bitmap c = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
        return scaleTo(c, out);
    }

    private Bitmap scaleTo(Bitmap b, int max) {
        float k = (float) max / Math.max(b.getWidth(), b.getHeight());
        if (k >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
    }

    private String b64(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 78, bo);
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "";
    }

    private long lastModified(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0;
    }

    private static long parseVideoDate(String s) {
        if (s == null || s.length() < 15 || s.startsWith("1904") || s.startsWith("1970")) return 0;
        String[] fmts = {"yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss"};
        for (String f : fmts) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
