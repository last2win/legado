package android.os
object Binder { var caller = 100; fun getCallingUid() = caller }
object Process { fun myUid() = 100 }
object Build { val SUPPORTED_ABIS = arrayOf("arm64-v8a") }
