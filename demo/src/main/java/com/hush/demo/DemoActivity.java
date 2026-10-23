package com.hush.demo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** A tiny, self-contained app used to preview app launching from the HUSH desktop. */
public final class DemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        String className = getIntent().getComponent() == null
                ? "" : getIntent().getComponent().getClassName();
        String label;
        int accent;
        int index;
        if (className.endsWith("Music")) {
            label = "音乐";
            accent = 0xFFE7A56A;
            index = 2;
        } else if (className.endsWith("Live")) {
            label = "直播";
            accent = 0xFF63C79C;
            index = 3;
        } else if (className.endsWith("Gallery")) {
            label = "相册";
            accent = 0xFFD58AC8;
            index = 4;
        } else if (className.endsWith("Games")) {
            label = "游戏";
            accent = 0xFFA8B4FF;
            index = 5;
        } else {
            label = "影院";
            accent = 0xFF8B98FF;
            index = 1;
        }
        setContentView(new DemoView(this, label, accent, index));
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
