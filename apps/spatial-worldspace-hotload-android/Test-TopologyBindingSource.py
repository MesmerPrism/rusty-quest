"""Source integration checks; no compiler, SDK, Gradle cache or headset calls."""
from pathlib import Path
import unittest

APP=Path(__file__).resolve().parent
PKG=Path('java/io/github/mesmerprism/rustyquest/spatial_worldspace_hotload')
def source(kind,name):return (APP/'app/src'/kind/PKG/name).read_text(encoding='utf-8')
OWNER=source('main','WorkloadSceneTopology.kt')
ACTIVITY=source('main','SpatialWorldspaceHotloadActivity.kt')
class TopologyBindingSourceTest(unittest.TestCase):
    def test_fresh_constructor_never_calls_mesh_setter_or_allocates_mesh(self):
        body=OWNER.split('fun compareBinding(',1)[1].split('    fun culling(',1)[0]
        self.assertNotIn('.setSceneMesh(',body)
        self.assertNotIn('WorkloadGeometry.',body)
        self.assertIn('val mesh=prepared.meshes.single()',body)
        self.assertIn('SceneObject(scene,mesh,"workload-binding-fresh",entity)',body)
        self.assertLess(body.index('bindingAllocationAttempted=true'),body.index('Entity.create('))
        self.assertLess(body.index('bindingFresh=Slot(entity,obj)'),body.index('register(entity,obj)'))
        self.assertLess(body.index('obj.setIsVisible(false)'),body.index('register(entity,obj)'))
        self.assertIn('check(bindingMesh==null||bindingMesh===mesh)',body)
        self.assertIn('bindingRoute="uncertain";quarantine();throw error',body)
    def test_same_owner_handler_and_closed_debug_provider(self):
        provider=source('debug','ProbeControlProvider.kt')
        self.assertIn('"topology-binding"->if(arg !in setOf("retained","fresh","off"))closed()',provider)
        self.assertIn('Binder.getCallingUid() != Process.SHELL_UID',provider)
        self.assertIn('enforceCallingPermission("android.permission.DUMP"',provider)
        self.assertIn('applyWorkloadControl("topology-binding",route)',ACTIVITY)
        body=ACTIVITY.split('override fun applyWorkloadControl(',1)[1]
        self.assertLess(body.index('if(method=="topology-binding")'),body.index('bindingComparisonActive'))
        prerequisites=ACTIVITY.split('private fun applyBindingComparison(',1)[1].split('private fun endBindingComparison(',1)[0]
        for guard in ('BuildConfig.DEBUG','source!="cpu"','producerHandle!=0L','retiringHandle!=0L',
                      'scaleLadder!="particles"','topology.active?.profile!="p512"',
                      'scaleConfigurationRevision!=controls.controlRevision','lifecycleUncertain()',
                      'ProbeDebugFeatures.isolationBlocksChanges()','ProbeDebugFeatures.cohortComparisonsReady()'):
            self.assertIn(guard,prerequisites)
        self.assertIn('if(route!="off"&&',prerequisites)
    def test_control_freeze_and_lifecycle_cancellation(self):
        for name in ('selectCpu','selectGpu','configure','applyTopology','prepareScale'):
            body=ACTIVITY.split('private fun '+name,1)[1].split('private fun ',1)[0]
            self.assertIn('bindingComparisonActive',body)
        probe=ACTIVITY.split('override fun applyProbeControl(',1)[1].split('override fun applyWorkloadControl(',1)[0]
        self.assertLess(probe.index('ProbeControl.STATUS'),probe.index('bindingComparisonActive'))
        self.assertLess(probe.index('ProbeControl.STOP'),probe.index('bindingComparisonActive'))
        self.assertIn('endBindingComparison("stop")',probe)
        self.assertIn('handler.post{if(!destroyed)endBindingComparison("xr_unavailable")}',ACTIVITY)
        pause=ACTIVITY.split('override fun onPause()',1)[1].split('override fun onDestroy()',1)[0]
        self.assertLess(pause.index('endBindingComparison("pause")'),pause.index('restoreBounds()'))
        self.assertIn('endBindingComparison("destroy")',ACTIVITY)
    def test_release_inertness_and_retention_bounds(self):
        body=OWNER.split('fun compareBinding(',1)[1]
        self.assertLess(body.index('if(!BuildConfig.DEBUG)return'),body.index('Entity.create('))
        self.assertIn('if(BuildConfig.DEBUG)result.put("bindingComparison"',OWNER)
        self.assertIn('.put("freshObjectLimit",1)',OWNER)
        self.assertIn('bindingFresh?.let{runCatching{it.obj.setIsVisible(false)}}',OWNER)
        self.assertFalse((APP/'app/src/release'/PKG/'ProbeControlProvider.kt').exists())
        self.assertIn('check(!uncertain&&!bindingComparisonActive&&revision<Long.MAX_VALUE)',OWNER)

if __name__=='__main__':unittest.main()
