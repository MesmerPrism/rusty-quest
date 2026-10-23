package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.os.Looper
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.AppSystemActivity

/** Runs the actual debug/release ProbeDebugFeatures, not a second implementation of ownership. */
object DiagnosticOwnerHostTest {
    class Owner(val name:String):AppSystemActivity(),ProbeControlTarget {
        override fun applyProbeControl(control:ProbeControl,value:Boolean?)="unused"
        override fun applyWorkloadControl(method:String,value:String?)="unused"
        override fun probeControlStatus()="{}"
    }
    private fun rejects(block:()->Unit){check(runCatching(block).isFailure)}
    private fun calls(owner:Owner){
        ProbeDebugFeatures.diagnosticCohort(owner,1L,2)
        ProbeDebugFeatures.diagnosticTexture(owner,SceneTexture("replacement"))
        ProbeDebugFeatures.diagnosticControl(owner,"diag-page","1")
        ProbeDebugFeatures.diagnosticStatus(owner)
    }
    private fun blocked(owner:Owner){
        val count=AtlasDiagnosticController.created.size
        ProbeDebugFeatures.createDiagnostic(owner,SceneTexture("must-not-allocate"))
        ProbeDebugFeatures.diagnosticCohort(owner,9L,10)
        ProbeDebugFeatures.diagnosticTexture(owner,SceneTexture("must-not-write"))
        check(ProbeDebugFeatures.diagnosticControl(owner,"diag-page","7")=="blocked:no_diagnostic")
        check(ProbeDebugFeatures.diagnosticStatus(owner)=="{}")
        check(AtlasDiagnosticController.created.size==count)
    }
    @JvmStatic fun main(args:Array<String>){
        val a=Owner("A");val b=Owner("B")
        if(args.single()=="release"){
            for(main in listOf(true,false)){
                Looper.onMain=main
                ProbeDebugFeatures.attach(a);ProbeDebugFeatures.createDiagnostic(a,SceneTexture("a"));calls(a)
                ProbeDebugFeatures.attach(b);ProbeDebugFeatures.detach(a)
                check(ProbeDebugFeatures.diagnosticControl(b,"diag-page","1")=="blocked:debug_only")
                check(ProbeDebugFeatures.diagnosticStatus(b)=="{}");ProbeDebugFeatures.detach(b)
            }
            check(AtlasDiagnosticController.created.isEmpty());println("Actual release diagnostic facade remains inert");return
        }
        blocked(a)
        ProbeDebugFeatures.attach(a);ProbeDebugFeatures.createDiagnostic(a,SceneTexture("a"))
        val old=AtlasDiagnosticController.created.single();calls(a);check(old.calls.size==4)
        old.invalid=true // Native scene A has retired before its Activity's late onDestroy.
        ProbeDebugFeatures.attach(b) // B attaches before detach(A), reproducing the actual failed ordering.
        check(ProbeDebugFeatures.diagnosticStatus(b)=="{}")
        ProbeDebugFeatures.diagnosticCohort(b,0L,0) // B's first material/updateFlags before createDiagnostic.
        ProbeDebugFeatures.diagnosticTexture(b,SceneTexture("startup"))
        check(ProbeDebugFeatures.diagnosticControl(b,"diag-page","0")=="blocked:no_diagnostic")
        blocked(a);check(old.calls.size==4)
        ProbeDebugFeatures.detach(a) // Late A detach cannot clear B's current target.
        ProbeDebugFeatures.createDiagnostic(b,SceneTexture("b"))
        val current=AtlasDiagnosticController.created.last();check(current.owner===b)
        calls(b);check(current.calls.size==4)
        ProbeDebugFeatures.diagnosticCohort(a,0L,0) // Exact retiring A.stopProducer callback after B created.
        blocked(a);ProbeDebugFeatures.detach(a)
        check(current.calls.size==4&&ProbeDebugFeatures.diagnosticStatus(b)=="diagnostic:B")
        current.invalid=true
        rejects{ProbeDebugFeatures.diagnosticCohort(b,0L,0)}
        rejects{ProbeDebugFeatures.diagnosticTexture(b,SceneTexture("current-fault"))}
        rejects{ProbeDebugFeatures.diagnosticControl(b,"diag-page","1")}
        rejects{ProbeDebugFeatures.diagnosticStatus(b)}
        current.invalid=false // Admitted current-owner native failures were propagated, not hidden.
        val before=current.calls.size
        Looper.onMain=false
        blocked(b);rejects{ProbeDebugFeatures.attach(a)};rejects{ProbeDebugFeatures.detach(b)}
        check(current.calls.size==before)
        Looper.onMain=true
        check(ProbeDebugFeatures.diagnosticStatus(b)=="diagnostic:B")
        ProbeDebugFeatures.detach(b);current.invalid=true;blocked(b)

        // A constructor can reenter the owner lifecycle; its result must not replace the new owner's controller.
        val c=Owner("C");val d=Owner("D")
        ProbeDebugFeatures.attach(c)
        AtlasDiagnosticController.constructing={owner->
            if(owner===c){AtlasDiagnosticController.constructing=null;ProbeDebugFeatures.attach(d);ProbeDebugFeatures.createDiagnostic(d,SceneTexture("d"))}
        }
        ProbeDebugFeatures.createDiagnostic(c,SceneTexture("c"))
        check(ProbeDebugFeatures.diagnosticStatus(c)=="{}")
        check(ProbeDebugFeatures.diagnosticStatus(d)=="diagnostic:D")
        ProbeDebugFeatures.detach(c)
        check(ProbeDebugFeatures.diagnosticStatus(d)=="diagnostic:D")
        ProbeDebugFeatures.detach(d)
        println("Actual debug diagnostic facade: replacement startup, stale owner create/read/write/detach, main-thread refusal and reentrant publication passed")
    }
}
