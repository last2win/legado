package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.App
import io.legado.app.BuildConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.fileBook.RemoteZipWrapper
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.LogUtils
import io.legado.app.utils.atomicWriteTempPath
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("ConstPropertyName")
@Keep
object CronetLoader : CronetEngine.Builder.LibraryLoader(), Cronet.LoaderInterface {

    private const val soVersion = BuildConfig.Cronet_Version
    private const val aarUrl =
        "https://dl.google.com/dl/android/maven2/org/chromium/net/cronet-embedded/$soVersion/cronet-embedded-$soVersion.aar"
    private const val soName = "libcronet.$soVersion.so"

    /** 中间件名每次唯一: 落于目标同目录 (见 [io.legado.app.utils.atomicWriteTempPath])。 */
    private val soFile: File
    private var cpuAbi: String? = null

    /** "下载中"闸门: 两次并发触发只放一份进来, 判定必须是原子的读改写。 */
    private val download = AtomicBoolean(false)

    @Volatile
    private var providerInstalled = false
    private val installedLibrary = VerifiedLibraryCache()

    init {
        val cpuAbi = getCpuAbi(App.instance)
        val dir = App.instance.getDir("cronet", Context.MODE_PRIVATE)
        soFile = File(dir, cpuAbi).apply { mkdirs() }.resolve(soName)
    }

    /**
     * 判断系统是否支持 HttpEngine (Android 14+ / API 34+ / Backported API 30+)
     */
    fun isHttpEngineAvailable(): Boolean {
        return try {
            Class.forName("android.net.http.HttpEngine")
            true
        } catch (_: Throwable) {
            hasExternalProvider()
        }
    }

    /**
     * 判断Cronet是否安装完成
     */
    override fun install(): Boolean {
        if (providerInstalled || installedLibrary.isCached(soFile, soVersion, getCpuAbi(App.instance))) return true
        synchronized(this) {
            if (providerInstalled) return true
            providerInstalled = isHttpEngineAvailable() || hasExternalProvider()
            return providerInstalled || isSoDownloaded()
        }
    }

    fun isSoDownloaded(): Boolean {
        return installedLibrary.available(soFile, soVersion, getCpuAbi(App.instance))
    }

    fun hasExternalProvider(): Boolean {
        return try {
            CronetProvider.getAllProviders(App.instance).any {
                it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK && it.name != CronetProvider.PROVIDER_NAME_APP_PACKAGED
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun preDownload(onComplete: ((Boolean) -> Unit)?) {
        // 优化逻辑：如果系统级 Provider 可用，则跳过下载
        if (isHttpEngineAvailable() || hasExternalProvider()) {
            LogUtils.d(javaClass.simpleName, "系统支持 Cronet，跳过下载 SO")
            onComplete?.invoke(true)
            return
        }
        Coroutine.async {
            if (isSoDownloaded()) {
                onComplete?.invoke(true)
                return@async
            }
            // 闸门原子地读改写: 两次并发触发都进来时, 两次落盘会共写同一份中间件
            if (!download.compareAndSet(false, true)) return@async
            installedLibrary.invalidate()
            Coroutine.async {
                val downloadDir = File(App.instance.cacheDir, "so_download")
                val downloadFile = File(downloadDir, soName)
                // 中间件名每次唯一: 两次落盘绝不共用同一份, 谁也读不到对方写了一半的那份
                val downloadFileTmp = File(atomicWriteTempPath(downloadFile.path))
                try {
                    val result = if (verified(downloadFile)) true
                    else try {
                        val webDav = WebDav(aarUrl, Authorization("", ""))
                        val fileSize = webDav.webDavClient.newCall(Request.Builder().url(aarUrl).head().build())
                            .execute().use { response ->
                                if (!response.isSuccessful) throw IOException("Failed to inspect Cronet artifact")
                                response.header("Content-Length")?.toLongOrNull() ?: -1L
                            }
                        if (fileSize <= 0) throw IOException("Failed to get file size")
                        val remoteZip = RemoteZipWrapper(
                            { offset, length, fs -> webDav.readRange(offset, length, fs) },
                            "cronet.aar", fileSize
                        )
                        try {
                            val entry = remoteZip.entries().asSequence().find {
                                it.name.startsWith("jni/${getCpuAbi(App.instance)}/libcronet") && it.name.endsWith(
                                    ".so"
                                )
                            } ?: throw IOException("SO entry not found for ${getCpuAbi(App.instance)}")

                            downloadFileTmp.parentFile?.mkdirs()
                            remoteZip.getInputStream(entry).use { input ->
                                downloadFileTmp.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!verified(downloadFileTmp)) throw IOException("Cronet SHA-256 mismatch or unpinned version/ABI")
                            FileUtilsBase.replaceFileAtomically(downloadFileTmp, downloadFile)
                            true
                        } finally { remoteZip.close() }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        false
                    }

                    if (result && verified(downloadFile)) {
                        // 装载只看目标文件在不在: 半截 so 会被长期当成可用, 必须整份落到同目录临时文件再替换
                        soFile.parentFile?.mkdirs()
                        val staged = File(atomicWriteTempPath(soFile.path))
                        try {
                            downloadFile.inputStream().use { input ->
                                staged.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (!verified(staged)) throw IOException("Cronet staged library SHA-256 mismatch")
                            synchronized(this@CronetLoader) {
                                installedLibrary.invalidate()
                                FileUtilsBase.replaceFileAtomically(staged, soFile)
                                soFile.setReadable(true)
                            }
                        } finally {
                            staged.delete()
                        }
                        onComplete?.invoke(true)
                    } else {
                        downloadFile.delete()
                        onComplete?.invoke(false)
                    }
                } finally {
                    // 中间件名随机唯一: 中途失败残留的那份只能由本次清; 闸门在失败与抛出路径上同样放行
                    downloadFileTmp.delete()
                    download.set(false)
                }
            }
        }
    }


    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun loadLibrary(libName: String) {
        if (!libName.contains("cronet")) {
            System.loadLibrary(libName)
            return
        }
        try {
            System.loadLibrary(libName)
        } catch (_: Throwable) {
            synchronized(this) {
                // Never use the availability cache as a substitute for pre-load integrity checking.
                if (!verified(soFile)) {
                    installedLibrary.invalidate()
                    soFile.delete()
                    throw UnsatisfiedLinkError("Cronet library is missing or failed SHA-256 verification")
                }
                try { System.load(soFile.absolutePath) }
                catch (failure: Throwable) {
                    installedLibrary.invalidate()
                    throw failure
                }
            }
        }
    }

    private fun verified(file: File): Boolean =
        CronetLibraryIntegrity.verify(file, soVersion, getCpuAbi(App.instance))

    @SuppressLint("DiscouragedPrivateApi")
    private fun getCpuAbi(context: Context): String {
        cpuAbi?.let { return it }
        return try {
            val appInfo = context.applicationInfo
            val abiField = ApplicationInfo::class.java.getDeclaredField("primaryCpuAbi")
            abiField.isAccessible = true
            (abiField.get(appInfo) as? String) ?: Build.SUPPORTED_ABIS[0]
        } catch (_: Exception) {
            Build.SUPPORTED_ABIS[0]
        }.also { cpuAbi = it }
    }

}
