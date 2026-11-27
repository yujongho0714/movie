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

    public RopeJudge(int n, Boxes boxes, Listener l) {
        this.n = Math.max(1, n); this.boxes = boxes; this.listener = l;
        det = new PoseDetector[this.n]; tr = new Track[this.n];
        for (int i = 0; i < this.n; i++) {
            det[i] = PoseDetection.getClient(new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build());
            tr[i] = new Track();
        }
    }

    public void start() { synchronized (tr) { for (int i = 0; i < n; i++) tr[i] = new Track(); } on = true; post(); }
    public void stop() { on = false; synchronized (tr) { for (Track t : tr) t.finish(); } post(); }
    public void close() { on = false; for (PoseDetector d : det) try { d.close(); } catch (Exception ignored) {} }

    public String json() {
        JSONArray a = new JSONArray();
        synchronized (tr) { for (Track t : tr) { JSONArray o = new JSONArray(); o.put(t.miss); o.put(t.jumps); a.put(o); } }
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
                int x = clamp((int) (q[0] * W), 0, W - 2), y = clamp((int) (q[1] * H), 0, H - 2);
                int w = clamp((int) ((q[2] - q[0]) * W), 2, W - x), h = clamp((int) ((q[3] - q[1]) * H), 2, H - y);
                Bitmap c = Bitmap.createBitmap(b, x, y, w, h);
                Float ay = null; float sc = 0;
                try {
                    Pose pose = Tasks.await(det[i].process(InputImage.fromBitmap(c, 0)));
                    PoseLandmark la = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE), ra = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE);
                    PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER), rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
                    if (la != null && ra != null && ls != null && rs != null && la.getInFrameLikelihood() > 0.5f && ra.getInFrameLikelihood() > 0.5f) {
                        float ank = (la.getPosition().y + ra.getPosition().y) / 2f, sh = (ls.getPosition().y + rs.getPosition().y) / 2f;
                        if (ank - sh > 10) { ay = ank; sc = ank - sh; }
                    }
                } catch (Exception ignored) {}
                synchronized (tr) { tr[i].feed(t, ay, sc); }
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
        static final float THR = 0.03f;       // 몸 길이의 3% 넘게 오르내리면 한 번 뜀
        static final long STOP_MS = 900;      // 뛰다가 이만큼 멈추면 걸림

        void feed(long t, Float y, float sc) {
            if (y != null) {
                scale = scale <= 0 ? sc : scale * 0.9f + sc * 0.1f;
                float v = y / Math.max(1f, scale);
                if (Float.isNaN(ext)) { ext = v; up = false; }
                else if (!up) { if (v > ext) ext = v; else if (ext - v > THR) { up = true; ext = v; } }      // 화면 위쪽 = y 작음
                else { if (v < ext) ext = v; else if (v - ext > THR) { up = false; ext = v; jump(t); } }
            }
            if (active && t - lastJump > STOP_MS) { miss++; since = 0; missed = true; active = false; }
        }
        void jump(long t) {
            jumps++; since++;
            // 1.2초 안에 이어서 뛰면 "뛰는 중"
            if (!active) { if (lastJump > 0 && t - lastJump < 1200) active = true; }
            lastJump = t;
        }
        void finish() {
            // 마지막 걸림 뒤로 한 번도 안 뛰었으면 그건 심사가 끝나서 멈춘 것
            if (missed && since == 0 && miss > 0) miss--;
            active = false;
        }
    }
}
