# 安全修复与验证记录

基线：`d31c0a270e7c8610e9679dbbda5a6bee06927952`。以下先保留第一轮修复与本地验证记录；第二轮修复及实际 GitHub CI 验证范围见本文末尾。

## 修复范围

| 问题 | 修改与边界 |
| --- | --- |
| iOS WebView Cookie 串域 | 前台、后台 WebView 均使用独立的非持久网站存储；同步时保留 NSHTTPCookie 属性并按书源分区，不再将身份提供方 Cookie 复制到书源 URL。空快照清除已注销 Cookie。 |
| Cookie 后续发送 | 查询使用完整请求 URL，匹配 host-only/domain、路径、Secure、过期时间；系统隐式 Cookie 存储关闭。每次重定向重新选取 Cookie，跨 origin 移除 Authorization，不向服务器发送内部书源标记。 |
| JavaScript 运行时释放 | 原生桥移除 `java.close`，底层 method ID 也拒绝调用。运行时使用计数、关闭状态和可重入锁防止执行中释放；资源指针在释放时清空，重复关闭安全。修复“当前上下文”错误地复制所有权，以及原来以全局 atomic 冒充线程局部上下文的问题。跨 scope 的原生对象 handle 不可读取。 |
| TAR 整数截断 | 大小和偏移用 Long 校验，包含头、内容、对齐、校验和与严格前进检查，之后才转 Int。拒绝负数、超长、损坏及截断输入；每个条目检查脚本取消状态。 |
| ZIP / EPUB / GZIP 资源无限增长 | ZIP/EPUB 输入上限 64 MiB、单条目 32 MiB、总展开 128 MiB、10,000 个条目、压缩比 1000。DEFLATE 在写入及扩容之前检查实际输出，不能靠伪造声明大小绕过。GZIP 同样有累计展开上限；EPUB 文件先检查大小再整读。 |
| Native SOCKS 静默直连 | SOCKS4/5 及非法配置明确抛错；HTTP 代理保留支持。错误文本不打印代理密码。 |
| Android/桌面 TLS 验证关闭 | 主网络客户端、jsoup、TVBox 默认客户端恢复系统证书/主机名验证；移除 Cronet 的反射信任覆盖。SSLHelper 旧命名保留兼容，但返回安全的系统验证实现。 |
| LAN Web 管理无认证 | 每次服务启动生成系统安全随机的 256-bit 凭证；HTTP 上传解析前和 API 分派前均检查；WebSocket 同样检查。凭证由 App 显示的 URL fragment 交给本地页面交换为 HttpOnly、SameSite=Strict Cookie；不接受 query token。校验 Origin，重启/停止使旧凭证失效。Android ContentProvider 保留其原有权限边界，不从 HTTP 接受本地可信标志。 |
| GitHub iOS Remote 公开凭证 | 移除公开 Quick Tunnel / noVNC 和固定密码，改为私有 SSH 网关回环端口转发；缺少 Secrets 则在启用屏幕共享前失败。日志/摘要不输出连接地址、口令或密钥，结束时停止共享。 |
| 书源脚本的数据访问 | Cookie API 限同 origin，缓存及文件目录按书源分区，拒绝路径穿越与目录链上的符号链接；原生书源身份不可由脚本修改。暂时拒绝脚本调用共享目录解压入口，App 文件导入仍可使用解压。 |

## 兼容性与剩余边界

- 旧版 iOS Cookie 已丢失域、路径及 Secure 等属性，无法安全迁移。新存储不读取旧 DB Cookie；用户需要重新登录。新持久 Cookie 保存绝对过期时间，不在重启后重新计算 Max-Age；会话 Cookie 不跨 App 启动持久化。
- 私有 WebView 不保留跨实例的 localStorage/IndexedDB。依赖这些网站状态的登录需要重新验证；OAuth Cookie 保留原始域，后续请求仍受相应 URL 条件限制。
- 超出资源预算的真实大型书籍/备份也会被拒绝。EPUB 仍是**有上限的全量内存解析**，没有改成流式按需解析；远程 EPUB 下载到磁盘的流量不由这个内存预算限制。并发导入的总进程内存仍需设备测试。
- 自签名证书、过期证书、证书名不匹配的网站不再默认可访问。需要正确配置证书。
- 新的脚本缓存与文件目录不读取旧共享目录；没有删除旧数据。无书源身份的脚本不能使用私有文件/缓存 API。跨源授权 UI 尚未实现，目前默认拒绝跨 origin Cookie API。
- 这不是完整的不可信 JavaScript 沙箱。脚本仍可联网，仍可能读取其所属书源授予的数据；其它宿主 API、JVM 危险 API/反射及对象模型不在完整隔离证明之内。不要据此宣称陌生书源安全。
- Web 服务仍使用 HTTP，认证不提供传输加密；仅应在可信网络或加密隧道中使用。跨 origin 的独立 Web 前端不再获得任意 CORS 授权；从 App 提供的完整访问地址打开页面。

## iOS Remote 私有访问配置

配置以下 repository Secrets，口令和 SSH 私钥不要写入工作流或日志：

- `IOS_REMOTE_VNC_PASSWORD`：独立生成的 8 位随机 ASCII 口令（legacy VNC 仅使用 8 字节）。
- `IOS_REMOTE_SSH_HOST`、`IOS_REMOTE_SSH_USER`：受控网关地址与专用账号。
- `IOS_REMOTE_SSH_KEY`：专用私钥，不复用个人管理员密钥。
- `IOS_REMOTE_SSH_KNOWN_HOSTS`：通过可信渠道核验的网关主机公钥记录。

网关必须配置 `GatewayPorts no`，将专用账号/key 的转发权限限制为 `127.0.0.1:15900`（例如 OpenSSH `PermitListen 127.0.0.1:15900`），不要把这个端口代理到公网。操作者从自己的机器运行：

```sh
ssh -N -L 5901:127.0.0.1:15900 <operator>@<gateway>
```

随后 VNC 客户端连接 `127.0.0.1:5901`，输入通过 Secrets 私下管理的口令。操作者的账号和工作流账号可以不同。配置只写入了可审查的工作流，本次没有连接任何网关或创建仓库 Secrets。

## 已执行验证

独立测试直接编译被修改的生产 Kotlin 文件。文件平台壳只用 JVM File 类型别名；HTTP 测试替换数据库控制器、日志和资源加载，以便验证真实 NanoHTTPD/WebApi/NanoWSD 路由及认证而无需 App 启动。这些测试**不是整个 App 的构建或设备测试**。

- 修改前 `ZipRegression.kt` 复现：4,211 字节的 ZIP 被接受并展开为 4 MiB。
- 修改后普通 ZIP 通过，压缩炸弹被拒绝；76 条边界断言覆盖 Cookie、TAR、延迟关闭、代理、Web 凭证和 ZIP 输出预算。
- 真实本地 HTTP/WebSocket 测试：未认证的备份、书架、书源读取与删除/上传返回 401；控制器调用次数不增加。有效凭证可访问，恶意 Origin、未认证 WebSocket 握手及停止后的旧 Cookie 被拒绝。
- TLS 握手测试：受信任证书及正确主机名成功；不受信任证书和错误主机名失败。
- 工作流 YAML 解析及所有 `run` 块的 `bash -n` 通过；Kotlin 语法树检查通过；`git diff --check` 通过。

运行独立测试需要 JDK 与 Kotlin 编译器。示例变量以本机安装位置替换：

```sh
export JAVA_HOME=/path/to/jdk
export KOTLIN_COMPILER_CLASSPATH='/path/to/kotlinc/lib/*'
export KOTLIN_STDLIB=/path/to/kotlinc/lib/kotlin-stdlib.jar
# 按脚本头部说明提供 okio、coroutines、serialization-core、JUnit 4、Hamcrest 的 jar。
export SECURITY_TEST_DEPS=/path/to/security-test-dependencies
scripts/security-tests/run.sh

# HTTP 测试另需 nanohttpd、nanohttpd-websocket、serialization-json。
scripts/security-tests/run-transport.sh
```

## 第一轮验证限制（历史记录）

第一轮执行时，本机为 Linux，没有 Xcode/iOS SDK。已添加 `IosCookieStoreTest` 和 `NativeJsLifetimeTest`，但**尚未在模拟器执行**。应在 macOS 上按项目既有流程准备 QuickJS/mbedtls 原生库后执行：

```sh
./gradlew :data:iosSimulatorArm64Test -PenableIosTarget=true
```

上一轮本地完整 JVM Gradle 验证尝试了 `:data:jvmTest :core:jvmTest`。项目固定的 Gradle 9.6.0 分发包在 wrapper 重定向及官方直接地址下载时均超时；未更换项目版本或声称完整构建通过。Android、iOS、鸿蒙端的编译与设备回归仍需在对应工具链完成，重点包括 Cookie 登录/注销/重定向、双书源隔离、异常导入及并发运行时关闭。


参考规范：[Apple NSHTTPCookie domain](https://developer.apple.com/documentation/foundation/httpcookie/domain)、[RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html)、[Ktor Darwin 配置](https://api.ktor.io/3.5.x/ktor-client-darwin/io.ktor.client.engine.darwin/-darwin-client-engine-config/index.html)。

## 第二轮修复与验证（2026-10-27）

上一提交 `42664107b` 的 [Test Build](https://github.com/last2win/legado/actions/runs/40357370970)
实际失败于生产代码编译；Android 日志明确包含 `JsExtensionsCommon.kt:1151` 的
`Unresolved reference SecurityException`。本地下载失败与 CI 编译失败是两件事，不能互相替代解释。

- 公共异常改为 `UnsupportedOperationException`。`WebApiRequest.trustedLocalCall` 放在末尾，鸿蒙三个调用改用具名参数。
- `RemoteZipCore` 在读目录、分配压缩内容前检查范围/溢出/条目数/单条目/总量/压缩比；拒绝短读、STORED 大小不一致及 CRC 不符。Native 导入与本地 CBZ 整读前检查输入长度。JVM 与 Native DEFLATE 均在输出增长时执行上限。
- 脚本 GZIP/zlib/deflate 入口共享 `ArchiveLimits`，按块计算实际输出并传播取消；iOS HTTP 压缩响应也复用此实现。默认单段输出最多 32 MiB，输入最多 64 MiB，压缩比最多 1000。
- KSP 生成的对象返回值登记到当前 scope；增加真实 QuickJS 的 `java.get → Response.parse → select/text`、跨 scope 拒绝和关闭用例。
- 私有 WebView 只注入一次完整有效的书源 Cookie；URL 与 HTML 加载都等待注入完成。快照回写根据初始/上次快照逐项协调，保留其他路径、Secure Cookie 和并发刷新。清除登录态按完整书源分区删除，包括跨站登录域。
- iOS Coil 和简单图片下载改走 KmpHttpClient 的逐跳策略；音频、视频、WebView、Cronet 及 HTTP 出口按大小写不敏感方式过滤两个内部头。新增 iOS KmpCall 重定向用例，网络引擎使用 MockEngine，验证逐跳 Cookie、跨 origin Authorization 删除与内部头不外发。

本轮本地验证：`run.sh` 通过 81 条边界断言、35 条生产解压入口断言及 24 个原有/扩展 JUnit ZIP 用例；
`run-transport.sh` 的真实本地 HTTP/WebSocket 和 TLS 握手测试通过。
异常 ZIP 回归也用上一版 `RemoteZipCore` 做反向检查，旧实现不能通过新增断言。
独立测试使用 Kotlin 2.2.0 编译生产文件子集，不等于项目 Kotlin 2.3.20 全量编译。

新增 `Security regression checks` 自动工作流：JVM 生产模块编译、项目 ZIP/脚本解压测试；
macOS 编译 iOS UI、链接并执行 data 模拟器测试。工作流结果必须以当前提交 SHA 为准。
本机仍无 Xcode，未执行 iOS/QuickJS/MockEngine 用例；未做真机 WebKit 登录、AVPlayer 抓包或完整 IPA 验证。
本轮再次下载 Gradle 9.6.0 被 GitHub 分发地址连接超时阻断，本地全量编译仍没有通过证据。


### 真实 CI 验证范围

新增工作流会对每次相关修改编译生产 JVM 模块和 iOS UI，并链接、执行 iOS 模拟器测试；
不能用独立测试、旧提交结果或工作流文件存在代替运行成功。
具体提交结果见 [Security regression checks](https://github.com/last2win/legado/actions/workflows/security-validation.yml)，
桌面与 Android 打包见 [Test Build](https://github.com/last2win/legado/actions/workflows/test.yml)。

本轮已根据实际 iOS 编译/模拟器日志修复 Foundation 到期时间扩展、Native `okio.use` 导入和测试 cinterop 声明。
实际 QuickJS 用例还发现并修复了整数标签句柄被当作浮点 union 读取的问题，以及 `Response.parse()` 返回的
jsoup 包装对象与 ksoup DOM 分派类型不一致的问题；跨 scope 拒绝用例使用同一条可在所属 scope 成功执行的 DOM 方法。
EPUB 测试夹具补齐 ZIP 根目录文件；生产解析器允许读取包内已存在但未列入 manifest 的封面图片。

这些模拟器测试使用真实 QuickJS、DOM 和 Foundation Cookie；网络重定向使用 Ktor MockEngine。
它们仍不覆盖真机 WebKit 登录、AVPlayer 网络抓包或完整 IPA 签名安装。
本轮 Android CI 已越过源码编译，但发布打包日志报告 `key.jks` 的 `Tag number over 30 is not supported`；
这属于现有签名配置失败，本次没有更换签名材料或修改仓库 Secrets。


### 对照补充截图的验收清单

| 截图中的问题 | 当前实现和回归证据 |
| --- | --- |
| commonMain 引用 Native 专有 SecurityException | `JsExtensionsCommon.unArchiveFile` 使用公共 `UnsupportedOperationException`；JVM、Android 和桌面实际编译已越过该错误。 |
| RemoteZipCore 信任 compressedSize 分配、接受短读 | 先校验输入、目录、条目及实际文件区间，再读取；STORED 压缩/展开大小必须相等，读取必须精确，结果校验长度和 CRC。`ArchiveEntryRegression` 包含同类伪造大小样本和截断样本。 |
| Native 导入直接整包读取 | `NativeFileBookAccessor`、本地 CBZ 和 `NativeArchiveProvider` 均在文件整读前检查输入上限，解析使用受限 RemoteZipCore。目录遍历检查条目数量和总展开量。 |
| 脚本 GZIP/zlib/deflate 无实际输出预算 | `decompressBytes` 接入 StreamDecompressor，逐块检查实际累计输出、压缩比与取消状态；`StreamDecompressorTest` 直接调用脚本入口验证三种格式的压缩炸弹。 |
| 鸿蒙路由构造参数错位 | 新默认字段移至末尾，三处鸿蒙调用使用具名参数；完整鸿蒙工具链验证仍未执行。 |
| iOS 只编部分生产文件，未完整链接/打包 | 安全工作流新增 simulator/device 两条完整 App 构建：Xcode 编译 Swift 与扩展、链接共享 framework，分别生成模拟器 ZIP 和未签名 IPA。失败直接令作业失败，日志及产物保留 7 天；结果以对应提交的 Actions 为准。 |

完整 iOS 构建脚本为 `bash scripts/security-tests/build-ios-app.sh simulator` 或 `device`，
需 macOS、Xcode、JDK 21、XcodeGen。该验证只上传 Actions 产物，不创建或覆盖 GitHub Release，
也不代表已完成 Apple 签名、真机安装或真机业务流程验证。
