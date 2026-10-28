package io.legado.app.help.http
object Cronet {
    interface LoaderInterface {
        fun install(): Boolean
        fun preDownload(onComplete: ((Boolean) -> Unit)?)
    }
}
