package com.hush.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/** Receives the result of a PackageInstaller session started by remote push. */
public final class PushInstallReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_RESULT = "com.hush.tv.action.PUSH_INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String resultMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.w("HushPush", "Install result status=" + status + " message=" + resultMessage);
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
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    Log.w("HushPush", "Cannot show install confirmation", e);
                    Toast.makeText(context, "无法打开安装确认界面", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(context, "安装确认信息丢失", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "安装成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context,
                    "安装未完成" + (resultMessage == null || resultMessage.isEmpty()
                            ? "" : "：" + resultMessage),
                    Toast.LENGTH_LONG).show();
        }
    }
}
