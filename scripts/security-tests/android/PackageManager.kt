package android.content.pm
class PackageManager {
    fun checkSignatures(a: Int, b: Int) = if (sameSignature) SIGNATURE_MATCH else -1
    companion object { const val SIGNATURE_MATCH = 0; var sameSignature = false }
}
class ApplicationInfo { val packageName = "io.legado.test"; @JvmField val primaryCpuAbi = "arm64-v8a" }
