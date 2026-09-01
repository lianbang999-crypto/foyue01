# 佛乐 APP · R8 规则
#
# release 开了 minify。默认规则不认得「靠反射调用」这回事，
# 下面这些若被裁掉，症状都是「不报错，但什么也没发生」—— 最难查的那一类。

# 一、暴露给页面的原生桥。要同时保住两样，缺一整座桥就哑了：
#
#    (a) 方法名 —— WebView 是按名字反射调用的，media 改成 a，
#        页面里 window.__fyNative.media(...) 就成了未定义；
#    (b) @JavascriptInterface 注解 —— API 17 起只有带这个注解的方法才会被暴露。
#        注解若被剥掉，__fyNative 是个空对象，每一次调用都静静地什么都不做。
#
#    注：曾写作 `-keepattributes JavascriptInterface`。那是个空转 ——
#    根本没有叫这个名字的属性，注解能留下来全靠默认规则里的 *Annotation*。
#    靠别处的默认值兜着而自己看不出来，正是日后最难查的那种。这里写全。
-keepclassmembers class org.foyue.app.NativeBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# 二、WebView 相关回调同理，保住类名与成员。
-keep class org.foyue.app.AppContentHandler { *; }

# 三、前台服务由系统按类名实例化，不能混淆。
-keep class org.foyue.app.MediaService { *; }

# 四、androidx.media 的会话回调经反射与 IPC 走，保守起见整体保留。
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
