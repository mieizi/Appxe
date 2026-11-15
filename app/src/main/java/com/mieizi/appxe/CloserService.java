package com.mieizi.appxe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * خدمة إمكانية الوصول التي تنفّذ «فرض الإيقاف» للتطبيقات المحددة.
 * لكل تطبيق: تفتح صفحة معلوماته في الإعدادات، تضغط زر فرض الإيقاف ثم زر التأكيد.
 * أثناء ذلك تغطي الشاشة بلقطة من واجهة Appxe حتى لا تظهر صفحات الإعدادات.
 */
public class CloserService extends AccessibilityService {

    public interface Done {
        void onDone(List<String> closed);
    }

    static volatile CloserService instance;

    private static final int IDLE = 0;
    private static final int WAIT_BUTTON = 1;
    private static final int WAIT_DIALOG = 2;
    private static final int SETTLE = 3;

    private static final String SETTINGS_PKG = "com.android.settings";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    private TextView pill;

    private List<String> queue = new ArrayList<>();
    private int index;
    private Done done;
    private boolean running;
    private int state = IDLE;
    private long stepStart;

    private final Set<String> forceTexts = new LinkedHashSet<>();
    private final Set<String> okTexts = new LinkedHashSet<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (running) finish();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        removeOverlay();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running) return;
        // أي تغيّر في النوافذ يعني أن هناك ما يستحق الفحص الآن
        h.removeCallbacks(tick);
        h.postDelayed(tick, 60);
    }

    @Override
    public void onInterrupt() {
    }

    public boolean isBusy() {
        return running;
    }

    /** يبدأ إغلاق القائمة. يُستدعى من الخيط الرئيسي. */
    public void begin(List<String> pkgs, Bitmap cover, Done callback) {
        if (running) return;
        running = true;
        queue = new ArrayList<>(pkgs);
        done = callback;
        index = -1;
        loadTexts();
        try {
            showOverlay(cover);
        } catch (Throwable t) {
            overlay = null;
        }
        h.postDelayed(watchdog, 8000L + 6000L * queue.size());
        next();
    }

    // ---------------------------------------------------------------
    // آلة الحالات
    // ---------------------------------------------------------------

    private void next() {
        h.removeCallbacks(tick);
        index++;
        while (index < queue.size() && isStopped(queue.get(index))) index++;
        if (index >= queue.size()) {
            finish();
            return;
        }
        setPill("جارٍ الإغلاق " + (index + 1) + " / " + queue.size());
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + queue.get(index)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } catch (Throwable t) {
            next();
            return;
        }
        state = WAIT_BUTTON;
        stepStart = SystemClock.uptimeMillis();
        h.postDelayed(tick, 350);
    }

    private void step() {
        if (!running) return;
        h.removeCallbacks(tick);
        long el = SystemClock.uptimeMillis() - stepStart;
        try {
            if (state == WAIT_BUTTON) {
                if (el >= 350) {
                    List<AccessibilityNodeInfo> roots = roots();
                    AccessibilityNodeInfo btn = findById(roots, SETTINGS_PKG + ":id/force_stop_button");
                    if (btn == null) btn = findByText(roots, forceTexts);
                    if (btn != null) {
                        AccessibilityNodeInfo target = clickable(btn);
                        if (btn.isEnabled() && target.isEnabled()) {
                            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                state = WAIT_DIALOG;
                                stepStart = SystemClock.uptimeMillis();
                            }
                        } else if (el > 1000) {
                            // الزر معطّل: التطبيق موقوف أصلاً أو لا يمكن إيقافه
                            next();
                            return;
                        }
                    } else if (el > 4500) {
                        next();
                        return;
                    }
                }
            } else if (state == WAIT_DIALOG) {
                List<AccessibilityNodeInfo> roots = roots();
                AccessibilityNodeInfo ok = findById(roots, "android:id/button1");
                if (ok == null && el > 700) ok = findByText(roots, okTexts);
                if (ok == null && el > 1200) ok = findByText(roots, forceTexts);
                if (ok != null && clickable(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    state = SETTLE;
                    stepStart = SystemClock.uptimeMillis();
                } else if (el > 3500) {
                    next();
                    return;
                }
            } else if (state == SETTLE) {
                if (el > 450) {
                    next();
                    return;
                }
            }
        } catch (Throwable t) {
            if (el > 4500) {
                next();
                return;
            }
        }
        h.postDelayed(tick, 120);
    }

    private void finish() {
        if (!running) return;
        h.removeCallbacks(tick);
        h.removeCallbacks(watchdog);
        state = IDLE;
        final List<String> closed = new ArrayList<>();
        for (String p : queue) if (isStopped(p)) closed.add(p);
        try {
            Intent back = new Intent(this, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(back);
        } catch (Throwable ignored) {
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeOverlay();
                running = false;
                Done d = done;
                done = null;
                if (d != null) d.onDone(closed);
            }
        }, 450);
    }

    private boolean isStopped(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (Throwable t) {
            return true; // غير موجود: لا شيء لإغلاقه
        }
    }

    // ---------------------------------------------------------------
    // البحث في شاشة الإعدادات
    // ---------------------------------------------------------------

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        String self = getPackageName();
        try {
            List<AccessibilityWindowInfo> ws = getWindows(); // الأقرب للمستخدم أولاً
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p != null && self.contentEquals(p)) continue;
                    out.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                CharSequence p = r.getPackageName();
                if (p == null || !self.contentEquals(p)) out.add(r);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo findById(List<AccessibilityNodeInfo> roots, String id) {
        for (AccessibilityNodeInfo r : roots) {
            try {
                List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(id);
                if (l != null && !l.isEmpty() && l.get(0) != null) return l.get(0);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(List<AccessibilityNodeInfo> roots, Set<String> texts) {
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo n = search(r, texts, 0);
            if (n != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo n, Set<String> texts, int depth) {
        if (n == null || depth > 40) return null;
        if (matches(n.getText(), texts) || matches(n.getContentDescription(), texts)) return n;
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo r = search(n.getChild(i), texts, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean matches(CharSequence cs, Set<String> texts) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.isEmpty()) return false;
        for (String t : texts) if (s.equalsIgnoreCase(t)) return true;
        return false;
    }

    /** أقرب عنصر قابل للضغط: العنصر نفسه أو أحد آبائه. */
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return n;
    }

    /** نصوص الأزرار بلغة الجهاز، مأخوذة من تطبيق الإعدادات نفسه مع بدائل ثابتة. */
    private void loadTexts() {
        forceTexts.clear();
        okTexts.clear();
        try {
            Resources res = getPackageManager().getResourcesForApplication(SETTINGS_PKG);
            addRes(res, "force_stop", forceTexts);
            addRes(res, "dlg_ok", okTexts);
            addRes(res, "okay", okTexts);
        } catch (Throwable ignored) {
        }
        forceTexts.add("Force stop");
        forceTexts.add("فرض الإيقاف");
        forceTexts.add("فرض التوقف");
        forceTexts.add("إيقاف إجباري");
        try {
            okTexts.add(getString(android.R.string.ok));
        } catch (Throwable ignored) {
        }
        okTexts.add("OK");
        okTexts.add("موافق");
    }

    private static void addRes(Resources res, String name, Set<String> into) {
        try {
            int id = res.getIdentifier(name, "string", SETTINGS_PKG);
            if (id != 0) {
                String s = res.getString(id);
                if (s != null && !s.trim().isEmpty()) into.add(s.trim());
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    // الغطاء فوق صفحات الإعدادات
    // ---------------------------------------------------------------

    private void showOverlay(Bitmap cover) {
        removeOverlay();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F17);
        root.setClickable(true); // يمنع اللمس أثناء الإغلاق

        if (cover != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageBitmap(cover);
            root.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        pill = new TextView(this);
        pill.setTextColor(0xFFF1F5F9);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141A26);
        bg.setCornerRadius(dp(100));
        bg.setStroke(dp(1), 0x4DF59E0B);
        pill.setBackground(bg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = dp(56);
        root.addView(pill, plp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
        }
        wm.addView(root, lp);
        overlay = root;
    }

    private void setPill(String text) {
        if (pill != null) pill.setText(text);
    }

    private void removeOverlay() {
        View v = overlay;
        overlay = null;
        pill = null;
        if (v != null && wm != null) {
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
