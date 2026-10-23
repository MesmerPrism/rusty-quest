package org.json
/** Compile-only status collaborators, not a JSON parser under test. */
class JSONObject(value:String?=null) {
    companion object { val NULL=Any() }
    fun put(key:String,value:Any?):JSONObject=this
    override fun toString()="{}"
}
class JSONArray { fun put(value:Any?):JSONArray=this }
