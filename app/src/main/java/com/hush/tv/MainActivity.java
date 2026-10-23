package com.hush.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import java.util.List;

/** Full-screen, ten-foot launcher shell. */
public final class MainActivity extends Activity {

    private static final int REQUEST_HOME = 41;
    private static final String PREFS = "hush_setup";
    private static final String KEY_PROMPTED = "home_prompted";
    private static final String KEY_POST_NOTICE = "post_notice_shown";
    private static final String KEY_STOCK_HOME = "stock_home";

    private LauncherView launcherView;
    private AlertDialog homeDialog;
    private boolean launchedFromLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        hideSystemUi();

        launchedFromLauncher = getIntent().hasCategory(Intent.CATEGORY_LAUNCHER);
        launcherView = new LauncherView(this);
        launcherView.setOnDesktopSettings(this::showHomeSetupDialog);
        setContentView(launcherView);
        updateHomeState();
        launcherView.post(this::maybePromptForHome);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        launcherView.onResume();
        updateHomeState();
        if (isDefaultHome() && homeDialog != null && homeDialog.isShowing()) {
            homeDialog.dismiss();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launchedFromLauncher = intent.hasCategory(Intent.CATEGORY_LAUNCHER);
        updateHomeState();
        maybePromptForHome();
    }

    @Override
    protected void onPause() {
        launcherView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (homeDialog != null && homeDialog.isShowing()) {
            homeDialog.dismiss();
        }
        homeDialog = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_HOME) {
            return;
        }
        launcherView.postDelayed(() -> {
            updateHomeState();
            if (isDefaultHome()) {
                Toast.makeText(this, R.string.home_setup_done, Toast.LENGTH_SHORT).show();
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_POST_NOTICE, true).apply();
                showPostSetupNotice();
            } else if (isHomeRoleHeld()) {
                showOverrideInstructions();
            }
        }, 300L);
    }

    private void maybePromptForHome() {
        if (isFinishing()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!isDefaultHome()) {
            if (prefs.getBoolean(KEY_PROMPTED, false)) {
                return;
            }
            prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
            showHomeSetupDialog();
            return;
        }
        if (launchedFromLauncher && isHomeRoleHeld()
                && !prefs.getBoolean(KEY_POST_NOTICE, false)) {
            prefs.edit().putBoolean(KEY_POST_NOTICE, true).apply();
            showPostSetupNotice();
        }
    }

    private void showHomeSetupDialog() {
        if (isFinishing()) {
            return;
        }
        updateHomeState();
        if (isDefaultHome()) {
            showPostSetupNotice();
            return;
        }
        if (homeDialog != null && homeDialog.isShowing()) {
            return;
        }
        if (isHomeRoleHeld()) {
            showOverrideInstructions();
        } else {
            showRoleDialog();
        }
    }

    private void showRoleDialog() {
        homeDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.home_setup_title)
                .setMessage(R.string.home_setup_message)
                .setPositiveButton(R.string.home_setup_confirm, (dialog, which) -> requestHomeRole())
                .setNegativeButton(R.string.home_setup_cancel, null)
                .create();
        homeDialog.setOnDismissListener(dialog -> homeDialog = null);
        homeDialog.show();
    }

    private void showPostSetupNotice() {
        if (isFinishing() || homeDialog != null && homeDialog.isShowing()) {
            return;
        }
        if (!isDefaultHome()) {
            showOverrideInstructions();
            return;
        }
        String stockHome = findOtherHomePackage();
        homeDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.home_info_title)
                .setMessage(getString(R.string.home_post_message, stockHome))
                .setPositiveButton(R.string.home_override_ok, null)
                .create();
        homeDialog.setOnDismissListener(dialog -> homeDialog = null);
        homeDialog.show();
    }

    /**
     * Some TV launchers are persistent system apps. They keep the HOME role even after the
     * role dialog succeeds, so the only reliable fix on those devices is to disable the
     * stock launcher once over ADB.
     */
    private void showOverrideInstructions() {
        if (isFinishing() || isDefaultHome()) {
            return;
        }
        if (homeDialog != null && homeDialog.isShowing()) {
            return;
        }
        String stockHome = findOtherHomePackage();
        homeDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.home_override_title)
                .setMessage(getString(R.string.home_override_message, stockHome))
                .setPositiveButton(R.string.home_override_ok, null)
                .create();
        homeDialog.setOnDismissListener(dialog -> homeDialog = null);
        homeDialog.show();
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Object service = getSystemService("role");
            if (service instanceof RoleManager) {
                RoleManager roleManager = (RoleManager) service;
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    try {
                        startActivityForResult(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                                REQUEST_HOME);
                        return;
                    } catch (ActivityNotFoundException ignored) {
                        // Fall through to the settings screen below.
                    }
                }
            }
        }

        try {
            startActivityForResult(new Intent("android.settings.HOME_SETTINGS"), REQUEST_HOME);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Some TV boxes do not expose the home settings screen.
        }

        try {
            startActivityForResult(new Intent(Settings.ACTION_SETTINGS), REQUEST_HOME);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "没有找到桌面设置，请在系统设置中手动更改", Toast.LENGTH_LONG).show();
        }
    }

    private void updateHomeState() {
        if (launcherView != null) {
            launcherView.setDefaultHome(isDefaultHome());
        }
    }

    private boolean isDefaultHome() {
        return getPackageName().equals(resolveHomePackage());
    }

    private String resolveHomePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info == null || info.activityInfo == null ? null : info.activityInfo.packageName;
    }

    private boolean isHomeRoleHeld() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        Object service = getSystemService("role");
        if (!(service instanceof RoleManager)) {
            return false;
        }
        RoleManager roleManager = (RoleManager) service;
        return roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                && roleManager.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private String findOtherHomePackage() {
        SharedPreferences setupPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String remembered = setupPrefs.getString(KEY_STOCK_HOME, "");
        if (!remembered.isEmpty()) {
            return remembered;
        }
        String resolved = resolveHomePackage();
        if (resolved != null && !resolved.equals(getPackageName()) && !"android".equals(resolved)) {
            setupPrefs.edit().putString(KEY_STOCK_HOME, resolved).apply();
            return resolved;
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> candidates = getPackageManager().queryIntentActivities(home, 0);
        String fallback = null;
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo == null) {
                continue;
            }
            String pkg = candidate.activityInfo.packageName;
            if (getPackageName().equals(pkg) || "android".equals(pkg)) {
                continue;
            }
            if (pkg.toLowerCase().contains("launcher")) {
                setupPrefs.edit().putString(KEY_STOCK_HOME, pkg).apply();
                return pkg;
            }
            if (fallback == null && !"com.android.tv.settings".equals(pkg)) {
                fallback = pkg;
            }
        }
        if (fallback != null) {
            setupPrefs.edit().putString(KEY_STOCK_HOME, fallback).apply();
            return fallback;
        }
        return "com.google.android.tvlauncher";
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
