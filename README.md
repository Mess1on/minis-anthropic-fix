# Minis Anthropic Fix

> 让 Minis 的 Anthropic 类型服务商可以直接对接 **OpenCode Go**，无需反代、无需改 APK、不丢数据。

一个约 9 KB 的 LSPosed 模块，在 OkHttp 层给发往 `opencode.ai` 的 Anthropic 请求补上两个东西：

| 补什么 | 为什么 |
|---|---|
| `x-opencode-session` 头 | OpenCode 的路由要求，缺失直接返回 `400 MissingSessionID` |
| `x-api-key` | Minis 对自定义 endpoint 用 `Authorization: Bearer` 发送密钥，而 OpenCode 的 `/v1/messages` 只认 `x-api-key` |

---

## 背景

OpenCode Go（$10/月）提供了 Anthropic 兼容端点：

```
POST https://opencode.ai/zen/go/v1/messages
```

但它强制要求一个请求头：

```http
x-opencode-session: <任意值>
```

缺失时返回：

```json
{"type":"error","error":{"type":"MissingSessionID",
"message":"Request is missing x-opencode-session and cannot be routed efficiently"}}
```

换任何 User-Agent 都绕不过——服务端只认这个头。

而 Minis 的 provider 配置里**没有自定义 Header 的位置**，字段只有：

```
apiKey / customBaseURL / appendV1Suffix / customUserAgent
label / networkRoute / responseIdleTimeout / useResponsesAPI
```

于是产生了这个模块：不改 Minis、不重签名、不丢数据，只在内核层给请求补头。

---

## 原理

```
Minis 构造请求
    ↓
okhttp3.Request$Builder.<build>()     ← LSPosed 拦截
    ↓ 匹配 URL 含 opencode.ai + /zen/go/v1/messages
    ↓ 补 x-opencode-session
    ↓ 把 Authorization: Bearer xxx 转成 x-api-key: xxx
    ↓
直连 opencode.ai，200 OK
```

### 为什么 hook OkHttp 而不是 Minis 自己的代码

Minis 的业务类被 R8 混淆（`com.openminis.app.provider.anthropic.*` 在 dex 里已经不存在），但 **OkHttp 是第三方库，类名保留**：

```
Lokhttp3/Request$Builder;   ← dex 里真实存在
Lokhttp3/Request;
Lokhttp3/Headers;
```

不过 OkHttp 的**方法名也被 R8 改了**：

| 官方方法 | dex 里的名字 |
|---|---|
| `Request$Builder.build()` | `a()` |
| `Request$Builder.header(String, String)` | `d(String, String)` |
| `Request.newBuilder()` | `c()` |
| `Request.url()` | `d()` |
| `Request.headers()` | `b()` |
| `Headers.get(String)` | `b(String)` |

**所以本模块不认方法名，只认类型签名**：

```java
// build(): 返回值是 okhttp3.Request、无参数
for (Method m : builderCls.getDeclaredMethods())
    if (m.getParameterCount() == 0 && m.getReturnType() == reqCls) buildM = m;
```

R8 能改名字，改不掉签名。这样无论混淆策略怎么变都能定位。

---

## 适用环境

| 项目 | 要求 |
|---|---|
| Android | 8.1+（实测 Android 17 / ColorOS） |
| LSPosed | **必须支持 Modern API（libxposed 102）** |
| Minis | 1.16-beta.8 实测通过，其它版本需确认仍用 OkHttp |
| 网络库 | OkHttp（未重定位的情况下） |

> **注意**：传统 `de.robv.android.xposed` API 的模块在 LSPosed 2.2.1 上会被**静默跳过**，日志里一个字都没有。本模块从第一版起就是 Modern API。

---

## 安装

1. 从 [Releases](../../releases) 下载 APK（或自己编译，见下文）
2. 安装——**没有桌面图标是正常的**，纯 Hook 模块不需要界面
3. 打开 LSPosed → 模块 → 启用 **Minis Anthropic Fix**
4. 作用域勾选 **Minis**
5. 强制停止 Minis 再打开（或重启手机）

---

## 配置

在 Minis 里新建一个 **Anthropic** 类型的服务商：

| 字段 | 值 | 说明 |
|---|---|---|
| Provider Type | `Anthropic` | |
| **Base URL** | **`https://opencode.ai/zen/go`** | **只填到这里** |
| 追加 `/v1` 后缀 | 按 base 决定 | base 不含 `/v1` 时打开 |
| API Key | 你的 OpenCode key | |

### Base URL 规则（最容易错的地方）

Minis 的 Anthropic 客户端会自己拼 `/v1/messages`：

```
base = https://opencode.ai/zen/go
客户端拼 → https://opencode.ai/zen/go/v1/messages     ✅ 200

base = https://opencode.ai/zen/go/v1/messages
客户端拼 → https://opencode.ai/zen/go/v1/messages/v1/messages   ❌ 404
```

**Base URL 永远不要带 `/v1` 或 `/messages`。**

---

## 验证

模块日志不在 `logcat` 里，LSPosed 会把它收进自己的日志文件：

```bash
adb shell su -c 'grep "ktwo.minisa" /data/adb/lspd/log/modules_*.log | tail -5'
```

**启动时**应看到：

```
onPackageLoaded: com.openminis.app
resolved: build=Request$Builder.a() header=Request$Builder.d(String,String)
          url=Request.d() newBuilder=Request.c() headers=Request.b() get=Headers.b(String)
hook installed on build()
```

六项全部解析成功才说明方法定位正确。

**每次请求**应看到：

```
Bearer -> x-api-key (51 chars)
injected -> https://opencode.ai/zen/go/v1/messages
```

**模块有没有被加载**（最硬的判据）：

```bash
adb shell su -c '/data/adb/modules/zygisk_lsposed/lspctl status | grep Modules'
```

装上模块并重启后，数字应该 +1。

---

## 技术细节

### Modern API 模块的三个声明文件

APK 的 `META-INF/xposed/` 下必须齐全，**缺一个框架就静默跳过**：

```
java_init.list      入口类全限定名，如 com.ktwo.minisa.MinisModule
module.prop         minApiVersion / targetApiVersion / staticScope / exceptionMode
scope.list          每行一个目标包名
```

`module.prop` 示例：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
exceptionMode=protective
autoHotReload=true
```

Gradle 里必须显式打包，否则会被 AGP 丢弃：

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
    }
}
```

### 模块入口

```java
public class MinisModule extends XposedModule {
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!"com.openminis.app".equals(param.getPackageName())) return;
        // ...
    }
}
```

依赖：

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
```

### 防递归

hook `build()` 之后，在 hook 里用 `newBuilder().build()` 重建请求会再次触发 hook。代码里用「请求里是否已经有 `x-opencode-session`」来短路：

```java
if (headers.get(SESSION_HEADER) != null) return req;   // 已补过，直接返回
```

---

## 构建

```bash
git clone https://github.com/Mess1on/minis-anthropic-fix.git
cd minis-anthropic-fix
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 签名

仓库里**不含签名密钥**。`app/build.gradle.kts` 会自动判断：

- 放了 `app/minisa.jks` → 用它签名（自用升级不用卸载）
- 没放 → 回落到 debug 签名（每次构建随机）

**自己长期用建议生成一个固定 keystore**：

```bash
keytool -genkeypair -v -keystore app/minisa.jks -alias minisa \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 -dname "CN=YourName"
```

然后在 `build.gradle.kts` 的 `signingConfigs` 里填上对应密码。

### CI

`.github/workflows/build.yml` 会在 push 到 `main` 时自动编译，产物在 Actions 的 Artifacts 里。

---

## 常见问题

**Q：装了但 LSPosed 里看不到模块？**

A：先重启。模块列表是开机时加载进内存的，装完不重启不会出现。用 `lspctl status` 看 `Modules` 数字有没有 +1。

**Q：日志里有 `onPackageLoaded` 但没有 `hook installed`？**

A：说明方法定位失败。看 `resolved:` 那行，六项里哪一项是 `null`，说明对应的类型签名没匹配上——Minis 换 OkHttp 版本后方法签名可能变了。

**Q：消息报 404？**

A：Base URL 填多了。只填到 `/zen/go`，别带 `/v1/messages`。

**Q：消息报 401 Invalid API key？**

A：模块没生效，`Bearer` 没被转成 `x-api-key`。检查日志里有没有 `Bearer -> x-api-key` 这行。

**Q：消息报 400 MissingSessionID？**

A：模块完全没加载，或 URL 匹配条件没命中。检查作用域是否勾了 Minis。

---

## 免责声明

- 本项目只做**请求头改写**，不含任何 API Key、不上传任何数据、不修改 Minis 的任何业务逻辑
- 与 OpenCode、Minis 官方均无关联
- 修改运行时行为存在导致应用异常的可能，请自行评估风险
- 仅供学习与个人使用

---

## License

[MIT](LICENSE)
