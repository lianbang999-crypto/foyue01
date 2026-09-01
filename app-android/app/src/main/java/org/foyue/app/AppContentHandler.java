package org.foyue.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * APP 内容的取件台：一个请求进来，按「放行 → 覆盖层 → 出厂内容 → 兜底首页」依次处理。
 *
 * <p><b>一、放行（本类相对 wenchao 那份样板最要紧的一处不同）</b><br>
 * 本 APP 的内容挂在自有域 {@code https://foyue.org} 下（见 MainActivity 的说明），
 * 与线上同源。于是 {@code /api/} 与 {@code /audio/} 这类本该打到后端的地址，也会先经过这里。
 * 对它们一律返回 null —— {@code WebViewAssetLoader} 见处理器落空便整体返回 null，
 * WebView 随即按常规发真实网络请求。
 *
 * <p>音频尤其要走这条路：点播拖动进度靠的是 HTTP Range 分段，交回 WebView 自己的
 * 网络栈去谈，比在这里手搓一个字节区间代理稳当得多，也不必操心 206、Content-Range
 * 与连接复用那一摊。
 *
 * <p><b>二、覆盖层</b>——APK 里的内容是只读的，收了新讲座、改了目录都动不了。
 * 故先查 {@code filesDir/content/}，那是 {@link ContentUpdater} 更新下来的新版目录；
 * 没有才回落到出厂内容。听经的人因此不必为多几集讲座重装 20MB。
 *
 * <p><b>三、SPA 回退</b>——站点用 pushState 把地址改成 {@code /read/…}、{@code /qa/…}，
 * 这些页面在线上由 worker/ssr.js 现渲染，包里并没有。正常点击不会请求它们，
 * 可一旦 APP 被系统回收后从该地址恢复，就会真的来取。这时兜到 index.html，
 * 由 public/js/app.js 自己解析地址还原到那一篇（它本就「只认路径」，见该文件开头的注释）。
 *
 * <p><b>MIME 必须给准</b>：app.js 是 {@code <script type="module">}，浏览器对模块脚本
 * 做严格 MIME 检查，给错类型会被直接拒绝执行，表现为整个应用不动 ——
 * 正是要避免的那种「装了却打不开」。讲记正文是 .txt，缺了 UTF-8 声明则整篇乱码。
 */
class AppContentHandler implements WebViewAssetLoader.PathHandler {

    private final AssetManager assets;
    /** 增量更新落盘处；出厂时不存在，下过更新才有 */
    private final File overlayDir;

    private static final Map<String, String> MIME;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("html", "text/html");
        m.put("js", "text/javascript");          // 模块脚本必须是 JS MIME，否则不执行
        m.put("mjs", "text/javascript");
        m.put("css", "text/css");
        m.put("json", "application/json");
        m.put("webmanifest", "application/manifest+json");
        m.put("txt", "text/plain");              // 讲记正文
        m.put("svg", "image/svg+xml");
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("gif", "image/gif");
        m.put("webp", "image/webp");
        m.put("ico", "image/x-icon");
        m.put("woff2", "font/woff2");
        m.put("woff", "font/woff");
        m.put("ttf", "font/ttf");
        MIME = Collections.unmodifiableMap(m);
    }

    AppContentHandler(Context ctx) {
        this.assets = ctx.getAssets();
        this.overlayDir = new File(ctx.getFilesDir(), "content");
    }

    @Nullable
    @Override
    public WebResourceResponse handle(String path) {
        String p = normalize(path);

        // 〇、放行：这两类属于线上后端，本地没有也不该有
        if (p.startsWith("api/") || p.startsWith("audio/")) return null;

        // 一、覆盖层：更新下来的新版目录优先
        File local = new File(overlayDir, p);
        if (isInside(overlayDir, local) && local.isFile()) {
            try {
                return respond(p, new FileInputStream(local));
            } catch (IOException ignored) {
                // 覆盖层坏了不是致命的，继续往下回落到出厂内容
            }
        }

        // 二、出厂内容
        InputStream in = openAsset(p);
        if (in != null) return respond(p, in);

        // 三、SPA 回退：只对「看起来是页面」的地址兜底。
        //    静态资源（.json/.txt/.css…）取不到就该老实落空，交回 WebView 去联网现取 ——
        //    包出厂之后新加的讲记正文，走的正是这条路。若在此塞一份 HTML 回去，
        //    fetch 拿到的会是一篇网页，反而把错误藏起来、更难查。
        if (looksLikePage(p)) {
            InputStream home = openAsset("index.html");
            if (home != null) return respond("index.html", home);
        }
        return null;   // 交回 WebView：或联网现取，或按常规 404
    }

    /** 去掉前导斜杠；目录地址补 index.html。 */
    private static String normalize(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.isEmpty()) return "index.html";
        if (p.endsWith("/")) return p + "index.html";
        return p;
    }

    /** 无扩展名、或以 .html 结尾的，视为页面地址。 */
    private static boolean looksLikePage(String p) {
        int slash = p.lastIndexOf('/');
        String last = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = last.lastIndexOf('.');
        return dot < 0 || last.endsWith(".html");
    }

    @Nullable
    private InputStream openAsset(String p) {
        try {
            return assets.open(p, AssetManager.ACCESS_STREAMING);
        } catch (IOException e) {
            return null;    // assets 里没有这一份
        }
    }

    private static WebResourceResponse respond(String path, InputStream data) {
        String mime = mimeOf(path);
        // 文本类显式声明 UTF-8：讲记正文全是中文，缺了这一项会整篇乱码
        String enc = mime.startsWith("text/") || mime.contains("json")
                || mime.contains("javascript") || mime.contains("manifest") ? "utf-8" : null;
        WebResourceResponse r = new WebResourceResponse(mime, enc, data);
        Map<String, String> headers = new HashMap<>();
        // 本地内容不涉及跨源，给一条宽松的 CORS 头即可，省得 fetch 被拦
        headers.put("Access-Control-Allow-Origin", "*");
        // 出厂内容随包更新，覆盖层由 ContentUpdater 管；再交给 WebView 缓存一层
        // 只会让「更新完还是旧的」变得难查
        headers.put("Cache-Control", "no-cache");
        r.setResponseHeaders(headers);
        return r;
    }

    private static String mimeOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "text/html";
        String ext = path.substring(dot + 1).toLowerCase();
        String m = MIME.get(ext);
        return m != null ? m : "application/octet-stream";
    }

    /** 防目录穿越：覆盖层的路径来自网络下发的清单，必须确认它没跑出沙箱。 */
    private static boolean isInside(File dir, File child) {
        try {
            return child.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
