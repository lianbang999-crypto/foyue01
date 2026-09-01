package org.foyue.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 目录更新：把三份会变的清单刷成最新，写进覆盖层，下次启动生效。
 *
 * <h3>为什么这不是个可有可无的功能</h3>
 * 二十四小时播经台的排播是各客户端自己推演出来的（见 public/js/station.js）：
 * 从开播纪元起算，谁算都该算出同一时刻、同一集、同一秒 —— 「天下同闻」就靠这个。
 * 而推演的依据正是 catalog.json 里的集目与时长。若目录随安装包冻在出厂那天，
 * 站里新收了讲座之后，APP 用户听到的就不再是大众此刻正听的那一句，
 * 而他自己毫不知情。所以这三份必须跟得上，不能等下一个版本的安装包。
 *
 * <p>讲记正文不在此列：那是定稿的开示原文，且包出厂之后新加的篇目，
 * 取件台本就会放行到网络现取（见 {@link AppContentHandler} 的三级查找）。
 *
 * <p>带 ETag 问，没变就是一个 304、几百字节，故每次启动都问得起；
 * 只加一道半小时的下限，挡住反复冷启动那种问法。
 * 全程失败无声 —— 没网、超时、服务端出错，照旧用包里那一份，与从前一样，不比从前更差。
 */
class ContentUpdater {

    /** 会变的三份。名字即线上路径，也即覆盖层里的相对路径 */
    private static final String[] FILES = { "catalog.json", "library.json", "qa.json" };

    private static final String ORIGIN = "https://foyue.org";
    private static final String PREFS = "foyue.content";
    private static final String KEY_LAST = "lastCheck";
    private static final long MIN_GAP = 30 * 60 * 1000L;   // 半小时内不重复问

    private final Context ctx;

    ContentUpdater(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void start() {
        final SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong(KEY_LAST, 0);
        // 时钟被往回拨过时 now-last 会是负数，那种情况也照问一次，别把自己锁死
        if (last != 0 && now - last >= 0 && now - last < MIN_GAP) return;

        new Thread(new Runnable() {
            @Override public void run() {
                File dir = new File(ctx.getFilesDir(), "content");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                boolean reached = false;
                for (String name : FILES) {
                    if (fetch(sp, dir, name)) reached = true;
                }
                // 只有真联系上服务端才记时间：没网时记了，回头有网的头半小时反倒不问
                if (reached) sp.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
            }
        }, "foyue-content").start();
    }

    /** @return 是否成功联系上服务端（含 304）；网络不通返回 false */
    private boolean fetch(SharedPreferences sp, File dir, String name) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream os = null;
        File part = new File(dir, name + ".part");
        try {
            conn = (HttpURLConnection) new URL(ORIGIN + "/" + name).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            String etag = sp.getString("etag." + name, null);
            // 只在本地确有这一份时才带 ETag 去问：覆盖层被清过而 ETag 还留着的话，
            // 服务端一个 304 就把我们打发了，本地却空着
            if (etag != null && new File(dir, name).isFile()) {
                conn.setRequestProperty("If-None-Match", etag);
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return true;   // 没变，正常
            if (code / 100 != 2) return true;                               // 服务端有话说，联系上了，但这次不动

            in = conn.getInputStream();
            os = new FileOutputStream(part);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;

            // 目录是 JSON，落盘前粗查一眼：截断的响应写进覆盖层，
            // 会让 APP 下次启动直接解析失败 —— 那比目录旧得多。
            if (part.length() < 64 || !looksLikeJson(part)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return true;
            }

            File dest = new File(dir, name);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (part.renameTo(dest)) {
                String tag = conn.getHeaderField("ETag");
                if (tag != null) sp.edit().putString("etag." + name, tag).apply();
            }
            return true;
        } catch (Exception e) {
            return false;     // 多半是没网
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
            if (part.exists()) { //noinspection ResultOfMethodCallIgnored
                part.delete(); }
        }
    }

    /** 首末非空字符是不是 {} 或 []。不做完整解析 —— 只为挡住截断与错页。 */
    private static boolean looksLikeJson(File f) {
        java.io.RandomAccessFile r = null;
        try {
            r = new java.io.RandomAccessFile(f, "r");
            int first = r.read();
            while (first == ' ' || first == '\n' || first == '\r' || first == '\t') first = r.read();
            long pos = r.length() - 1;
            int last = -1;
            while (pos >= 0) {
                r.seek(pos);
                last = r.read();
                if (last != ' ' && last != '\n' && last != '\r' && last != '\t') break;
                pos--;
            }
            return (first == '{' && last == '}') || (first == '[' && last == ']');
        } catch (Exception e) {
            return false;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }
}
