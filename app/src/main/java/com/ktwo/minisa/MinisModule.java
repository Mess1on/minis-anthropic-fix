package com.ktwo.minisa;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern Xposed API (libxposed API 102) 实现。
 *
 * 给 Minis 发往 OpenCode Go 的 Anthropic 请求补两个东西：
 *   1. x-opencode-session 头（OpenCode 的路由要求）
 *   2. 把 Authorization: Bearer 转成 x-api-key（Minis 对 custom endpoint 用前者，
 *      OpenCode 的 /v1/messages 只认后者）
 *
 * Hook 点：okhttp3.Request$Builder.build()
 *   OkHttp 是第三方库，Minis 未重定位（dex 中确认 Lokhttp3/Request$Builder; 存在）
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

        try {
            ClassLoader cl = param.getDefaultClassLoader();
            Class<?> builderCls = cl.loadClass("okhttp3.Request$Builder");
            Method buildMethod = builderCls.getDeclaredMethod("build");
            buildMethod.setAccessible(true);

            hook(buildMethod).intercept(chain -> {
                Object req = chain.proceed();
                if (req == null) return null;

                try {
                    Class<?> reqCls = req.getClass();
                    Method urlM = reqCls.getMethod("url");
                    Object urlObj = urlM.invoke(req);
                    String url = String.valueOf(urlObj);

                    if (!url.contains("opencode.ai")) return req;
                    if (!url.contains("/zen/go/v1/messages")) return req;

                    Method headerM = reqCls.getMethod("header", String.class);

                    // 已经补过就跳过（避免 newBuilder().build() 递归）
                    Object existing = headerM.invoke(req, SESSION_HEADER);
                    if (existing != null) return req;

                    Method newBuilderM = reqCls.getMethod("newBuilder");
                    Object nb = newBuilderM.invoke(req);
                    Class<?> nbCls = nb.getClass();
                    Method setHeaderM = nbCls.getMethod("header", String.class, String.class);

                    setHeaderM.invoke(nb, SESSION_HEADER, SESSION_VALUE);

                    // Bearer -> x-api-key
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
                    log(Log.WARN, TAG, "hook body error: " + t);
                    return req;
                }
            });

            log(Log.INFO, TAG, "request hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed: " + t);
        }
    }
}
