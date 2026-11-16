package com.mieizi.appxe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.PixelCopy;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** الواجهة: صفحة HTML الأصلية داخل WebView، مع جسر يزوّدها بالبيانات الحقيقية. */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0F17);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.onAppResume && window.onAppResume()");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 1 : 0",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (!"1".equals(v)) moveTaskToBack(true);
                    }
                });
    }

    private void js(final String code) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    // ---------------------------------------------------------------
    // بيانات النظام
    // ---------------------------------------------------------------

    /** تطبيقات لا تُعرض للإغلاق: إيقافها يعطّل الجهاز أو هذا التطبيق نفسه. */
    private Set<String> excludedPackages() {
        Set<String> ex = new HashSet<>();
        ex.add(getPackageName());
        ex.add("com.android.settings");
        ex.add("com.android.systemui");
        PackageManager pm = getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) ex.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
                    ex.add(i.getPackageName());
                }
            }
        } catch (Throwable ignored) {
        }
        return ex;
    }

    private JSONArray listApps() {
        final List<JSONObject> items = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Set<String> ex = excludedPackages();
        Set<String> seen = new HashSet<>();
        Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(li, 0);
        } catch (Throwable t) {
            ris = new ArrayList<>();
        }
        for (ResolveInfo ri : ris) {
            try {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || ex.contains(pkg) || !seen.add(pkg)) continue;
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                // الموقوف قسراً ليس قيد التشغيل
                if ((ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) continue;
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (sys && !updated) continue; // تطبيقات النظام الأساسية تُترك
                long bytes = 0;
                if (ai.sourceDir != null) bytes = new File(ai.sourceDir).length();
                JSONObject o = new JSONObject();
                o.put("id", pkg);
                o.put("name", String.valueOf(pm.getApplicationLabel(ai)));
                o.put("size", Math.max(1, Math.round(bytes / 1048576.0)));
                items.add(o);
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Integer.compare(b.optInt("size"), a.optInt("size"));
            }
        });
        JSONArray arr = new JSONArray();
        for (JSONObject o : items) arr.put(o);
        return arr;
    }

    private JSONObject ram() {
        JSONObject o = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            o.put("totalMb", mi.totalMem / 1048576.0);
            o.put("availMb", mi.availMem / 1048576.0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private void startClosing(String json) {
        final CloserService svc = CloserService.instance;
        if (svc == null || svc.isBusy()) {
            js("window.onCloseFinished && window.onCloseFinished({error:'service'})");
            return;
        }
        final List<String> pkgs = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            Set<String> ex = excludedPackages();
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty() && !ex.contains(p)) pkgs.add(p);
            }
        } catch (Throwable ignored) {
        }
        if (pkgs.isEmpty()) {
            js("window.onCloseFinished && window.onCloseFinished({closed:[]})");
            return;
        }

        final CloserService.Done cb = new CloserService.Done() {
            @Override
            public void onDone(List<String> closed) {
                JSONObject o = new JSONObject();
                try {
                    o.put("closed", new JSONArray(closed));
                } catch (Throwable ignored) {
                }
                js("window.onCloseFinished && window.onCloseFinished(" + o + ")");
            }
        };

        // لقطة من الواجهة الحالية تُعرض فوق صفحات الإعدادات أثناء الإغلاق
        View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int hgt = decor.getHeight();
        if (w <= 0 || hgt <= 0) {
            svc.begin(pkgs, null, cb);
            return;
        }
        try {
            final Bitmap bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    svc.begin(pkgs, result == PixelCopy.SUCCESS ? bmp : null, cb);
                }
            }, main);
        } catch (Throwable t) {
            svc.begin(pkgs, null, cb);
        }
    }

    // ---------------------------------------------------------------
    // الجسر مع الصفحة
    // ---------------------------------------------------------------

    public class Bridge {

        @JavascriptInterface
        public String getRam() {
            return ram().toString();
        }

        @JavascriptInterface
        public String getApps() {
            return listApps().toString();
        }

        @JavascriptInterface
        public String getIcon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                int size = 96;
                Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, size, size);
                d.draw(c);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
                return "data:image/png;base64,"
                        + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public String getBattery() {
            JSONObject o = new JSONObject();
            try {
                Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i != null) {
                    o.put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                    int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    o.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);
                    o.put("charging", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                }
            } catch (Throwable ignored) {
            }
            return o.toString();
        }

        /** شريط حالة النظام بلون خلفية التطبيق: أسود في وضع OLED، وإلا Obsidian. */
        @JavascriptInterface
        public void setStatusBarDark(final boolean oled) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    getWindow().setStatusBarColor(oled ? 0xFF000000 : 0xFF0B0F17);
                }
            });
        }

        @JavascriptInterface
        public boolean isServiceEnabled() {
            return CloserService.instance != null;
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void openAppInfo() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void closeApps(final String json) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    startClosing(json);
                }
            });
        }
    }
}
