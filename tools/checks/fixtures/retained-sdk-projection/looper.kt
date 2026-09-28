package android.os
object Looper {private val main=Any();private val worker=Any();private val local=ThreadLocal<Boolean>();fun setMain(v:Boolean){local.set(v)};fun myLooper()=if(local.get()==true)main else worker;fun getMainLooper()=main}
