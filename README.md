# Minis Anthropic Fix

LSPosed 模块：给 Minis 发往 OpenCode Go 的 Anthropic 请求补上 `x-opencode-session` 头。

**不需要反代，不需要改 Minis APK，不需要重新签名，数据不丢。**

## 为什么需要

OpenCode Go 的 Anthropic 端点 `https://opencode.ai/zen/go/v1/messages` 强制要求：

```
x-opencode-session: <任意值>
```

缺失 → `400 MissingSessionID`。

而 Minis 的 provider 配置里没有自定义 Header 的位置，填了 `customBaseURL` 还会改用 `Authorization: Bearer` 而不是 `x-api-key`。

## 它做什么

Hook `okhttp3.Request$Builder.build()`：

- URL 命中 `opencode.ai` + `/zen/go/v1/messages` → 加 `x-opencode-session`
- 其它请求原样放过
- 不依赖 Minis 的混淆后类名（OkHttp 未重定位，dex 里确认有 `Lokhttp3/Request$Builder;`）

## 用法

1. 装 APK（全新安装，不覆盖 Minis）
2. LSPosed → 模块 → 启用 → 作用域勾选 **Minis**
3. 强制停止 Minis 再打开
4. Minis 里配 Anthropic provider：
   - Base URL：`https://opencode.ai/zen/go`（**不要**带 `/v1/messages`）
   - 追加 `/v1` 后缀：**开**
   - API Key：你的 OpenCode key

## 验证

```
adb logcat | grep MinisA
```

应看到：

```
[MinisA] hooked into com.openminis.app
[MinisA] injected x-opencode-session -> https://opencode.ai/zen/go/v1/messages
```

## 构建

推送到 GitHub 后由 Actions 自动编译，产物在 Artifacts 里。

本地构建：`gradle assembleRelease`

## 注意

- 模块作用域只勾 Minis，不要勾系统框架
- 如果 Minis 更新后改了网络栈（不再用 OkHttp），需要改 hook 点
