package org.json

/** Minimal test-only status container, deliberately not an Android JSON implementation. */
class JSONObject {
    private val values=linkedMapOf<String,Any?>()
    fun put(key:String,value:Any?):JSONObject{values[key]=value;return this}
    fun get(key:String):Any?=values[key]
    fun has(key:String)=values.containsKey(key)
    fun getJSONObject(key:String)=values[key] as JSONObject
    fun getString(key:String)=values[key] as String
    fun getLong(key:String)=(values[key] as Number).toLong()
    fun getBoolean(key:String)=values[key] as Boolean
    override fun toString()=values.toString()
}
class JSONArray(values:Collection<Any?> = emptyList()) {
    private val items=values.toMutableList()
    fun put(value:Any?):JSONArray{items.add(value);return this}
    override fun toString()=items.toString()
}
