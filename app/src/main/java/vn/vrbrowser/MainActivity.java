package vn.vrbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * VR Browser cho kính lắp điện thoại.
 * Một WebView thật mở trang web (nằm phía sau), ảnh của nó được vẽ hai lần (mắt trái / mắt phải)
 * và trượt theo hướng đầu, như một màn hình lớn đứng yên trước mặt.
 * Nhìn yên vào một chỗ để bấm; chạm màn hình cũng bấm vào chỗ đang nhìn.
 */
public class MainActivity extends Activity implements SensorEventListener {

    // Kích thước trang web (px). Cũng là hệ toạ độ "không gian ảo".
    static final int W = 1440, H = 900;
    static final String HOME = "https://www.google.com";

    static final float SCROLL_SPEED = 700f;          // px CSS mỗi giây
    static final float PAGE_FOV_DEG = 70f;           // trang web chiếm 70° chiều ngang
    static final float EYE_FOV_DEG = 85f;            // mỗi mắt nhìn thấy 85°

    WebView web;
    StereoView stereo;
    SensorManager sensors;
    Bitmap pageBmp;
    Canvas pageCanvas;
    SharedPreferences prefs;

    // Hướng đầu
    final float[] rot = new float[9];
    volatile float yaw, pitch, roll;
    float yaw0 = Float.NaN;
    boolean hasSensor;

    // Trạng thái trang
    String currentUrl = "";
    boolean loading;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("vr", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);   // link mở tab mới -> mở luôn tại chỗ
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) { loading = true; currentUrl = url; }
            @Override public void onPageFinished(WebView v, String url) {
                loading = false; currentUrl = url;
                prefs.edit().putString("last", url).apply();
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new FrameLayout.LayoutParams(W, H));

        stereo = new StereoView(this);
        root.addView(stereo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        pageBmp = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
        pageCanvas = new Canvas(pageBmp);
        pageCanvas.drawColor(Color.WHITE);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        web.loadUrl(prefs.getString("last", HOME));
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        Sensor sen = null;
        if (Build.VERSION.SDK_INT >= 18) sen = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sen == null) sen = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        hasSensor = sen != null;
        if (sen != null) sensors.registerListener(this, sen, SensorManager.SENSOR_DELAY_GAME);
        web.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        web.onPause();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }

    void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------- Cảm biến xoay ----------
    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        // Hướng camera sau của điện thoại (-Z của máy) trong toạ độ thế giới
        float lx = -rot[2], ly = -rot[5], lz = -rot[8];
        float ny = (float) Math.atan2(lx, ly);
        float np = (float) Math.asin(Math.max(-1f, Math.min(1f, lz)));
        // Hướng "lên" của màn hình theo chiều xoay ngang
        int r = getWindowManager().getDefaultDisplay().getRotation();
        float ux, uy, uz;
        if (r == Surface.ROTATION_90) { ux = -rot[0]; uy = -rot[3]; uz = -rot[6]; }
        else if (r == Surface.ROTATION_270) { ux = rot[0]; uy = rot[3]; uz = rot[6]; }
        else { ux = rot[1]; uy = rot[4]; uz = rot[7]; }
        // Vector phải nằm ngang và vector lên không nghiêng
        float rx = ly, ry = -lx, rlen = (float) Math.sqrt(rx * rx + ry * ry);
        if (rlen > 1e-4f) { rx /= rlen; ry /= rlen; }
        float cux = ry * lz, cuy = -rx * lz, cuz = rx * ly - ry * lx; // right x look
        float nr = (float) Math.atan2(ux * rx + uy * ry, ux * cux + uy * cuy + uz * cuz);
        yaw = ny; pitch = np; roll = nr;
        if (Float.isNaN(yaw0)) yaw0 = ny;
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void recenter() { yaw0 = yaw; }

    // ---------- Điều khiển trang ----------
    static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return HOME;
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return s;
        if (s.matches("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")) return "http://" + s;
        if (s.matches("^\\S+\\.\\S{2,}$")) return "https://" + s;
        try { return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return HOME; }
    }

    void clickPage(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(d); web.dispatchTouchEvent(u);
        d.recycle(); u.recycle();
    }

    float pendingScroll = 0; long lastScrollSent = 0;
    void scrollPage(float dy) {
        pendingScroll += dy;
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollSent < 50 || Math.abs(pendingScroll) < 1) return;
        int v = Math.round(pendingScroll); pendingScroll = 0; lastScrollSent = now;
        // Cuộn khung có thanh cuộn ở giữa màn hình, nếu không có thì cuộn cả trang
        web.evaluateJavascript("(function(dy){var e=document.elementFromPoint(innerWidth/2,innerHeight/2);"
                + "while(e&&e!==document.body&&e!==document.documentElement){var s=getComputedStyle(e);"
                + "if(/(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+2){e.scrollBy(0,dy);return;}e=e.parentElement;}"
                + "window.scrollBy(0,dy);})(" + v + ")", null);
    }

    void typeText(String t) {
        KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(t.toCharArray());
        if (evs == null) return;
        for (KeyEvent e : evs) web.dispatchKeyEvent(e);
    }
    void pressKeyCode(int code) {
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        // Nút âm lượng = cuộn trang (tiện khi điện thoại đã nằm trong kính)
        if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { lastScrollSent = 0; scrollPage(c == KeyEvent.KEYCODE_VOLUME_UP ? -300 : 300); }
            return true;
        }
        // Tay cầm / remote Bluetooth của kính: nút chính = bấm, lên/xuống = cuộn
        if (c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_BUTTON_A
                || c == KeyEvent.KEYCODE_BUTTON_R1 || c == KeyEvent.KEYCODE_BUTTON_R2 || c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.getAction() == KeyEvent.ACTION_UP) stereo.activate(stereo.hoverObj);
            return true;
        }
        if (c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { lastScrollSent = 0; scrollPage(c == KeyEvent.KEYCODE_DPAD_UP ? -300 : 300); }
            return true;
        }
        if (c == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (stereo.kbMode != null) { stereo.closeKeyboard(); return true; }
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        if (c == KeyEvent.KEYCODE_BACK) return web.canGoBack() || stereo.kbMode != null || super.dispatchKeyEvent(e);
        return super.dispatchKeyEvent(e);
    }

    // =====================================================================
    // Màn hình chia đôi
    // =====================================================================
    class Button {
        final String label; final RectF r; final Runnable action; final boolean repeat; final float dir;
        Button(String l, float x0, float y0, float x1, float y1, Runnable a) { label = l; r = new RectF(x0, y0, x1, y1); action = a; repeat = false; dir = 0; }
        Button(String l, float x0, float y0, float x1, float y1, float scrollDir) { label = l; r = new RectF(x0, y0, x1, y1); action = null; repeat = true; dir = scrollDir; }
    }
    static class Key { String k; RectF r; Key(String k, RectF r) { this.k = k; this.r = r; } }

    class StereoView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final List<Button> buttons = new ArrayList<>();
        final List<Key> keys = new ArrayList<>();
        final RectF urlRect = new RectF(120, -120, W - 120, -30);
        final RectF pageRect = new RectF(0, 0, W, H);
        final RectF kbRect = new RectF(170, H + 170, W - 170, H + 590);
        String kbMode = null; boolean shift = false; String urlDraft = "";
        float gx, gy;                 // điểm đang nhìn (toạ độ ảo)
        Object hoverObj;
        float progress;   // (không dùng nữa, giữ cho vẽ tâm ngắm)
        long lastFrame = SystemClock.uptimeMillis(); int frameNo;
        long touchDownAt;

        StereoView(Context c) {
            super(c);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFFE8ECF4);
            String[] labels = {"◀ Lùi", "Tiến ▶", "Tải lại", "Trang chủ", "Bàn phím", "Căn giữa"};
            Runnable[] acts = {
                new Runnable() { public void run() { if (web.canGoBack()) web.goBack(); } },
                new Runnable() { public void run() { if (web.canGoForward()) web.goForward(); } },
                new Runnable() { public void run() { web.reload(); } },
                new Runnable() { public void run() { web.loadUrl(HOME); } },
                new Runnable() { public void run() { toggleKeyboard("page"); } },
                new Runnable() { public void run() { recenter(); } },
            };
            float bw = 220, gap = 24, total = labels.length * bw + (labels.length - 1) * gap, x = (W - total) / 2f;
            for (int i = 0; i < labels.length; i++) {
                buttons.add(new Button(labels[i], x, H + 30, x + bw, H + 130, acts[i]));
                x += bw + gap;
            }
            buttons.add(new Button("▲", -230, 120, -50, 400, -1));
            buttons.add(new Button("▼", -230, 500, -50, 780, 1));
            buttons.add(new Button("▲", W + 50, 120, W + 230, 400, -1));
            buttons.add(new Button("▼", W + 50, 500, W + 230, 780, 1));

            String[][] rows = {
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", ":"},
                {"⇧", "z", "x", "c", "v", "b", "n", "m", ".", "/"},
                {"✕", "-", "cách", ".com", "⌫", "Enter"},
            };
            float[][] widths = {null, null, null, null, {1.5f, 1, 3, 1.5f, 1.5f, 1.5f}};
            float rh = kbRect.height() / rows.length;
            for (int ri = 0; ri < rows.length; ri++) {
                float tw = 0;
                for (int i = 0; i < rows[ri].length; i++) tw += widths[ri] == null ? 1 : widths[ri][i];
                float kx = kbRect.left;
                for (int i = 0; i < rows[ri].length; i++) {
                    float w = (widths[ri] == null ? 1 : widths[ri][i]) / tw * kbRect.width();
                    keys.add(new Key(rows[ri][i], new RectF(kx, kbRect.top + ri * rh, kx + w, kbRect.top + (ri + 1) * rh)));
                    kx += w;
                }
            }
        }

        void toggleKeyboard(String mode) {
            if (mode.equals(kbMode)) closeKeyboard();
            else { kbMode = mode; if (mode.equals("url")) urlDraft = ""; }
        }
        void closeKeyboard() { kbMode = null; }

        void pressKey(String k) {
            if (k.equals("⇧")) { shift = !shift; return; }
            if (k.equals("✕")) { closeKeyboard(); return; }
            String ch = k.equals("cách") ? " " : (shift && k.length() == 1 ? k.toUpperCase() : k);
            if ("url".equals(kbMode)) {
                if (k.equals("⌫")) { if (urlDraft.length() > 0) urlDraft = urlDraft.substring(0, urlDraft.length() - 1); }
                else if (k.equals("Enter")) { web.loadUrl(normalize(urlDraft)); closeKeyboard(); }
                else urlDraft += ch;
            } else {
                if (k.equals("⌫")) pressKeyCode(KeyEvent.KEYCODE_DEL);
                else if (k.equals("Enter")) pressKeyCode(KeyEvent.KEYCODE_ENTER);
                else typeText(ch);
            }
            if (shift && k.length() == 1) shift = false;
        }

        // ----- Bấm vào chỗ đang nhìn -----
        void activate(Object o) {
            if (o == pageRect) clickPage(gx, gy);
            else if (o == urlRect) toggleKeyboard("url");
            else if (o instanceof Key) pressKey(((Key) o).k);
            else if (o instanceof Button) {
                Button b = (Button) o;
                if (b.repeat) { lastScrollSent = 0; scrollPage(b.dir * 450); }   // mỗi lần bấm cuộn một đoạn
                else b.action.run();
            }
        }

        Object hitTest(float x, float y) {
            if (kbMode != null) for (Key k : keys) if (k.r.contains(x, y)) return k;
            if (kbMode != null && kbRect.contains(x, y)) return null;
            for (Button b : buttons) if (b.r.contains(x, y)) return b;
            if (urlRect.contains(x, y)) return urlRect;
            if (pageRect.contains(x, y)) return pageRect;
            return null;
        }

        boolean holding;
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { touchDownAt = SystemClock.uptimeMillis(); holding = true; }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                holding = false;
                boolean onScroll = hoverObj instanceof Button && ((Button) hoverObj).repeat;
                long held = SystemClock.uptimeMillis() - touchDownAt;
                if (a == MotionEvent.ACTION_CANCEL) return true;
                if (onScroll) { if (held < 400) activate(hoverObj); }       // giữ thì đã cuộn liên tục rồi
                else if (held > 800) recenter();                            // giữ lâu = căn giữa
                else activate(hoverObj);
            }
            return true;
        }

        float pxPerRad() { return W / (float) Math.toRadians(PAGE_FOV_DEG); }

        void updateGaze(long now, float dt) {
            float dyaw = yaw - (Float.isNaN(yaw0) ? yaw : yaw0);
            while (dyaw > Math.PI) dyaw -= 2 * Math.PI;
            while (dyaw < -Math.PI) dyaw += 2 * Math.PI;
            // Màn hình hơi thấp hơn tầm mắt một chút cho đỡ mỏi cổ
            gx = W / 2f + dyaw * pxPerRad();
            gy = H / 2f - (pitch + 0.08f) * pxPerRad();
            if (!hasSensor) { gx = W / 2f; gy = H / 2f; }

            Object h = hitTest(gx, gy);
            hoverObj = h;
            // Không còn "nhìn để bấm": chỉ bấm khi chạm màn hình / nút kính / tay cầm.
            // Giữ ngón tay khi đang trỏ vào ▲ ▼ thì cuộn liên tục.
            if (holding && h instanceof Button && ((Button) h).repeat
                    && SystemClock.uptimeMillis() - touchDownAt > 400) {
                scrollPage(((Button) h).dir * SCROLL_SPEED * dt);
            }
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - lastFrame) / 1000f); lastFrame = now;
            // Chụp lại trang web (mỗi 2 khung hình để đỡ nặng máy)
            if ((frameNo++ & 1) == 0) {
                try { web.draw(pageCanvas); } catch (Throwable ignored) {}
            }
            updateGaze(now, dt);

            c.drawColor(0xFF0B0D12);
            int w = getWidth(), h = getHeight(), eyeW = w / 2;
            float scale = eyeW / ((float) Math.toRadians(EYE_FOV_DEG) * pxPerRad());
            float rollDeg = hasSensor ? (float) Math.toDegrees(roll) : 0;
            for (int eye = 0; eye < 2; eye++) {
                c.save();
                c.clipRect(eye * eyeW, 0, (eye + 1) * eyeW, h);
                c.translate(eye * eyeW + eyeW / 2f, h / 2f);
                c.save();
                c.rotate(-rollDeg);
                c.scale(scale, scale);
                c.translate(-gx, -gy);
                drawWorld(c);
                c.restore();
                drawReticle(c);
                c.restore();
            }
            // Vạch chia giữa hai mắt
            p.setColor(0xFF000000); c.drawRect(eyeW - 2, 0, eyeW + 2, h, p);
            postInvalidateOnAnimation();
        }

        void drawWorld(Canvas c) {
            // Sàn: vài đường kẻ cho có cảm giác không gian
            p.setColor(0xFF161B26); p.setStrokeWidth(3);
            for (int i = 0; i < 8; i++) { float y = H + 700 + i * i * 40; c.drawLine(-4000, y, W + 4000, y, p); }
            // Khung + trang web
            p.setColor(0xFF2A3142); c.drawRoundRect(new RectF(-16, -16, W + 16, H + 16), 24, 24, p);
            c.drawBitmap(pageBmp, 0, 0, p);
            if (loading) { p.setColor(0xFF4F8CFF); c.drawRect(0, 0, W * 0.35f + (SystemClock.uptimeMillis() / 3 % (W * 0.65f)), 8, p); }

            // Thanh địa chỉ
            boolean urlHover = hoverObj == urlRect;
            p.setColor(urlHover ? 0xFF2B3550 : 0xFF151924); c.drawRoundRect(urlRect, 40, 40, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4);
            p.setColor("url".equals(kbMode) ? 0xFF4F8CFF : 0xFF3A4560); c.drawRoundRect(urlRect, 40, 40, p);
            p.setStyle(Paint.Style.FILL);
            text.setTextAlign(Paint.Align.LEFT); text.setTextSize(38);
            String t = "url".equals(kbMode) ? urlDraft + "▏" : (currentUrl.isEmpty() ? "Trỏ vào đây rồi chạm để nhập địa chỉ" : currentUrl);
            float maxW = urlRect.width() - 60;
            while (text.measureText(t) > maxW && t.length() > 4) t = "…" + t.substring(2);
            text.setColor("url".equals(kbMode) ? Color.WHITE : 0xFFC8D0E0);
            c.drawText(t, urlRect.left + 30, urlRect.centerY() + 13, text);
            text.setTextAlign(Paint.Align.CENTER); text.setColor(0xFFE8ECF4);

            // Nút
            for (Button b : buttons) {
                p.setColor(hoverObj == b ? 0xFF4F8CFF : 0xFF222A3A); c.drawRoundRect(b.r, 26, 26, p);
                text.setTextSize(b.repeat ? 90 : 38);
                c.drawText(b.label, b.r.centerX(), b.r.centerY() + (b.repeat ? 32 : 13), text);
            }
            // Bàn phím
            if (kbMode != null) {
                p.setColor(0xF00F121A); c.drawRoundRect(new RectF(kbRect.left - 16, kbRect.top - 16, kbRect.right + 16, kbRect.bottom + 16), 30, 30, p);
                for (Key k : keys) {
                    RectF r = new RectF(k.r.left + 6, k.r.top + 6, k.r.right - 6, k.r.bottom - 6);
                    p.setColor(hoverObj == k ? 0xFF4F8CFF : (k.k.equals("⇧") && shift) ? 0xFF3A5FA8 : 0xFF262E40);
                    c.drawRoundRect(r, 16, 16, p);
                    String l = shift && k.k.length() == 1 ? k.k.toUpperCase() : k.k;
                    text.setTextSize(l.length() > 2 ? 30 : 42);
                    c.drawText(l, r.centerX(), r.centerY() + 14, text);
                }
            }
        }

        void drawReticle(Canvas c) {
            float base = Math.max(getHeight() / 90f, 8f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(base * 0.5f); p.setColor(0x73000000); c.drawCircle(0, 0, base * 1.5f, p);
            if (progress > 0) {
                p.setColor(0xFF4F8CFF);
                c.drawArc(new RectF(-base * 1.5f, -base * 1.5f, base * 1.5f, base * 1.5f), -90, 360 * Math.min(1, progress), false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB3000000); c.drawCircle(0, 0, base * 0.7f, p);
            p.setColor(Color.WHITE); c.drawCircle(0, 0, base * 0.45f, p);
        }
    }
}
