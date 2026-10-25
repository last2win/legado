# 安全修复与验证记录

基线：`d31c0a270e7c8610e9679dbbda5a6bee06927952`。本次修改针对提供的静态审查截图，未发布安装包、未运行 GitHub 工作流。

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
scripts/security-tests/run.sh

# 按脚本头部说明提供 nanohttpd、okio、coroutines、serialization 的 jar。
export SECURITY_TEST_DEPS=/path/to/security-test-dependencies
scripts/security-tests/run-transport.sh
```

## 尚未完成的验证

本机为 Linux，没有 Xcode/iOS SDK。已添加 `IosCookieStoreTest` 和 `NativeJsLifetimeTest`，但**尚未在模拟器执行**。应在 macOS 上按项目既有流程准备 QuickJS/mbedtls 原生库后执行：

```sh
./gradlew :data:iosSimulatorArm64Test -PenableIosTarget=true
```

完整 JVM Gradle 验证尝试了 `:data:jvmTest :core:jvmTest`。项目固定的 Gradle 9.6.0 分发包在 wrapper 重定向及官方直接地址下载时均超时；未更换项目版本或声称完整构建通过。Android、iOS、鸿蒙端的编译与设备回归仍需在对应工具链完成，重点包括 Cookie 登录/注销/重定向、双书源隔离、异常导入及并发运行时关闭。


参考规范：[Apple NSHTTPCookie domain](https://developer.apple.com/documentation/foundation/httpcookie/domain)、[RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html)、[Ktor Darwin 配置](https://api.ktor.io/3.5.x/ktor-client-darwin/io.ktor.client.engine.darwin/-darwin-client-engine-config/index.html)。
