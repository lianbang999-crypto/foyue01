package org.foyue.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;

import org.json.JSONObject;

/**
 * 佛乐 APP 主界面。
 *
 * <h3>为什么把内容挂在自有域 foyue.org 上</h3>
 * androidx 给 {@link WebViewAssetLoader} 备了个默认域 appassets.androidplatform.net，
 * 同门的 wenchao 用的就是它。那对纯阅读的站点够用，可本站不同 ——
 * 页面里到处是 {@code /api/…} 与 {@code /audio/…} 这样的相对地址：问道、莲号同步、
 * 留言、朗读，还有六个 R2 桶的音频流。挂在默认域下，这些地址会落到本地、打不到后端，
 * 就得像 wenchao 那样逐条改写成绝对地址 —— 接口面这么大，改写既繁琐又迟早漏一条。
 *
 * <p>改用 {@code setDomain("foyue.org")}（这是自家域名，正当其用），APP 内的源
 * 与线上一模一样：相对地址天然打得到后端，Worker 那边的跨域白名单一个字都不用改，
 * localStorage 的键也与网页版同一套。取件台对 {@code /api/} 与 {@code /audio/}
 * 一律放行（见 {@link AppContentHandler}），WebView 便照常发真实网络请求，
 * 音频的 Range 分段也就由它自己的网络栈去谈。
 *
 * <p>代价说明白：壳代码（html/js/css）在 APP 内永远来自安装包，线上改了也看不到，
 * 要发新包才更新。这正是想要的语义 —— APP 的版本就是安装包的版本。
 * 会变的那几份目录另有 {@link ContentUpdater} 管。
 */
public class MainActivity extends Activity implements MediaService.Commands {

    /** 自有域。改这里要同步改 AppContentHandler 与 build-app-assets.py 的说明 */
    private static final String DOMAIN = "foyue.org";

    /** 带上外壳版本：页面「我的」页拿它与线上最新版比对 */
    private static final String START_URL =
            "https://" + DOMAIN + "/?app=" + BuildConfig.VERSION_NAME;

    /**
     * app.js 是 {@code <script type="module">}，模块脚本要 Chrome 61 才支持。
     * 低于此版本的内核解析不了，页面会静静地什么都不做 —— 与其让人对着白屏，
     * 不如直说是系统组件太旧、该去哪儿更新。
     */
    private static final int MIN_WEBVIEW = 61;

    private WebView web;
    private View splash;
    private NativeBridge bridge;
    private MediaService media;
    private boolean askedNoti = false;
    private OnBackInvokedCallback backCallback;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            media = ((MediaService.LocalBinder) service).get();
            media.setCommandSink(MainActivity.this);
            if (bridge != null) bridge.attachMedia(media);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            media = null;
            if (bridge != null) bridge.attachMedia(null);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int wv = webViewMajorVersion();
        if (wv > 0 && wv < MIN_WEBVIEW) {
            setContentView(unsupportedView(wv));
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF6F1E6);      // 与启动屏同色，避免加载瞬间闪白

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        configureWebView();
        registerBack();

        bindService(MediaService.intent(this), conn, BIND_AUTO_CREATE);
        new ContentUpdater(this).start();

        web.loadUrl(START_URL);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);             // 念佛计数、阅读进度、收藏全在 localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);              // 内容一律经取件台，不开 file 通道
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);                  // 字号由阅读器自己的设置控制，双指缩放会打架
        s.setBuiltInZoomControls(false);
        // 直播是进门即起播、点播是接着上次自动续，都不该等一次点击
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        // 留个记号，便于线上分辨 APP 来的请求；页面判断是否在 APP 内仍以 __fyNative 为准
        s.setUserAgentString(s.getUserAgentString() + " FoyueApp/" + BuildConfig.VERSION_NAME);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(DOMAIN)
                .addPathHandler("/", new AppContentHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleExternal(req.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternal(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideSplash();
            }
        });

        /* 不装 WebChromeClient 的话，页面里的 alert / confirm / prompt 会被默默丢弃 ——
           不报错、不显示，代码看着执行了却什么也没发生。清空离线音频、恢复备份这些
           都要先 confirm 一句，少了它就成了「点了没反应」。用默认实现即可正常弹出。 */
        web.setWebChromeClient(new WebChromeClient());
        web.setDownloadListener(downloads);

        bridge = new NativeBridge(this);
        if (media != null) bridge.attachMedia(media);
        web.addJavascriptInterface(bridge, NativeBridge.NAME);
    }

    /**
     * 站点用 {@code <a download>} 存海报与功课备份，地址是 blob:。
     * WebView 既没有 Web Share API，也下载不了 blob —— 点了什么也不会发生。
     * 故在这里接住：让页面自己把这份 blob 读成 base64 递回原生（{@link NativeBridge#share}），
     * 再由系统分享面板决定发给谁、存到哪。
     *
     * <p>页面那边 blob 地址是 4 秒后才作废的（见 app.js 里的 revokeObjectURL），
     * 这一来一回够用。
     */
    private final DownloadListener downloads = new DownloadListener() {
        @Override
        public void onDownloadStart(String url, String ua, String disposition, String mime, long size) {
            if (url == null) return;
            if (url.startsWith("blob:")) {
                if (web != null) web.evaluateJavascript(blobToNativeJs(url, fileName(disposition, mime)), null);
                return;
            }
            handleExternal(Uri.parse(url));   // 普通链接交给系统去下
        }
    };

    private static String blobToNativeJs(String url, String name) {
        return "(function(){try{fetch(" + JSONObject.quote(url) + ")"
                + ".then(function(r){return r.blob()})"
                + ".then(function(b){var fr=new FileReader();fr.onload=function(){"
                + "var s=String(fr.result),i=s.indexOf(',');"
                + "window." + NativeBridge.NAME + ".share(" + JSONObject.quote(name)
                + ",s.slice(i+1),b.type||'');};fr.readAsDataURL(b);})"
                + ".catch(function(){});}catch(e){}})();";
    }

    /** 从 Content-Disposition 里取文件名；取不到就按类型给个像样的默认名。 */
    private static String fileName(String disposition, String mime) {
        if (disposition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
            if (m.find()) {
                try {
                    return Uri.decode(m.group(1));
                } catch (Exception ignored) {
                    return m.group(1);
                }
            }
        }
        if (mime != null && mime.startsWith("image/")) return "foyue-share.png";
        return "foyue.txt";
    }

    /**
     * 站外地址交给系统浏览器，别在应用里打开。
     * 判据是主机名：只有 foyue.org 本身属于 APP，别院（wenchao / game 等子域）与
     * 一切外链都往外送 —— 那些站点没有随包出厂，在这里打开只会是一片空白。
     */
    private boolean handleExternal(Uri uri) {
        if (uri == null) return false;
        if (DOMAIN.equals(uri.getHost())) return false;     // 自家地址，照常在 WebView 里走
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // 没有可处理的应用就什么都不做，总好过崩掉
        }
        return true;
    }

    /* ══════════ 媒体键与锁屏操作 → 回到页面执行 ══════════ */

    /**
     * 通知栏按钮、耳机线控、蓝牙、车机的操作最终都落到这里，转成一句 JS 交给页面。
     * 播放逻辑只有页面那一套（直播要对表、点播要记进度、念佛堂要循环），
     * 原生这边不另起炉灶，只做传话。
     */
    @Override
    public void onMediaCommand(final String cmd, final long argMs) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                web.evaluateJavascript(
                        "window.__fyMedia&&window.__fyMedia.cmd(" + JSONObject.quote(cmd)
                                + "," + (argMs / 1000.0) + ")", null);
            }
        });
    }

    /**
     * 起播时才问要不要通知权限 —— 冷启动就弹一句「允许发送通知吗」，
     * 用户还没听上一句经，不知道这权限是干什么用的，多半随手就拒了。
     * 拒了也照常播，只是没有锁屏那块控制面板。
     */
    void ensureNotificationPermission() {
        if (askedNoti || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        askedNoti = true;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } catch (Exception ignored) { }
    }

    /* ══════════ 返回键 ══════════ */

    /**
     * Android 13 起系统改用「预测式返回」，targetSdk 35 以上默认开启，
     * 届时 onBackPressed 不再被调用 —— 若只留旧那一条，新机上按返回会直接退出应用，
     * 而不是回到上一页。故两条都留：新系统注册回调，旧系统走 onBackPressed。
     */
    private void registerBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        backCallback = new OnBackInvokedCallback() {
            @Override public void onBackInvoked() { goBackOrExit(); }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    private void goBackOrExit() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { super.onBackPressed(); return; }
        goBackOrExit();
    }

    /* ══════════ 启动屏与兜底页 ══════════ */

    private void hideSplash() {
        if (splash == null) return;
        final View s = splash;
        splash = null;
        s.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            @Override public void run() {
                if (s.getParent() instanceof ViewGroup) ((ViewGroup) s.getParent()).removeView(s);
            }
        }).start();
    }

    private View buildSplash() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFFF6F1E6);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.splash);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        f.addView(iv, lp);
        return f;
    }

    /** 系统 WebView 主版本号；取不到返回 -1（取不到时不拦，让它试着跑）。 */
    private int webViewMajorVersion() {
        try {
            PackageInfo info = WebViewCompat.getCurrentWebViewPackage(this);
            if (info == null || info.versionName == null) return -1;
            return Integer.parseInt(info.versionName.split("\\.")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 内核过旧时的说明页。用原生控件而非 HTML —— 这种时候 WebView 本身就不可信。 */
    private View unsupportedView(int ver) {
        TextView t = new TextView(this);
        t.setText("很抱歉，本机的「Android System WebView」系统组件版本过旧（"
                + ver + " 版），无法运行本应用。\n\n"
                + "请到手机的应用商店搜索「Android System WebView」或「Chrome」并更新，"
                + "之后重新打开本应用即可。\n\n"
                + "也可以直接用手机浏览器访问：\nfoyue.org");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLineSpacing(0f, 1.5f);
        t.setTextColor(Color.parseColor("#322A1E"));
        t.setBackgroundColor(Color.parseColor("#F6F1E6"));
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        t.setPadding(pad, pad * 3, pad, pad);
        return t;
    }

    /* ══════════ 生命周期 ══════════ */

    /*
     * 刻意不在 onPause 里调 web.onPause()：那会把 WebView 连同它正在放的音频一起停掉，
     * 「后台恭听」就无从谈起。进程的存活交给 MediaService 的前台通知去撑。
     */

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    protected void onDestroy() {
        if (backCallback != null) {
            try { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback); }
            catch (Exception ignored) { }
            backCallback = null;
        }
        if (media != null) {
            media.setCommandSink(null);
            media.clear();          // 页面没了就没人在播，撤掉通知面板
            media = null;
        }
        try { unbindService(conn); } catch (Exception ignored) { }
        if (bridge != null) { bridge.shutdown(); bridge = null; }
        if (web != null) {
            web.removeJavascriptInterface(NativeBridge.NAME);
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
