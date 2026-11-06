package android.os

/** Test-only thread selector; production code still calls the Android Looper API. */
class Looper private constructor() {
    companion object {
        private val main=Looper()
        private val worker=Looper()
        var onMain=true
        @JvmStatic fun myLooper():Looper=if(onMain)main else worker
        @JvmStatic fun getMainLooper():Looper=main
    }
}
