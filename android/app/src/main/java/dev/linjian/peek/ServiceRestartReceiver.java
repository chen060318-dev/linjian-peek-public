package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ServiceRestartReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTART = "dev.linjian.peek.RESTART_SERVICE";

    @Override public void onReceive(Context context, Intent intent) {
        try {
            boolean userStopped = AppPrefs.get(context).getBoolean("user_stopped", true);
            String server = AppPrefs.server(context);
            String token = AppPrefs.token(context);
            if (userStopped || server.isEmpty() || token.isEmpty()) return;
            Intent service = new Intent(context, CompanionService.class);
            service.putExtra("server_url", server);
            service.putExtra("token", token);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
            DebugState.append(context, "系统事件后已请求恢复后台命令轮询");
        } catch (Exception e) {
            DebugState.append(context, "自动恢复后台服务失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
