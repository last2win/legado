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

复核时进一步补齐 ZIP 数据源入口：WebDAV Range 请求使用流式响应，实际读取上限为请求区间长度，
拒绝超长响应、非 206 响应及偏移溢出；Native 使用 `prepareGet().execute {}`，避免 Ktor 在预算检查前
自动缓存整份响应。iOS 授权文件及 Native 本地 CBZ 除文件长度预检外，实际读取同样有 64 MiB 上限。
`ArchiveReadLimitsTest` 验证超限时最多消费预算加一个探测字节，`WebDavRangeTest` 用 MockEngine
直接覆盖生产 Range 请求函数，包含无 Content-Length 和伪造 Content-Length 的响应。

## 备份、文件与运行预算补充（2026-10-28）

- `config.json` 导出与新旧配置恢复共用显式白名单。`password`、iOS 持久 Cookie、WebDAV
  账号/口令、未知配置键不迁移；恢复保留设备现有凭据。服务器配置仍按既有密码加密流程备份，
  加密失败直接终止，取消原先明文兜底。此策略不扫描用户写入书源规则中的任意字符串。
- Native/桌面书籍保存入口只接受单个文件名，拒绝分隔符、绝对路径、NUL、Windows 设备名；
  在规范化的用户选定目录中保存，拒绝已有符号链接目标。WebDAV 初次下载与远端更新共用该入口。
- iOS 普通内存响应和 Native WebDAV 内存读取以流式请求执行，实际接收最多 32 MiB，
  不依赖 Content-Length。Native 文件下载使用 `prepareGet().execute` 和 64 KiB 分块原子写入，
  取消/失败清除临时件；该下载路径不把整份响应缓存到内存，也不受 32 MiB 内存响应上限限制。
- 请求 Cookie 保留不同路径的同名条目和顺序；显式临时 Cookie 覆盖同名存储值，不修改持久存储。
- Native 每次解压使用独立目录，失败清理该次目录；同名 ZIP 不复用上次解压产物。
- Native QuickJS 所有 runtime（包括编译器）设置 64 MiB 堆上限、512 KiB 栈上限；
  每次最外层执行限时 30 秒，嵌套调用不重置期限，C 中断回调同时检查协程取消。
  这些限制不等于整个进程的总内存预算，也不能中断阻塞中的任意宿主函数。

本轮本地独立测试通过 81 条边界断言、35 条解压入口断言及 31 个 JUnit 用例。
新增 `PrivacyBoundaryTest` 验证配置白名单、ZIP 中配置内容、恢复凭据保留、文件越界/符号链接、
正常文件更新与 Cookie 合并。Native/iOS 新增真实 QuickJS 超时/内存、同名 ZIP 连续导入、
无 Content-Length 的超限 KmpCall 及实际 Cookie 请求链测试；结果以本次提交的 CI 为准。
`IosFileDownloadStreamingTest` 让模拟服务端暂停在响应中途，断言真实下载器已经写出临时文件，
再允许发送剩余内容，验证整包预缓存不会绕过流式落盘。普通 AnalyzeUrl 书源下载仍受 32 MiB
内存响应上限约束；大型文件应走 WebDAV/文件下载器。
上述测试不代表已执行 iPhone 真机登录、后台音频、签名安装或并发导入的进程内存实测。

## 重复导入、无正文响应和宿主转换补充（2026-10-28）

- Native 文件关联导入仍先独立解压，再按压缩包内容 SHA-256 与条目路径存到持久的
  `ImportedArchives` 目录，完成后清理该次临时目录。未变化的 ZIP 重复导入复用书籍地址；
  旧临时地址记录通过事务迁移，保留阅读进度、分组和阅读配置。`ArchiveImportStateTest`
  使用真实 Room/SQLite，检查章节 8、位置 260、分组、排序、阅读配置和自定义书名，
  同时检查清理缓存后持久文件仍存在，而不只断言书架数量。
- HEAD、1xx、204、205、304 先按无正文语义处理，保留响应头并返回空正文；
  不将表示资源大小的 Content-Length 当成实际接收长度，也不对空正文做 gzip 解码。
  有正文的响应继续执行声明长度预检和实际字节上限。MockEngine 用例覆盖 HEAD/304
  声明 40 MiB 并带 gzip 元数据的情形。依据 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.6)。
- Native JS 返回值与桥接参数转换，在整数收窄和宿主集合分配前检查长度；一次递归转换
  共用最多 100,000 个节点/元素、16 MiB 估算分配和 5 秒期限，逐项检查取消与执行期限。
  字符串在复制前检查 UTF-8 长度，TypedArray 在分配前检查实际缓冲区范围和剩余预算。
  深度限制继续保留；这些保守配额不是整个进程的精确内存测量。
  真实 QuickJS 用例覆盖十亿长度稀疏数组、重复嵌套数组/字符串/对象、超限 TypedArray、
  桥接参数及拒绝后正常执行；普通文本、中文和内嵌 NUL 保持可转换。

本地独立测试通过 81 条边界断言、35 条归档入口断言及 35 项 JUnit 测试。
新增 Room/QuickJS/HTTP 用例与全量 iOS 构建的结果须查看本提交 Actions；本地 Linux
独立测试不能代替 iOS 编译、模拟器执行或真机回归。

## 初始化、外部入口与供应链补充（2026-10-28）

- Native 共享 jsLib 在初始化和 LRU 交接异常时关闭新 scope，并移除失效缓存条目。
  真实 QuickJS 测试核对语法错误、脚本异常、取消、64 MiB 堆超限及 30 秒期限触发后，
  活跃 scope 数恢复到基线，覆盖 runtime 和中断回调 StableRef 的清理路径。
- 远程 ZIP 导入先验证完整归档路径，再转成单文件名；不同目录下同名条目用路径摘要区分，
  不放宽公共保存接口的单文件限制。RemoteZip 在图片、文本和异常分支均关闭。
- Android ReaderProvider 声明 signature 权限，同时在每个 CRUD 入口检查 Binder UID／签名，
  通过后才允许设置 `trustedLocalCall`。第三方普通签名 App 不再能直接读取或修改阅读数据。
  RSS 已迁移为统一 BookSource 模型，旧 `rssSource(s)/*` URI 明确报不支持，不能转入普通
  书源 CRUD；现有 RSS 使用统一的带类型书源接口。
- Web 管理页面不执行书源 jsLib（规则仍由后端脚本引擎处理）。章节 HTML 使用固定版本
  DOMPurify 和格式标签白名单；图片统一经过 image 入口，不能用任意同源 img URL 发起管理请求。
  `modules/web/tests/security.spec.ts` 在 Chromium 中挂载真实 Vue 组件，检查正常正文、图片、
  HTML 攻击和三种 jsLib 形式。上一版生产文件不能通过这四个用例；当前实现通过。
  打包的单文件 HTML 同步入库，CI 再次构建并比对，避免只修改源码而继续发布旧资源。
- 安全 CI 对所有 push/PR 运行，涵盖 Android、Web、vendored 引擎与构建配置的改动。
  QuickJS 同步校验在下载缺失、网络失败、文件缺失时直接失败，完成全部文件比较后才输出结论；
  工作流 ref 经环境变量传入并验证完整 SHA，不能通过字符串插入 shell。
- Cronet 固定 Google 官方 Maven 当前 AAR 的四种 ABI SHA-256，在下载完成、缓存安装和
  `System.load` 前校验。未知版本/ABI、旧的不可信缓存和篡改库全部拒绝；升级 Cronet 版本时
  必须同步核实并更新摘要。HEAD 响应和 RemoteZip 也增加了明确关闭。
- 默认 GitHub 更新仓库改为 `last2win/legado`。

本地 Android 入口命令为 `bash scripts/security-tests/run-android-ingress.sh`，使用与 `run.sh`
相同的编译器环境；可设置 `CRONET_TEST_AAR` 指向官方 143.7445.0 AAR，验证四种 ABI 正常与
篡改文件。该测试直接编译生产 ReaderProvider 和摘要验证器，但 Binder、Android 框架、数据库
路由使用测试替身；不能据此声称已在 Android 设备验证跨应用权限。CI 另编译真实 Android App。
`python3 scripts/security-tests/test_quickjs_upstream.py` 验证下载失败与比较完整性。
Web 回归：在 `modules/web` 执行 `pnpm install --frozen-lockfile`、`pnpm exec playwright install chromium`
和 `pnpm exec playwright test`（Node 22）。iOS 回归与完整 App 构建仍以当前提交 Actions 为准。

### 弹窗、升级前改名及远程 ZIP 恢复回归

- 确认框和输入框只把静态结构写入 HTML，所有调用者提供的文字均通过文本／属性 API 写入。
  Chromium 用真实 BookInfo、BookChapter 和 Router 覆盖“自动保存失败 → 临时阅读 → 退出确认”，
  并覆盖标题、按钮、占位符和输入错误文字。新增三个用例在修复前均失败。
- Native 旧归档迁移不再依赖可编辑的书名和作者。只在应用旧 ArchiveTemp 目录中，按原条目
  路径摘要和有总量限制的流式内容摘要确认唯一旧记录，保留旧书名、作者、进度、分组和设置。
  多条旧记录匹配时直接拒绝迁移；未经确认的同名记录也不会被 REPLACE 覆盖。
  若旧缓存文件已被删除，无法从旧版未保存的指纹中恢复可靠身份：保留旧记录，不猜测合并。
- 远程 ZIP 在书籍变量中持久化原条目路径，安全保存名仍是单文件名。重新下载先精确选择条目，
  再恢复文件，不重新导入默认状态的 Book，也不把 WebDAV origin 改成本地 origin。
  没有新元数据的旧记录可通过同一安全命名表恢复；缺失或歧义会失败，不用 contains 模糊匹配。
- `LegacyArchiveIdentityTest` 在 JVM 上验证真实文件指纹、歧义、路径变化和删除后的 ZIP 恢复；
  `ArchiveImportStateTest` 在 iOS 上使用真实 ZIP、生产导入代码和真实 Room/SQLite，覆盖升级前
  同时改书名／作者、重复导入、歧义保留，以及同名条目导入 → 删除本地文件 → 重新下载两次。
  远程恢复测试仅替换下载传输和测试目录，实际 ZIP 解析和数据库状态均执行生产实现。
