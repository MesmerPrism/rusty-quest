package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Transform

/** Executes the actual scene owner against SDK call recorders, never a second binding implementation. */
object WorkloadSceneTopologyHostTest {
    private fun rejects(block:()->Unit){check(runCatching(block).isFailure)}
    private fun newOwner(register:(Entity,SceneObject)->Unit={_,_->}):WorkloadSceneTopology {
        val particles=2562+17934+2;val corners=(300+401)*3
        val layout=WorkloadLayout(2562,17934,0,particles,particles,particles*2,corners,particles*2+corners,corners*5,2)
        val descriptor=WorkloadDescriptor(layout,HandTopology(500,300,610,401),"synthetic")
        return WorkloadSceneTopology(Scene(),descriptor,List(3){SceneMaterial()},register)
    }
    private fun comparison(owner:WorkloadSceneTopology)=owner.status().getJSONObject("bindingComparison")
    @JvmStatic fun main(args:Array<String>){
        val registered=mutableListOf<Pair<Entity,SceneObject>>()
        val owner=newOwner{entity,obj->registered.add(entity to obj)}
        owner.apply("p64");val retained=registered.single().second
        check(retained.initialMesh.records.size==64&&retained.meshSetters==1)
        owner.apply("p512");val selected=retained.mesh;val setters=retained.meshSetters
        check(selected.records.size==512&&selected.records.toList()==(0 until 512).toList())
        val meshAllocations=WorkloadGeometry.allocations.size
        owner.compareBinding("retained")
        check(owner.bindingComparisonActive&&registered.size==1&&retained.visible&&retained.meshSetters==setters)
        owner.culling(true);owner.translateHost(1f,2f,3f)
        owner.compareBinding("fresh");val fresh=registered[1].second
        check(fresh!==retained&&fresh.entity.id!=retained.entity.id)
        check(fresh.initialMesh===selected&&fresh.mesh===selected&&fresh.initialMesh.material===selected.material)
        check(fresh.meshSetters==0&&retained.meshSetters==setters&&WorkloadGeometry.allocations.size==meshAllocations)
        check(!retained.visible&&fresh.visible&&fresh.cull==retained.cull&&fresh.position==retained.position)
        check((fresh.entity.components.single() as Transform).pose.t.x==1f)
        rejects{owner.apply("p64")};rejects{owner.visibility(true,false)}
        repeat(10){
            owner.compareBinding("retained");check(retained.visible&&!fresh.visible)
            owner.compareBinding("fresh");check(!retained.visible&&fresh.visible)
        }
        check(registered.size==2&&fresh.meshSetters==0&&retained.meshSetters==setters)
        owner.culling(false);owner.translateHost(4f,5f,6f)
        check(!fresh.cull&&!retained.cull&&fresh.position==retained.position)
        owner.compareBinding("off");check(!owner.bindingComparisonActive&&retained.visible&&!fresh.visible)
        owner.apply("p64");check(!fresh.visible)
        owner.apply("p512");owner.compareBinding("fresh")
        check(registered.size==2&&fresh.initialMesh===selected&&fresh.meshSetters==0&&!fresh.cull)
        owner.compareBinding("off")
        check(comparison(owner).getLong("freshObjectLimit")==1L)
        check(comparison(owner).getLong("freshMeshSetterInvocations")==0L)

        val largerSlots=mutableListOf<SceneObject>()
        val largerHistory=newOwner{_,obj->largerSlots.add(obj)}
        largerHistory.apply("retained");check(largerSlots.size==2)
        largerHistory.apply("p512");check(!largerSlots[1].visible)
        largerHistory.compareBinding("fresh");check(largerSlots.size==3&&!largerSlots[1].visible)
        largerHistory.compareBinding("retained");check(largerSlots[0].visible&&!largerSlots[1].visible&&!largerSlots[2].visible)
        largerHistory.compareBinding("off");check(!largerSlots[1].visible&&!largerSlots[2].visible)

        val wrong=newOwner();wrong.apply("p64");val objects=SceneObject.created.size
        rejects{wrong.compareBinding("fresh")};rejects{wrong.compareBinding("unknown")}
        check(SceneObject.created.size==objects&&!comparison(wrong).getBoolean("freshAllocationAttempted"))
        BuildConfig.DEBUG=false
        check(wrong.compareBinding("fresh")=="blocked:debug_only"&&!wrong.status().has("bindingComparison"))
        check(SceneObject.created.size==objects);BuildConfig.DEBUG=true

        var failRegistration=false
        val partial=newOwner{_,_->check(!failRegistration)};partial.apply("p512")
        failRegistration=true;val beforeFailure=SceneObject.created.size
        rejects{partial.compareBinding("fresh")}
        check(partial.uncertain&&partial.bindingComparisonActive)
        check(comparison(partial).getBoolean("freshAllocationAttempted"))
        val failedFresh=SceneObject.created.last();check(!failedFresh.visible&&failedFresh.meshSetters==0)
        rejects{partial.compareBinding("fresh")};rejects{partial.compareBinding("off")}
        check(SceneObject.created.size==beforeFailure+1&&!failedFresh.visible)

        val constructorFailure=newOwner();constructorFailure.apply("p512")
        SceneObject.failConstruction=true
        rejects{constructorFailure.compareBinding("fresh")};SceneObject.failConstruction=false
        check(constructorFailure.uncertain&&comparison(constructorFailure).getBoolean("freshAllocationAttempted"))
        check(comparison(constructorFailure).getString("freshEntityId")!="unavailable")
        rejects{constructorFailure.compareBinding("fresh")}

        val visibilityFailure=newOwner();visibilityFailure.apply("p512");SceneObject.failShow=true
        rejects{visibilityFailure.compareBinding("fresh")};SceneObject.failShow=false
        check(visibilityFailure.uncertain&&!SceneObject.created.last().visible)
        println("Actual WorkloadSceneTopology: retained/fresh identity, zero fresh rebinds, bounded reuse, transforms/culling, release refusal and partial-failure quarantine passed")
    }
}
