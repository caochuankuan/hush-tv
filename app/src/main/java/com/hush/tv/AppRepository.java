package com.hush.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads launchable TV apps off the main thread, including their icons. */
final class AppRepository {

    interface Callback {
        void onAppsLoaded(List<AppEntry> apps);
    }

    private static final int ICON_PX = 160;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    void load(Callback callback) {
        executor.execute(() -> {
            List<AppEntry> apps;
            try {
                apps = queryApps();
            } catch (RuntimeException ignored) {
                apps = new ArrayList<>();
            }
            final List<AppEntry> result = apps;
            mainHandler.post(() -> callback.onAppsLoaded(result));
        });
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = context.getPackageManager();
        Map<String, ResolveInfo> byComponent = new LinkedHashMap<>();

        // Leanback entries win over ordinary launcher entries for the same activity.
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byComponent);
        collect(pm, Intent.CATEGORY_LAUNCHER, byComponent);

        List<AppEntry> apps = new ArrayList<>(byComponent.size());
        for (ResolveInfo info : byComponent.values()) {
            String packageName = info.activityInfo.packageName;
            if (context.getPackageName().equals(packageName)) {
                continue;
            }
            CharSequence loaded = info.loadLabel(pm);
            String label = loaded == null ? "" : loaded.toString().trim();
            if (label.isEmpty()) {
                label = info.activityInfo.name;
                int dot = label.lastIndexOf('.');
                if (dot >= 0 && dot < label.length() - 1) {
                    label = label.substring(dot + 1);
                }
            }
            Bitmap icon = null;
            try {
                icon = drawIcon(info.loadIcon(pm));
            } catch (RuntimeException ignored) {
                // A single broken app icon must not hide the whole desktop.
            }
            ComponentName component = new ComponentName(packageName, info.activityInfo.name);
            apps.add(new AppEntry(label, component, icon));
        }

        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    private void collect(PackageManager pm, String category, Map<String, ResolveInfo> out) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo == null) {
                continue;
            }
            String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
            out.putIfAbsent(key, info);
        }
    }

    private Bitmap drawIcon(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_PX, ICON_PX);
        drawable.draw(canvas);
        return bitmap;
    }
}
