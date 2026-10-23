package com.hush.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * PackageInstaller needs a component to forward the user-confirmation Intent.
 * A foreground Activity is more reliable on TV builds that restrict background
 * Activity starts from broadcast receivers.
 */
public final class InstallProxyActivity extends Activity {

    private static final String TAG = "HushPush";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Install proxy status=" + status + " message=" + message);

        try {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                } else {
                    //noinspection deprecation
                    confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                } else {
                    Toast.makeText(this, "安装确认信息丢失", Toast.LENGTH_LONG).show();
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(this, "安装成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "安装未完成" + (message == null || message.isEmpty()
                                ? "" : "：" + message),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot forward install confirmation", e);
            Toast.makeText(this, "无法打开安装确认界面：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }
}
