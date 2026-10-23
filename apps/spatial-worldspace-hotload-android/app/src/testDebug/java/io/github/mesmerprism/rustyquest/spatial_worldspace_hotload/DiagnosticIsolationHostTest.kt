package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Exercises production callbacks/state; not an SDK renderer or pixel test. */
object DiagnosticIsolationHostTest {
    @JvmStatic fun main(args:Array<String>){
        val calls=mutableListOf<Pair<String,Boolean>>()
        var fail:String?=null
        fun target(name:String,baseline:Boolean)=DiagnosticIsolationController.Target(name,name,baseline){visible->
            calls.add(name to visible)
            if(name==fail)throw IllegalStateException("setter may have changed native state")
        }
        var legacyFlag=1f // Restore exact original host-geometry flag, rather than assuming0.
        val legacy=DiagnosticIsolationController.Target("legacy-draw","material1",true,"legacy_material_draw_flag"){
            enabled->legacyFlag=if(enabled)1f else 2f;calls.add("legacy-draw" to enabled)
        }
        val targets=listOf(legacy,target("active",true),target("inactive-cached",false))
        val controller=DiagnosticIsolationController()
        check(controller.off().startsWith("already_restored")&&calls.isEmpty())
        check(controller.on("owner1"){targets}.startsWith("presentation_setters_invoked"))
        check(calls==targets.map{it.name to false}&&controller.blocksChanges&&controller.phase=="on")
        check(legacyFlag==2f&&controller.objects()[0].operation=="legacy_material_draw_flag")
        check(controller.objects().map{it.handle}==targets.map{it.handle})
        val count=calls.size
        check(controller.on("owner1"){error("idempotent on must not enumerate replacements")}.startsWith("already_isolated"))
        check(controller.on("owner2"){error("new owner cannot replace retained targets")}.startsWith("blocked"))
        check(calls.size==count)
        check(controller.off().startsWith("presentation_setters_invoked"))
        check(calls.takeLast(3)==listOf("legacy-draw" to true,"active" to true,"inactive-cached" to false))
        check(!controller.blocksChanges&&controller.phase=="off"&&controller.objects().all{it.setterReturned==true})
        check(legacyFlag==1f)
        // A partial hide stays locked and does not automatically restore/replay.
        fail="active";calls.clear()
        check(controller.on("owner1"){targets}=="uncertain:presentation_setter")
        check(calls.size==3&&controller.blocksChanges&&controller.phase=="uncertain")
        check(controller.objects()[1].setterReturned==null&&controller.objects()[0].setterReturned==true)
        check(controller.on("owner1"){targets}.startsWith("blocked")&&calls.size==3)
        check(controller.off()=="uncertain:presentation_setter"&&calls.size==6&&controller.blocksChanges)
        check(!controller.requestedIsolated&&controller.objects()[2].requestedEnabled==false)
        fail=null
        check(controller.off().startsWith("presentation_setters_invoked")&&!controller.blocksChanges)
        controller.on("owner1"){targets};val beforeInvalidation=calls.size
        controller.invalidate()
        check(controller.phase=="uncertain"&&controller.objects().all{it.setterReturned==null}&&calls.size==beforeInvalidation)
        controller.close()
        check(controller.phase=="detached"&&!controller.blocksChanges&&calls.size==beforeInvalidation)
        check(controller.off().startsWith("blocked")&&controller.on("owner1"){targets}.startsWith("blocked"))
        check(calls.size==beforeInvalidation)
        val duplicate=DiagnosticIsolationController();calls.clear()
        check(runCatching{duplicate.on("owner1"){listOf(targets[0],targets[0])}}.isFailure)
        check(!duplicate.blocksChanges&&calls.isEmpty())
        check(runCatching{duplicate.on("owner1"){emptyList()}}.isFailure&&!duplicate.blocksChanges)
        println("Diagnostic isolation production callback assertions passed; SDK visibility and pixels unobserved")
    }
}
