/*
  © 2026 유종호 (용인대 정도 태권도 2관). All rights reserved.
  줄넘기 실시간 판정 — 촬영하는 동안 아이마다(칸마다) 뛴 횟수와 걸린 횟수를 세요.
  · 몸 관절(발목 · 어깨)을 찾아서 발이 위아래로 뛰는 리듬을 따라가요
  · 뛰다가 발이 0.9초 넘게 멈추면(줄에 걸리거나 줄이 멈추면) 걸림 1번
  · 마지막에 멈춘 건(심사 끝) 걸림으로 안 세요
  · 자동으로 센 값이라 틀릴 수 있어요 (참고용)
*/
package kr.jungdo.video;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import org.json.JSONArray;

public class RopeJudge implements ImageAnalysis.Analyzer {
    public interface Boxes { float[][] get(); }          // 칸 위치 (영상 기준 0~1: 왼쪽, 위, 오른쪽, 아래)
    public interface Listener { void onUpdate(int[] miss, int[] jumps); }

    private final int n;
    private final PoseDetector[] det;
    private final Track[] tr;
    private final Boxes boxes;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean on = false;
    private long t0 = 0;

    public RopeJudge(int n, Boxes boxes, Listener l) {
        this.n = Math.max(1, n); this.boxes = boxes; this.listener = l;
        det = new PoseDetector[this.n]; tr = new Track[this.n];
        for (int i = 0; i < this.n; i++) {
            det[i] = PoseDetection.getClient(new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build());
            tr[i] = new Track();
        }
    }

    public void start() { synchronized (tr) { for (int i = 0; i < n; i++) tr[i] = new Track(); } t0 = SystemClock.uptimeMillis(); on = true; post(); }
    public void stop() { on = false; synchronized (tr) { for (Track t : tr) t.finish(); } post(); }
    public void close() { on = false; for (PoseDetector d : det) try { d.close(); } catch (Exception ignored) {} }

    public String json() {
        JSONArray a = new JSONArray();
        // [걸림, 뛴 횟수, [걸린 때(ms)], [뛴 때(ms)]] — 영상에 숫자를 시간에 맞춰 넣을 때 써요
        synchronized (tr) { for (Track t : tr) { JSONArray o = new JSONArray(); o.put(t.miss); o.put(t.jumps);
            JSONArray mt = new JSONArray(); for (long x : t.missT) mt.put(x - t0); JSONArray jt = new JSONArray(); for (long x : t.jumpT) jt.put(x - t0);
            o.put(mt); o.put(jt); a.put(o); } }
        return a.toString();
    }

    private void post() {
        final int[] m = new int[n], j = new int[n];
        synchronized (tr) { for (int i = 0; i < n; i++) { m[i] = tr[i].miss; j[i] = tr[i].jumps; } }
        main.post(() -> listener.onUpdate(m, j));
    }

    @Override
    public void analyze(@NonNull ImageProxy p) {
        try {
            if (!on) return;
            Bitmap b = p.toBitmap();
            int rot = p.getImageInfo().getRotationDegrees();
            if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
            float[][] bx = boxes.get();
            long t = SystemClock.uptimeMillis();
            int W = b.getWidth(), H = b.getHeight();
            for (int i = 0; i < n && on; i++) {
                float[] q = bx != null && i < bx.length ? bx[i] : new float[]{0, 0, 1, 1};
                int bx0 = clamp((int) (q[0] * W), 0, W - 2), by0 = clamp((int) (q[1] * H), 0, H - 2);
                int bx1 = clamp((int) (q[2] * W), bx0 + 2, W), by1 = clamp((int) (q[3] * H), by0 + 2, H);
                // 아이가 작게 찍혀도 또렷하게: 지난번에 찾은 아이 둘레만 크게 잘라서 봐요 (못 찾으면 칸 전체)
                Track T = tr[i]; boolean zoom = T.roi != null;
                int x = bx0, y = by0, x2 = bx1, y2 = by1;
                if (zoom) { x = Math.max(bx0, T.roi[0]); y = Math.max(by0, T.roi[1]); x2 = Math.min(bx1, T.roi[2]); y2 = Math.min(by1, T.roi[3]); if (x2 - x < 40 || y2 - y < 60) { zoom = false; x = bx0; y = by0; x2 = bx1; y2 = by1; } }
                int w = x2 - x, h = y2 - y;
                Bitmap c = Bitmap.createBitmap(b, x, y, w, h);
                Float ay = null; float sc = 0;
                try {
                    Pose pose = Tasks.await(det[i].process(InputImage.fromBitmap(c, 0)));
                    // 사람이 진짜 있을 때만: 코 · 어깨 · 골반 · 무릎 · 발목이 다 또렷하고, 위에서 아래로 순서가 맞아야 해요
                    int[] K = {PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE};
                    float[] X = new float[K.length], Y = new float[K.length]; boolean ok = true;
                    for (int k = 0; k < K.length && ok; k++) { PoseLandmark l = pose.getPoseLandmark(K[k]); if (l == null || l.getInFrameLikelihood() < 0.8f) ok = false; else { X[k] = l.getPosition().x; Y[k] = l.getPosition().y; } }
                    if (ok) {
                        float nose = Y[0], sh = (Y[1] + Y[2]) / 2f, hip = (Y[3] + Y[4]) / 2f, knee = (Y[5] + Y[6]) / 2f, ank = (Y[7] + Y[8]) / 2f, body = ank - nose;
                        if (nose < sh && sh < hip && hip < knee && knee < ank && body > h * (zoom ? 0.3f : 0.1f) && body > 40) {
                            ay = (hip + ank) / 2f; sc = ank - sh;   // 골반+발목: 몸 전체가 떠야 1개
                            float mnx = 1e9f, mxx = -1e9f; for (float v : X) { mnx = Math.min(mnx, v); mxx = Math.max(mxx, v); }
                            float cx = x + (mnx + mxx) / 2f, cy = y + (nose + ank) / 2f, half = Math.max(body * 0.85f, (mxx - mnx) * 1.2f);
                            T.roi = new int[]{(int) (cx - half * 0.75f), (int) (cy - half), (int) (cx + half * 0.75f), (int) (cy + half)};
                        }
                    }
                } catch (Exception ignored) {}
                if (ay == null) T.roi = null;
                synchronized (tr) { T.feed(t, ay, sc); }
            }
            post();
        } catch (Throwable ignored) {
        } finally { p.close(); }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 한 아이: 발목 높이(어깨~발목 길이로 나눔)가 올라갔다 내려오면 1번 뜀 */
    static class Track {
        int jumps = 0, miss = 0, since = 0;   // since: 마지막 걸림 뒤로 뛴 횟수
        boolean up = false, active = false, missed = false;
        float ext = Float.NaN, scale = 0;
        long lastJump = 0, firstRun = 0;
        final java.util.ArrayList<Long> missT = new java.util.ArrayList<>(), jumpT = new java.util.ArrayList<>();
        static final float THR = 0.045f;      // 몸 길이(어깨~발목)의 4.5% 넘게 몸 전체가 오르내리면 한 번 뜀
        static final long STOP_MS = 900;      // 뛰다가 이만큼 멈추면 걸림

        int seen = 0; long lastSeen = 0, tUp = 0; float sm = Float.NaN; int[] roi;
        void feed(long t, Float y, float sc) {
            if (y == null) { seen = 0; ext = Float.NaN; sm = Float.NaN; up = false; }      // 사람이 안 보이면 세던 걸 처음부터 (빈 화면에서 안 세요)
            else if (++seen < 3) { lastSeen = t; ext = Float.NaN; sm = Float.NaN; scale = sc; }   // 3장 연속 또렷하게 보여야 세기 시작
            else {
                lastSeen = t;
                scale = scale <= 0 ? sc : scale * 0.9f + sc * 0.1f;
                float v0 = y / Math.max(1f, scale);
                sm = Float.isNaN(sm) ? v0 : sm * 0.45f + v0 * 0.55f;     // 떨림 줄이기
                float v = sm;
                if (Float.isNaN(ext)) { ext = v; up = false; }
                else if (!up) { if (v > ext) ext = v; else if (ext - v > THR) { up = true; ext = v; tUp = t; } }      // 화면 위쪽 = y 작음
                else { if (v < ext) ext = v; else if (v - ext > THR) { up = false; ext = v;
                    // 진짜 점프는 떴다 내려오는 데 0.8초 안 걸려요 (천천히 움직이거나 자세 바꾸는 건 안 세요)
                    if (t - tUp < 800 && t - lastJump > 200) jump(t); } }
                if (up && t - tUp > 1200) { up = false; ext = v; }
            }
            if (active && t - lastJump > STOP_MS) { miss++; since = 0; missed = true; active = false; missT.add(lastJump + 300); }
        }
        void jump(long t) {
            jumps++; since++; jumpT.add(t);
            // 1.2초 안에 이어서 뛰면 "뛰는 중"
            if (!active) { if (lastJump > 0 && t - lastJump < 1200) active = true; }
            lastJump = t;
        }
        void finish() {
            // 마지막 걸림 뒤로 한 번도 안 뛰었으면 그건 심사가 끝나서 멈춘 것
            if (missed && since == 0 && miss > 0) { miss--; if (!missT.isEmpty()) missT.remove(missT.size() - 1); }
            active = false;
        }
    }
}
