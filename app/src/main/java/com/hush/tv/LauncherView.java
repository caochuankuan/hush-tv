package com.hush.tv;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.text.Collator;

/**
 * The whole TV desktop is one custom view. Keeping it in a single canvas gives us exact
 * control over spacing, focus state and the quiet, low-contrast look.
 */
final class LauncherView extends View {

    private static final int COLOR_BG_TOP = 0xFF0B0C0F;
    private static final int COLOR_BG_BOTTOM = 0xFF070809;
    private static final int COLOR_CREAM = 0xFFF2EEE5;
    private static final int COLOR_DATE = 0xFFB6B1A7;
    private static final int COLOR_MUTED = 0xFF858B94;
    private static final int COLOR_DIM = 0xFF5A6069;
    private static final int COLOR_HAIRLINE = 0xFF1D2025;
    private static final int COLOR_CARD = 0xFF14161A;
    private static final int COLOR_CARD_BORDER = 0xFF24272D;
    private static final int COLOR_CARD_FOCUS = 0xFFF1EDE3;
    private static final int COLOR_LABEL = 0xFFC9C5BC;
    private static final int COLOR_INK = 0xFF0A0B0D;
    private static final int COLOR_ACCENT = 0xFFE7A56A;

    private static final String TAG = "HushSettings";
    private static final String[] WEEKDAYS = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
    private static final String PIN_PREFS = "hush_pins";
    private static final String PIN_KEY = "pins";
    private static final int MENU_OPEN = 0;
    private static final int MENU_PIN = 1;
    private static final int MENU_INFO = 2;
    private static final int MENU_UNINSTALL = 3;
    private static final int MENU_ITEM_COUNT = 4;
    private static final int SETTINGS_ITEM_COUNT = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppRepository repository;
    private final SharedPreferences pinPrefs;
    private final Collator collator;
    private final List<String> pinnedKeys = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint measurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF cardRect = new RectF();

    private final Typeface typeLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface typeRegular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface typeMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final float density;

    private List<AppEntry> apps = Collections.emptyList();
    private int focusedIndex = -1;
    private int columns = 3;
    private int visibleRows = 3;
    private int rows = 0;

    private float padH;
    private float padTop;
    private float leftWidth;
    private float dividerX;
    private float gridLeft;
    private float gridRight;
    private float gridTop;
    private float gridBottom;
    private float gridHeight;
    private float gapX;
    private float gapY;
    private float cardWidth;
    private float cardHeight;
    private float gridOffsetY;
    private float maxScroll;
    private float scroll;
    private float targetScroll;

    private float[] focusProgress = new float[0];
    private ValueAnimator[] focusAnimators = new ValueAnimator[0];
    private ValueAnimator scrollAnimator;

    private LinearGradient backgroundShader;
    private RadialGradient warmGlow;
    private RadialGradient coolGlow;
    private RadialGradient vignette;
    private LinearGradient dividerShader;

    private String timeText = "";
    private String dateText = "";
    private String greeting = "";
    private boolean clockRunning;
    private boolean loading = false;
    private boolean defaultHome = true;
    private boolean attached;
    private long lastLoadAt;
    private int pressedIndex = -1;
    private boolean refreshPending;

    private Runnable desktopSettingsAction;
    private boolean headerFocused;
    private final RectF settingsChip = new RectF();
    private boolean settingsOpen;
    private int settingsIndex;
    private float settingsProgress;
    private ValueAnimator settingsAnimator;
    private final RectF settingsPanel = new RectF();
    private boolean pushOpen;
    private String pushUrl = "";
    private String pushStatus = "";
    private String pushDirPath = "";
    private int pushPercent;
    private final List<File> pushFiles = new ArrayList<>();
    private int pushSelected = -1;
    private int pushListScroll;
    private float pushPanelProgress;
    private ValueAnimator pushAnimator;
    private RemotePushServer pushServer;
    private final RectF pushPanel = new RectF();
    private boolean packageReceiverRegistered;
    private final Runnable packageReload = () -> {
        if (attached) {
            reloadApps();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(packageReload);
            handler.postDelayed(packageReload, 500L);
        }
    };
    private boolean menuOpen;
    private int menuIndex;
    private AppEntry menuTarget;
    private float menuProgress;
    private ValueAnimator menuAnimator;
    private final RectF menuPanel = new RectF();

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            invalidate();
            handler.postDelayed(this, millisToNextMinute());
        }
    };

    LauncherView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        repository = new AppRepository(context);
        pinPrefs = context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE);
        collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        loadPins();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setBackgroundColor(COLOR_BG_BOTTOM);
        updateClock();
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    void onResume() {
        startClock();
        if (refreshPending) {
            refreshPending = false;
            reloadApps();
        } else if (SystemClock.elapsedRealtime() - lastLoadAt > 20_000L) {
            reloadApps();
        }
    }

    void setOnDesktopSettings(Runnable action) {
        desktopSettingsAction = action;
    }

    void onPause() {
        stopClock();
        closePush();
    }

    void setDefaultHome(boolean isDefaultHome) {
        if (defaultHome == isDefaultHome) {
            return;
        }
        defaultHome = isDefaultHome;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        startClock();
        registerPackageReceiver();
        if (apps.isEmpty()) {
            reloadApps();
        }
        requestFocus();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stopClock();
        closePush();
        unregisterPackageReceiver();
        super.onDetachedFromWindow();
    }

    private void startClock() {
        if (clockRunning) {
            return;
        }
        clockRunning = true;
        updateClock();
        handler.postDelayed(clockTick, millisToNextMinute());
    }

    private static long millisToNextMinute() {
        long now = System.currentTimeMillis();
        return 60_000L - (now % 60_000L) + 50L;
    }

    private void stopClock() {
        clockRunning = false;
        handler.removeCallbacks(clockTick);
    }

    private void registerPackageReceiver() {
        if (packageReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(packageReceiver, filter);
            }
            packageReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot register package receiver", e);
        }
    }

    private void unregisterPackageReceiver() {
        if (!packageReceiverRegistered) {
            return;
        }
        packageReceiverRegistered = false;
        try {
            getContext().unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
    }

    private void reloadApps() {
        if (loading) {
            return;
        }
        lastLoadAt = SystemClock.elapsedRealtime();
        loading = true;
        repository.load(loaded -> {
            loading = false;
            applyApps(loaded);
        });
        invalidate();
    }

    private void applyApps(List<AppEntry> loaded) {
        String previousKey = null;
        if (focusedIndex >= 0 && focusedIndex < apps.size()) {
            previousKey = apps.get(focusedIndex).key();
        }

        closeMenu();
        apps = loaded == null ? new ArrayList<>() : loaded;
        sortApps();
        if (getWidth() > 0) {
            computeLayout();
        }

        focusProgress = new float[apps.size()];
        cancelFocusAnimations();
        focusAnimators = new ValueAnimator[apps.size()];

        int next = 0;
        if (previousKey != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (previousKey.equals(apps.get(i).key())) {
                    next = i;
                    break;
                }
            }
        }
        focusedIndex = apps.isEmpty() ? -1 : next;
        scroll = 0f;
        targetScroll = 0f;
        if (focusedIndex >= 0) {
            focusProgress[focusedIndex] = 1f;
            ensureVisible(focusedIndex, false);
        }
        invalidate();
    }

    private void cancelFocusAnimations() {
        for (ValueAnimator animator : focusAnimators) {
            if (animator != null) {
                animator.cancel();
            }
        }
    }

    private void loadPins() {
        pinnedKeys.clear();
        String raw = pinPrefs.getString(PIN_KEY, "");
        if (raw.isEmpty()) {
            return;
        }
        for (String key : raw.split("\n")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty() && !pinnedKeys.contains(trimmed)) {
                pinnedKeys.add(trimmed);
            }
        }
    }

    private void savePins() {
        StringBuilder builder = new StringBuilder();
        for (String key : pinnedKeys) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(key);
        }
        pinPrefs.edit().putString(PIN_KEY, builder.toString()).apply();
    }

    private void sortApps() {
        if (apps.size() < 2) {
            return;
        }
        Collections.sort(apps, (a, b) -> {
            int aPin = pinnedKeys.indexOf(a.key());
            int bPin = pinnedKeys.indexOf(b.key());
            if (aPin >= 0 || bPin >= 0) {
                if (aPin < 0) {
                    return 1;
                }
                if (bPin < 0) {
                    return -1;
                }
                return Integer.compare(aPin, bPin);
            }
            return collator.compare(a.label, b.label);
        });
    }

    private boolean isPinned(AppEntry entry) {
        return entry != null && pinnedKeys.contains(entry.key());
    }

    private void togglePin(AppEntry target) {
        boolean wasPinned = isPinned(target);
        if (wasPinned) {
            pinnedKeys.remove(target.key());
        } else {
            pinnedKeys.add(0, target.key());
        }
        savePins();
        sortApps();
        restoreFocusTo(target.key());
        Toast.makeText(getContext(), wasPinned ? "已取消置顶" : "已置顶", Toast.LENGTH_SHORT).show();
    }

    private void restoreFocusTo(String key) {
        cancelFocusAnimations();
        focusProgress = new float[apps.size()];
        focusAnimators = new ValueAnimator[apps.size()];
        focusedIndex = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (key.equals(apps.get(i).key())) {
                focusedIndex = i;
                break;
            }
        }
        scroll = 0f;
        targetScroll = 0f;
        if (focusedIndex < 0 && !apps.isEmpty()) {
            focusedIndex = 0;
        }
        if (focusedIndex >= 0) {
            focusProgress[focusedIndex] = 1f;
            ensureVisible(focusedIndex, false);
        }
        invalidate();
    }

    private void openMenu() {
        headerFocused = false;
        if (focusedIndex < 0 || focusedIndex >= apps.size()) {
            if (desktopSettingsAction != null) {
                desktopSettingsAction.run();
            }
            return;
        }
        menuTarget = apps.get(focusedIndex);
        menuOpen = true;
        menuIndex = 0;
        menuProgress = 0f;
        computeMenuPanel();
        if (menuAnimator != null) {
            menuAnimator.cancel();
        }
        menuAnimator = ValueAnimator.ofFloat(0f, 1f);
        menuAnimator.setDuration(150L);
        menuAnimator.setInterpolator(new DecelerateInterpolator(2f));
        menuAnimator.addUpdateListener(animation -> {
            menuProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        menuAnimator.start();
        invalidate();
    }

    private void closeMenu() {
        menuOpen = false;
        menuTarget = null;
        menuProgress = 0f;
        if (menuAnimator != null) {
            menuAnimator.cancel();
            menuAnimator = null;
        }
        invalidate();
    }

    private void computeMenuPanel() {
        float width = dp(312);
        float height = dp(258);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        menuPanel.set(left, top, left + width, top + height);
    }

    private void openSettings() {
        closeMenu();
        headerFocused = false;
        settingsOpen = true;
        settingsIndex = 0;
        settingsProgress = 0f;
        computeSettingsPanel();
        if (settingsAnimator != null) {
            settingsAnimator.cancel();
        }
        settingsAnimator = ValueAnimator.ofFloat(0f, 1f);
        settingsAnimator.setDuration(150L);
        settingsAnimator.setInterpolator(new DecelerateInterpolator(2f));
        settingsAnimator.addUpdateListener(animation -> {
            settingsProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        settingsAnimator.start();
        invalidate();
    }

    private void closeSettings(boolean focusHeader) {
        settingsOpen = false;
        settingsProgress = 0f;
        headerFocused = focusHeader;
        if (settingsAnimator != null) {
            settingsAnimator.cancel();
            settingsAnimator = null;
        }
        invalidate();
    }

    private void computeSettingsPanel() {
        float width = dp(360);
        float height = dp(360);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        settingsPanel.set(left, top, left + width, top + height);
    }

    private String settingsLabel(int index) {
        switch (index) {
            case 0:
                return "开发者选项";
            case 1:
                return "关于设备";
            case 2:
                return "应用管理";
            case 3:
                return "系统设置";
            case 4:
            default:
                return "远程推送";
        }
    }

    private void executeSettingsAction() {
        if (settingsIndex == 4) {
            closeSettings(false);
            openPush();
            return;
        }
        String action;
        switch (settingsIndex) {
            case 0:
                action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
                break;
            case 1:
                action = Settings.ACTION_DEVICE_INFO_SETTINGS;
                break;
            case 2:
                action = Settings.ACTION_APPLICATION_SETTINGS;
                break;
            case 3:
            default:
                action = Settings.ACTION_SETTINGS;
                break;
        }
        closeSettings(false);
        launchSystemPage(action);
    }

    private void launchSystemPage(String action) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            // OEM TV builds may hide a page or throw SecurityException instead of
            // returning ActivityNotFoundException. Never let a settings shortcut
            // take down the launcher.
            Log.w(TAG, "Unable to open system settings page: " + action, e);
            Toast.makeText(getContext(), "这个系统没有对应的设置页面", Toast.LENGTH_SHORT).show();
        }
    }

    private int settingsRowAt(float x, float y) {
        if (!settingsOpen || !settingsPanel.contains(x, y)) {
            return -1;
        }
        float rowsTop = settingsPanel.top + dp(70);
        if (y < rowsTop) {
            return -1;
        }
        int row = (int) ((y - rowsTop) / dp(50));
        return row >= 0 && row < SETTINGS_ITEM_COUNT ? row : -1;
    }

    private void drawSettings(Canvas canvas) {
        if (!settingsOpen || settingsProgress <= 0f) {
            return;
        }
        computeSettingsPanel();
        float progress = Math.max(0f, Math.min(1f, settingsProgress));
        float left = settingsPanel.left;
        float top = settingsPanel.top;
        float right = settingsPanel.right;
        float bottom = settingsPanel.bottom;
        float radius = dp(18);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (172 * progress), 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        canvas.save();
        float scale = 0.965f + 0.035f * progress;
        canvas.scale(scale, scale, settingsPanel.centerX(), settingsPanel.centerY());

        paint.setColor(0x22FFFFFF);
        rect.set(left - dp(2), top - dp(2), right + dp(2), bottom + dp(2));
        canvas.drawRoundRect(rect, radius + dp(2), radius + dp(2), paint);
        paint.setColor(0xFF14161A);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF2A2D33);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.03f);
        drawText(canvas, "桌面设置", left + dp(22), top + dp(30), sp(16), COLOR_CREAM);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.12f);
        drawText(canvas, "ADB / 系统 / 推送", left + dp(22), top + dp(48), sp(8), 0xFF747A83);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.14f);
        drawText(canvas, "BACK 关闭", right - dp(16), top + dp(24), sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(16), top + dp(60), right - dp(16), top + dp(61), paint);

        for (int i = 0; i < SETTINGS_ITEM_COUNT; i++) {
            float rowTop = top + dp(70) + i * dp(50);
            boolean selected = i == settingsIndex;
            if (selected) {
                rect.set(left + dp(8), rowTop + dp(4), right - dp(8), rowTop + dp(46));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_CARD_FOCUS);
                canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            }
            int color = selected ? COLOR_INK : 0xFFD2CDC3;
            textPaint.setTypeface(selected ? typeMedium : typeRegular);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setLetterSpacing(0.03f);
            drawText(canvas, settingsLabel(i), left + dp(24),
                    rowTop + dp(25) + sp(4.5f), sp(13), color);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0f);
            drawText(canvas, "›", right - dp(24), rowTop + dp(25) + sp(4.5f), sp(14),
                    selected ? 0xFF4A4B4D : 0xFF5E646D);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.03f);
        drawText(canvas, "开发者选项被隐藏时，先到「关于设备」连点版本号。",
                left + dp(22), top + dp(342), sp(7.5f), 0xFF6A7079);
        canvas.restore();
    }

    private void openPush() {
        closeMenu();
        closeSettings(false);
        if (!canInstallPackages()) {
            Toast.makeText(getContext(), "请先允许留白安装未知应用，然后重新打开远程推送",
                    Toast.LENGTH_LONG).show();
            launchUnknownSourcesSettings();
            return;
        }

        pushOpen = true;
        pushUrl = "";
        pushStatus = "正在启动接收服务…";
        pushPercent = 0;
        pushSelected = -1;
        pushListScroll = 0;
        pushDirPath = RemotePushServer.getPushDir(getContext()).getAbsolutePath();
        refreshPushFiles(null);
        pushPanelProgress = 0f;
        computePushPanel();
        if (pushAnimator != null) {
            pushAnimator.cancel();
        }
        pushAnimator = ValueAnimator.ofFloat(0f, 1f);
        pushAnimator.setDuration(160L);
        pushAnimator.setInterpolator(new DecelerateInterpolator(2f));
        pushAnimator.addUpdateListener(animation -> {
            pushPanelProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        pushAnimator.start();

        pushServer = new RemotePushServer(getContext(), new RemotePushServer.Listener() {
            @Override
            public void onReady(String url, String directory) {
                if (!pushOpen) {
                    return;
                }
                pushUrl = url == null ? "" : url;
                pushDirPath = directory;
                pushStatus = url == null
                        ? "未检测到局域网 IP，请确认电视已联网"
                        : "等待手机上传文件…";
                refreshPushFiles(null);
                invalidate();
            }

            @Override
            public void onProgress(int percent) {
                if (!pushOpen) {
                    return;
                }
                pushPercent = percent;
                pushStatus = "接收中 " + percent + "%";
                invalidate();
            }

            @Override
            public void onFileReceived(File file) {
                if (!pushOpen) {
                    return;
                }
                pushPercent = 100;
                pushStatus = "已收到 " + file.getName();
                refreshPushFiles(file.getName());
                if (isApkFile(file)) {
                    pushStatus = "已收到 APK，正在准备安装…";
                    invalidate();
                    installPushedApk(file);
                }
            }

            @Override
            public void onError(String message) {
                if (!pushOpen) {
                    return;
                }
                pushStatus = message;
                invalidate();
            }
        });
        pushServer.start();
        invalidate();
    }

    private void closePush() {
        if (!pushOpen && pushServer == null) {
            return;
        }
        pushOpen = false;
        pushPercent = 0;
        pushUrl = "";
        pushStatus = "";
        pushSelected = -1;
        pushListScroll = 0;
        pushFiles.clear();
        pushPanelProgress = 0f;
        if (pushAnimator != null) {
            pushAnimator.cancel();
            pushAnimator = null;
        }
        if (pushServer != null) {
            pushServer.stop();
            pushServer = null;
        }
        invalidate();
    }

    private void computePushPanel() {
        float width = dp(540);
        float height = dp(430);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        pushPanel.set(left, top, left + width, top + height);
    }

    private void refreshPushFiles(String selectName) {
        File dir = RemotePushServer.getPushDir(getContext());
        pushDirPath = dir.getAbsolutePath();
        File[] files = dir.listFiles();
        pushFiles.clear();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                if (file.isFile()) {
                    pushFiles.add(file);
                }
            }
        }
        if (selectName != null) {
            for (int i = 0; i < pushFiles.size(); i++) {
                if (selectName.equals(pushFiles.get(i).getName())) {
                    pushSelected = i;
                    break;
                }
            }
        }
        if (pushFiles.isEmpty()) {
            pushSelected = -1;
            pushListScroll = 0;
        } else {
            if (pushSelected < 0 || pushSelected >= pushFiles.size()) {
                pushSelected = 0;
            }
            adjustPushScroll();
        }
        invalidate();
    }

    private void adjustPushScroll() {
        int visible = 5;
        if (pushSelected < pushListScroll) {
            pushListScroll = pushSelected;
        } else if (pushSelected >= pushListScroll + visible) {
            pushListScroll = pushSelected - visible + 1;
        }
        int maxScroll = Math.max(0, pushFiles.size() - visible);
        pushListScroll = Math.max(0, Math.min(maxScroll, pushListScroll));
    }

    private boolean isApkFile(File file) {
        return file != null && file.getName().toLowerCase(Locale.US).endsWith(".apk");
    }

    private void openSelectedPushFile() {
        if (pushSelected < 0 || pushSelected >= pushFiles.size()) {
            return;
        }
        File file = pushFiles.get(pushSelected);
        if (isApkFile(file)) {
            installPushedApk(file);
            return;
        }
        Uri uri = PushFileProvider.uriFor(file);
        String mime = getContext().getContentResolver().getType(uri);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime == null ? "application/octet-stream" : mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "No viewer for " + file.getName(), e);
            Toast.makeText(getContext(), "没有可打开这个文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeletePushFile() {
        if (pushSelected < 0 || pushSelected >= pushFiles.size()) {
            return;
        }
        File file = pushFiles.get(pushSelected);
        new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除文件")
                .setMessage("确定删除 " + file.getName() + "？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (file.delete()) {
                        pushStatus = "已删除 " + file.getName();
                        refreshPushFiles(null);
                    } else {
                        Toast.makeText(getContext(), "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int pushRowAt(float x, float y) {
        if (!pushOpen || !pushPanel.contains(x, y)) {
            return -1;
        }
        float rowsTop = pushPanel.top + dp(200);
        if (y < rowsTop || y > rowsTop + 5 * dp(38)) {
            return -1;
        }
        int row = (int) ((y - rowsTop) / dp(38));
        int index = pushListScroll + row;
        return index >= 0 && index < pushFiles.size() ? index : -1;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024f * 1024f));
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getContext().getPackageManager().canRequestPackageInstalls();
    }

    private void launchUnknownSourcesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return;
        } catch (Exception ignored) {
            // Fall through to the general security settings page.
        }
        try {
            Intent intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "请在系统设置中允许安装未知应用", Toast.LENGTH_LONG).show();
        }
    }

    private void installPushedApk(File apk) {
        if (!canInstallPackages()) {
            pushStatus = "需要允许安装未知应用";
            invalidate();
            launchUnknownSourcesSettings();
            return;
        }

        // The system package installer is the most compatible path: opening the
        // APK with ACTION_VIEW lets the TV's own installer show the confirmation.
        // Fall back to an explicit PackageInstaller session only if no handler
        // exists or the launch is rejected.
        Uri uri = PushFileProvider.uriFor(apk);
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            getContext().startActivity(open);
            refreshPending = true;
            pushPercent = 100;
            pushStatus = "已打开系统安装界面";
            invalidate();
            return;
        } catch (Exception e) {
            Log.w(TAG, "ACTION_VIEW installer failed; falling back to PackageInstaller", e);
        }

        installViaPackageInstaller(apk);
    }

    private void installViaPackageInstaller(File apk) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller installer = getContext().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
                session.fsync(output);
            }

            Intent resultIntent = new Intent(getContext(), InstallProxyActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    getContext(), sessionId, resultIntent, flags);
            session.commit(pendingIntent.getIntentSender());
            Log.i(TAG, "PackageInstaller commit session=" + sessionId);

            refreshPending = true;
            pushPercent = 100;
            pushStatus = "安装请求已发送，请在电视上确认";
            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "Cannot install pushed APK", e);
            pushStatus = "安装请求失败，请检查安装权限后重试";
            Toast.makeText(getContext(), "无法发起安装，请允许安装未知应用", Toast.LENGTH_LONG).show();
            invalidate();
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void drawPush(Canvas canvas) {
        if (!pushOpen || pushPanelProgress <= 0f) {
            return;
        }
        computePushPanel();
        float progress = Math.max(0f, Math.min(1f, pushPanelProgress));
        float left = pushPanel.left;
        float top = pushPanel.top;
        float right = pushPanel.right;
        float bottom = pushPanel.bottom;
        float radius = dp(18);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (172 * progress), 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        canvas.save();
        float scale = 0.965f + 0.035f * progress;
        canvas.scale(scale, scale, pushPanel.centerX(), pushPanel.centerY());

        paint.setColor(0x22FFFFFF);
        rect.set(left - dp(2), top - dp(2), right + dp(2), bottom + dp(2));
        canvas.drawRoundRect(rect, radius + dp(2), radius + dp(2), paint);
        paint.setColor(0xFF14161A);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF2A2D33);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.03f);
        drawText(canvas, "远程推送", left + dp(24), top + dp(32), sp(17), COLOR_CREAM);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "手机浏览器打开地址上传", left + dp(24), top + dp(50), sp(8), 0xFF747A83);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.14f);
        drawText(canvas, "BACK 停止", right - dp(18), top + dp(26), sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(16), top + dp(62), right - dp(16), top + dp(63), paint);

        rect.set(left + dp(24), top + dp(76), right - dp(24), top + dp(112));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0B0C0F);
        canvas.drawRoundRect(rect, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0xFF2A2D33);
        canvas.drawRoundRect(rect, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setLetterSpacing(0f);
        if (pushUrl.isEmpty()) {
            drawText(canvas, "正在获取电视地址…", left + dp(38), top + dp(99), sp(13), 0xFF777D86);
        } else {
            drawText(canvas, pushUrl, left + dp(38), top + dp(99), sp(15), COLOR_ACCENT);
        }

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.04f);
        measurePaint.setTypeface(typeRegular);
        measurePaint.setTextSize(sp(10));
        CharSequence status = TextUtils.ellipsize(pushStatus, measurePaint,
                right - left - dp(48), TextUtils.TruncateAt.END);
        drawText(canvas, status.toString(), left + dp(24), top + dp(136), sp(10), 0xFFC9C5BC);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(24), top + dp(146), right - dp(24), top + dp(149), paint);
        if (pushPercent > 0) {
            paint.setColor(COLOR_ACCENT);
            canvas.drawRect(left + dp(24), top + dp(146),
                    left + dp(24) + (right - left - dp(48)) * pushPercent / 100f,
                    top + dp(149), paint);
        }

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.02f);
        textPaint.setTextSize(sp(7.5f));
        drawText(canvas, "保存到", left + dp(24), top + dp(166), sp(7.5f), 0xFF5E646D);
        float saveLabelWidth = textPaint.measureText("保存到  ");
        measurePaint.setTypeface(typeRegular);
        measurePaint.setTextSize(sp(7.5f));
        measurePaint.setLetterSpacing(0.02f);
        CharSequence path = TextUtils.ellipsize(pushDirPath, measurePaint,
                right - left - dp(48) - saveLabelWidth, TextUtils.TruncateAt.START);
        drawText(canvas, path.toString(), left + dp(24) + saveLabelWidth, top + dp(166),
                sp(7.5f), 0xFF9A8F80);

        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.06f);
        drawText(canvas, "已接收文件", left + dp(24), top + dp(190), sp(9), 0xFFE1DCD2);
        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, pushFiles.size() + " 个  ·  OK 打开  ·  MENU 删除",
                right - dp(24), top + dp(190), sp(7), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float rowsTop = top + dp(200);
        float rowHeight = dp(38);
        if (pushFiles.isEmpty()) {
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.03f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawText(canvas, "还没有收到文件", left + (right - left) / 2f,
                    rowsTop + dp(76), sp(11), 0xFF777D86);
            textPaint.setTextAlign(Paint.Align.LEFT);
        } else {
            for (int i = 0; i < 5; i++) {
                int index = pushListScroll + i;
                if (index >= pushFiles.size()) {
                    break;
                }
                File file = pushFiles.get(index);
                float rowTop = rowsTop + i * rowHeight;
                boolean selected = index == pushSelected;
                if (selected) {
                    rect.set(left + dp(16), rowTop + dp(2), right - dp(16), rowTop + dp(36));
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(COLOR_CARD_FOCUS);
                    canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                }
                int color = selected ? COLOR_INK : 0xFFD2CDC3;
                int metaColor = selected ? 0xFF55575A : 0xFF6F757E;
                measurePaint.setTypeface(selected ? typeMedium : typeRegular);
                measurePaint.setTextSize(sp(10.5f));
                measurePaint.setLetterSpacing(0.03f);
                CharSequence name = TextUtils.ellipsize(file.getName(), measurePaint,
                        right - left - dp(190), TextUtils.TruncateAt.MIDDLE);
                textPaint.setTypeface(selected ? typeMedium : typeRegular);
                textPaint.setTextAlign(Paint.Align.LEFT);
                textPaint.setLetterSpacing(0.03f);
                drawText(canvas, name.toString(), left + dp(28), rowTop + dp(23),
                        sp(10.5f), color);
                textPaint.setTypeface(typeRegular);
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setLetterSpacing(0.02f);
                String size = formatFileSize(file.length());
                if (isApkFile(file)) {
                    size = "APK  ·  " + size;
                }
                drawText(canvas, size, right - dp(28), rowTop + dp(23), sp(8), metaColor);
            }
        }
        textPaint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(16), top + dp(400), right - dp(16), top + dp(401), paint);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.12f);
        drawText(canvas, "BACK  停止并返回", left + dp(24), top + dp(417), sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        drawText(canvas, "同一局域网 · 无需数据线", right - dp(24), top + dp(417),
                sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    private String menuLabel(int index) {
        switch (index) {
            case MENU_OPEN:
                return "打开";
            case MENU_PIN:
                return isPinned(menuTarget) ? "取消置顶" : "置顶";
            case MENU_INFO:
                return "信息";
            case MENU_UNINSTALL:
            default:
                return "卸载";
        }
    }

    private void executeMenuAction() {
        AppEntry target = menuTarget;
        int action = menuIndex;
        if (target == null) {
            closeMenu();
            return;
        }
        closeMenu();
        switch (action) {
            case MENU_OPEN:
                launch(target);
                break;
            case MENU_PIN:
                togglePin(target);
                break;
            case MENU_INFO:
                openAppInfo(target);
                break;
            case MENU_UNINSTALL:
                uninstallApp(target);
                break;
            default:
                break;
        }
    }

    private void openAppInfo(AppEntry entry) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + entry.component.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), "没有找到应用信息", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstallApp(AppEntry entry) {
        refreshPending = true;
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + entry.component.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            refreshPending = false;
            Toast.makeText(getContext(), "无法打开卸载界面", Toast.LENGTH_SHORT).show();
        }
    }

    private int menuRowAt(float x, float y) {
        if (!menuOpen || !menuPanel.contains(x, y)) {
            return -1;
        }
        float rowsTop = menuPanel.top + dp(64);
        if (y < rowsTop) {
            return -2;
        }
        int row = (int) ((y - rowsTop) / dp(46));
        return row >= 0 && row < MENU_ITEM_COUNT ? row : -1;
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        timeText = String.format(Locale.CHINA, "%02d:%02d", hour, minute);
        dateText = String.format(Locale.CHINA, "%d月%d日  %s",
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
                WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1]);
        if (hour < 5) {
            greeting = "夜深了";
        } else if (hour < 11) {
            greeting = "早上好";
        } else if (hour < 14) {
            greeting = "中午好";
        } else if (hour < 18) {
            greeting = "下午好";
        } else {
            greeting = "晚上好";
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeLayout();
        buildBackground(w, h);
    }

    private void computeLayout() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        padH = w * 0.0542f;
        padTop = h * 0.0704f;
        leftWidth = w * 0.2625f;
        dividerX = padH + leftWidth + dp(20);
        gridLeft = dividerX + dp(32);
        gridRight = w - padH;
        gridTop = h * 0.1704f;
        gridBottom = h - dp(52);
        gridHeight = Math.max(dp(120), gridBottom - gridTop);
        gapX = dp(12);
        gapY = dp(12);

        int count = apps.size();
        columns = count <= 4 ? 2 : 3;
        visibleRows = columns == 2 ? 2 : 3;
        rowMetrics();

        float contentHeight = contentHeight();
        gridOffsetY = contentHeight < gridHeight ? (gridHeight - contentHeight) / 2f : 0f;
        maxScroll = Math.max(0f, contentHeight - gridHeight);
        if (scroll > maxScroll) {
            scroll = maxScroll;
            targetScroll = maxScroll;
        }
        if (maxScroll <= 0f) {
            scroll = 0f;
            targetScroll = 0f;
        }
    }

    private void rowMetrics() {
        cardWidth = (gridRight - gridLeft - (columns - 1) * gapX) / columns;
        cardHeight = (gridHeight - (visibleRows - 1) * gapY) / visibleRows;
        rows = apps.isEmpty() ? 0 : (apps.size() + columns - 1) / columns;
    }

    private float contentHeight() {
        if (rows <= 0) {
            return 0f;
        }
        return rows * cardHeight + (rows - 1) * gapY;
    }

    private void buildBackground(int w, int h) {
        backgroundShader = new LinearGradient(0, 0, 0, h,
                new int[]{COLOR_BG_TOP, 0xFF090A0C, COLOR_BG_BOTTOM},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        warmGlow = new RadialGradient(w * 0.82f, -h * 0.08f, w * 0.72f,
                new int[]{0x46C58A5B, 0x1C9A623E, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        coolGlow = new RadialGradient(w * 0.02f, h * 1.04f, w * 0.62f,
                new int[]{0x2A1B545A, 0x0E123438, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(w * 0.5f, h * 0.48f, w * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x6E000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        dividerShader = new LinearGradient(0, padTop, 0, h - dp(44),
                new int[]{0x001D2025, 0x381D2025, 0x001D2025},
                new float[]{0f, 0.2f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(backgroundShader);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        paint.setShader(warmGlow);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(coolGlow);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(vignette);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);

        drawHeader(canvas);
        drawHero(canvas);
        drawDivider(canvas);
        drawApps(canvas);
        drawFooter(canvas);
        drawMenu(canvas);
        drawSettings(canvas);
        drawPush(canvas);
    }

    private void drawHeader(Canvas canvas) {
        // A tiny version of the app mark: a cream ring and one amber dot.
        float size = dp(21);
        float cx = padH + size / 2f;
        float cy = padTop + size / 2f;
        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeWidth(dp(1.15f));
        textPaint.setColor(0xFFEDE8DE);
        canvas.drawArc(new RectF(padH + dp(2), padTop + dp(2),
                padH + size - dp(2), padTop + size - dp(2)), -70f, 305f, false, textPaint);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(COLOR_ACCENT);
        canvas.drawCircle(padH + size * 0.78f, padTop + size * 0.22f, dp(2.2f), textPaint);

        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.18f);
        drawText(canvas, "HUSH", padH + dp(32), padTop + dp(15), sp(14), COLOR_CREAM);

        float brandWidth = textPaint.measureText("HUSH");
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "留白", padH + dp(32) + brandWidth + dp(8), padTop + dp(15), sp(9), 0xFF747A83);

        textPaint.setTextAlign(Paint.Align.LEFT);

        // The single global control: press UP from the top row of apps.
        float chipWidth = dp(72);
        float chipHeight = dp(28);
        float chipLeft = getWidth() - padH - chipWidth;
        float chipTop = padTop - dp(2);
        settingsChip.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight);
        if (headerFocused) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(COLOR_ACCENT, 0x24));
            rect.set(settingsChip);
            rect.inset(-dp(4), -dp(4));
            canvas.drawRoundRect(rect, dp(18), dp(18), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(headerFocused ? COLOR_CARD_FOCUS : 0x16191D);
        canvas.drawRoundRect(settingsChip, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(headerFocused ? 0x66FFFFFF : 0xFF30343B);
        canvas.drawRoundRect(settingsChip, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, "↑ 设置", settingsChip.centerX(), settingsChip.centerY() + sp(3.2f),
                sp(9), headerFocused ? COLOR_INK : 0xFFA5ABB3);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHero(Canvas canvas) {
        float x = padH;

        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.24f);
        textPaint.setColor(COLOR_ACCENT);
        canvas.drawCircle(x + dp(2), gridTop + dp(51), dp(2), textPaint);
        drawText(canvas, "现在", x + dp(11), gridTop + dp(55), sp(9), 0xFF7A8089);

        textPaint.setTypeface(typeLight);
        textPaint.setLetterSpacing(-0.035f);
        textPaint.setFontFeatureSettings("tnum");
        float clockSize = sp(84);
        textPaint.setTextSize(clockSize);
        float clockWidth = textPaint.measureText(timeText);
        if (clockWidth > leftWidth + dp(6)) {
            clockSize *= (leftWidth + dp(6)) / clockWidth;
        }
        drawText(canvas, timeText, x, dp(248), clockSize, COLOR_CREAM);
        textPaint.setFontFeatureSettings(null);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.055f);
        drawText(canvas, dateText, x + dp(2), dp(285), sp(14), COLOR_DATE);

        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, greeting, x + dp(2), dp(322), sp(17), 0xFFE7E2D8);

        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeWidth(dp(1));
        textPaint.setShader(dividerShader);
        canvas.drawLine(x + dp(2), dp(360), x + dp(30), dp(360), textPaint);
        textPaint.setShader(null);
        textPaint.setStyle(Paint.Style.FILL);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.12f);
        drawText(canvas, "少一点，刚刚好。", x + dp(2), dp(390), sp(10), 0xFF686E77);
    }

    private void drawDivider(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(dividerShader);
        canvas.drawRect(dividerX, padTop, dividerX + dp(0.8f), getHeight() - dp(44), paint);
        paint.setShader(null);
    }

    private void drawApps(Canvas canvas) {
        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.12f);
        drawText(canvas, "应用", gridLeft, gridTop - dp(21), sp(12), 0xFFE9E4DA);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.28f);
        float titleWidth = textPaint.measureText("应用");
        float appsX = gridLeft + titleWidth + dp(8);
        drawText(canvas, "APPS", appsX, gridTop - dp(21), sp(8), 0xFF5B616A);
        float appsWidth = textPaint.measureText("APPS");

        String count = loading && apps.isEmpty() ? "··" :
                String.format(Locale.CHINA, "%02d", apps.size());
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, count, appsX + appsWidth + dp(10), gridTop - dp(21), sp(8), 0xFF5B616A);
        textPaint.setTextAlign(Paint.Align.LEFT);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_HAIRLINE);
        canvas.drawRect(gridLeft, gridTop - dp(9), gridRight, gridTop - dp(8.2f), paint);

        if (loading && apps.isEmpty()) {
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.12f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawText(canvas, "正在整理桌面", (gridLeft + gridRight) / 2f,
                    gridTop + gridHeight / 2f, sp(11), 0xFF777D86);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        if (apps.isEmpty()) {
            textPaint.setTypeface(typeMedium);
            textPaint.setLetterSpacing(0.03f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawText(canvas, "这里还没有应用", (gridLeft + gridRight) / 2f,
                    gridTop + gridHeight / 2f - dp(6), sp(15), 0xFFD2CDC3);
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.1f);
            drawText(canvas, "按 OK 打开系统设置", (gridLeft + gridRight) / 2f,
                    gridTop + gridHeight / 2f + dp(20), sp(10), 0xFF737983);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        canvas.save();
        canvas.clipRect(gridLeft - dp(12), gridTop - dp(2), gridRight + dp(12), gridBottom + dp(2));
        for (int i = 0; i < apps.size(); i++) {
            int row = i / columns;
            float top = gridTop + gridOffsetY + row * (cardHeight + gapY) - scroll;
            if (top + cardHeight < gridTop - dp(8) || top > gridBottom + dp(8)) {
                continue;
            }
            drawCard(canvas, i, top);
        }
        canvas.restore();

        drawScrollBar(canvas);
    }

    private void drawCard(Canvas canvas, int index, float top) {
        AppEntry entry = apps.get(index);
        float focus = !headerFocused && index < focusProgress.length ? focusProgress[index] : 0f;
        float scale = 1f + 0.045f * focus;
        float centerX = gridLeft + (index % columns) * (cardWidth + gapX) + cardWidth / 2f;
        float centerY = top + cardHeight / 2f;

        canvas.save();
        canvas.scale(scale, scale, centerX, centerY);
        float drawTop = top - dp(3) * focus;

        cardRect.set(centerX - cardWidth / 2f, drawTop,
                centerX + cardWidth / 2f, drawTop + cardHeight);
        float radius = dp(18);

        if (focus > 0.01f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(COLOR_ACCENT, (int) (0x1C * focus)));
            rect.set(cardRect);
            rect.inset(-dp(7), -dp(7));
            canvas.drawRoundRect(rect, radius + dp(7), radius + dp(7), paint);
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (0x0C * focus)));
            rect.set(cardRect);
            rect.inset(-dp(3), -dp(3));
            canvas.drawRoundRect(rect, radius + dp(3), radius + dp(3), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(COLOR_CARD, COLOR_CARD_FOCUS, focus));
        canvas.drawRoundRect(cardRect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(blend(COLOR_CARD_BORDER, 0x66FFFFFF, focus));
        canvas.drawRoundRect(cardRect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float tile = Math.min(cardWidth * 0.245f, cardHeight * 0.34f);
        tile = Math.max(dp(36), Math.min(dp(66), tile));
        float iconCenterY = cardRect.top + cardHeight * 0.385f;
        rect.set(centerX - tile / 2f, iconCenterY - tile / 2f,
                centerX + tile / 2f, iconCenterY + tile / 2f);
        paint.setColor(0xFF0B0C0F);
        canvas.drawRoundRect(rect, tile * 0.28f, tile * 0.28f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x1FFFFFFF);
        canvas.drawRoundRect(rect, tile * 0.28f, tile * 0.28f, paint);
        paint.setStyle(Paint.Style.FILL);

        float iconSize = tile * 0.76f;
        if (entry.icon != null) {
            rect.set(centerX - iconSize / 2f, iconCenterY - iconSize / 2f,
                    centerX + iconSize / 2f, iconCenterY + iconSize / 2f);
            paint.setAlpha(255);
            canvas.drawBitmap(entry.icon, null, rect, paint);
        } else {
            textPaint.setTypeface(typeMedium);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setLetterSpacing(0);
            String letter = entry.label.isEmpty() ? "?" : entry.label.substring(0, 1);
            drawText(canvas, letter, centerX, iconCenterY + sp(9), sp(20), COLOR_CREAM);
        }

        float labelSize = cardWidth > dp(220) ? sp(16) : sp(14);
        measurePaint.set(textPaint);
        measurePaint.setTypeface(typeMedium);
        measurePaint.setTextSize(labelSize);
        measurePaint.setLetterSpacing(0.02f);
        CharSequence label = TextUtils.ellipsize(entry.label, measurePaint,
                cardWidth - dp(24), TextUtils.TruncateAt.END);
        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0.02f);
        drawText(canvas, label.toString(), centerX, cardRect.top + cardHeight * 0.845f,
                labelSize, blend(COLOR_LABEL, COLOR_INK, focus));
        textPaint.setTextAlign(Paint.Align.LEFT);

        if (isPinned(entry)) {
            paint.setColor(withAlpha(COLOR_ACCENT, focus > 0.5f ? 210 : 145));
            canvas.drawCircle(cardRect.left + dp(13), cardRect.top + dp(13), dp(2.2f), paint);
        }

        if (focus > 0.01f) {
            paint.setColor(withAlpha(COLOR_ACCENT, (int) (255 * focus)));
            canvas.drawCircle(cardRect.right - dp(13), cardRect.top + dp(13), dp(2.4f), paint);
        }

        canvas.restore();
    }

    private void drawScrollBar(Canvas canvas) {
        if (maxScroll <= 0f || rows <= visibleRows) {
            return;
        }
        float trackTop = gridTop;
        float trackBottom = gridBottom;
        float trackHeight = trackBottom - trackTop;
        float thumbHeight = Math.max(dp(28), trackHeight * visibleRows / rows);
        float thumbTop = trackTop + (trackHeight - thumbHeight) * (scroll / maxScroll);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x18FFFFFF);
        canvas.drawRoundRect(gridRight + dp(13), trackTop, gridRight + dp(15), trackBottom,
                dp(1), dp(1), paint);
        paint.setColor(0x66D8D3C9);
        canvas.drawRoundRect(gridRight + dp(13), thumbTop, gridRight + dp(15), thumbTop + thumbHeight,
                dp(1), dp(1), paint);
    }

    private void drawFooter(Canvas canvas) {
        float baseline = getHeight() - dp(26);
        float x = padH;

        drawKeycap(canvas, x, baseline, "OK", dp(29));
        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "打开", x + dp(37), baseline - dp(1), sp(10), 0xFF7A8089);

        float groupX = x + dp(93);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "← ↑ ↓ →", groupX, baseline - dp(1), sp(9), 0xFF9BA0A7);
        drawText(canvas, "选择", groupX + dp(54), baseline - dp(1), sp(10), 0xFF7A8089);
        drawText(canvas, "MENU 操作", x + dp(190), baseline - dp(1), sp(9), 0xFF7A8089);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.16f);
        if (defaultHome) {
            drawText(canvas, "HUSH  /  留白", getWidth() - padH, baseline - dp(1), sp(8), 0xFF4E545C);
        } else {
            drawText(canvas, "长按 MENU 桌面设置", getWidth() - padH, baseline - dp(1), sp(8), COLOR_ACCENT);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMenu(Canvas canvas) {
        if (!menuOpen || menuTarget == null || menuProgress <= 0f) {
            return;
        }
        computeMenuPanel();
        float progress = Math.max(0f, Math.min(1f, menuProgress));
        float left = menuPanel.left;
        float top = menuPanel.top;
        float right = menuPanel.right;
        float bottom = menuPanel.bottom;
        float radius = dp(18);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (172 * progress), 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        canvas.save();
        float scale = 0.965f + 0.035f * progress;
        canvas.scale(scale, scale, menuPanel.centerX(), menuPanel.centerY());

        paint.setColor(0x22FFFFFF);
        rect.set(left - dp(2), top - dp(2), right + dp(2), bottom + dp(2));
        canvas.drawRoundRect(rect, radius + dp(2), radius + dp(2), paint);
        paint.setColor(0xFF14161A);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF2A2D33);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float tile = dp(38);
        float tileLeft = left + dp(16);
        float tileTop = top + dp(13);
        rect.set(tileLeft, tileTop, tileLeft + tile, tileTop + tile);
        paint.setColor(0xFF0B0C0F);
        canvas.drawRoundRect(rect, tile * 0.28f, tile * 0.28f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x22FFFFFF);
        canvas.drawRoundRect(rect, tile * 0.28f, tile * 0.28f, paint);
        paint.setStyle(Paint.Style.FILL);
        if (menuTarget.icon != null) {
            paint.setAlpha(255);
            rect.set(tileLeft + tile * 0.13f, tileTop + tile * 0.13f,
                    tileLeft + tile * 0.87f, tileTop + tile * 0.87f);
            canvas.drawBitmap(menuTarget.icon, null, rect, paint);
        }

        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.02f);
        measurePaint.setTypeface(typeMedium);
        measurePaint.setTextSize(sp(15));
        measurePaint.setLetterSpacing(0.02f);
        CharSequence title = TextUtils.ellipsize(menuTarget.label, measurePaint,
                menuPanel.width() - dp(132), TextUtils.TruncateAt.END);
        drawText(canvas, title.toString(), left + dp(66), top + dp(32), sp(15), COLOR_CREAM);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.1f);
        String subtitle = isPinned(menuTarget) ? "已置顶  ·  应用操作" : "应用操作";
        drawText(canvas, subtitle, left + dp(66), top + dp(48), sp(8),
                isPinned(menuTarget) ? 0xFFD6B488 : 0xFF747A83);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.14f);
        drawText(canvas, "BACK 关闭", right - dp(16), top + dp(24), sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(16), top + dp(59), right - dp(16), top + dp(60), paint);

        for (int i = 0; i < MENU_ITEM_COUNT; i++) {
            float rowTop = top + dp(64) + i * dp(46);
            boolean selected = i == menuIndex;
            if (selected) {
                rect.set(left + dp(8), rowTop + dp(3), right - dp(8), rowTop + dp(43));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_CARD_FOCUS);
                canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            }
            int color;
            if (i == MENU_UNINSTALL) {
                color = selected ? 0xFF8B2E23 : 0xFFD38A7C;
            } else {
                color = selected ? COLOR_INK : 0xFFD2CDC3;
            }
            textPaint.setTypeface(selected ? typeMedium : typeRegular);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setLetterSpacing(0.03f);
            drawText(canvas, menuLabel(i), left + dp(24),
                    rowTop + dp(24) + sp(4.5f), sp(13), color);
        }

        canvas.restore();
    }

    private void drawKeycap(Canvas canvas, float x, float baseline, String label, float width) {
        float top = baseline - dp(13);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF3B3F46);
        canvas.drawRoundRect(x, top, x + width, top + dp(18), dp(5), dp(5), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, label, x + width / 2f, baseline - dp(3.5f), sp(8), 0xFFC4BEB4);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size, int color) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setStyle(Paint.Style.FILL);
        canvas.drawText(text, x, y, textPaint);
    }

    private void ensureVisible(int index, boolean animate) {
        if (index < 0 || appRows() == 0) {
            return;
        }
        int row = index / columns;
        float top = row * (cardHeight + gapY);
        float bottom = top + cardHeight;
        float target = targetScroll;
        if (top < target) {
            target = top;
        } else if (bottom > target + gridHeight) {
            target = bottom - gridHeight;
        }
        target = Math.max(0f, Math.min(maxScroll, target));
        if (Math.abs(target - targetScroll) < 0.5f) {
            return;
        }
        targetScroll = target;
        if (animate) {
            animateScroll(target);
        } else {
            scroll = target;
            invalidate();
        }
    }

    private int appRows() {
        return rows;
    }

    private void animateScroll(float target) {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }
        scrollAnimator = ValueAnimator.ofFloat(scroll, target);
        scrollAnimator.setDuration(220L);
        scrollAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        scrollAnimator.addUpdateListener(animation -> {
            scroll = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    private void setFocus(int index, boolean animate) {
        if (index < 0 || index >= apps.size() || index == focusedIndex) {
            return;
        }
        headerFocused = false;
        int previous = focusedIndex;
        focusedIndex = index;
        if (animate) {
            animateFocus(previous, 0f);
            animateFocus(index, 1f);
        } else {
            if (previous >= 0 && previous < focusProgress.length) {
                focusProgress[previous] = 0f;
            }
            focusProgress[index] = 1f;
        }
        ensureVisible(index, true);
        invalidate();
    }

    private void animateFocus(int index, float target) {
        if (index < 0 || index >= focusProgress.length) {
            return;
        }
        if (focusAnimators[index] != null) {
            focusAnimators[index].cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(focusProgress[index], target);
        animator.setDuration(180L);
        animator.setInterpolator(new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(animation -> {
            focusProgress[index] = (float) animation.getAnimatedValue();
            invalidate();
        });
        focusAnimators[index] = animator;
        animator.start();
    }

    private boolean moveFocus(int dx, int dy) {
        if (apps.isEmpty()) {
            return false;
        }
        int col = focusedIndex % columns;
        int row = focusedIndex / columns;
        int targetCol = col + dx;
        int targetRow = row + dy;
        if (targetCol < 0 || targetCol >= columns || targetRow < 0) {
            return true;
        }
        int target = targetRow * columns + targetCol;
        if (target >= apps.size()) {
            return true;
        }
        setFocus(target, true);
        playSoundEffect(SoundEffectConstants.CLICK);
        return true;
    }

    private void launchFocused() {
        if (focusedIndex >= 0 && focusedIndex < apps.size()) {
            launch(apps.get(focusedIndex));
        } else if (apps.isEmpty()) {
            openSystemSettings();
        }
    }

    private void launch(AppEntry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.setComponent(entry.component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException first) {
            try {
                Intent fallback = new Intent(Intent.ACTION_MAIN);
                fallback.addCategory(Intent.CATEGORY_LAUNCHER);
                fallback.setComponent(entry.component);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(fallback);
            } catch (ActivityNotFoundException second) {
                Toast.makeText(getContext(), "打不开 " + entry.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openSystemSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), "没有找到系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (pushOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (pushSelected > 0) {
                        pushSelected--;
                        adjustPushScroll();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (pushSelected >= 0 && pushSelected < pushFiles.size() - 1) {
                        pushSelected++;
                        adjustPushScroll();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_SPACE:
                    openSelectedPushFile();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    confirmDeletePushFile();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    closePush();
                    return true;
                default:
                    return true;
            }
        }

        if (settingsOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    settingsIndex = (settingsIndex + SETTINGS_ITEM_COUNT - 1) % SETTINGS_ITEM_COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    settingsIndex = (settingsIndex + 1) % SETTINGS_ITEM_COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_SPACE:
                    executeSettingsAction();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    closeSettings(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        if (menuOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    menuIndex = (menuIndex + MENU_ITEM_COUNT - 1) % MENU_ITEM_COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    menuIndex = (menuIndex + 1) % MENU_ITEM_COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_SPACE:
                    executeMenuAction();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    closeMenu();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        if (headerFocused) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    headerFocused = false;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_MENU:
                    openSettings();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    headerFocused = false;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        if (event.getRepeatCount() > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveFocus(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveFocus(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusedIndex >= 0 && focusedIndex / columns == 0) {
                    headerFocused = true;
                    playSoundEffect(SoundEffectConstants.CLICK);
                    invalidate();
                    return true;
                }
                return moveFocus(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveFocus(0, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_SPACE:
                launchFocused();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                    if (!apps.isEmpty() && focusedIndex >= 0) {
                        openMenu();
                    } else if (desktopSettingsAction != null) {
                        desktopSettingsAction.run();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_SETTINGS:
                if (desktopSettingsAction != null) {
                    desktopSettingsAction.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (settingsOpen || pushOpen) {
                return true;
            }
            closeMenu();
            if (desktopSettingsAction != null) {
                desktopSettingsAction.run();
            }
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (pushOpen) {
            if (action == MotionEvent.ACTION_UP) {
                int row = pushRowAt(event.getX(), event.getY());
                if (row >= 0) {
                    pushSelected = row;
                    openSelectedPushFile();
                } else if (!pushPanel.contains(event.getX(), event.getY())) {
                    closePush();
                }
            }
            return true;
        }
        if (settingsOpen) {
            if (action == MotionEvent.ACTION_UP) {
                int row = settingsRowAt(event.getX(), event.getY());
                if (row >= 0) {
                    settingsIndex = row;
                    executeSettingsAction();
                } else if (!settingsPanel.contains(event.getX(), event.getY())) {
                    closeSettings(true);
                }
            }
            return true;
        }
        if (menuOpen) {
            if (action == MotionEvent.ACTION_UP) {
                int row = menuRowAt(event.getX(), event.getY());
                if (row >= 0) {
                    menuIndex = row;
                    executeMenuAction();
                } else if (!menuPanel.contains(event.getX(), event.getY())) {
                    closeMenu();
                }
            }
            return true;
        }
        if (settingsChip.contains(event.getX(), event.getY())) {
            if (action == MotionEvent.ACTION_UP) {
                headerFocused = true;
                openSettings();
                performClick();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int hit = hitTest(event.getX(), event.getY());
            if (hit != pressedIndex) {
                pressedIndex = hit;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            int hit = hitTest(event.getX(), event.getY());
            if (hit >= 0 && hit < apps.size()) {
                setFocus(hit, true);
                pressedIndex = -1;
                performClick();
                launchFocused();
                return true;
            }
            if (apps.isEmpty()) {
                performClick();
                openSystemSettings();
            }
            pressedIndex = -1;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressedIndex = -1;
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int hitTest(float x, float y) {
        if (apps.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < apps.size(); i++) {
            int row = i / columns;
            float top = gridTop + gridOffsetY + row * (cardHeight + gapY) - scroll;
            if (top + cardHeight < gridTop || top > gridBottom) {
                continue;
            }
            float left = gridLeft + (i % columns) * (cardWidth + gapX);
            if (x >= left && x <= left + cardWidth && y >= top && y <= top + cardHeight) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static int blend(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped);
        return Color.argb(a, r, g, b);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
