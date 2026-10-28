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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
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
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final String LAYOUT_PREFS = "hush_layout";
    private static final String LAYOUT_PURE = "pure_mode";
    private static final String LAYOUT_TAGLINE = "show_tagline";
    private static final String LAYOUT_CLOCK = "clock_position";
    private static final String LAYOUT_CENTER = "center_clock";
    private static final String LAYOUT_WALLPAPER = "wallpaper_path";
    private static final int MENU_OPEN = 0;
    private static final int MENU_PIN = 1;
    private static final int MENU_INFO = 2;
    private static final int MENU_UNINSTALL = 3;
    private static final int MENU_ITEM_COUNT = 4;
    private static final int SETTINGS_ITEM_COUNT = 7;
    private static final long[] SCREEN_TIMEOUT_VALUES = {
            5L * 60 * 1000, 10L * 60 * 1000, 15L * 60 * 1000,
            30L * 60 * 1000, 60L * 60 * 1000, Integer.MAX_VALUE
    };
    private static final String[] SCREEN_TIMEOUT_LABELS = {
            "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时", "永不"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppRepository repository;
    private final SharedPreferences pinPrefs;
    private final SharedPreferences layoutPrefs;
    private final Collator collator;
    private final List<String> pinnedKeys = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint measurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF cardRect = new RectF();

    private final ColorMatrixColorFilter cardBackdropFilter = createCardBackdropFilter();
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
    private String screenTimeoutSummary = "—";
    private boolean pureMode;
    private boolean showTagline;
    private String clockPosition = "left";
    private boolean centerClock;
    private String wallpaperPath = "";
    private Bitmap wallpaperBitmap;
    private Bitmap wallpaperBlurred;
    private boolean wallpaperOpen;
    private float wallpaperProgress;
    private ValueAnimator wallpaperAnimator;
    private final RectF wallpaperPanel = new RectF();
    private RemotePushServer wallpaperServer;
    private String wallpaperUrl = "";
    private String wallpaperStatus = "";
    private final List<File> wallpaperFiles = new ArrayList<>();
    private final Map<String, Bitmap> wallpaperThumbs = new HashMap<>();
    private int wallpaperSelected = -1;
    private int wallpaperScroll;
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
        layoutPrefs = context.getSharedPreferences(LAYOUT_PREFS, Context.MODE_PRIVATE);
        pureMode = layoutPrefs.getBoolean(LAYOUT_PURE, false);
        showTagline = layoutPrefs.getBoolean(LAYOUT_TAGLINE, true);
        clockPosition = layoutPrefs.getString(LAYOUT_CLOCK, "left");
        centerClock = layoutPrefs.getBoolean(LAYOUT_CENTER, false);
        wallpaperPath = layoutPrefs.getString(LAYOUT_WALLPAPER, "");
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
        closeWallpaper();
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
        loadWallpaperIfNeeded();
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
        closeWallpaper();
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
        refreshScreenTimeoutSummary();
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
        float height = dp(460);
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
                return "熄屏时间";
            case 5:
                return "布局设置";
            case 6:
            default:
                return "远程推送";
        }
    }

    private String layoutSummary() {
        if (pureMode) {
            return "纯净";
        }
        String summary = "标准 · " + clockPositionLabel(clockPosition);
        return centerClock ? summary + " · 居中" : summary;
    }

    private String clockPositionLabel(String position) {
        switch (position) {
            case "top":
                return "上";
            case "right":
                return "右";
            case "bottom":
                return "下";
            case "left":
            default:
                return "左";
        }
    }

    private void showLayoutDialog() {
        Context context = getContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) dp(24), (int) dp(8), (int) dp(24), 0);

        CheckBox pure = new CheckBox(context);
        pure.setText("纯净模式");
        pure.setTextSize(16);
        pure.setTextColor(COLOR_CREAM);
        pure.setChecked(pureMode);
        root.addView(pure);

        TextView pureHint = new TextView(context);
        pureHint.setText("只保留全屏应用宫格，隐藏时钟和文案");
        pureHint.setTextSize(12);
        pureHint.setTextColor(0xFF858B94);
        pureHint.setPadding((int) dp(32), 0, 0, (int) dp(10));
        root.addView(pureHint);

        CheckBox tagline = new CheckBox(context);
        tagline.setText("显示桌面提示文案");
        tagline.setTextSize(15);
        tagline.setTextColor(COLOR_CREAM);
        tagline.setChecked(showTagline);
        root.addView(tagline);

        TextView positionLabel = new TextView(context);
        positionLabel.setText("时间位置");
        positionLabel.setTextSize(12);
        positionLabel.setTextColor(0xFF858B94);
        positionLabel.setPadding(0, (int) dp(12), 0, (int) dp(4));
        root.addView(positionLabel);

        RadioGroup positions = new RadioGroup(context);
        positions.setOrientation(RadioGroup.HORIZONTAL);
        String[] values = {"left", "top", "right", "bottom"};
        String[] labels = {"左", "上", "右", "下"};
        int[] ids = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            RadioButton button = new RadioButton(context);
            button.setText(labels[i]);
            button.setTextSize(15);
            button.setTextColor(COLOR_CREAM);
            button.setId(View.generateViewId());
            ids[i] = button.getId();
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            positions.addView(button, params);
            if (values[i].equals(clockPosition)) {
                positions.check(button.getId());
            }
        }
        root.addView(positions);

        CheckBox center = new CheckBox(context);
        center.setText("时间居中");
        center.setTextSize(14);
        center.setTextColor(COLOR_CREAM);
        center.setChecked(centerClock);
        center.setPadding(0, (int) dp(8), 0, 0);
        root.addView(center);

        Runnable updateEnabled = () -> {
            boolean enabled = !pure.isChecked();
            tagline.setEnabled(enabled);
            tagline.setAlpha(enabled ? 1f : 0.35f);
            positionLabel.setEnabled(enabled);
            positionLabel.setAlpha(enabled ? 1f : 0.35f);
            positions.setEnabled(enabled);
            for (int i = 0; i < positions.getChildCount(); i++) {
                View child = positions.getChildAt(i);
                child.setEnabled(enabled);
                child.setAlpha(enabled ? 1f : 0.35f);
            }
            center.setEnabled(enabled);
            center.setAlpha(enabled ? 1f : 0.35f);
        };
        pure.setOnCheckedChangeListener((buttonView, isChecked) -> updateEnabled.run());
        updateEnabled.run();

        AlertDialog dialog = new AlertDialog.Builder(context,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("布局设置")
                .setView(root)
                .setPositiveButton("保存", (d, which) -> {
                    String position = "left";
                    for (int i = 0; i < ids.length; i++) {
                        if (positions.getCheckedRadioButtonId() == ids[i]) {
                            position = values[i];
                            break;
                        }
                    }
                    pureMode = pure.isChecked();
                    showTagline = tagline.isChecked();
                    centerClock = center.isChecked();
                    clockPosition = position;
                    layoutPrefs.edit()
                            .putBoolean(LAYOUT_PURE, pureMode)
                            .putBoolean(LAYOUT_TAGLINE, showTagline)
                            .putString(LAYOUT_CLOCK, clockPosition)
                            .putBoolean(LAYOUT_CENTER, centerClock)
                            .apply();
                    closeSettings(false);
                    applyLayoutChange();
                    Toast.makeText(getContext(), "布局已更新", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("壁纸设置", (d, which) -> openWallpaper())
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> pure.requestFocus());
        dialog.show();
    }

    private void applyLayoutChange() {
        String previousKey = null;
        if (focusedIndex >= 0 && focusedIndex < apps.size()) {
            previousKey = apps.get(focusedIndex).key();
        }
        computeLayout();
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

    private void refreshScreenTimeoutSummary() {
        try {
            int value = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60 * 1000);
            screenTimeoutSummary = formatScreenTimeout(value);
        } catch (Exception e) {
            screenTimeoutSummary = "—";
        }
    }

    private String formatScreenTimeout(int value) {
        if (value >= 24 * 60 * 60 * 1000 || value == Integer.MAX_VALUE) {
            return "永不";
        }
        if (value >= 60 * 60 * 1000 && value % (60 * 60 * 1000) == 0) {
            return (value / (60 * 60 * 1000)) + " 小时";
        }
        return Math.max(1, value / 60000) + " 分钟";
    }

    private void showScreenTimeoutDialog() {
        if (!canWriteSettings()) {
            new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("需要修改系统设置权限")
                    .setMessage("设置熄屏时间需要允许「留白」修改系统设置。")
                    .setPositiveButton("去授权", (dialog, which) -> openWriteSettingsPermission())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        refreshScreenTimeoutSummary();
        int selected = -1;
        try {
            int current = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, -1);
            for (int i = 0; i < SCREEN_TIMEOUT_VALUES.length; i++) {
                if (SCREEN_TIMEOUT_VALUES[i] == Integer.MAX_VALUE
                        && current >= 24 * 60 * 60 * 1000) {
                    selected = i;
                    break;
                }
                if (Math.abs(SCREEN_TIMEOUT_VALUES[i] - current) < 1000) {
                    selected = i;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("熄屏时间")
                .setSingleChoiceItems(SCREEN_TIMEOUT_LABELS, selected, (dialog, which) -> {
                    applyScreenTimeout((int) SCREEN_TIMEOUT_VALUES[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean canWriteSettings() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.System.canWrite(getContext());
    }

    private void openWriteSettingsPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "请在系统设置中允许留白修改系统设置",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void applyScreenTimeout(int value) {
        try {
            Settings.System.putInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, value);
            screenTimeoutSummary = formatScreenTimeout(value);
            Toast.makeText(getContext(), "熄屏时间已设为 " + screenTimeoutSummary,
                    Toast.LENGTH_SHORT).show();
            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "Cannot write screen timeout", e);
            Toast.makeText(getContext(), "设置失败，请允许留白修改系统设置",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void executeSettingsAction() {
        if (settingsIndex == 6) {
            closeSettings(false);
            openPush();
            return;
        }
        if (settingsIndex == 5) {
            showLayoutDialog();
            return;
        }
        if (settingsIndex == 4) {
            showScreenTimeoutDialog();
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
            if (i == 4 || i == 5) {
                String value = i == 4 ? screenTimeoutSummary : layoutSummary();
                drawText(canvas, value, right - dp(40),
                        rowTop + dp(25) + sp(4.5f), sp(8),
                        selected ? 0xFF55575A : 0xFF7A8089);
            }
            drawText(canvas, "›", right - dp(24), rowTop + dp(25) + sp(4.5f), sp(14),
                    selected ? 0xFF4A4B4D : 0xFF5E646D);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.03f);
        drawText(canvas, "开发者选项被隐藏时，先到「关于设备」连点版本号。",
                left + dp(22), top + dp(442), sp(7.5f), 0xFF6A7079);
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
        loadWallpaperIfNeeded();
        if (wallpaperBitmap != null) {
            buildBlurredWallpaper();
        }
    }

    private void computeLayout() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        padH = w * 0.0542f;
        padTop = h * 0.0704f;
        gapX = dp(12);
        gapY = dp(12);

        if (pureMode) {
            leftWidth = 0f;
            dividerX = 0f;
            gridLeft = padH;
            gridRight = w - padH;
            gridTop = h * 0.158f;
            gridBottom = h - dp(44);
            columns = 4;
            visibleRows = 3;
        } else if ("top".equals(clockPosition)) {
            leftWidth = w - padH * 2f;
            dividerX = 0f;
            gridLeft = padH;
            gridRight = w - padH;
            gridTop = h * 0.36f;
            gridBottom = h - dp(48);
            columns = 4;
            visibleRows = 2;
        } else if ("bottom".equals(clockPosition)) {
            leftWidth = w - padH * 2f;
            dividerX = 0f;
            gridLeft = padH;
            gridRight = w - padH;
            gridTop = h * 0.19f;
            gridBottom = h * 0.62f;
            columns = 4;
            visibleRows = 2;
        } else {
            leftWidth = w * 0.2625f;
            if ("right".equals(clockPosition)) {
                dividerX = w - padH - leftWidth - dp(20);
                gridLeft = padH;
                gridRight = dividerX - dp(32);
            } else {
                dividerX = padH + leftWidth + dp(20);
                gridLeft = dividerX + dp(32);
                gridRight = w - padH;
            }
            gridTop = h * 0.1704f;
            gridBottom = h - dp(52);

            int count = apps.size();
            columns = count <= 4 ? 2 : 3;
            visibleRows = columns == 2 ? 2 : 3;
        }

        gridHeight = Math.max(dp(120), gridBottom - gridTop);
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

    private boolean sideClock() {
        return !"top".equals(clockPosition) && !"bottom".equals(clockPosition);
    }

    private boolean rightClock() {
        return "right".equals(clockPosition);
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

        paint.setAlpha(255);
        paint.setColorFilter(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        if (wallpaperBitmap != null) {
            drawWallpaperBitmap(canvas, wallpaperBitmap);
            paint.setShader(null);
        } else {
            paint.setShader(backgroundShader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(warmGlow);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(coolGlow);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(vignette);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
        paint.setShader(null);

        drawHeader(canvas);
        if (pureMode) {
            drawApps(canvas);
        } else if ("top".equals(clockPosition)) {
            drawHeroTop(canvas);
            drawApps(canvas);
        } else if ("bottom".equals(clockPosition)) {
            drawHeroBottom(canvas);
            drawApps(canvas);
        } else {
            drawHero(canvas);
            drawApps(canvas);
            drawFooter(canvas);
        }
        drawMenu(canvas);
        drawSettings(canvas);
        drawPush(canvas);
        drawWallpaperDialog(canvas);
    }

    private void loadWallpaperIfNeeded() {
        if (wallpaperBitmap != null || wallpaperPath.isEmpty()) {
            return;
        }
        File file = new File(wallpaperPath);
        if (!file.isFile()) {
            wallpaperPath = "";
            layoutPrefs.edit().remove(LAYOUT_WALLPAPER).apply();
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Bitmap bitmap = decodeImage(file, getWidth(), getHeight());
        if (bitmap != null) {
            wallpaperBitmap = bitmap;
            buildBlurredWallpaper();
            invalidate();
        }
    }

    private boolean applyWallpaperFile(File file) {
        Bitmap bitmap = decodeImage(file, Math.max(getWidth(), 1280),
                Math.max(getHeight(), 720));
        if (bitmap == null) {
            return false;
        }
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
        }
        wallpaperBitmap = bitmap;
        wallpaperPath = file.getAbsolutePath();
        layoutPrefs.edit().putString(LAYOUT_WALLPAPER, wallpaperPath).apply();
        buildBlurredWallpaper();
        invalidate();
        return true;
    }

    private void clearWallpaper() {
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
            wallpaperBitmap = null;
        }
        if (wallpaperBlurred != null) {
            wallpaperBlurred.recycle();
            wallpaperBlurred = null;
        }
        wallpaperPath = "";
        layoutPrefs.edit().remove(LAYOUT_WALLPAPER).apply();
        invalidate();
    }

    private Bitmap decodeImage(File file, int maxWidth, int maxHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidth
                && bounds.outHeight / (sample * 2) >= maxHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    private void drawWallpaperBitmap(Canvas canvas, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        paint.setAlpha(255);
        paint.setColorFilter(null);
        paint.setShader(null);
        RectF dst = new RectF(0, 0, getWidth(), getHeight());
        drawBitmapCrop(canvas, bitmap, dst);
    }

    private void buildBlurredWallpaper() {
        int w = getWidth();
        int h = getHeight();
        if (wallpaperBitmap == null || w <= 0 || h <= 0) {
            if (wallpaperBlurred != null) {
                wallpaperBlurred.recycle();
                wallpaperBlurred = null;
            }
            return;
        }
        int smallWidth = Math.max(1, w / 36);
        int smallHeight = Math.max(1, h / 36);
        Bitmap small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        drawBitmapCrop(smallCanvas, wallpaperBitmap,
                new RectF(0, 0, smallWidth, smallHeight));
        Bitmap blurred = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();
        if (wallpaperBlurred != null) {
            wallpaperBlurred.recycle();
        }
        wallpaperBlurred = blurred;
    }

    private void drawBitmapCrop(Canvas canvas, Bitmap bitmap, RectF dst) {
        if (bitmap == null || bitmap.isRecycled() || dst.width() <= 0 || dst.height() <= 0) {
            return;
        }
        float scale = Math.max(dst.width() / bitmap.getWidth(),
                dst.height() / bitmap.getHeight());
        float sourceWidth = dst.width() / scale;
        float sourceHeight = dst.height() / scale;
        int left = (int) ((bitmap.getWidth() - sourceWidth) / 2f);
        int top = (int) ((bitmap.getHeight() - sourceHeight) / 2f);
        Rect src = new Rect(left, top, (int) (left + sourceWidth), (int) (top + sourceHeight));
        paint.setAlpha(255);
        paint.setColorFilter(null);
        paint.setShader(null);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(bitmap, src, dst, paint);
        paint.setFilterBitmap(false);
    }

    private void computeWallpaperPanel() {
        float width = dp(660);
        float height = dp(430);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        wallpaperPanel.set(left, top, left + width, top + height);
    }

    private void openWallpaper() {
        closeMenu();
        closeSettings(false);
        closePush();
        wallpaperOpen = true;
        wallpaperUrl = "";
        wallpaperStatus = "正在启动壁纸接收服务…";
        wallpaperSelected = -1;
        wallpaperScroll = 0;
        refreshWallpapers(null);
        computeWallpaperPanel();
        wallpaperProgress = 0f;
        if (wallpaperAnimator != null) {
            wallpaperAnimator.cancel();
        }
        wallpaperAnimator = ValueAnimator.ofFloat(0f, 1f);
        wallpaperAnimator.setDuration(160L);
        wallpaperAnimator.setInterpolator(new DecelerateInterpolator(2f));
        wallpaperAnimator.addUpdateListener(animation -> {
            wallpaperProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        wallpaperAnimator.start();

        wallpaperServer = new RemotePushServer(getContext(), new RemotePushServer.Listener() {
            @Override
            public void onReady(String url, String directory) {
                if (!wallpaperOpen) {
                    return;
                }
                wallpaperUrl = url == null ? "" : url;
                wallpaperStatus = url == null
                        ? "未检测到局域网 IP，请确认电视已联网"
                        : "等待手机上传图片…";
                invalidate();
            }

            @Override
            public void onProgress(int percent) {
                if (!wallpaperOpen) {
                    return;
                }
                wallpaperStatus = "接收中 " + percent + "%";
                invalidate();
            }

            @Override
            public void onFileReceived(File file) {
                if (!wallpaperOpen) {
                    return;
                }
                if (!isImageFile(file) || !applyWallpaperFile(file)) {
                    file.delete();
                    wallpaperStatus = "只支持图片文件";
                    refreshWallpapers(null);
                    invalidate();
                    return;
                }
                wallpaperStatus = "已设为壁纸：" + file.getName();
                refreshWallpapers(file.getName());
                invalidate();
            }

            @Override
            public void onError(String message) {
                if (!wallpaperOpen) {
                    return;
                }
                wallpaperStatus = message;
                invalidate();
            }
        }, RemotePushServer.getWallpaperDir(getContext()), true);
        wallpaperServer.start();
        invalidate();
    }

    private void closeWallpaper() {
        wallpaperOpen = false;
        wallpaperProgress = 0f;
        if (wallpaperAnimator != null) {
            wallpaperAnimator.cancel();
            wallpaperAnimator = null;
        }
        if (wallpaperServer != null) {
            wallpaperServer.stop();
            wallpaperServer = null;
        }
        for (Bitmap bitmap : wallpaperThumbs.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        wallpaperThumbs.clear();
        wallpaperSelected = -1;
        wallpaperScroll = 0;
        invalidate();
    }

    private void refreshWallpapers(String selectName) {
        File dir = RemotePushServer.getWallpaperDir(getContext());
        File[] files = dir.listFiles();
        wallpaperFiles.clear();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                if (file.isFile() && isImageFile(file)) {
                    wallpaperFiles.add(file);
                }
            }
        }

        if (selectName != null) {
            for (int i = 0; i < wallpaperFiles.size(); i++) {
                if (selectName.equals(wallpaperFiles.get(i).getName())) {
                    wallpaperSelected = i;
                    break;
                }
            }
        }
        if (wallpaperSelected < 0 && !wallpaperPath.isEmpty()) {
            for (int i = 0; i < wallpaperFiles.size(); i++) {
                if (wallpaperPath.equals(wallpaperFiles.get(i).getAbsolutePath())) {
                    wallpaperSelected = i;
                    break;
                }
            }
        }
        if (wallpaperFiles.isEmpty()) {
            wallpaperSelected = -1;
            wallpaperScroll = 0;
        } else {
            if (wallpaperSelected < 0 || wallpaperSelected >= wallpaperFiles.size()) {
                wallpaperSelected = 0;
            }
            ensureWallpaperVisible();
        }
        invalidate();
    }

    private void ensureWallpaperVisible() {
        int visible = 8;
        if (wallpaperSelected < wallpaperScroll) {
            wallpaperScroll = wallpaperSelected;
        } else if (wallpaperSelected >= wallpaperScroll + visible) {
            wallpaperScroll = wallpaperSelected - visible + 1;
        }
        int maxScroll = Math.max(0, wallpaperFiles.size() - visible);
        wallpaperScroll = Math.max(0, Math.min(maxScroll, wallpaperScroll));
    }

    private Bitmap wallpaperThumb(File file) {
        String key = file.getAbsolutePath();
        Bitmap cached = wallpaperThumbs.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap bitmap = decodeImage(file, 320, 200);
        if (bitmap != null) {
            wallpaperThumbs.put(key, bitmap);
        }
        return bitmap;
    }

    private void applySelectedWallpaper() {
        if (wallpaperSelected < 0 || wallpaperSelected >= wallpaperFiles.size()) {
            return;
        }
        File file = wallpaperFiles.get(wallpaperSelected);
        if (applyWallpaperFile(file)) {
            wallpaperStatus = "壁纸已设置：" + file.getName();
            Toast.makeText(getContext(), "壁纸已设置", Toast.LENGTH_SHORT).show();
        } else {
            wallpaperStatus = "这张图片无法读取";
        }
        invalidate();
    }

    private void confirmDeleteWallpaper() {
        if (wallpaperSelected < 0 || wallpaperSelected >= wallpaperFiles.size()) {
            return;
        }
        File file = wallpaperFiles.get(wallpaperSelected);
        new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除壁纸")
                .setMessage("确定删除 " + file.getName() + "？")
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean isCurrent = wallpaperPath.equals(file.getAbsolutePath());
                    if (isCurrent) {
                        clearWallpaper();
                    }
                    if (file.delete()) {
                        Bitmap thumb = wallpaperThumbs.remove(file.getAbsolutePath());
                        if (thumb != null && !thumb.isRecycled()) {
                            thumb.recycle();
                        }
                        wallpaperStatus = "已删除 " + file.getName();
                        refreshWallpapers(null);
                    } else {
                        Toast.makeText(getContext(), "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int wallpaperThumbAt(float x, float y) {
        if (!wallpaperOpen || !wallpaperPanel.contains(x, y)) {
            return -1;
        }
        float thumbTop = wallpaperPanel.top + dp(184);
        float thumbHeight = dp(80);
        float rowGap = dp(10);
        if (y < thumbTop || y > thumbTop + 2 * thumbHeight + rowGap) {
            return -1;
        }
        int row = y < thumbTop + thumbHeight ? 0 : 1;
        float gap = dp(12);
        float thumbWidth = (wallpaperPanel.width() - dp(48) - 3 * gap) / 4f;
        float column = (x - wallpaperPanel.left - dp(24)) / (thumbWidth + gap);
        if (column < 0 || column >= 4) {
            return -1;
        }
        int index = wallpaperScroll + row * 4 + (int) column;
        return index >= 0 && index < wallpaperFiles.size() ? index : -1;
    }

    private void drawWallpaperDialog(Canvas canvas) {
        if (!wallpaperOpen || wallpaperProgress <= 0f) {
            return;
        }
        computeWallpaperPanel();
        float progress = Math.max(0f, Math.min(1f, wallpaperProgress));
        float left = wallpaperPanel.left;
        float top = wallpaperPanel.top;
        float right = wallpaperPanel.right;
        float bottom = wallpaperPanel.bottom;
        float radius = dp(18);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (172 * progress), 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        canvas.save();
        float scale = 0.965f + 0.035f * progress;
        canvas.scale(scale, scale, wallpaperPanel.centerX(), wallpaperPanel.centerY());

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
        drawText(canvas, "壁纸设置", left + dp(24), top + dp(32), sp(17), COLOR_CREAM);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "手机上传图片，收到后自动设为壁纸", left + dp(24), top + dp(50),
                sp(8), 0xFF747A83);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.14f);
        drawText(canvas, "BACK 关闭", right - dp(18), top + dp(26), sp(8), 0xFF5E646D);
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
        if (wallpaperUrl.isEmpty()) {
            drawText(canvas, "正在获取电视地址…", left + dp(38), top + dp(99), sp(13),
                    0xFF777D86);
        } else {
            drawText(canvas, wallpaperUrl, left + dp(38), top + dp(99), sp(15), COLOR_ACCENT);
        }

        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.04f);
        measurePaint.setTypeface(typeRegular);
        measurePaint.setTextSize(sp(10));
        CharSequence status = TextUtils.ellipsize(wallpaperStatus, measurePaint,
                right - left - dp(48), TextUtils.TruncateAt.END);
        drawText(canvas, status.toString(), left + dp(24), top + dp(136), sp(10),
                0xFFC9C5BC);

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(24), top + dp(146), right - dp(24), top + dp(149), paint);

        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.06f);
        drawText(canvas, "历史壁纸", left + dp(24), top + dp(172), sp(9), 0xFFE1DCD2);
        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, wallpaperFiles.size() + " 张  ·  OK 设为壁纸  ·  MENU 删除",
                right - dp(24), top + dp(172), sp(7), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float thumbTop = top + dp(184);
        float gap = dp(12);
        float thumbWidth = (right - left - dp(48) - 3 * gap) / 4f;
        float thumbHeight = dp(80);
        if (wallpaperFiles.isEmpty()) {
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.03f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawText(canvas, "还没有壁纸", left + (right - left) / 2f,
                    thumbTop + dp(46), sp(12), 0xFF777D86);
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.08f);
            drawText(canvas, "手机上传图片后会自动设置", left + (right - left) / 2f,
                    thumbTop + dp(68), sp(9), 0xFF5E646D);
            textPaint.setTextAlign(Paint.Align.LEFT);
        } else {
            for (int i = 0; i < 8; i++) {
                int index = wallpaperScroll + i;
                if (index >= wallpaperFiles.size()) {
                    break;
                }
                int row = i / 4;
                int column = i % 4;
                float thumbLeft = left + dp(24) + column * (thumbWidth + gap);
                float thumbTopRow = thumbTop + row * (thumbHeight + dp(10));
                RectF thumbRect = new RectF(thumbLeft, thumbTopRow,
                        thumbLeft + thumbWidth, thumbTopRow + thumbHeight);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF0B0C0F);
                canvas.drawRoundRect(thumbRect, dp(8), dp(8), paint);
                Bitmap thumb = wallpaperThumb(wallpaperFiles.get(index));
                if (thumb != null) {
                    canvas.save();
                    canvas.clipRect(thumbRect);
                    drawBitmapCrop(canvas, thumb, thumbRect);
                    canvas.restore();
                } else {
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    textPaint.setLetterSpacing(0.04f);
                    drawText(canvas, "?", thumbRect.centerX(), thumbRect.centerY() + sp(4),
                            sp(16), 0xFF5E646D);
                    textPaint.setTextAlign(Paint.Align.LEFT);
                }
                boolean selected = index == wallpaperSelected;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(selected ? dp(2) : dp(1));
                paint.setColor(selected ? COLOR_CREAM : 0xFF2A2D33);
                canvas.drawRoundRect(thumbRect, dp(8), dp(8), paint);
                if (wallpaperPath.equals(wallpaperFiles.get(index).getAbsolutePath())) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(COLOR_ACCENT);
                    canvas.drawCircle(thumbRect.right - dp(10), thumbRect.top + dp(10),
                            dp(3), paint);
                }
            }
        }

        paint.setColor(0xFF24272D);
        canvas.drawRect(left + dp(16), top + dp(400), right - dp(16), top + dp(401), paint);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.12f);
        drawText(canvas, "BACK 关闭", left + dp(24), top + dp(417), sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        drawText(canvas, "同一局域网 · 上传后自动设置", right - dp(24), top + dp(417),
                sp(8), 0xFF5E646D);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawWallpaperReadabilityScrim(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (!pureMode) {
            Shader shader;
            if (sideClock()) {
                if (rightClock()) {
                    shader = new LinearGradient(w, 0, w * 0.45f, 0, 0x3307080A, 0x0007080A,
                            Shader.TileMode.CLAMP);
                } else {
                    shader = new LinearGradient(0, 0, w * 0.55f, 0, 0x3307080A, 0x0007080A,
                            Shader.TileMode.CLAMP);
                }
            } else if ("top".equals(clockPosition)) {
                shader = new LinearGradient(0, 0, 0, h * 0.42f, 0x5C07080A, 0x0007080A,
                        Shader.TileMode.CLAMP);
            } else {
                shader = new LinearGradient(0, h, 0, h * 0.58f, 0x5C07080A, 0x0007080A,
                        Shader.TileMode.CLAMP);
            }
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        }

        paint.setShader(new LinearGradient(0, 0, 0, dp(78), 0x3307080A, 0x0007080A,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, dp(78), paint);
        paint.setShader(new LinearGradient(0, h, 0, h - dp(78), 0x4007080A, 0x0007080A,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, h - dp(84), w, h, paint);
        paint.setShader(null);
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
        drawText(canvas, "留白", padH + dp(32) + brandWidth + dp(8), padTop + dp(15), sp(9),
                wallpaperBitmap != null ? 0xFFD0CBC2 : 0xFF747A83);

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
        boolean chipGlass = wallpaperBitmap != null;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(headerFocused ? COLOR_CARD_FOCUS
                : (chipGlass ? 0x2EFFFFFF : 0x16191D));
        canvas.drawRoundRect(settingsChip, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(headerFocused ? 0x66FFFFFF
                : (chipGlass ? 0x40FFFFFF : 0xFF30343B));
        canvas.drawRoundRect(settingsChip, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, "↑ 设置", settingsChip.centerX(), settingsChip.centerY() + sp(3.2f),
                sp(9), headerFocused ? COLOR_INK
                        : (chipGlass ? 0xFFF5F1E8 : 0xFFA5ABB3));
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private float textWidth(String text, Typeface face, float size, float spacing) {
        measurePaint.setTypeface(face);
        measurePaint.setTextSize(size);
        measurePaint.setLetterSpacing(spacing);
        return measurePaint.measureText(text);
    }

    private void drawHero(Canvas canvas) {
        boolean right = rightClock();
        boolean centered = centerClock;
        float x;
        Paint.Align align;
        if (centered) {
            x = right ? getWidth() - padH - leftWidth / 2f : padH + leftWidth / 2f;
            align = Paint.Align.CENTER;
        } else {
            x = right ? getWidth() - padH : padH;
            align = right ? Paint.Align.RIGHT : Paint.Align.LEFT;
        }
        float direction = right ? -1f : 1f;
        float shift = centered ? (showTagline ? dp(24) : dp(39)) : 0f;

        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(align);
        textPaint.setLetterSpacing(0.24f);
        textPaint.setColor(COLOR_ACCENT);
        if (centered) {
            float labelWidth = textWidth("现在", typeRegular, sp(9), 0.24f);
            canvas.drawCircle(x - labelWidth / 2f - dp(7), gridTop + dp(51) + shift, dp(2), textPaint);
            drawText(canvas, "现在", x + dp(4), gridTop + dp(55) + shift, sp(9),
                    wallpaperBitmap != null ? 0xFFF1EDE3 : 0xFF7A8089);
        } else {
            canvas.drawCircle(x + direction * dp(2), gridTop + dp(51), dp(2), textPaint);
            drawText(canvas, "现在", x + direction * dp(11), gridTop + dp(55), sp(9),
                    wallpaperBitmap != null ? 0xFFF1EDE3 : 0xFF7A8089);
        }

        textPaint.setTypeface(typeLight);
        textPaint.setTextAlign(align);
        textPaint.setLetterSpacing(-0.035f);
        textPaint.setFontFeatureSettings("tnum");
        float clockSize = sp(84);
        textPaint.setTextSize(clockSize);
        float clockWidth = textPaint.measureText(timeText);
        if (clockWidth > leftWidth + dp(6)) {
            clockSize *= (leftWidth + dp(6)) / clockWidth;
        }
        drawText(canvas, timeText, x, dp(248) + shift, clockSize, COLOR_CREAM);
        textPaint.setFontFeatureSettings(null);

        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(align);
        textPaint.setLetterSpacing(0.055f);
        drawText(canvas, dateText, centered ? x : x + direction * dp(2), dp(285) + shift,
                sp(14), wallpaperBitmap != null ? 0xFFF2EEE5 : COLOR_DATE);

        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, greeting, centered ? x : x + direction * dp(2), dp(322) + shift,
                sp(17), wallpaperBitmap != null ? 0xFFFFFBF3 : 0xFFE7E2D8);

        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeWidth(dp(1));
        textPaint.setShader(dividerShader);
        if (centered) {
            canvas.drawLine(x - dp(14), dp(360) + shift, x + dp(14), dp(360) + shift, textPaint);
        } else if (right) {
            canvas.drawLine(x - dp(2), dp(360), x - dp(30), dp(360), textPaint);
        } else {
            canvas.drawLine(x + dp(2), dp(360), x + dp(30), dp(360), textPaint);
        }
        textPaint.setShader(null);
        textPaint.setStyle(Paint.Style.FILL);

        if (showTagline) {
            textPaint.setTypeface(typeRegular);
            textPaint.setTextAlign(align);
            textPaint.setLetterSpacing(0.12f);
            drawText(canvas, "少一点，刚刚好。", centered ? x : x + direction * dp(2),
                    dp(390) + shift, sp(10),
                    wallpaperBitmap != null ? 0xFFEDE8DE : 0xFF686E77);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHeroTop(Canvas canvas) {
        textPaint.setTypeface(typeLight);
        textPaint.setTextAlign(centerClock ? Paint.Align.CENTER : Paint.Align.LEFT);
        textPaint.setLetterSpacing(-0.035f);
        textPaint.setFontFeatureSettings("tnum");
        float clockSize = sp(centerClock ? 58 : 66);
        textPaint.setTextSize(clockSize);
        float clockWidth = textPaint.measureText(timeText);
        float maxClockWidth = getWidth() * 0.55f;
        if (clockWidth > maxClockWidth) {
            clockSize *= maxClockWidth / clockWidth;
            clockWidth = maxClockWidth;
        }
        if (centerClock) {
            drawText(canvas, timeText, getWidth() / 2f, getHeight() * 0.26f, clockSize,
                    COLOR_CREAM);
            textPaint.setFontFeatureSettings(null);
            textPaint.setTypeface(typeRegular);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setLetterSpacing(0.04f);
            drawText(canvas, dateText + "  ·  " + greeting, getWidth() / 2f,
                    getHeight() * 0.315f, sp(13),
                    wallpaperBitmap != null ? 0xFFF2EEE5 : COLOR_DATE);
            if (showTagline) {
                textPaint.setLetterSpacing(0.12f);
                drawText(canvas, "少一点，刚刚好。", getWidth() / 2f,
                        getHeight() * 0.35f, sp(9),
                        wallpaperBitmap != null ? 0xFFEDE8DE : 0xFF686E77);
            }
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float startX = padH;
        drawText(canvas, timeText, startX, getHeight() * 0.28f, clockSize, COLOR_CREAM);
        textPaint.setFontFeatureSettings(null);

        float infoX = startX + clockWidth + dp(28);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.055f);
        drawText(canvas, dateText, infoX, getHeight() * 0.218f, sp(13), COLOR_DATE);
        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, greeting, infoX, getHeight() * 0.266f, sp(16), 0xFFE7E2D8);
        if (showTagline) {
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.12f);
            drawText(canvas, "少一点，刚刚好。", infoX, getHeight() * 0.315f, sp(9),
                    0xFF686E77);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHeroBottom(Canvas canvas) {
        textPaint.setTypeface(typeLight);
        textPaint.setTextAlign(centerClock ? Paint.Align.CENTER : Paint.Align.LEFT);
        textPaint.setLetterSpacing(-0.035f);
        textPaint.setFontFeatureSettings("tnum");
        float clockSize = sp(centerClock ? 58 : 66);
        textPaint.setTextSize(clockSize);
        float clockWidth = textPaint.measureText(timeText);
        float maxClockWidth = getWidth() * 0.48f;
        if (clockWidth > maxClockWidth) {
            clockSize *= maxClockWidth / clockWidth;
            clockWidth = maxClockWidth;
        }
        if (centerClock) {
            drawText(canvas, timeText, getWidth() / 2f, getHeight() * 0.86f, clockSize,
                    COLOR_CREAM);
            textPaint.setFontFeatureSettings(null);
            textPaint.setTypeface(typeRegular);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setLetterSpacing(0.04f);
            drawText(canvas, dateText + "  ·  " + greeting, getWidth() / 2f,
                    getHeight() * 0.77f, sp(13),
                    wallpaperBitmap != null ? 0xFFF2EEE5 : COLOR_DATE);
            if (showTagline) {
                textPaint.setLetterSpacing(0.12f);
                drawText(canvas, "少一点，刚刚好。", getWidth() / 2f,
                        getHeight() * 0.96f, sp(9),
                        wallpaperBitmap != null ? 0xFFEDE8DE : 0xFF686E77);
            }
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float startX = padH;
        textPaint.setTypeface(typeRegular);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(0.24f);
        textPaint.setColor(COLOR_ACCENT);
        canvas.drawCircle(padH + dp(2), getHeight() * 0.70f, dp(2), textPaint);
        drawText(canvas, "现在", padH + dp(11), getHeight() * 0.704f, sp(9),
                wallpaperBitmap != null ? 0xFFF1EDE3 : 0xFF7A8089);
        textPaint.setTypeface(typeLight);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setLetterSpacing(-0.035f);
        textPaint.setFontFeatureSettings("tnum");
        drawText(canvas, timeText, startX, getHeight() * 0.885f, clockSize, COLOR_CREAM);
        textPaint.setFontFeatureSettings(null);

        float infoX = startX + clockWidth + dp(28);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.055f);
        drawText(canvas, dateText, infoX, getHeight() * 0.825f, sp(13), COLOR_DATE);
        textPaint.setTypeface(typeMedium);
        textPaint.setLetterSpacing(0.04f);
        drawText(canvas, greeting, infoX, getHeight() * 0.875f, sp(16), 0xFFE7E2D8);
        if (showTagline) {
            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.12f);
            drawText(canvas, "少一点，刚刚好。", infoX, getHeight() * 0.955f, sp(9),
                    0xFF686E77);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDivider(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(dividerShader);
        canvas.drawRect(dividerX, padTop, dividerX + dp(0.8f), getHeight() - dp(44), paint);
        paint.setShader(null);
    }

    private void drawApps(Canvas canvas) {
        if (!pureMode && !"top".equals(clockPosition)) {
            textPaint.setTypeface(typeMedium);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setLetterSpacing(0.12f);
            drawText(canvas, "应用", gridLeft, gridTop - dp(21), sp(12), 0xFFE9E4DA);

            textPaint.setTypeface(typeRegular);
            textPaint.setLetterSpacing(0.28f);
            float titleWidth = textPaint.measureText("应用");
            float appsX = gridLeft + titleWidth + dp(8);
            int muted = wallpaperBitmap != null ? 0xFFD8D3CA : 0xFF5B616A;
            drawText(canvas, "APPS", appsX, gridTop - dp(21), sp(8), muted);
            float appsWidth = textPaint.measureText("APPS");

            String count = loading && apps.isEmpty() ? "··" :
                    String.format(Locale.CHINA, "%02d", apps.size());
            textPaint.setLetterSpacing(0.08f);
            drawText(canvas, count, appsX + appsWidth + dp(10), gridTop - dp(21), sp(8), muted);
            textPaint.setTextAlign(Paint.Align.LEFT);

        }

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
        canvas.clipRect(gridLeft - dp(16), gridTop - dp(24),
                gridRight + dp(16), gridBottom + dp(24));
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
        float centerX = gridLeft + (index % columns) * (cardWidth + gapX) + cardWidth / 2f;

        canvas.save();
        float drawTop = top;

        cardRect.set(centerX - cardWidth / 2f, drawTop,
                centerX + cardWidth / 2f, drawTop + cardHeight);
        float radius = dp(18);
        boolean glass = wallpaperBitmap != null;
        boolean focused = focus > 0.5f;

        if (focus > 0.01f && !glass) {
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
        if (glass && wallpaperBlurred != null && !wallpaperBlurred.isRecycled()) {
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(cardRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);

            Rect src = new Rect((int) Math.floor(cardRect.left),
                    (int) Math.floor(cardRect.top),
                    (int) Math.ceil(cardRect.right),
                    (int) Math.ceil(cardRect.bottom));
            Rect safe = new Rect(src);
            if (safe.intersect(0, 0, getWidth(), getHeight())) {
                RectF dst = new RectF(
                        cardRect.left + (safe.left - src.left),
                        cardRect.top + (safe.top - src.top),
                        cardRect.left + (safe.right - src.left),
                        cardRect.top + (safe.bottom - src.top));
                paint.setShader(null);
                paint.setAlpha(255);
                paint.setFilterBitmap(true);
                paint.setColorFilter(cardBackdropFilter);
                canvas.drawBitmap(wallpaperBlurred, safe, dst, paint);
                paint.setColorFilter(null);
                paint.setFilterBitmap(false);
            }

            paint.setColor(0x0C0B2038);
            canvas.drawRect(cardRect, paint);
            paint.setShader(new LinearGradient(cardRect.left, cardRect.top,
                    cardRect.left, cardRect.top + cardHeight * 0.55f,
                    0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(cardRect, paint);
            paint.setShader(new LinearGradient(cardRect.left, cardRect.top + cardHeight * 0.38f,
                    cardRect.left, cardRect.bottom, 0x00000000, 0x99000000,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(cardRect, paint);
            paint.setShader(new LinearGradient(cardRect.left, cardRect.top,
                    cardRect.right, cardRect.bottom,
                    new int[]{0x28FFFFFF, 0x06FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 0.82f}, Shader.TileMode.CLAMP));
            canvas.drawRect(cardRect, paint);
            paint.setShader(null);
            paint.setColor(0x08FFFFFF);
            canvas.drawRect(cardRect, paint);
            canvas.restore();

            if (focus > 0.01f) {
                paint.setColor(withAlpha(COLOR_CARD_FOCUS, (int) (255 * focus)));
                canvas.drawRoundRect(cardRect, radius, radius, paint);
            }
        } else {
            paint.setColor(blend(COLOR_CARD, COLOR_CARD_FOCUS, focus));
            canvas.drawRoundRect(cardRect, radius, radius, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        int borderColor = glass ? 0x30FFFFFF : COLOR_CARD_BORDER;
        paint.setColor(blend(borderColor, 0x66FFFFFF, focus));
        canvas.drawRoundRect(cardRect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float tile = Math.min(cardWidth * 0.245f, cardHeight * 0.34f);
        tile = Math.max(dp(36), Math.min(dp(66), tile));
        float iconCenterY = cardRect.top + cardHeight * 0.385f;
        rect.set(centerX - tile / 2f, iconCenterY - tile / 2f,
                centerX + tile / 2f, iconCenterY + tile / 2f);
        paint.setColor(glass && !focused ? 0x99101216 : 0xFF0B0C0F);
        canvas.drawRoundRect(rect, tile * 0.28f, tile * 0.28f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(glass && !focused ? 0x2EFFFFFF : 0x1FFFFFFF);
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
        int labelColor = glass ? 0xFFF5F1E8 : COLOR_LABEL;
        drawText(canvas, label.toString(), centerX, cardRect.top + cardHeight * 0.845f,
                labelSize, blend(labelColor, COLOR_INK, focus));
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
        if (maxScroll <= 0f || rows <= visibleRows || wallpaperBitmap != null) {
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
        drawText(canvas, "打开", x + dp(37), baseline - dp(1), sp(10),
                wallpaperBitmap != null ? 0xFFD0CBC2 : 0xFF7A8089);

        float groupX = x + dp(93);
        textPaint.setTypeface(typeRegular);
        textPaint.setLetterSpacing(0.08f);
        drawText(canvas, "← ↑ ↓ →", groupX, baseline - dp(1), sp(9),
                wallpaperBitmap != null ? 0xFFE2DED6 : 0xFF9BA0A7);
        drawText(canvas, "选择", groupX + dp(54), baseline - dp(1), sp(10),
                wallpaperBitmap != null ? 0xFFD0CBC2 : 0xFF7A8089);
        drawText(canvas, "MENU 操作", x + dp(190), baseline - dp(1), sp(9),
                wallpaperBitmap != null ? 0xFFD0CBC2 : 0xFF7A8089);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setLetterSpacing(0.16f);
        if (defaultHome) {
            drawText(canvas, "HUSH  /  留白", getWidth() - padH, baseline - dp(1), sp(8),
                    wallpaperBitmap != null ? 0xFFC9C5BC : 0xFF4E545C);
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
        boolean glass = wallpaperBitmap != null;
        float top = baseline - dp(13);
        if (glass) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x24FFFFFF);
            canvas.drawRoundRect(x, top, x + width, top + dp(18), dp(5), dp(5), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(glass ? 0x40FFFFFF : 0xFF3B3F46);
        canvas.drawRoundRect(x, top, x + width, top + dp(18), dp(5), dp(5), paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTypeface(typeMedium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0f);
        textPaint.setTextSize(sp(8));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float centerY = top + dp(9);
        float textY = centerY - (metrics.ascent + metrics.descent) / 2f;
        drawText(canvas, label, x + width / 2f, textY, sp(8),
                glass ? 0xFFF5F1E8 : 0xFFC4BEB4);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size, int color) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setStyle(Paint.Style.FILL);
        if (wallpaperBitmap != null) {
            textPaint.setShadowLayer(dp(3f), 0, dp(1), 0xB3000000);
        } else {
            textPaint.clearShadowLayer();
        }
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
        if (wallpaperOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (wallpaperSelected > 0) {
                        wallpaperSelected--;
                        ensureWallpaperVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (wallpaperSelected >= 0 && wallpaperSelected < wallpaperFiles.size() - 1) {
                        wallpaperSelected++;
                        ensureWallpaperVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (wallpaperSelected >= 0) {
                        wallpaperSelected = Math.max(0, wallpaperSelected - 4);
                        ensureWallpaperVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (wallpaperSelected >= 0 && wallpaperSelected < wallpaperFiles.size()) {
                        wallpaperSelected = Math.min(wallpaperFiles.size() - 1,
                                wallpaperSelected + 4);
                        ensureWallpaperVisible();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_SPACE:
                    applySelectedWallpaper();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    confirmDeleteWallpaper();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    closeWallpaper();
                    return true;
                default:
                    return true;
            }
        }

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
            if (settingsOpen || pushOpen || wallpaperOpen) {
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
        if (wallpaperOpen) {
            if (action == MotionEvent.ACTION_UP) {
                int index = wallpaperThumbAt(event.getX(), event.getY());
                if (index >= 0) {
                    wallpaperSelected = index;
                    applySelectedWallpaper();
                } else if (!wallpaperPanel.contains(event.getX(), event.getY())) {
                    closeWallpaper();
                }
            }
            return true;
        }
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

    private static ColorMatrixColorFilter createCardBackdropFilter() {
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.35f);
        saturation.postConcat(new ColorMatrix(new float[]{
                0.80f, 0f, 0f, 0f, 0f,
                0f, 0.80f, 0f, 0f, 0f,
                0f, 0f, 0.80f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        }));
        return new ColorMatrixColorFilter(saturation);
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
