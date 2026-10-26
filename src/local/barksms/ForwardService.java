package local.barksms;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForwardService extends Service {
    private static final String CHANNEL = "sms_listener";
    private static final int NOTIFICATION = 1;
    private static final int RECOVERY_REQUEST = 17302;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Runnable wake = this::trigger;
    private QueueDb db;
    private Settings settings;
    private ConnectivityManager connectivity;
    private volatile boolean destroyed;
    private boolean callbackRegistered;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            handler.post(() -> {
                if (!destroyed) { db.retryNow(); trigger(); }
            });
        }
    };

    static void start(Context context, boolean retryNow) {
        try {
            context.startForegroundService(new Intent(context, ForwardService.class).putExtra("retry", retryNow));
        } catch (IllegalStateException | SecurityException e) {
            QueueDb.get(context).event("系统限制了后台启动，请打开应用");
        }
    }

    static void cancelRecovery(Context context) {
        PendingIntent pending = PendingIntent.getForegroundService(context, RECOVERY_REQUEST,
                new Intent(context, ForwardService.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) {
            context.getSystemService(AlarmManager.class).cancel(pending);
            pending.cancel();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        db = QueueDb.get(this);
        settings = new Settings(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "短信接收服务", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        startForeground(NOTIFICATION, notification());
        connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerDefaultNetworkCallback(callback);
        callbackRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        cancelRecovery(this);
        if (!settings.enabled() || !Settings.hasPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getBooleanExtra("retry", false)) db.retryNow();
        trigger();
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (settings.enabled() && Settings.hasPermission(this)) {
            // One recovery attempt per task removal; no recurring keep-alive alarm.
            PendingIntent pending = PendingIntent.getForegroundService(this, RECOVERY_REQUEST,
                    new Intent(this, ForwardService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            getSystemService(AlarmManager.class).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, pending);
        }
        super.onTaskRemoved(rootIntent);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int count = db.count();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification_icon).setContentTitle("短信转发已开启")
                .setContentText(count == 0 ? "正在等待短信" : "待发送：" + count + " 条")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET).build();
    }

    private boolean online() {
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void trigger() {
        if (destroyed || !settings.enabled() || !Settings.hasPermission(this)) return;
        handler.removeCallbacks(wake);
        if (!busy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            PowerManager.WakeLock lock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BarkSms:send");
            lock.setReferenceCounted(false);
            try {
                lock.acquire(120_000);
                for (int i = 0; i < 6 && !destroyed && settings.enabled() && online(); i++) {
                    QueueDb.Item item = db.first();
                    if (item == null || item.due > System.currentTimeMillis()) break;
                    String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(item.received));
                    try {
                        BarkClient.send(settings.endpoint(), "短信 · " + item.sender,
                                item.body + "\n\n接收时间：" + stamp, settings.group(), settings.archive());
                        db.sent(item.id);
                    } catch (IOException | IllegalArgumentException e) {
                        db.failed(item, e.getMessage() == null ? "转发失败" : e.getMessage());
                    }
                }
            } finally {
                if (lock.isHeld()) lock.release();
                busy.set(false);
                handler.post(this::planNext);
            }
        });
    }

    private void planNext() {
        if (destroyed || !settings.enabled()) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        QueueDb.Item item = db.first();
        if (item == null) {
            RetryJob.cancel(this);
            return;
        }
        long delay = online() ? Math.max(1000, item.due - System.currentTimeMillis()) : 60_000;
        handler.removeCallbacks(wake);
        handler.postDelayed(wake, delay);
        RetryJob.schedule(this, delay);
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback);
        executor.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
