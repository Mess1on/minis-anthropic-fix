package com.ktwo.minisa;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 给 Minis 发往 OpenCode Go 的 Anthropic 请求补 x-opencode-session 头。
 *
 * hook 点：okhttp3.Request$Builder.build()
 *   — OKHttp 是第三方库，Minis 未重定位（dex 中确认存在 Lokhttp3/Request$Builder;）
 *   — 不依赖 Minis 自己的混淆后类名
 *
 * 命中条件：URL host = opencode.ai 且 path 含 /zen/go/v1/messages
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.openminis.app";
    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String SESSION_VALUE = "minis-xposed-7f3a91";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        XposedBridge.log("[MinisA] hooked into " + lpparam.packageName
                + " (process=" + lpparam.processName + ")");

        try {
            Class<?> builderCls = XposedHelpers.findClass(
                    "okhttp3.Request$Builder", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(builderCls, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object req = param.getResult();
                        if (req == null) return;

                        // 已经补过就跳过（防止 newBuilder().build() 递归触发）
                        Object existing = XposedHelpers.callMethod(req, "header", SESSION_HEADER);
                        if (existing != null) return;

                        Object url = XposedHelpers.callMethod(req, "url");
                        if (url == null) return;
                        String s = url.toString();

                        if (!s.contains("opencode.ai")) return;
                        if (!s.contains("/zen/go/v1/messages")) return;

                        Object nb = XposedHelpers.callMethod(req, "newBuilder");
                        XposedHelpers.callMethod(nb, "header", SESSION_HEADER, SESSION_VALUE);
                        Object rebuilt = XposedHelpers.callMethod(nb, "build");
                        param.setResult(rebuilt);

                        XposedBridge.log("[MinisA] injected " + SESSION_HEADER + " -> " + s);
                    } catch (Throwable t) {
                        XposedBridge.log("[MinisA] err: " + t);
                    }
                }
            });
            XposedBridge.log("[MinisA] request hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[MinisA] install failed: " + t);
        }
    }
}
