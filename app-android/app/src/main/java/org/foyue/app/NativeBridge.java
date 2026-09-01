package org.foyue.app;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 暴露给页面的原生能力，挂在 {@code window.__fyNative} 上。
 *
 * <p>页面据「这个对象在不在」判断自己是否跑在 APP 里（见 public/js/appinstall.js
 * 与 app.js 跳过 Service Worker 的那一处）—— 注入发生在载入页面之前，
 * 脚本一开始执行就已可见。
 *
 * <p>只放三类 WebView 自己办不到的事：
 * <ul>
 *   <li><b>后台恭听</b>——把播放状态转给 {@link MediaService}，换来锁屏控制与进程存活；</li>
 *   <li><b>把文件递出去</b>——WebView 没有 Web Share API，{@code <a download>} 拿着
 *       blob 地址也走不到头。分享海报与功课备份要经这里才落得了地；</li>
 *   <li><b>装新版</b>——下载 APK 并唤起系统安装器。</li>
 * </ul>
 *
 * <p>方法全部由 WebView 反射调用，名字不能被 R8 混淆 —— 规则见 proguard-rules.pro。
 * 出了错一律吞掉、只在状态里留话：这些都不是听经念佛的主路，
 * 不该因为一次分享失败把人从正在读的一篇里掀出去。
 */
public class NativeBridge {

    /** 页面里的对象名。改这里要同步改 public/js/ 里所有 __fyNative 的引用 */
    public static final String NAME = "__fyNative";

    private final MainActivity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaService media;
    private boolean firstTrack = true;

    /** 更新进度，供「我的」页轮询显示 */
    private volatile String upStage = "idle";   // idle | downloading | ready | error
    private volatile int upPercent = 0;
    private volatile String upMsg = "";

    NativeBridge(MainActivity host) {
        this.host = host;
    }

    /** MainActivity 绑上服务后交进来；未绑成功时为 null，此处一律做好为空的准备。 */
    void attachMedia(MediaService s) { this.media = s; }

    void shutdown() { this.media = null; }

    /* ══════════ 一、身份与环境 ══════════ */

    /** 外壳版本号（安装包的 versionName），「我的」页拿它与线上最新版比对。 */
    @JavascriptInterface
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    /** 当前是否有网。页面据此决定是提示「离线」还是照常发请求。 */
    @JavascriptInterface
    public boolean online() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) host.getSystemService(Activity.CONNECTIVITY_SERVICE);
            if (cm == null) return true;      // 判不出就当有网，让请求自己去碰
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /* ══════════ 二、后台恭听 ══════════ */

    /**
     * 上报当前这一集。字段见 public/js/app.js 的 pushNativeMedia()：
     * {@code {mode,title,artist,playing,position,duration,canPrev,canNext,seekable}}。
     * 页面每秒推一次，切集与起停时另推一次。
     */
    @JavascriptInterface
    public void media(String json) {
        MediaService s = media;
        if (s != null) s.apply(json);
        // 第一次报上曲目，才是问通知权限的时候（理由见 MainActivity#ensureNotificationPermission）
        if (firstTrack) {
            firstTrack = false;
            main.post(new Runnable() {
                @Override public void run() { host.ensureNotificationPermission(); }
            });
        }
    }

    /** 离开播放器：撤掉通知面板。 */
    @JavascriptInterface
    public void mediaClear() {
        MediaService s = media;
        if (s != null) s.clear();
    }

    /* ══════════ 三、把文件递出去 ══════════ */

    /**
     * 收下页面生成的文件（海报 PNG、功课备份 TXT），落到缓存后唤起系统分享面板，
     * 由用户决定发给谁、存到哪。
     *
     * <p>不直接写进「下载」目录：那在 Android 9 及以下要外部存储权限，
     * 为一张海报去要这个权限不值当；而分享面板里本就有「保存到文件」「保存到相册」。
     *
     * @param name     文件名（含扩展名）
     * @param base64   文件内容的 base64（不含 data: 前缀）
     * @param mime     MIME 类型
     */
    @JavascriptInterface
    public void share(final String name, final String base64, final String mime) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            File dir = new File(host.getCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            // 只留当次这一份，免得缓存里越积越多
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) { //noinspection ResultOfMethodCallIgnored
                f.delete(); }

            final File out = new File(dir, safeName(name));
            OutputStream os = new FileOutputStream(out);
            try { os.write(bytes); } finally { os.close(); }

            final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            main.post(new Runnable() {
                @Override public void run() { sendChooser(out, type); }
            });
        } catch (Exception ignored) {
            // 分享失败就静静地什么也不发生 —— 页面那边已经提示过「已生成」，
            // 再弹一个原生错误框只会更乱。
        }
    }

    private void sendChooser(File f, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    host, host.getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            host.startActivity(Intent.createChooser(send, "分享"));
        } catch (Exception ignored) { }
    }

    /** 文件名消毒：名字来自页面，不能让它带出目录去。 */
    private static String safeName(String name) {
        if (name == null || name.isEmpty()) return "foyue.bin";
        String n = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "foyue.bin" : n;
    }

    /* ══════════ 四、装新版 ══════════ */

    /**
     * 下载新版安装包并唤起系统安装器。
     * 版本比对在页面里做（直接 fetch 线上的 /app/release.json 即可 —— 那个地址
     * 不在包内，取件台会放它走网络），此处只管下载与安装这一段。
     */
    @JavascriptInterface
    public void update(final String url) {
        if (url == null || !url.startsWith("https://")) return;   // 只认 https，免得被换包
        if ("downloading".equals(upStage)) return;                // 已在下，不重复起
        upStage = "downloading";
        upPercent = 0;
        upMsg = "";
        new Thread(new Runnable() {
            @Override public void run() { doUpdate(url); }
        }, "foyue-update").start();
    }

    /** 更新进度，「我的」页轮询显示。JSON：{stage, percent, msg} */
    @JavascriptInterface
    public String updateState() {
        try {
            return new JSONObject()
                    .put("stage", upStage)
                    .put("percent", upPercent)
                    .put("msg", upMsg)
                    .toString();
        } catch (Exception e) {
            return "{\"stage\":\"error\",\"percent\":0,\"msg\":\"\"}";
        }
    }

    private void doUpdate(String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            File dir = new File(host.getCacheDir(), "apk");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法建立缓存目录");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) { //noinspection ResultOfMethodCallIgnored
                f.delete(); }
            // 先下到 .part，完整落盘后才改名 —— 半个包被拿去安装，
            // 系统只会报「解析包时出现问题」，用户无从判断是网断了
            File part = new File(dir, "update.apk.part");
            final File apk = new File(dir, "foyue-update.apk");

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) throw new Exception("下载失败 " + conn.getResponseCode());
            int total = conn.getContentLength();

            in = conn.getInputStream();
            os = new FileOutputStream(part);
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (total > 0) upPercent = (int) (got * 100 / total);
            }
            os.flush();
            os.close();
            os = null;

            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            if (!part.renameTo(apk)) throw new Exception("落盘失败");

            upPercent = 100;
            upStage = "ready";
            main.post(new Runnable() {
                @Override public void run() { install(apk); }
            });
        } catch (Exception e) {
            upStage = "error";
            upMsg = String.valueOf(e.getMessage());
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    private void install(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    host, host.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(i);
        } catch (Exception e) {
            // 多半是「安装未知应用」还没放行。系统本会自己引导去设置页，
            // 若连这一步都起不来，就把话留在状态里，由页面显示。
            upStage = "error";
            upMsg = "请在系统设置中允许本应用安装应用";
        }
    }
}
