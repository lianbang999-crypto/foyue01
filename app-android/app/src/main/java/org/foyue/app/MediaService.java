package org.foyue.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * 后台恭听：让人锁了屏、切了应用，法音还在。
 *
 * <p><b>分工要先讲清楚：声音不是这个服务放的。</b>
 * 真正在播的是 WebView 里那个 HTML5 {@code <audio>}（见 public/js/app.js），
 * 音频焦点也归它 —— 来电话、别的应用起播时的让位，WebView 自己会办。
 * 本服务只管两件 WebView 办不了的事：
 * <ol>
 *   <li>撑起一个前台服务，让进程在后台不被系统回收 —— 否则听着听着人就没了；</li>
 *   <li>提供一枚媒体会话，把当前这一集报给系统，锁屏、通知栏、耳机线控、
 *       蓝牙、车机才有得看、有得按。</li>
 * </ol>
 *
 * <p><b>两边都不去抢音频焦点</b>，这是刻意的。若原生这边也 requestAudioFocus，
 * 就成了同一个应用内两个焦点持有者互相打断，症状是起播即自停、或暂停后无法恢复，
 * 且极难查。焦点的事交给 WebView 一家。
 *
 * <p><b>进出前台的时机</b>：起播即转前台，之后<b>暂停也不退</b>。
 * 一来暂停时通知还留着，人在锁屏上才点得到「继续」；二来更实际 ——
 * Android 12 起禁止从后台启动前台服务，若暂停就退，那么后台自动接下一集时
 * 再想转前台会被系统拒掉（并抛异常）。只在明确停止（离开播放器）时才退。
 */
public class MediaService extends Service {

    /** 通知栏按钮与锁屏操作最终都变成这几个字，回传给页面执行 */
    interface Commands {
        void onMediaCommand(String cmd, long argMs);
    }

    private static final String CHANNEL = "foyue.playback";
    private static final int NOTI_ID = 1;

    private final IBinder binder = new LocalBinder();
    private MediaSessionCompat session;
    private Commands sink;
    private boolean foreground = false;

    // 当前这一集的状态，全部由页面推上来（见 NativeBridge#media）
    private String title = "佛乐 · 净土法音";
    private String artist = "";
    private boolean playing = false;
    private long positionMs = 0;
    private long durationMs = 0;
    private boolean canPrev = false;
    private boolean canNext = false;
    private boolean seekable = false;
    private boolean hasTrack = false;

    private static Bitmap art;   // 应用标志，作锁屏封面；解一次留着用

    class LocalBinder extends Binder {
        MediaService get() { return MediaService.this; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        session = new MediaSessionCompat(this, "foyue");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()             { send("play", 0); }
            @Override public void onPause()            { send("pause", 0); }
            @Override public void onStop()             { send("stop", 0); }
            @Override public void onSkipToNext()       { send("next", 0); }
            @Override public void onSkipToPrevious()   { send("prev", 0); }
            @Override public void onSeekTo(long pos)   { send("seek", pos); }
        });
        session.setActive(true);
    }

    /**
     * 媒体键（耳机线控、蓝牙）由 MediaButtonReceiver 转投到这里。
     *
     * <p>它在 Android 8 以上是用 startForegroundService 唤起本服务的，
     * 那就欠下一句 startForeground —— 五秒内不还，系统直接判崩。
     * 故这里无论如何都要把前台状态补上；手里没有曲目可报的，当即收摊。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (session != null) MediaButtonReceiver.handleIntent(session, intent);
        if (hasTrack) ensureForeground();
        else stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        sink = null;
        super.onDestroy();
    }

    /* ══════════ 对 MainActivity / NativeBridge 开放 ══════════ */

    void setCommandSink(Commands c) { this.sink = c; }

    /**
     * 页面推来的播放状态。字段见 public/js/app.js 的 pushNativeMedia()。
     * 解析失败就原样不动 —— 通知面板停在上一刻，总好过把正在播的曲目抹掉。
     */
    void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String mode = o.optString("mode", "");
            hasTrack = !mode.isEmpty();
            if (!hasTrack) { leaveForeground(); return; }

            title = o.optString("title", "佛乐 · 净土法音");
            artist = o.optString("artist", "");
            playing = o.optBoolean("playing", false);
            // 页面按秒给（audio.currentTime 本就是秒），到这儿统一换成毫秒
            positionMs = (long) (o.optDouble("position", 0) * 1000);
            durationMs = (long) (o.optDouble("duration", 0) * 1000);
            canPrev = o.optBoolean("canPrev", false);
            canNext = o.optBoolean("canNext", false);
            seekable = o.optBoolean("seekable", false);
        } catch (Exception e) {
            return;
        }
        publish();
    }

    /** 离开播放器：撤通知、停服务。 */
    void clear() {
        hasTrack = false;
        playing = false;
        leaveForeground();
    }

    /* ══════════ 内部 ══════════ */

    private void send(String cmd, long argMs) {
        Commands c = sink;
        if (c != null) c.onMediaCommand(cmd, argMs);
    }

    private void publish() {
        if (session == null) return;

        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "佛乐 · 净土法音");
        // 直播没有总长，报 0 会让锁屏进度条显示成一条走不完的线；干脆不报
        if (durationMs > 0) md.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        Bitmap cover = cover();
        if (cover != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        session.setMetadata(md.build());

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_STOP;
        if (canPrev) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if (canNext) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (seekable) actions |= PlaybackStateCompat.ACTION_SEEK_TO;

        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        positionMs, playing ? 1f : 0f)
                .build());

        ensureForeground();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, build());
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // API 31 起 PendingIntent 必须表态可变与否，不表态直接抛异常
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_IMMUTABLE;

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lotus)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(cover())
                .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_STOP))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 锁屏上要看得见集名
                .setShowWhen(false)
                .setOnlyAlertOnce(true)                                 // 每秒刷新一次，不能每次都响
                .setOngoing(playing);

        // 紧凑视图最多放三枚。上一集/下一集按有无决定，位次随之变，
        // 故一边收集一边记下播放/暂停那一枚落在第几位。
        int compact = 0;
        int idx = 0;
        if (canPrev) {
            b.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "上一集",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
            idx++;
        }
        b.addAction(new NotificationCompat.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        compact = idx;
        idx++;
        if (canNext) {
            b.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "下一集",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
        }

        androidx.media.app.NotificationCompat.MediaStyle style =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(compact);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private void ensureForeground() {
        if (foreground || !hasTrack) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTI_ID, build());
            }
            foreground = true;
        } catch (Exception e) {
            // Android 12 起从后台转前台会被拒（ForegroundServiceStartNotAllowedException）。
            // 这不该连累听经：声音是 WebView 在放，照常继续，只是这一程没有通知面板。
            foreground = false;
        }
    }

    private void leaveForeground() {
        if (!foreground) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        foreground = false;
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        // IMPORTANCE_LOW：这是块控制面板，不是提醒。响一声、震一下都是打扰。
        NotificationChannel ch = new NotificationChannel(CHANNEL, "正在恭听", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("显示当前正在播放的讲经，并提供暂停与切集");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    /** 锁屏封面用应用标志。解一次留着 —— 每秒刷新一次通知，不能每次都去解一张 512 的图。 */
    @Nullable
    private Bitmap cover() {
        if (art != null) return art;
        InputStream in = null;
        try {
            in = getAssets().open("icon-512.png");
            art = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
            art = null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored2) { }
        }
        return art;
    }

    /* ══════════ 供 MainActivity 起停 ══════════ */

    static Intent intent(Context ctx) {
        return new Intent(ctx, MediaService.class);
    }
}
