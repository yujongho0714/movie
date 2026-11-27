/*
  © 2026 유종호 (용인대 정도 태권도 2관). All rights reserved.
  심사 영상 촬영 화면 — 앱 안에서 직접 찍어요 (노래를 같이 틀 수 있게)
  · 녹화를 누르면 고른 노래도 같이 시작 (블루투스 스피커로 나가요)
  · 다 찍고 [완료 · 저장]을 눌러야만 저장 · 취소 · 뒤로 가기 · 화면 꺼짐은 저장 안 함
  · 여러 명(2~4명) 같이 찍기: 아이마다 칸(이름 표시)을 끌어서 옮기고 모서리로 크기 조절 → 저장할 때 칸마다 따로 잘라서 저장
*/
package kr.jungdo.video;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;

import java.io.File;
import java.util.Locale;

public class RecordActivity extends ComponentActivity {
    public static final String EX_PATH = "path", EX_NAME = "name", EX_MODE = "mode", EX_SONG = "song", EX_SONGNAME = "songName", EX_NAMES = "names", EX_RECTS = "rects", EX_IMPORT = "import", EX_JUDGE = "judge", EX_MISS = "miss", EX_SONGOFS = "songOfs";

    private PreviewView preview;
    private TextView title, timer, songBtn, recBtn, flipBtn, hint;
    private LinearLayout doneBar;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ProcessCameraProvider provider;
    private boolean back = true, discard = false, hasClip = false, songOn = true;
    private File out;
    private String songUri, songName;
    private MediaPlayer player;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private long startMs;
    private String[] names = new String[0];
    private Grid grid;
    private String importUri;
    private RopeJudge judge;
    private TextView judgePill;
    private final java.util.concurrent.ExecutorService anEx = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (recording == null) return;
            long s = (System.currentTimeMillis() - startMs) / 1000;
            timer.setText(String.format(Locale.KOREA, "● %02d:%02d", s / 60, s % 60));
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Intent it = getIntent();
        out = new File(it.getStringExtra(EX_PATH));
        songUri = it.getStringExtra(EX_SONG);
        songName = it.getStringExtra(EX_SONGNAME);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try { JSONArray a = new JSONArray(it.getStringExtra(EX_NAMES) == null ? "[]" : it.getStringExtra(EX_NAMES));
            importUri = it.getStringExtra(EX_IMPORT);
            if (a.length() > (importUri != null ? 0 : 1)) { names = new String[Math.min(4, a.length())]; for (int k = 0; k < names.length; k++) names[k] = a.getString(k); } } catch (Exception ignored) {}
        if (importUri != null) {
            buildImportUi(it.getStringExtra(EX_MODE));
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { setResult(RESULT_CANCELED); finish(); } });
            return;
        }
        if (it.getBooleanExtra(EX_JUDGE, false)) {
            final int kn = Math.max(1, names.length);
            judge = new RopeJudge(kn, () -> grid != null ? grid.rectsArr(!back) : new float[][]{{0, 0, 1, 1}}, (m, j) -> {
                if (grid != null) { for (int k = 0; k < kn && k < grid.extra.length; k++) grid.extra[k] = "걸림 " + m[k] + " · " + j[k] + "개"; grid.invalidate(); }
                if (judgePill != null && grid == null) judgePill.setText("실시간 판정 · 걸림 " + m[0] + "번 · " + j[0] + "개");
            });
        }
        buildUi(it.getStringExtra(EX_NAME), it.getStringExtra(EX_MODE));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { askCancel(); }
        });
        String[] need = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : need) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) startCamera(); else requestPermissions(need, 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) startCamera();
        else { Toast.makeText(this, "카메라 · 마이크 권한이 있어야 찍을 수 있어요", Toast.LENGTH_LONG).show(); cancelOut(); }
    }

    /* ---------------- 화면 ---------------- */
    private TextView pill(String t, int bg, float size) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(Color.WHITE); v.setTextSize(size); v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(dp(14)); g.setStroke(dp(2), 0xFF0B1020);
        v.setBackground(g);
        return v;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi(String name, String mode) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        if (names.length > 1) preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);   // 화면에 보이는 그대로 = 녹화되는 그대로 (칸 위치가 정확하게)
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        if (names.length > 1) {
            grid = new Grid(this, names, gridPrefs().getString(gridKey(), null));
            grid.onTouch = () -> hint.setVisibility(View.GONE);
            root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(12), dp(12), dp(12), dp(12));
        title = pill(("jump".equals(mode) ? "줄넘기 심사 · " : "태권도 심사 · ") + (names.length > 1 ? names.length + "명 같이" : name), 0xCC14213D, 16);
        top.addView(title);
        View sp = new View(this); top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        timer = pill("대기", 0xCC14213D, 15);
        top.addView(timer);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        hint = pill(names.length > 1 ? "폰을 가로로 돌려도 돼요 · 칸은 끌어서 옮기고 점으로 크기 조절 · 위아래는 모든 칸 같이" : "빨간 버튼을 누르면 녹화" + (songUri != null ? "와 노래가 같이 시작돼요" : "가 시작돼요"), 0x99000000, 13);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP); hl.topMargin = dp(70);
        root.addView(hint, hl);
        if (judge != null && grid == null) {
            judgePill = pill("실시간 판정 · 녹화하면 걸린 횟수를 세요", 0xCC8B55E0, 15);
            FrameLayout.LayoutParams jl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP); jl.topMargin = dp(118);
            root.addView(judgePill, jl);
        }

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL); bot.setGravity(Gravity.CENTER); bot.setPadding(dp(12), dp(12), dp(12), dp(22));
        songBtn = pill(songUri != null ? "노래 켬 · " + shorten(songName) : "노래 없음", songUri != null ? 0xFF8B55E0 : 0xFF55607A, 13);
        songBtn.setOnClickListener(v -> {
            if (songUri == null) { Toast.makeText(this, "촬영 목록 화면에서 노래를 먼저 골라 주세요", Toast.LENGTH_SHORT).show(); return; }
            if (recording != null) return;
            songOn = !songOn;
            songBtn.setText(songOn ? "노래 켬 · " + shorten(songName) : "노래 끔");
            ((GradientDrawable) songBtn.getBackground()).setColor(songOn ? 0xFF8B55E0 : 0xFF55607A);
        });
        recBtn = pill("녹화", 0xFFE24850, 20);
        recBtn.setPadding(dp(30), dp(16), dp(30), dp(16));
        recBtn.setOnClickListener(v -> { if (recording == null) startRec(); else stopRec(); });
        flipBtn = pill("카메라 전환", 0xFF55607A, 13);
        flipBtn.setOnClickListener(v -> { if (recording != null) return; back = !back; startCamera(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(8), 0, dp(8), 0);
        bot.addView(songBtn, lp); bot.addView(recBtn, lp); bot.addView(flipBtn, lp);
        if (grid != null) {
            TextView reset = pill("칸 다시 나누기", 0xFF55607A, 13);
            reset.setOnClickListener(v -> { if (recording == null) grid.reset(); });
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER);
            bot.removeView(flipBtn); col.addView(flipBtn); LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2); l2.topMargin = dp(6); col.addView(reset, l2);
            bot.addView(col, lp);
        }
        root.addView(bot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        doneBar = new LinearLayout(this);
        doneBar.setOrientation(LinearLayout.HORIZONTAL); doneBar.setGravity(Gravity.CENTER); doneBar.setPadding(dp(12), dp(12), dp(12), dp(22));
        doneBar.setBackgroundColor(0xDD0B1020);
        TextView ok = pill("완료 · 저장", 0xFF38B75C, 18), again = pill("다시 찍기", 0xFF3A8FE0, 15), cancel = pill("취소", 0xFF55607A, 15);
        ok.setOnClickListener(v -> finishOk());
        again.setOnClickListener(v -> { deleteOut(); hasClip = false; showDone(false); hint.setText("다시 찍어요 · 빨간 버튼을 누르세요"); hint.setVisibility(View.VISIBLE); });
        cancel.setOnClickListener(v -> askCancel());
        doneBar.addView(ok, lp); doneBar.addView(again, lp); doneBar.addView(cancel, lp);
        doneBar.setVisibility(View.GONE);
        root.addView(doneBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }
    private static String shorten(String s) { if (s == null) return ""; s = s.replaceAll("\\.[A-Za-z0-9]+$", ""); return s.length() > 12 ? s.substring(0, 12) + "…" : s; }
    private void showDone(boolean on) {
        doneBar.setVisibility(on ? View.VISIBLE : View.GONE);
        ((View) recBtn.getParent()).setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) timer.setText("다 찍었어요");
        else timer.setText("대기");
    }

    /* ---------------- 이미 찍은 영상 가져오기: 장면 보면서 아이 칸 잡기 ---------------- */
    private android.widget.ImageView frameView;
    private long durMs = 0;
    private void buildImportUi(String mode) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        frameView = new android.widget.ImageView(this);
        frameView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        root.addView(frameView, new FrameLayout.LayoutParams(-1, -1));
        grid = new Grid(this, names, null);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(12), dp(12), dp(12), dp(12));
        title = pill(("jump".equals(mode) ? "줄넘기 심사 · " : "태권도 심사 · ") + "가져온 영상 · " + names.length + "명", 0xCC14213D, 15);
        top.addView(title);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        hint = pill("아이마다 칸을 맞춰 주세요 · 아래 막대로 장면을 넘겨 볼 수 있어요", 0x99000000, 13);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP); hl.topMargin = dp(70);
        root.addView(hint, hl);
        grid.onTouch = () -> hint.setVisibility(View.GONE);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.VERTICAL); bot.setPadding(dp(12), dp(8), dp(12), dp(18)); bot.setBackgroundColor(0xAA0B1020);
        android.widget.SeekBar sb = new android.widget.SeekBar(this); sb.setMax(1000);
        bot.addView(sb, new LinearLayout.LayoutParams(-1, dp(36)));
        LinearLayout btns = new LinearLayout(this); btns.setGravity(Gravity.CENTER);
        TextView ok = pill("완료 · 저장", 0xFF38B75C, 17), reset = pill("칸 다시 나누기", 0xFF55607A, 13), cancel = pill("취소", 0xFF55607A, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(6), dp(6), dp(6), 0);
        btns.addView(ok, lp); btns.addView(reset, lp); btns.addView(cancel, lp);
        bot.addView(btns);
        root.addView(bot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        ok.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EX_RECTS, grid.videoRects(false)));
            finish();
        });
        reset.setOnClickListener(v -> grid.reset());
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        final java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        final long[] want = {0};
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) { if (u && durMs > 0) { want[0] = durMs * p / 1000; loadFrame(ex, want); } }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        want[0] = -1; loadFrame(ex, want);
    }
    private boolean frameBusy = false;
    private void loadFrame(java.util.concurrent.ExecutorService ex, long[] want) {
        if (frameBusy) return; frameBusy = true;
        final long at = want[0];
        ex.execute(() -> {
            android.graphics.Bitmap bm = null;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(this, Uri.parse(importUri));
                if (durMs == 0) { try { durMs = Long.parseLong(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)); } catch (Exception ignored) {} }
                long t = at < 0 ? Math.min(1500, durMs / 3) : at;
                bm = r.getScaledFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280);
            } catch (Exception ignored) {} finally { try { r.release(); } catch (Exception ignored) {} }
            final android.graphics.Bitmap f = bm;
            h.post(() -> {
                frameBusy = false;
                if (f != null) { frameView.setImageBitmap(f); grid.aspect = f.getWidth() / (float) f.getHeight(); grid.invalidate(); }
                else if (at < 0) Toast.makeText(this, "영상을 열 수 없어요", Toast.LENGTH_LONG).show();
                if (want[0] != at && want[0] >= 0) loadFrame(ex, want);
            });
        });
    }

    /* ---------------- 카메라 ---------------- */
    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                provider.unbindAll();
                @SuppressWarnings("deprecation") Preview pv = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                Recorder rec = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build();
                videoCapture = VideoCapture.withOutput(rec);
                CameraSelector cs = back ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                boolean bound = false;
                if (judge != null) {
                    try {
                        ImageAnalysis an = new ImageAnalysis.Builder()
                                .setResolutionSelector(new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                        .setResolutionStrategy(new androidx.camera.core.resolutionselector.ResolutionStrategy(new android.util.Size(1280, 720),
                                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build();
                        an.setAnalyzer(anEx, judge);
                        provider.bindToLifecycle(this, cs, pv, videoCapture, an);
                        bound = true;
                    } catch (Exception e2) {
                        provider.unbindAll();
                        Toast.makeText(this, "이 폰은 찍으면서 판정을 같이 못 해요 · 판정 없이 찍어요", Toast.LENGTH_LONG).show();
                        judge = null; if (judgePill != null) judgePill.setVisibility(View.GONE);
                    }
                }
                if (!bound) provider.bindToLifecycle(this, cs, pv, videoCapture);
                h.post(this::prepSong);
            } catch (Exception e) {
                Toast.makeText(this, "카메라를 열 수 없어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
                cancelOut();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("MissingPermission")
    private void startRec() {
        if (videoCapture == null) return;
        deleteOut();
        discard = false;
        FileOutputOptions o = new FileOutputOptions.Builder(out).build();
        try {
            recording = videoCapture.getOutput().prepareRecording(this, o).withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this), ev -> {
                        if (ev instanceof VideoRecordEvent.Start) {
                            startMs = System.currentTimeMillis();
                            if (judge != null) judge.start();
                            h.post(tick);
                            startSong();
                        } else if (ev instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize fz = (VideoRecordEvent.Finalize) ev;
                            recording = null;
                            h.removeCallbacks(tick);
                            stopSong();
                            if (discard) { deleteOut(); return; }
                            if (fz.hasError() && fz.getError() != VideoRecordEvent.Finalize.ERROR_NONE && !out.exists()) {
                                Toast.makeText(this, "녹화가 저장되지 않았어요 (" + fz.getError() + ")", Toast.LENGTH_LONG).show();
                                showDone(false); return;
                            }
                            hasClip = out.exists() && out.length() > 0;
                            if (hasClip) { showDone(true); hint.setText("[완료 · 저장]을 눌러야 저장돼요"); hint.setVisibility(View.VISIBLE); }
                            else showDone(false);
                        }
                    });
            if (grid != null) { grid.locked = true; grid.invalidate(); }
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            recBtn.setText("멈춤");
            ((GradientDrawable) recBtn.getBackground()).setColor(0xFF2A3044);
            flipBtn.setVisibility(View.INVISIBLE);
            hint.setVisibility(View.GONE);
        } catch (Exception e) {
            Toast.makeText(this, "녹화를 시작할 수 없어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void stopRec() {
        if (recording != null) recording.stop();
        if (judge != null) judge.stop();
        if (grid != null) { grid.locked = false; grid.invalidate(); }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        recBtn.setText("녹화");
        ((GradientDrawable) recBtn.getBackground()).setColor(0xFFE24850);
        flipBtn.setVisibility(View.VISIBLE);
    }

    /* ---------------- 노래 (블루투스 스피커로도 나가요) ---------------- */
    /* 노래는 미리 준비해 두고 녹화가 시작되는 순간 바로 틀어요 · 실제로 틀어진 시각을 재서 저장할 때 영상과 맞춰요 */
    private long songAt = 0;
    private boolean prepared = false;
    private void prepSong() {
        if (songUri == null || !songOn || (player != null && prepared)) return;
        try {
            stopSong();
            player = new MediaPlayer();
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            player.setAudioAttributes(aa);
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa).build();
            if (songUri.startsWith("asset:")) {
                android.content.res.AssetFileDescriptor fd = getAssets().openFd(songUri.substring(6));
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            } else player.setDataSource(this, Uri.parse(songUri));
            player.setLooping(true);
            player.prepare();
            prepared = true;
        } catch (Exception e) {
            prepared = false;
            Toast.makeText(this, "노래를 틀 수 없어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void startSong() {
        songAt = 0;
        if (songUri == null || !songOn) return;
        prepSong();
        if (player == null || !prepared) return;
        try { am.requestAudioFocus(focusReq); player.seekTo(0); player.start(); songAt = System.currentTimeMillis(); } catch (Exception ignored) {}
    }
    /** 노래가 영상보다 몇 ms 늦게 들렸는지 (틀기까지 걸린 시간 + 스피커 지연, 블루투스는 더 늦어요) */
    private long songOffset() {
        if (songAt <= 0 || startMs <= 0) return 0;
        long lat = 60;
        try { for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) { int t = d.getType();
            if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 /* BLE 스피커 */ || t == 27) { lat = 250; break; } } } catch (Exception ignored) {}
        return Math.max(0, Math.min(3000, songAt - startMs + lat));
    }
    private void stopSong() {
        prepared = false;
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (focusReq != null) { am.abandonAudioFocusRequest(focusReq); focusReq = null; }
    }

    /* ---------------- 끝내기 ---------------- */
    private void deleteOut() { if (out != null && out.exists()) out.delete(); }
    private void finishOk() {
        if (!hasClip) return;
        Intent r = new Intent().putExtra(EX_PATH, out.getAbsolutePath()).putExtra(EX_SONG, songUri != null && songOn ? songUri : "");
        if (judge != null) r.putExtra(EX_MISS, judge.json());
        r.putExtra(EX_SONGOFS, songOffset());
        if (grid != null) { r.putExtra(EX_RECTS, grid.videoRects(!back)); gridPrefs().edit().putString(gridKey(), grid.save()).apply(); }
        setResult(RESULT_OK, r);
        finish();
    }
    private void cancelOut() {
        discard = true;
        if (recording != null) recording.stop();
        stopSong();
        deleteOut();
        setResult(RESULT_CANCELED);
        finish();
    }
    private void askCancel() {
        if (recording == null && !hasClip) { cancelOut(); return; }
        new AlertDialog.Builder(this).setMessage("촬영을 취소할까요?\n찍은 영상은 저장되지 않아요.")
                .setPositiveButton("취소하기", (d, w) -> cancelOut())
                .setNegativeButton("계속 찍기", null).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 찍는 도중 다른 앱으로 나가거나 화면이 꺼지면 → 그 영상은 버려요
        if (recording != null) {
            discard = true; recording.stop(); recording = null; stopSong(); deleteOut();
            hasClip = false;
            h.post(() -> { if (grid != null) { grid.locked = false; grid.invalidate(); } setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR); showDone(false); recBtn.setText("녹화"); ((GradientDrawable) recBtn.getBackground()).setColor(0xFFE24850); flipBtn.setVisibility(View.VISIBLE);
                hint.setText("화면을 벗어나서 녹화를 취소했어요 · 다시 찍어요"); hint.setVisibility(View.VISIBLE); });
        }
    }
    private SharedPreferences gridPrefs() { return getSharedPreferences("grid", MODE_PRIVATE); }

    /* ---------------- 여러 명 칸 (끌어서 옮기기 · 모서리로 크기 조절) ---------------- */
    private String gridKey() { return "g" + names.length + (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "L" : "P"); }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // 폰을 돌리면 화면도 같이 돌고, 카메라를 다시 연결해서 영상 방향을 맞춰요 (칸은 가로·세로 따로 기억)
        if (importUri != null) { if (grid != null) grid.invalidate(); return; }
        if (recording == null && !hasClip) {
            startCamera();
            if (grid != null) { if (!grid.load(gridPrefs().getString(gridKey(), null))) grid.reset(); }
        }
    }

    /* ---------------- 여러 명 칸 ----------------
       · 칸은 세로로 길게 나란히 (아이들이 옆으로 나란히 서요)
       · 칸 가운데를 끌면: 그 칸은 옆으로, 위아래는 4칸이 한꺼번에 움직여요
       · 왼쪽 위 점: 그 칸 왼쪽 끝 + 모든 칸 위쪽 / 오른쪽 아래 점: 그 칸 오른쪽 끝 + 모든 칸 아래쪽 */
    static class Grid extends View {
        static final int[] COL = {0xFFE24850, 0xFF3A8FE0, 0xFF38B75C, 0xFFF2B233};
        final String[] names; final float[][] r; final RectF box = new RectF(); final float d;
        float top = 0.10f, bot = 0.80f, aspect = 0;   // aspect: 가져온 영상 가로/세로 (0이면 카메라 16:9)
        boolean locked = false; int drag = -1, mode = 0; float lx, ly;
        Runnable onTouch;
        String[] extra;
        float[][] rectsArr(boolean mirror) {
            float[][] o = new float[r.length][];
            for (int i = 0; i < r.length; i++) { float x0 = mirror ? 1 - r[i][1] : r[i][0], x1 = mirror ? 1 - r[i][0] : r[i][1]; o[i] = new float[]{x0, top, x1, bot}; }
            return o;
        }
        final Paint st = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
        Grid(android.content.Context c, String[] names, String saved) {
            super(c); this.names = names; d = c.getResources().getDisplayMetrics().density;
            r = new float[names.length][2];
            extra = new String[names.length];
            st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(3 * d);
            tx.setColor(Color.WHITE); tx.setTypeface(Typeface.DEFAULT_BOLD);
            shade.setColor(0x55000000);
            if (!load(saved)) reset();
        }
        void reset() {
            int n = names.length; float g = 0.012f, w = (1f - g * (n + 1)) / n;
            for (int i = 0; i < n; i++) { r[i][0] = g + i * (w + g); r[i][1] = r[i][0] + w; }
            top = 0.10f; bot = 0.80f;
            if (n == 1) { r[0][0] = 0f; r[0][1] = 1f; top = 0f; bot = 1f; }
            invalidate();
        }
        boolean load(String s) {
            if (s == null) return false;
            try { String[] p = s.split(";"); if (p.length != r.length + 1) return false;
                String[] tb = p[0].split(","); top = Float.parseFloat(tb[0]); bot = Float.parseFloat(tb[1]);
                for (int i = 0; i < r.length; i++) { String[] q = p[i + 1].split(","); r[i][0] = Float.parseFloat(q[0]); r[i][1] = Float.parseFloat(q[1]); }
                invalidate(); return true; } catch (Exception e) { return false; }
        }
        String save() { StringBuilder b = new StringBuilder().append(top).append(',').append(bot); for (float[] q : r) b.append(';').append(q[0]).append(',').append(q[1]); return b.toString(); }
        /** 녹화된 영상 기준 칸 위치 (0~1). 앞 카메라는 화면이 거울처럼 보여서 좌우를 뒤집어요 */
        String videoRects(boolean mirror) {
            JSONArray a = new JSONArray();
            try { for (float[] q : r) { JSONArray o = new JSONArray(); float x0 = mirror ? 1 - q[1] : q[0], x1 = mirror ? 1 - q[0] : q[1];
                o.put((double) x0).put((double) top).put((double) x1).put((double) bot); a.put(o); } } catch (Exception ignored) {}
            return a.toString();
        }
        private void calc() {
            float vw = getWidth(), vh = getHeight(); if (vw <= 0 || vh <= 0) return;
            float ar = aspect > 0 ? aspect : vw < vh ? 9f / 16f : 16f / 9f, cw, ch;
            if (vw / vh > ar) { ch = vh; cw = vh * ar; } else { cw = vw; ch = vw / ar; }
            box.set((vw - cw) / 2, (vh - ch) / 2, (vw + cw) / 2, (vh + ch) / 2);
        }
        private RectF px(int i) { return new RectF(box.left + r[i][0] * box.width(), box.top + top * box.height(), box.left + r[i][1] * box.width(), box.top + bot * box.height()); }
        @Override protected void onDraw(Canvas c) {
            calc();
            // 칸 밖(잘려 나가는 부분)은 살짝 어둡게
            float yt = box.top + top * box.height(), yb = box.top + bot * box.height();
            c.drawRect(box.left, box.top, box.right, yt, shade); c.drawRect(box.left, yb, box.right, box.bottom, shade);
            for (int i = 0; i < r.length; i++) {
                RectF p = px(i); int col = COL[i % COL.length];
                st.setColor(col); c.drawRect(p, st);
                // 이름: 칸 너비에 맞게 글자 크기를 줄여요
                String t = (i + 1) + " " + names[i];
                if (extra[i] != null) {   // 실시간 판정: 칸 아래쪽에 걸림 · 개수
                    float es = 15 * d; tx.setTextSize(es);
                    float ew = tx.measureText(extra[i]); while (ew > p.width() - 8 * d && es > 9 * d) { es -= d; tx.setTextSize(es); ew = tx.measureText(extra[i]); }
                    float eh = es * 1.6f;
                    fill.setColor(0xCC0B1020); c.drawRoundRect(new RectF(p.left + 2 * d, p.bottom - eh - 4 * d, p.left + ew + 12 * d, p.bottom - 4 * d), 5 * d, 5 * d, fill);
                    c.drawText(extra[i], p.left + 7 * d, p.bottom - 4 * d - eh / 2 - (tx.descent() + tx.ascent()) / 2, tx);
                }
                float ts = 16 * d; tx.setTextSize(ts);
                float maxW = p.width() - 10 * d;
                while (tx.measureText(t) > maxW && ts > 10 * d) { ts -= d; tx.setTextSize(ts); }
                while (tx.measureText(t) > maxW && t.length() > 3) t = t.substring(0, t.length() - 2) + "…";
                float ph = ts * 1.6f;
                fill.setColor(col); c.drawRoundRect(new RectF(p.left, p.top, p.left + tx.measureText(t) + 10 * d, p.top + ph), 5 * d, 5 * d, fill);
                c.drawText(t, p.left + 5 * d, p.top + ph / 2 - (tx.descent() + tx.ascent()) / 2, tx);
                if (!locked) {
                    fill.setColor(col); c.drawCircle(p.left, p.top, 10 * d, fill); c.drawCircle(p.right, p.bottom, 10 * d, fill);
                    fill.setColor(Color.WHITE); c.drawCircle(p.left, p.top, 4 * d, fill); c.drawCircle(p.right, p.bottom, 4 * d, fill);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (locked) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    calc(); drag = -1;
                    for (int i = r.length - 1; i >= 0 && drag < 0; i--) { RectF p = px(i);
                        if (Math.hypot(x - p.right, y - p.bottom) < 30 * d) { drag = i; mode = 2; }
                        else if (Math.hypot(x - p.left, y - p.top) < 30 * d) { drag = i; mode = 1; } }
                    for (int i = r.length - 1; i >= 0 && drag < 0; i--) { if (px(i).contains(x, y)) { drag = i; mode = 0; } }
                    lx = x; ly = y; if (drag >= 0 && onTouch != null) onTouch.run(); return drag >= 0;
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0 || box.width() <= 0) return false;
                    float dx = (x - lx) / box.width(), dy = (y - ly) / box.height(); lx = x; ly = y;
                    float[] q = r[drag];
                    if (mode == 0) {           // 옮기기: 이 칸은 옆으로, 위아래는 모든 칸 같이
                        float w = q[1] - q[0]; q[0] = Math.max(0f, Math.min(1f - w, q[0] + dx)); q[1] = q[0] + w;
                        float hh = bot - top; top = Math.max(0f, Math.min(1f - hh, top + dy)); bot = top + hh;
                    } else if (mode == 1) {    // 왼쪽 위 점
                        q[0] = Math.max(0f, Math.min(q[1] - 0.06f, q[0] + dx)); top = Math.max(0f, Math.min(bot - 0.1f, top + dy));
                    } else {                   // 오른쪽 아래 점
                        q[1] = Math.min(1f, Math.max(q[0] + 0.06f, q[1] + dx)); bot = Math.min(1f, Math.max(top + 0.1f, bot + dy));
                    }
                    invalidate(); return true;
                default: drag = -1; return true;
            }
        }
    }

    @Override
    protected void onDestroy() { super.onDestroy(); if (judge != null) judge.close(); anEx.shutdown(); stopSong(); h.removeCallbacksAndMessages(null); }
}
