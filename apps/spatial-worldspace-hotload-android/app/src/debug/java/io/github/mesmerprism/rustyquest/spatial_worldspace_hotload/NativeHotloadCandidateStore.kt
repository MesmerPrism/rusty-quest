package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID

/** Debug app-UID inbox. Bytes are copied once; no caller paths or deferred re-read. */
internal class NativeHotloadCandidateStore(private val context:Context) {
    private val session=UUID.randomUUID().toString()
    private var appBuild:String?=null
    private var candidate:JSONObject?=null
    private var lastRejection=""
    private val digestPattern=Regex("[a-f0-9]{64}")
    private val fields=setOf("schema","slot","codeSha256","codeBytes","abiSha256","appBuild","session",
        "producerGeneration","expectedGeneration","expectedActiveDigest","requestId","entry","target")
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun checkValue(ok:Boolean,reason:String){if(!ok)throw IllegalArgumentException(reason)}
    private fun manifest(bytes:ByteArray):JSONObject {
        val result=JSONObject();val seen=mutableSetOf<String>()
        JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
            reader.isLenient=false;reader.beginObject()
            while(reader.hasNext()){
                val key=reader.nextName();checkValue(key in fields&&seen.add(key),"manifest_duplicate_or_unknown_field")
                if(key=="codeBytes"){
                    checkValue(reader.peek()==JsonToken.NUMBER,"manifest_code_size_type")
                    val number=reader.nextString();checkValue(Regex("[1-9][0-9]{0,4}").matches(number),"manifest_code_size")
                    result.put(key,number.toInt())
                }else{checkValue(reader.peek()==JsonToken.STRING,"manifest_string_type");result.put(key,reader.nextString())}
            }
            reader.endObject();checkValue(reader.peek()==JsonToken.END_DOCUMENT&&seen==fields,"manifest_fields")
        }
        return result
    }
    private fun bounded(file:File,limit:Int):ByteArray {
        // Reject every inbox path component that could redirect the fixed app-owned slot.
        val root=context.filesDir.canonicalFile
        var cursor=file
        while(cursor!=root){
            val stat=Os.lstat(cursor.path)
            checkValue(!OsConstants.S_ISLNK(stat.st_mode),"inbox_symlink")
            checkValue(cursor.canonicalPath.startsWith(root.path+File.separator),"inbox_escape")
            cursor=cursor.parentFile?:throw IllegalArgumentException("inbox_escape")
        }
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        // Android FileInputStream(FileDescriptor) borrows the descriptor.
        try {
            return FileInputStream(fd).use { input ->
                val stat=Os.fstat(input.fd)
                checkValue(OsConstants.S_ISREG(stat.st_mode)&&stat.st_nlink==1L&&stat.st_size in 1..limit.toLong(),"inbox_size_or_type")
                val bytes=ByteArray(stat.st_size.toInt());var offset=0
                while(offset<bytes.size){val n=input.read(bytes,offset,bytes.size-offset);checkValue(n>0,"inbox_short_read");offset+=n}
                checkValue(input.read()==-1,"inbox_grew")
                bytes
            }
        } finally {
            Os.close(fd)
        }
    }
    private fun prepareBuild():String {
        appBuild?.let{return it}
        val apk=File(context.applicationInfo.sourceDir)
        checkValue(apk.isFile&&apk.length() in 1..(256L*1024*1024),"apk_size")
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        apk.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true){val n=input.read(buffer);if(n<0)break;count+=n;checkValue(count<=256L*1024*1024,"apk_size");digest.update(buffer,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}.also{appBuild=it}
    }
    fun status():String=JSONObject().put("phase",if(appBuild==null)"context_unprepared" else "context_prepared")
        .put("appBuild",appBuild?:JSONObject.NULL).put("session",session)
        .put("candidate",candidate?:JSONObject.NULL).put("lastRejection",lastRejection).toString()
    fun candidateId():String=candidate?.optString("codeSha256")?:""
    fun apply(method:String,value:String?,handle:Long,live:JSONObject,requestId:Long):String {
        var dispatched=false
        try {
            if(method=="compute-info")return JSONObject(live.toString()).put("schema","rusty.quest.hotload.external_context.v1")
                .put("appBuild",prepareBuild()).put("session",session).put("nextRequestId",requestId.toString()).toString()
            checkValue(appBuild!=null,"context_unprepared")
            checkValue(requestId>0,"request_id_exhausted")
            val producer=live.getString("producerGeneration").toLong()
            val generation=live.getString("expectedGeneration").toLong()
            val active=live.getString("expectedActiveDigest")
            if(method=="compute-stage"){
                val inbox=File(context.filesDir.canonicalFile,"native-hotload/inbox")
                val manifestBytes=bounded(File(inbox,"candidate.json"),8192)
                val code=bounded(File(inbox,"kernel.spv"),65536)
                val manifest=manifest(manifestBytes)
                checkValue(manifest.keys().asSequence().toSet()==fields,"manifest_fields")
                checkValue(manifest.getString("schema")=="rusty.quest.hotload.external_candidate.v1"&&
                    manifest.getString("slot")=="public-diagnostic-v1"&&manifest.getString("entry")=="main"&&
                    manifest.getString("target")=="vulkan1.1","manifest_contract")
                for(key in listOf("codeSha256","abiSha256","appBuild","expectedActiveDigest"))
                    checkValue(manifest.get(key) is String&&digestPattern.matches(manifest.getString(key)),"manifest_digest")
                for(key in listOf("producerGeneration","expectedGeneration","requestId"))
                    checkValue(manifest.get(key) is String&&Regex("[1-9][0-9]{0,18}").matches(manifest.getString(key))&&
                        manifest.getString(key).toLongOrNull()!=null,"manifest_decimal")
                checkValue(manifest.get("codeBytes") is Number&&manifest.get("codeBytes").toString()==code.size.toString(),"code_size")
                checkValue(code.size%4==0&&sha(code)==manifest.getString("codeSha256"),"code_digest")
                checkValue(manifest.getString("appBuild")==appBuild&&manifest.getString("session")==session,"stale_app_context")
                for(key in listOf("producerGeneration","expectedGeneration","expectedActiveDigest"))
                    checkValue(manifest.getString(key)==live.getString(key),"stale_producer_context")
                checkValue(manifest.getString("requestId")==requestId.toString(),"stale_request_id")
                dispatched=true
                val result=GpuWorkloadProducer.nativeHotloadStage(handle,code,manifest.getString("codeSha256"),
                    manifest.getString("abiSha256"),producer,generation,active,requestId)
                if(JSONObject(result).optString("phase")=="staged")candidate=JSONObject(manifest.toString())
                return result
            }
            checkValue(method in setOf("compute-activate","compute-discard","compute-rollback"),"unknown_action")
            if(method!="compute-rollback"){
                checkValue(value!=null&&digestPattern.matches(value)&&value==candidateId(),"candidate_id")
                val saved=requireNotNull(candidate)
                checkValue(saved.getString("appBuild")==appBuild&&saved.getString("session")==session,"stale_app_context")
                for(key in listOf("producerGeneration","expectedGeneration","expectedActiveDigest"))
                    checkValue(saved.getString(key)==live.getString(key),"stale_producer_context")
            }
            dispatched=true
            val result=GpuWorkloadProducer.nativeHotloadExternal(handle,method.removePrefix("compute-"),value?:"",
                producer,generation,active,requestId)
            if(method=="compute-discard"&&JSONObject(result).optString("phase")=="discarded")candidate=null
            return result
        }catch(e:Exception){
            lastRejection=if(e is IllegalArgumentException)e.message?:"invalid_candidate" else "candidate_io_or_native_result"
            return JSONObject().put("phase",if(dispatched)"dispatch_result_unknown" else "rejected")
                .put("reason",lastRejection).put("requestId",requestId.toString()).put("automaticRetry",false).toString()
        }
    }
}
