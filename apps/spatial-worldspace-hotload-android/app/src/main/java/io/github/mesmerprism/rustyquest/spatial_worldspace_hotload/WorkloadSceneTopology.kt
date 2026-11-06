package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Transform
import org.json.JSONArray
import org.json.JSONObject

/** Main/scene-owner calls only. SDK resources stay strongly held through normal scene teardown. */
@OptIn(SpatialSDKExperimentalAPI::class)
class WorkloadSceneTopology(
    private val scene:Scene,
    private val descriptor:WorkloadDescriptor,
    private val materials:List<SceneMaterial>,
    private val register:(Entity,SceneObject)->Unit,
) {
    private data class Prepared(val plan:TopologyPlan,val meshes:List<SceneMesh>)
    private data class Slot(val entity:Entity,val obj:SceneObject)
    private val cache=linkedMapOf<String,Prepared>()
    private val retainedMeshes=mutableListOf<SceneMesh>()
    private val slots=Array(3){mutableListOf<Slot>()}
    private var current:Prepared?=null
    private var showHands=false
    private var showGrafts=false
    private var allocatedVertices=0
    private var allocatedIndices=0
    private var hostTranslation=Vector3(0f,0f,0f)
    private var cullingRequested=false
    private var bindingOriginal:Slot?=null
    private var bindingFreshEntity:Entity?=null
    private var bindingFresh:Slot?=null
    private var bindingMesh:SceneMesh?=null
    private var bindingAllocationAttempted=false
    private var bindingRoute="off"
    private var bindingRevision=0L
    val bindingComparisonActive:Boolean get()=bindingRoute!="off"
    var revision=0L;private set
    var uncertain=false;private set
    val active:TopologyPlan? get()=current?.plan

    fun apply(profile:String):TopologyPlan {
        check(!uncertain&&!bindingComparisonActive&&revision<Long.MAX_VALUE)
        val prepared=cache[profile]?:prepare(profile).also{cache[profile]=it}
        val previous=current
        try {
            bind(prepared);current=prepared;revision++
        }catch(e:Exception){
            if(uncertain)throw e
            try{if(previous!=null)bind(previous)else slots.forEach{group->group.forEach{it.obj.setIsVisible(false)}}}
            catch(_:Exception){uncertain=true;current=null;slots.forEach{group->group.forEach{runCatching{it.obj.setIsVisible(false)}}}}
            throw e
        }
        return prepared.plan
    }
    private fun prepare(profile:String):Prepared {
        val plan=WorkloadTopology.plan(profile,descriptor)
        require(allocatedVertices.toLong()+plan.vertices<=WorkloadTopology.MAX_CACHED_VERTICES)
        require(allocatedIndices.toLong()+plan.indices<=WorkloadTopology.MAX_CACHED_INDICES)
        // A failed partial preparation still consumes the bounded retention budget.
        val meshes=plan.chunks.map{chunk->
            val mesh=try{WorkloadGeometry.records(materials[chunk.group],chunk.records,chunk.billboards)}
                catch(e:Exception){quarantine();throw e} // Unknown partial native allocation must not be retried.
            retainedMeshes.add(mesh);allocatedVertices+=chunk.vertices;allocatedIndices+=chunk.indices;mesh
        }
        return Prepared(plan,meshes)
    }
    private fun bind(prepared:Prepared) {
        bindingFresh?.obj?.setIsVisible(false)
        for(group in 0..2){
            val selected=prepared.plan.chunks.indices.filter{prepared.plan.chunks[it].group==group}
            selected.forEachIndexed{slotIndex,chunkIndex->
                val mesh=prepared.meshes[chunkIndex]
                if(slotIndex==slots[group].size){
                    val entity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))))
                    val obj=SceneObject(scene,mesh,"workload-$group-$slotIndex",entity)
                    obj.setIsVisible(false);obj.shouldFrustumCull(false)
                    slots[group].add(Slot(entity,obj))
                    try{register(entity,obj)}catch(e:Exception){quarantine();throw e}
                }
                val obj=slots[group][slotIndex].obj
                obj.setSceneMesh(mesh,"workload-$group-$slotIndex")
                obj.shouldFrustumCull(false)
                obj.setIsVisible(group==0||if(group==1)showHands else showGrafts)
            }
            for(i in selected.size until slots[group].size)slots[group][i].obj.setIsVisible(false)
        }
        cullingRequested=false
    }
    fun visibility(hands:Boolean,grafts:Boolean) {
        check(!uncertain&&!bindingComparisonActive)
        showHands=hands;showGrafts=grafts
        try{current?.let{bind(it)}}catch(e:Exception){quarantine();throw e}
    }
    /** Exact baseline setter intent, including inactive cached slots; no SDK visibility readback. */
    fun diagnosticVisibilityTargets():List<Pair<SceneObject,Boolean>> {
        check(!uncertain&&current!=null)
        val plan=requireNotNull(current).plan
        return slots.flatMapIndexed{group,items->
            val activeCount=plan.chunks.count{it.group==group}
            items.mapIndexed{index,slot->slot.obj to (index<activeCount&&(group==0||if(group==1)showHands else showGrafts))}
        }
    }
    /** The original p512 setter branch must already exist; this never rebinds the fresh object. */
    fun compareBinding(route:String):String {
        if(!BuildConfig.DEBUG)return "blocked:debug_only"
        require(route in listOf("retained","fresh","off"))
        check(!uncertain&&bindingRevision<Long.MAX_VALUE)
        if(route=="off"&&!bindingComparisonActive)return "unchanged:binding_off"
        val prepared=requireNotNull(current)
        check(prepared.plan.profile=="p512"&&prepared.plan.chunks.size==1&&prepared.plan.chunks.single().group==0)
        // Previous larger profiles may retain extra hidden slots; p512 selects only slot zero.
        val original=slots[0].first()
        val mesh=prepared.meshes.single()
        check(bindingOriginal==null||bindingOriginal===original)
        check(bindingMesh==null||bindingMesh===mesh)
        bindingOriginal=original;bindingMesh=mesh
        try {
            if(route=="fresh"&&bindingFresh==null){
                // Reserve the one-object budget before any native allocation. Unknown failure cannot retry.
                check(!bindingAllocationAttempted);bindingAllocationAttempted=true
                val entity=Entity.create(listOf(Transform(Pose(hostTranslation))))
                bindingFreshEntity=entity
                val obj=SceneObject(scene,mesh,"workload-binding-fresh",entity)
                bindingFresh=Slot(entity,obj) // Retain before any further SDK operation can fail.
                obj.setIsVisible(false)
                register(entity,obj)
                // Deliberately no setSceneMesh here or on any later fresh-object selection.
            }
            // Hide both before selecting one. Failure quarantines both rather than guessing a rollback.
            original.obj.setIsVisible(false);bindingFresh?.obj?.setIsVisible(false)
            if(route=="fresh")requireNotNull(bindingFresh).let{slot->
                slot.obj.shouldFrustumCull(cullingRequested)
                slot.entity.setComponent(Transform(Pose(hostTranslation)))
                slot.obj.setPosition(hostTranslation.x,hostTranslation.y,hostTranslation.z)
                slot.obj.setIsVisible(true)
            }
            else original.obj.setIsVisible(true)
            bindingRoute=route;bindingRevision++
            return "binding_visibility_setters_invoked:$route:no_pixel_proof"
        }catch(error:Exception){bindingRoute="uncertain";quarantine();throw error}
    }
    fun culling(value:Boolean){
        slots.forEach{group->group.forEach{it.obj.shouldFrustumCull(value)}}
        bindingFresh?.obj?.shouldFrustumCull(value);cullingRequested=value
    }
    fun quarantine(){uncertain=true;current=null;slots.forEach{group->group.forEach{runCatching{it.obj.setIsVisible(false)}}};bindingFresh?.let{runCatching{it.obj.setIsVisible(false)}}}
    fun translateHost(x:Float,y:Float,z:Float){
        require(x.isFinite()&&y.isFinite()&&z.isFinite())
        slots.forEach{group->group.forEach{slot->
            slot.entity.setComponent(Transform(Pose(Vector3(x,y,z))))
            slot.obj.setPosition(x,y,z)
        }}
        bindingFresh?.let{slot->slot.entity.setComponent(Transform(Pose(Vector3(x,y,z))));slot.obj.setPosition(x,y,z)}
        hostTranslation=Vector3(x,y,z)
    }
    fun status():JSONObject {
        val plan=active
        val groups=JSONArray()
        for(g in 0..2)groups.put(JSONObject().put("group",g).put("vertices",plan?.vertices(g)?:0)
            .put("indices",plan?.indices(g)?:0).put("visibleRequested",g==0||if(g==1)showHands else showGrafts))
        val bounds=JSONArray()
        current?.meshes?.forEach{mesh->bounds.put(runCatching{mesh.computeCombinedBounds().toString()}.getOrDefault("unavailable"))}
        val activeMeshes=JSONArray()
        current?.let{prepared->prepared.meshes.forEachIndexed{i,mesh->activeMeshes.put(JSONObject()
            .put("handle",mesh.handle.toString()).put("group",prepared.plan.chunks[i].group)
            .put("vertices",prepared.plan.chunks[i].vertices).put("indices",prepared.plan.chunks[i].indices))}}
        val objectSlots=JSONArray()
        slots.forEachIndexed{group,items->items.forEach{slot->objectSlots.put(JSONObject()
            .put("group",group).put("entityId",slot.entity.id.toString()).put("objectHandle",slot.obj.handle.toString()))}}
        return JSONObject().put("profile",plan?.profile?:"none").put("appliedRevision",revision)
            .put("activeVertices",plan?.vertices?:0).put("activeIndices",plan?.indices?:0)
            .put("activeChunks",plan?.chunks?.size?:0).put("groups",groups)
            .put("allocatedVertices",allocatedVertices).put("allocatedIndices",allocatedIndices)
            .put("cachedProfiles",JSONArray(cache.keys.toList())).put("retainedMeshCount",retainedMeshes.size)
            .put("createdEntitySlots",slots.sumOf{it.size}).put("meshReplacementRevision",revision)
            .put("activeMeshHandles",activeMeshes).put("objectSlots",objectSlots)
            .put("hostBounds",bounds).put("hostBoundsAreDisplacedBounds",false)
            .put("uncertain",uncertain).put("gpuDrawCountObserved",false)
            .also{result->if(BuildConfig.DEBUG)result.put("bindingComparison",JSONObject()
                .put("routeRequested",bindingRoute).put("active",bindingComparisonActive).put("revision",bindingRevision)
                .put("freshAllocationAttempted",bindingAllocationAttempted).put("freshObjectLimit",1)
                .put("retainedEntityId",bindingOriginal?.entity?.id?.toString()?:"unavailable")
                .put("retainedObjectHandle",bindingOriginal?.obj?.handle?.toString()?:"unavailable")
                .put("freshEntityId",bindingFreshEntity?.id?.toString()?:"unavailable")
                .put("freshObjectHandle",bindingFresh?.obj?.handle?.toString()?:"unavailable")
                .put("preparedMeshHandle",bindingMesh?.handle?.toString()?:"unavailable")
                .put("freshConstructorMeshHandle",if(bindingFresh==null)"unavailable" else bindingMesh?.handle?.toString())
                .put("freshMeshSetterInvocations",0).put("visibilityReadbackAvailable",false)
                .put("hostTranslationRequested",JSONArray(listOf(hostTranslation.x,hostTranslation.y,hostTranslation.z)))
                .put("cullingRequested",cullingRequested)
                .put("gpuDrawCountObserved",false).put("pixelProof",false))}
    }
}
