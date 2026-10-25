package io.legado.app.api
class ReturnData {
 var data: Any? = null
 var isSuccess = true
 @JvmField var errorMsg = ""
 fun setErrorMsg(message: String) { errorMsg = message; isSuccess = false }
 fun toJsonString() = "{}"
}
