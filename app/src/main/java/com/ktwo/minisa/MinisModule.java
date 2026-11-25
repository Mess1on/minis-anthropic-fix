package com.ktwo.minisa;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern Xposed API (libxposed API 102)。
 *
 * Hook 点：okhttp3.OkHttpClient.newCall(Request)
 *   — Request$Builder.build() 在 Minis 的 dex 里找不到（被 R8 优化/内联），
 *     改用 newCall：所有出站请求都必须经过它，且是 public API 入口，不会被内联。
 *
 * 做两件事：
 *   1. 补 x-opencode-session 头
 *   2. 把 Authorization: Bearer 转成 x-api-key
 */
public class MinisModule extends XposedModule {

    private static final String TAG = "MinisA";
    private static final String TARGET_PKG = "com.openminis.app";
    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String SESSION_VALUE = "minis-xposed-7f3a91";

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());

        ClassLoader cl = param.getDefaultClassLoader();

        // 诊断：把 Request$Builder 的方法名打出来
        try {
            Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
            StringBuilder sb = new StringBuilder();
            for (Method m : builderCls.getDeclaredMethods()) {
                if (m.getName().equals("build") || m.getName().equals("header")) {
                    sb.append(m.getName()).append("(");
                    for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                    sb.append(") ");
                }
            }
            log(Log.INFO, TAG, "builder methods: " + sb);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "probe builder failed: " + t);
        }

        // 主 hook：OkHttpClient.newCall(Request)
        try {
            Class<?> clientCls = cl.loadClass("okhttp3.OkHttpClient");
            Class<?> reqCls = cl.loadClass("okhttp3.Request");
            Method newCall = clientCls.getDeclaredMethod("newCall", reqCls);
            newCall.setAccessible(true);

            hook(newCall).intercept(chain -> {
                Object req = chain.getArg(0);
                Object rebuilt = inject(req);
                if (rebuilt != req) {
                    return chain.proceed(new Object[]{rebuilt});
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "hooked OkHttpClient.newCall");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "newCall hook failed: " + t);
            // 兜底：试 build()
            try {
                Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
                Method build = builderCls.getDeclaredMethod("build");
                build.setAccessible(true);
                hook(build).intercept(chain -> {
                    Object req = chain.proceed();
                    Object rebuilt = inject(req);
                    return rebuilt != req ? rebuilt : req;
                });
                log(Log.INFO, TAG, "fallback: hooked Request$Builder.build");
            } catch (Throwable t2) {
                log(Log.ERROR, TAG, "build fallback failed: " + t2);
            }
        }
    }

    /** 按需重建 Request（加 header / 换鉴权字段），不满足条件时原样返回。 */
    private Object inject(Object req) {
        if (req == null) return null;
        try {
            Class<?> reqCls = req.getClass();
            Method urlM = reqCls.getMethod("url");
            String url = String.valueOf(urlM.invoke(req));

            if (!url.contains("opencode.ai")) return req;
            if (!url.contains("/zen/go/v1/messages")) return req;

            Method headerM = reqCls.getMethod("header", String.class);
            Object existing = headerM.invoke(req, SESSION_HEADER);
            if (existing != null) return req;

            Object nb = reqCls.getMethod("newBuilder").invoke(req);
            Class<?> nbCls = nb.getClass();
            Method setHeaderM = nbCls.getMethod("header", String.class, String.class);

            setHeaderM.invoke(nb, SESSION_HEADER, SESSION_VALUE);

            Object xKey = headerM.invoke(req, "x-api-key");
            if (xKey == null) {
                Object auth = headerM.invoke(req, "Authorization");
                if (auth instanceof String) {
                    String a = (String) auth;
                    if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        String key = a.substring(7).trim();
                        if (!key.isEmpty()) {
                            setHeaderM.invoke(nb, "x-api-key", key);
                            log(Log.INFO, TAG, "converted Bearer -> x-api-key (" + key.length() + " chars)");
                        }
                    }
                }
            }

            Object rebuilt = nbCls.getMethod("build").invoke(nb);
            log(Log.INFO, TAG, "injected " + SESSION_HEADER + " -> " + url);
            return rebuilt;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "inject error: " + t);
            return req;
        }
    }
}
