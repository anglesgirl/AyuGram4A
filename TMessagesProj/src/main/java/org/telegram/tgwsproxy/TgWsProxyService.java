package org.telegram.tgwsproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Random;

/**
 * 内置 TG-WS 代理前台服务：加载 Rust 核心 libtgwsproxy.so，
 * 监听 127.0.0.1:1443，并把本地 MTProto 代理写入 TG 客户端代理列表。
 * 状态持久化到 SharedPreferences，App 重启后自动恢复。
 */
public class TgWsProxyService extends Service {
    public static final String TAG = "TgWsProxy";

    public static final String ACTION_START = "org.telegram.tgwsproxy.START";
    public static final String ACTION_STOP = "org.telegram.tgwsproxy.STOP";
    public static final String ACTION_STATE_CHANGED = "org.telegram.tgwsproxy.STATE_CHANGED";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ERROR = "error";

    public static final String EXTRA_BIND_IP = "bindIp";
    public static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "tgwsproxy_channel";
    private static final int NOTIFICATION_ID = 1443;
    private static final String PREFS_NAME = "tgwsproxy_prefs";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_ERROR = "last_error";

    private static final int DEFAULT_PORT = 1443;

    private static volatile boolean sRunning;

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static String getLastError(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LAST_ERROR, "");
    }

    public static String getSecret(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SECRET, "");
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void setError(Context context, String error) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_LAST_ERROR, error).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopProxy();
        } else if (ACTION_START.equals(action)) {
            startProxy(intent.getStringExtra(EXTRA_BIND_IP), intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT));
        } else {
            // Service restarted by the system: restore from persisted state
            if (isEnabled(this) && !sRunning) {
                startProxy("127.0.0.1", DEFAULT_PORT);
            } else {
                stopSelf();
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void startProxy(final String bindIp, final int port) {
        if (sRunning) {
            return;
        }
        setError(this, "");

        Notification notification = buildNotification("TG-WS 代理启动中…");
        startForeground(NOTIFICATION_ID, notification);

        Thread thread = new Thread(() -> {
            try {
                if (!isPortAvailable(bindIp, port)) {
                    String err = "端口 " + port + " 被占用，无法启动";
                    Log.e(TAG, err);
                    setError(TgWsProxyService.this, err);
                    setEnabled(TgWsProxyService.this, false);
                    updateNotification("启动失败：" + err);
                    sendStateBroadcast("error", err);
                    stopProxy();
                    return;
                }
                String secret = ensureSecret();
                File cacheDir = new File(getCacheDir(), "cfproxy");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                int result;
                try {
                    TgWsProxyNative.INSTANCE.SetPoolSize(4);
                    TgWsProxyNative.INSTANCE.SetCfProxyCacheDir(cacheDir.getAbsolutePath());
                    TgWsProxyNative.INSTANCE.SetCfProxyConfig(1, 1, "");
                    TgWsProxyNative.INSTANCE.SetFixedIpRange("");
                    result = TgWsProxyNative.INSTANCE.StartProxy(bindIp, port, "", secret, 1);
                } catch (Throwable t) {
                    String err = "Rust 核心加载/调用失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    Log.e(TAG, err, t);
                    setError(TgWsProxyService.this, err);
                    setEnabled(TgWsProxyService.this, false);
                    updateNotification("启动失败：" + err);
                    sendStateBroadcast("error", err);
                    stopProxy();
                    return;
                }
                if (result == 0) {
                    sRunning = true;
                    setEnabled(TgWsProxyService.this, true);
                    injectProxyIntoClient(port, secret);
                    updateNotification("TG-WS 代理运行中 (127.0.0.1:" + port + ")");
                    sendStateBroadcast("running", "");
                    Log.i(TAG, "proxy ready on " + bindIp + ":" + port);
                } else {
                    String err;
                    if (result == -1) {
                        err = "代理已在运行";
                    } else if (result == -3) {
                        err = "端口绑定失败";
                    } else {
                        err = "内部错误码 " + result;
                    }
                    Log.e(TAG, "StartProxy returned " + result + " (" + err + ")");
                    setError(TgWsProxyService.this, "启动失败：" + err);
                    setEnabled(TgWsProxyService.this, false);
                    updateNotification("启动失败：" + err);
                    sendStateBroadcast("error", "启动失败：" + err);
                    stopProxy();
                }
            } catch (Throwable t) {
                String err = "代理异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
                Log.e(TAG, err, t);
                setError(TgWsProxyService.this, err);
                setEnabled(TgWsProxyService.this, false);
                updateNotification("异常：" + err);
                sendStateBroadcast("error", err);
                stopProxy();
            }
        });
        thread.setName("tgwsproxy-start");
        thread.start();
    }

    private void sendStateBroadcast(String state, String error) {
        try {
            Intent i = new Intent(ACTION_STATE_CHANGED);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_STATE, state);
            i.putExtra(EXTRA_ERROR, error == null ? "" : error);
            sendBroadcast(i);
        } catch (Throwable ignore) {
        }
    }

    private void stopProxy() {
        sRunning = false;
        sendStateBroadcast("stopped", "");
        setEnabled(this, false);
        setError(this, "");
        try {
            TgWsProxyNative.INSTANCE.StopProxy();
        } catch (Throwable t) {
            Log.w(TAG, "StopProxy error", t);
        }
        removeProxyFromClient(DEFAULT_PORT);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isPortAvailable(String bindIp, int port) {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(bindIp), port));
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String ensureSecret() {
        String secret = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SECRET, "");
        if (secret != null && secret.length() >= 32) {
            return secret;
        }
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        secret = sb.toString();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_SECRET, secret).apply();
        return secret;
    }

    private void injectProxyIntoClient(int port, String secret) {
        try {
            for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
                SharedConfig.ProxyInfo p = SharedConfig.proxyList.get(i);
                if (p.address != null && p.address.equals("127.0.0.1") && p.port == port) {
                    SharedConfig.proxyList.remove(i);
                }
            }
            SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo("127.0.0.1", port, null, null, secret);
            SharedConfig.proxyList.add(0, info);
            SharedConfig.currentProxy = info;
            SharedConfig.saveConfig();
            org.telegram.messenger.MessagesController.getGlobalMainSettings().edit()
                    .putBoolean("proxy_enabled", true).apply();
            org.telegram.tgnet.ConnectionsManager.setProxySettings(true, "127.0.0.1", port, "", "", secret);
        } catch (Throwable t) {
            Log.w(TAG, "injectProxyIntoClient error", t);
        }
    }

    private void removeProxyFromClient(int port) {
        try {
            for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
                SharedConfig.ProxyInfo p = SharedConfig.proxyList.get(i);
                if (p.address != null && p.address.equals("127.0.0.1") && p.port == port) {
                    SharedConfig.proxyList.remove(i);
                }
            }
            if (SharedConfig.currentProxy != null
                    && "127.0.0.1".equals(SharedConfig.currentProxy.address)
                    && SharedConfig.currentProxy.port == port) {
                SharedConfig.currentProxy = null;
            }
            SharedConfig.saveConfig();
            org.telegram.tgnet.ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
        } catch (Throwable t) {
            Log.w(TAG, "removeProxyFromClient error", t);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TG-WS 代理", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(org.telegram.messenger.R.drawable.msg2_proxy_on)
                .setContentTitle("内置 TG-WS 代理")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
