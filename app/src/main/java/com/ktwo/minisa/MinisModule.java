package com.ktwo.minisa;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern Xposed API (libxposed API 102)。
 *
 * Minis 的 OkHttp 被 R8 重命名了，方法名全是 a()/b()/c()/d()：
 *   Request$Builder.a()          -> build()      （返回 Request，无参）
 *   Request$Builder.d(String,String) -> header(name,value)
 *   Request.b()                  -> headers()    （返回 Headers，无参）
 *   Request.c()                  -> newBuilder() （返回 Request$Builder，无参）
 *   Request.d()                  -> url()        （返回 HttpUrl，无参）
 *   Headers.b(String)            -> get(name)    （返回 String）
 *
 * 因此这里全部按「返回值类型 + 参数类型」定位，不依赖方法名，混淆怎么变都不怕。
 */
public class MinisModule extends XposedModule {

    private static final String TAG = "MinisA";
    private static final String TARGET_PKG = "com.openminis.app";
    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String SESSION_VALUE = "minis-xposed-7f3a91";

    private Method buildM, headerSetM, urlM, newBuilderM, headersM, headersGetM;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());

        ClassLoader cl = param.getDefaultClassLoader();

        try {
            Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
            Class<?> reqCls = cl.loadClass("okhttp3.Request");
            Class<?> headersCls = cl.loadClass("okhttp3.Headers");

            // build(): 返回 okhttp3.Request 且无参
            for (Method m : builderCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == reqCls) { buildM = m; break; }
            }
            // header(name,value): 两个 String 参数
            for (Method m : builderCls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == String.class) { headerSetM = m; break; }
            }
            // url(): 返回 HttpUrl 且无参
            Class<?> urlCls = cl.loadClass("okhttp3.HttpUrl");
            for (Method m : reqCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == urlCls) { urlM = m; break; }
            }
            // newBuilder(): 返回 Request$Builder 且无参
            for (Method m : reqCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == builderCls) { newBuilderM = m; break; }
            }
            // headers(): 返回 Headers 且无参
            for (Method m : reqCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == headersCls) { headersM = m; break; }
            }
            // Headers.get(name): 单个 String 参数，返回 String
            for (Method m : headersCls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class && m.getReturnType() == String.class) { headersGetM = m; break; }
            }

            log(Log.INFO, TAG, String.format("resolved: build=%s header=%s url=%s newBuilder=%s headers=%s get=%s",
                    buildM, headerSetM, urlM, newBuilderM, headersM, headersGetM));

            if (buildM == null || headerSetM == null || urlM == null
                    || newBuilderM == null || headersM == null || headersGetM == null) {
                log(Log.ERROR, TAG, "resolution incomplete, abort");
                return;
            }

            buildM.setAccessible(true);
            hook(buildM).intercept(chain -> {
                Object req = chain.proceed();
                Object rebuilt = inject(req);
                return rebuilt != null ? rebuilt : req;
            });
            log(Log.INFO, TAG, "hook installed on build()");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed: " + t);
        }
    }

    private Object inject(Object req) {
        if (req == null) return null;
        try {
            String url = String.valueOf(urlM.invoke(req));
            if (!url.contains("opencode.ai")) return req;
            if (!url.contains("/zen/go/v1/messages")) return req;

            Object headers = headersM.invoke(req);
            Object existing = headersGetM.invoke(headers, SESSION_HEADER);
            if (existing != null) return req;   // 已补过，防递归

            Object nb = newBuilderM.invoke(req);
            headerSetM.invoke(nb, SESSION_HEADER, SESSION_VALUE);

            Object xKey = headersGetM.invoke(headers, "x-api-key");
            if (xKey == null) {
                Object auth = headersGetM.invoke(headers, "Authorization");
                if (auth instanceof String) {
                    String a = (String) auth;
                    if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        String key = a.substring(7).trim();
                        if (!key.isEmpty()) {
                            headerSetM.invoke(nb, "x-api-key", key);
                            log(Log.INFO, TAG, "Bearer -> x-api-key (" + key.length() + " chars)");
                        }
                    }
                }
            }

            Object rebuilt = buildM.invoke(nb);
            log(Log.INFO, TAG, "injected -> " + url);
            return rebuilt;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "inject error: " + t);
            return req;
        }
    }
}
