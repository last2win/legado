package android.os
object Binder { var caller = 100; fun getCallingUid() = caller }
object Process { fun myUid() = 100 }
