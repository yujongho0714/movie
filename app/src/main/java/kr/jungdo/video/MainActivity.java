package kr.jungdo.video;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
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
import android.util.Size;
import android.Manifest;
import android.os.Build;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
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
import java.util.Collections;
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
    private static final int REQ_MEDIA = 3;
    private static final String KAKAO = "com.kakao.talk";

    // 카톡 보내기용으로 보여준 영상들 (id -> uri, 종류)
    private final Map<String, Uri> shareUris = new HashMap<>();
    private final Map<String, String> shareMime = new HashMap<>();
    private static final String ROOT = "정도태권도2관";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService lister = Executors.newSingleThreadExecutor(); // 카톡 보내기 목록 읽기 (저장 작업과 따로)
    private FaceDetector detector;

    // 분류용으로 가져온 영상들 (id -> uri)
    private final Map<String, Uri> picked = new HashMap<>();
    private final Map<String, Long> pickedDate = new HashMap<>();
    private int pickSeq = 0;

    // 날짜를 넣어서 저장하는 작업 줄 (한 번에 하나씩)
    private static class Job {
        Uri src; File temp; String name; long dateMs; String id; boolean replace;
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
        web.addJavascriptInterface(new KakaoBridge(), "Kakao");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("취소", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        checkUpdate();
    }

    /* ====================== 자동 업데이트 (깃허브 Releases) ====================== */

    private static final String REPO = "yujongho0714/movie";
    private File updateFile;

    private long myBuild() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (Exception e) { return 0; }
    }

    /** 앱을 켤 때 새 버전이 있는지 확인 → 있으면 '업데이트할까요?' */
    private void checkUpdate() {
        lister.execute(new Runnable() {
            @Override public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    if (c.getResponseCode() != 200) return;
                    String body = new String(readAll(c.getInputStream()), "UTF-8");
                    JSONObject o = new JSONObject(body);
                    long latest = Long.parseLong(("0" + o.optString("tag_name", "v0").replaceAll("[^0-9]", "")));
                    if (latest <= myBuild()) return;
                    JSONArray assets = o.optJSONArray("assets");
                    String url = null;
                    if (assets != null) for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.getJSONObject(k);
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                    }
                    if (url == null) return;
                    final String dl = url; final long ver = latest;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("새 버전 v" + ver + " 이 있어요")
                                    .setMessage("지금 업데이트할까요? (명단·촬영 기록은 그대로 남아요)")
                                    .setPositiveButton("업데이트", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) { downloadUpdate(dl); }
                                    })
                                    .setNegativeButton("나중에", null)
                                    .show();
                        }
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private void downloadUpdate(final String url) {
        toast("새 버전 받는 중…");
        lister.execute(new Runnable() {
            @Override public void run() {
                try {
                    String u = url;
                    java.net.HttpURLConnection c = null;
                    for (int hop = 0; hop < 5; hop++) {   // 깃허브 → 다운로드 서버로 넘어가는 주소 따라가기
                        c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000); c.setReadTimeout(60000);
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { u = c.getHeaderField("Location"); c.disconnect(); continue; }
                        break;
                    }
                    File dir = new File(getCacheDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "jungdo-video.apk");
                    try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    updateFile = f;
                    main.post(new Runnable() { @Override public void run() { installUpdate(); } });
                } catch (Exception e) {
                    toast("업데이트를 받지 못했어요: " + e.getMessage());
                }
            }
        });
    }

    private void installUpdate() {
        if (updateFile == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("'이 출처 허용'을 켜고 돌아오면 바로 설치돼요");
            prefs.edit().putBoolean("installAfterPerm", true).apply();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", updateFile);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("설치 화면을 열 수 없어요: " + e.getMessage()); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getBoolean("installAfterPerm", false) && getPackageManager().canRequestPackageInstalls()) {
            prefs.edit().remove("installAfterPerm").apply();
            installUpdate();
        }
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
        public void record2(final String name, final boolean replace) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putBoolean("replace", replace).apply();
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

    /* ====================== 카톡 보내기 ====================== */

    private class KakaoBridge {
        /** 갤러리 읽기 권한 있는지 (직접 옮긴 영상까지 보려면 필요) */
        @JavascriptInterface
        public boolean hasMediaPerm() { return mediaPermOk(); }

        @JavascriptInterface
        public void askMediaPerm() {
            main.post(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33)
                        requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES}, REQ_MEDIA);
                    else
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
                }
            });
        }

        /**
         * 갤러리 전체에서 명단 이름이 붙은 폴더(또는 '이름_날짜' 파일)를 찾아 아이별로 모음 → window.onKakaoKids([...])
         * namesJson = 명단 이름들 ["김시우", ...]
         */
        @JavascriptInterface
        public void listKids(final String namesJson) {
            lister.execute(new Runnable() {
                @Override public void run() {
                    try { setRoster(namesJson); js("window.onKakaoKids(" + kidsJson() + ")"); }
                    catch (Exception e) { toast("영상 목록을 못 읽었어요: " + e.getMessage()); js("window.onKakaoKids([])"); }
                }
            });
        }

        /** 한 아이 폴더의 영상(사진) 목록 + 작은 미리보기 → window.onKakaoMedia(name, [...]) */
        @JavascriptInterface
        public void listMedia(final String name) {
            lister.execute(new Runnable() {
                @Override public void run() {
                    try { js("window.onKakaoMedia(" + JSONObject.quote(name) + "," + mediaJson(name) + ")"); }
                    catch (Exception e) { toast("영상을 못 읽었어요: " + e.getMessage()); js("window.onKakaoMedia(" + JSONObject.quote(name) + ",[])"); }
                }
            });
        }

        @JavascriptInterface
        public void playMedia(final String id) {
            final Uri u = shareUris.get(id);
            if (u == null) return;
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, shareMime.containsKey(id) ? shareMime.get(id) : "video/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) { toast("열 수 없어요"); }
                }
            });
        }

        @JavascriptInterface
        public void frameAt(String id, long ms) { frameAtImpl(id, ms); }

        /** 구간 자르기·음소거 해서 보내기 (완료되면 window.onKakaoExportDone) */
        @JavascriptInterface
        public boolean shareKakaoEdited(String itemsJson, boolean mute) {
            try { shareEditedImpl(itemsJson, mute); return true; }
            catch (Exception e) { toast("보내기 준비 실패: " + e.getMessage()); return false; }
        }

        /** 고른 영상들을 카카오톡으로 보내기 (카톡이 없으면 공유 목록) */
        @JavascriptInterface
        public boolean shareKakao(final String idsJson) {
            try {
                JSONArray a = new JSONArray(idsJson);
                final ArrayList<Uri> uris = new ArrayList<>();
                boolean img = false, vid = false;
                for (int k = 0; k < a.length(); k++) {
                    String id = a.getString(k);
                    Uri u = shareUris.get(id);
                    if (u == null) continue;
                    uris.add(u);
                    String m = shareMime.get(id);
                    if (m != null && m.startsWith("image")) img = true; else vid = true;
                }
                if (uris.isEmpty()) return false;
                final String type = img && vid ? "*/*" : (img ? "image/*" : "video/*");
                main.post(new Runnable() {
                    @Override public void run() { sendToKakao(uris, type); }
                });
                return true;
            } catch (Exception e) {
                toast("보내기 실패: " + e.getMessage());
                return false;
            }
        }
    }

    private boolean mediaPermOk() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA) js("window.onMediaPerm(" + mediaPermOk() + ")");
    }

    /* ---------- ✂️ 구간 자르기 + 🔇 음소거 해서 보내기 ---------- */

    private MediaMetadataRetriever frameR; private String frameRid; private int frameRot, frameW, frameH;

    /** 구간 고를 때 미리보기 그림: 그 시각(ms)의 화면 → window.onKakaoFrame(id, ms, 그림) */
    private void frameAtImpl(final String id, final long ms) {
        lister.execute(new Runnable() {
            @Override public void run() {
                try {
                    Uri u = shareUris.get(id);
                    if (u == null) return;
                    if (!id.equals(frameRid)) {
                        if (frameR != null) try { frameR.release(); } catch (Exception ignored) {}
                        frameR = new MediaMetadataRetriever();
                        frameR.setDataSource(MainActivity.this, u);
                        frameRid = id;
                        frameRot = parseInt(frameR.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
                        frameW = parseInt(frameR.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                        frameH = parseInt(frameR.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                    }
                    Bitmap fr = frameR.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640);
                    if (fr == null) fr = frameR.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640);
                    if (fr == null) return;
                    fr = upright(fr, frameRot, frameW, frameH);
                    js("window.onKakaoFrame(" + JSONObject.quote(id) + "," + ms + "," + JSONObject.quote(b64(fr)) + ")");
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * items = [{id, s(시작 ms), e(끝 ms, 0이면 끝까지)}], mute = 소리 빼기
     * 한 개씩 새 영상으로 만든 뒤 한꺼번에 카톡으로 보냄. 원본은 그대로 둠
     */
    private void shareEditedImpl(String itemsJson, final boolean mute) throws Exception {
        JSONArray a = new JSONArray(itemsJson);
        final ArrayDeque<JSONObject> q = new ArrayDeque<>();
        for (int k = 0; k < a.length(); k++) q.add(a.getJSONObject(k));
        final File dir = new File(getCacheDir(), "share");
        if (dir.exists()) { File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete(); } else dir.mkdirs();
        final ArrayList<Uri> outs = new ArrayList<>();
        final int total = q.size();
        main.post(new Runnable() { @Override public void run() { exportNext(q, outs, dir, mute, total); } });
    }

    private void exportNext(final ArrayDeque<JSONObject> q, final ArrayList<Uri> outs, final File dir, final boolean mute, final int total) {
        final JSONObject it = q.poll();
        if (it == null) {
            if (outs.isEmpty()) { js("window.onKakaoExportDone(false)"); return; }
            sendToKakao(outs, "video/*");
            js("window.onKakaoExportDone(true)");
            return;
        }
        js("window.onKakaoExportProgress(" + (total - q.size()) + "," + total + ")");
        try {
            String id = it.getString("id");
            Uri src = shareUris.get(id);
            String mime = shareMime.get(id);
            if (src == null) { exportNext(q, outs, dir, mute, total); return; }
            if (mime != null && mime.startsWith("image")) {           // 사진은 그대로
                outs.add(src); exportNext(q, outs, dir, mute, total); return;
            }
            long sMs = it.optLong("s", 0), eMs = it.optLong("e", 0);
            MediaItem.Builder mb = new MediaItem.Builder().setUri(src);
            if (sMs > 0 || eMs > 0) {
                MediaItem.ClippingConfiguration.Builder cb = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Math.max(0, sMs));
                if (eMs > sMs) cb.setEndPositionMs(eMs);
                mb.setClippingConfiguration(cb.build());
            }
            // 음소거: 소리 트랙을 아예 빼면 카톡이 영상 처리에서 멈추는 경우가 있어서, 트랙은 두고 소리만 0으로 만듦
            EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mb.build());
            if (mute) {
                ChannelMixingAudioProcessor silent = new ChannelMixingAudioProcessor();
                for (int ch = 1; ch <= 8; ch++) silent.putChannelMixingMatrix(new ChannelMixingMatrix(ch, ch, new float[ch * ch]));
                eb.setEffects(new Effects(Collections.<AudioProcessor>singletonList(silent), Collections.<androidx.media3.common.Effect>emptyList()));
            }
            EditedMediaItem item = eb.build();
            String nm = it.optString("name", "video").replaceAll("[\\\\/:*?\"<>|]", "_");
            final File out = new File(dir, nm + "_" + (total - q.size()) + ".mp4");
            Transformer t = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition, ExportResult exportResult) {
                            Uri fu = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out);
                            // 카톡이 채팅방 고른 뒤 뒤에서 올릴 때도 읽을 수 있게 따로 허락 (안 하면 '전송 중'에서 멈춤)
                            try { grantUriPermission(KAKAO, fu, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                            outs.add(fu);
                            exportNext(q, outs, dir, mute, total);
                        }
                        @Override public void onError(Composition composition, ExportResult exportResult, ExportException e) {
                            toast("영상 하나를 만들지 못했어요: " + e.getMessage());
                            exportNext(q, outs, dir, mute, total);
                        }
                    })
                    .build();
            t.start(item, out.getAbsolutePath());
        } catch (Exception e) {
            toast("영상 만들기 실패: " + e.getMessage());
            exportNext(q, outs, dir, mute, total);
        }
    }

    private void sendToKakao(ArrayList<Uri> uris, String type) {
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(type);
        ClipData cd = ClipData.newRawUri("media", uris.get(0));
        for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent kakao = new Intent(i).setPackage(KAKAO);
        try {
            startActivity(kakao);
        } catch (Exception e) {
            try { startActivity(Intent.createChooser(i, "보낼 앱 고르기")); }
            catch (Exception e2) { toast("보낼 수 있는 앱이 없어요"); }
        }
    }

    private static final String[] MEDIA_COLS = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
    };
    /** 영상만 (사진은 빼고) */
    private static final String VIDEO_ONLY = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

    private final java.util.Set<String> roster = new java.util.HashSet<>();

    private void setRoster(String namesJson) {
        try {
            JSONArray a = new JSONArray(namesJson);
            synchronized (roster) {
                roster.clear();
                for (int k = 0; k < a.length(); k++) { String n = a.optString(k, "").trim(); if (n.length() >= 2) roster.add(n); }
            }
        } catch (Exception ignored) {}
    }

    private long takenMs(Cursor c) {
        long t = c.isNull(3) ? 0 : c.getLong(3);
        if (t <= 0 && !c.isNull(4)) t = c.getLong(4) * 1000L;
        return t;
    }

    private static boolean isHangul(char c) { return c >= '가' && c <= '힣'; }

    /** 글자 안에 이름이 '따로' 들어 있는지 (김시우 ≠ 김시우진). 앞뒤가 한글이 아니면 OK */
    private static boolean hasWord(String text, String name) {
        int from = 0;
        while (true) {
            int k = text.indexOf(name, from);
            if (k < 0) return false;
            boolean before = k == 0 || !isHangul(text.charAt(k - 1));
            int e = k + name.length();
            boolean after = e >= text.length() || !isHangul(text.charAt(e));
            if (before && after) return true;
            from = k + 1;
        }
    }

    /**
     * 이 영상이 누구 것인지: 폴더 이름(경로의 어느 단계든) 또는 파일 이름에 명단 이름이 있으면 그 아이.
     * 폴더 이름이 명단 이름과 '똑같으면' 그 아이만 (김시우(유치부) 폴더 → 김시우(유치부)만, 김시우는 아님)
     */
    private java.util.Set<String> ownersOf(String rel, String display) {
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.Set<String> names;
        synchronized (roster) { names = new java.util.HashSet<>(roster); }
        if (names.isEmpty()) return out;
        if (rel != null) for (String seg : rel.split("/")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            if (names.contains(seg)) { out.add(seg); continue; }
            for (String n : names) if (hasWord(seg, n)) out.add(n);
        }
        if (out.isEmpty() && display != null) {
            String base = display.contains(".") ? display.substring(0, display.lastIndexOf('.')) : display;
            String head = base.contains("_") ? base.substring(0, base.indexOf('_')) : base;
            if (names.contains(head)) out.add(head);
            else for (String n : names) if (hasWord(base, n)) out.add(n);
        }
        return out;
    }

    private String kidsJson() throws Exception {
        Map<String, long[]> m = new HashMap<>(); // 이름 -> [개수, 마지막 촬영 ms]
        Uri base = MediaStore.Files.getContentUri("external");
        try (Cursor c = getContentResolver().query(base, MEDIA_COLS, VIDEO_ONLY, null, null)) {
            if (c != null) while (c.moveToNext()) {
                for (String n : ownersOf(c.getString(1), c.getString(8))) {
                    long[] v = m.get(n);
                    if (v == null) { v = new long[]{0, 0}; m.put(n, v); }
                    v[0]++;
                    v[1] = Math.max(v[1], takenMs(c));
                }
            }
        }
        JSONArray a = new JSONArray();
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            JSONObject o = new JSONObject();
            o.put("n", e.getKey()); o.put("cnt", e.getValue()[0]); o.put("last", e.getValue()[1]);
            a.put(o);
        }
        return a.toString();
    }

    private String mediaJson(String name) throws Exception {
        Uri base = MediaStore.Files.getContentUri("external");
        SimpleDateFormat df = new SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA);
        // 이름 글자(괄호 앞)가 경로나 파일 이름에 들어 있는 영상만 먼저 추린 뒤, 정확히 다시 확인
        String key = name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
        String sel = VIDEO_ONLY + " AND (" + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)";
        String like = "%" + key + "%";
        List<JSONObject> list = new ArrayList<>();
        try (Cursor c = getContentResolver().query(base, MEDIA_COLS, sel, new String[]{like, like}, null)) {
            if (c != null) while (c.moveToNext()) {
                if (!ownersOf(c.getString(1), c.getString(8)).contains(name)) continue;
                long id = c.getLong(0);
                Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                String k = "k" + id;
                shareUris.put(k, u);
                shareMime.put(k, c.isNull(7) ? "video/mp4" : c.getString(7));
                long t = takenMs(c);
                JSONObject o = new JSONObject();
                o.put("id", k); o.put("ms", t); o.put("date", t > 0 ? df.format(new Date(t)) : "");
                o.put("video", true);
                o.put("dur", c.isNull(5) ? 0 : c.getLong(5));
                o.put("mb", c.isNull(6) ? 0 : Math.round(c.getLong(6) / 1048576.0 * 10) / 10.0);
                String rel = c.getString(1);
                o.put("folder", rel == null ? "" : rel.replaceAll("/+$", ""));
                list.add(o);
            }
        }
        // 찍은 시간 최신순, 미리보기는 최근 60개까지만 (느려지지 않게)
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject x, JSONObject y) { return Long.compare(y.optLong("ms"), x.optLong("ms")); }
        });
        for (int k = 0; k < list.size() && k < 60; k++) {
            JSONObject o = list.get(k);
            try {
                Bitmap th = getContentResolver().loadThumbnail(shareUris.get(o.getString("id")), new Size(240, 240), null);
                o.put("thumb", b64(th));
            } catch (Exception ignored) {}
        }
        return new JSONArray(list).toString();
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
        boolean replace = prefs.getBoolean("replace", false);
        prefs.edit().clear().apply();
        if (path == null) return;
        final File f = new File(path);

        Job j = new Job();
        j.name = name;
        j.dateMs = at;
        j.replace = replace;
        if (resultCode != RESULT_OK) {
            // 촬영 도중 멈추거나 뒤로 가기/취소 → 저장하지 않음
            f.delete();
            web.evaluateJavascript("window.onNativeCanceled()", null);
            return;
        }
        if (f.exists() && f.length() > 0) {
            j.src = Uri.fromFile(f);
            j.temp = f;
        } else if (data != null && data.getData() != null) {
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
        if (j.id != null) {
            // 영상 분류(업로드)로 정리하는 경우: 날짜 표시 없이 원본 그대로 복사 저장
            plainSave(j, null);
            return;
        }
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
                    if (ok) web.evaluateJavascript("window.onNativeSaved(" + JSONObject.quote(j.name) + "," + j.replace + ")", null);
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
                    afterShotSaved(j, copyToAlbum(in, j.name, j.dateMs));
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
                                    afterShotSaved(j, copyToAlbum(new FileInputStream(out), j.name, j.dateMs));
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

    private Uri copyToAlbum(InputStream in, String name, long dateMs) throws Exception {
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
        return out;
    }

    // 촬영 저장 후: 다시 찍기면 그 이름으로 바로 전에 저장한 영상을 지움 (이 앱이 만든 영상만)
    private void afterShotSaved(Job j, Uri saved) {
        if (j.id != null || saved == null) return;
        SharedPreferences sp = getSharedPreferences("saved", MODE_PRIVATE);
        String k = "last_" + j.name;
        String prev = sp.getString(k, null);
        if (j.replace && prev != null && !prev.equals(saved.toString())) {
            try { getContentResolver().delete(Uri.parse(prev), null, null); } catch (Exception ignored) {}
        }
        sp.edit().putString(k, saved.toString()).apply();
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
