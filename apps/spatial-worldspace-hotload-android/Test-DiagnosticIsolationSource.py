"""Source integration guards only; real SPIR-V reflection and SDK execution remain separate."""
from pathlib import Path
import re, unittest
ROOT=Path(__file__).resolve().parent
PKG="java/io/github/mesmerprism/rustyquest/spatial_worldspace_hotload"
def source(set_name,name):return (ROOT/"app/src"/set_name/PKG/name).read_text()
class IsolationSourceTest(unittest.TestCase):
    def test_carrier_never_receives_visibility_setter(self):
        debug=source("debug","ProbeDebugFeatures.kt")
        section=debug.split("fun isolationControl",1)[1].split("fun isolationStatus",1)[0]
        self.assertNotIn("carrier",section)
        self.assertIn('"legacy_material_draw_flag",requireNotNull(setLegacyDrawEnabled)',section)
        self.assertEqual(section.count("setIsVisible"),1)
        self.assertIn('obj.setIsVisible(it)',section)
        self.assertIn('diagnosticVisibilityTargets()',section)
    def test_shader_suppression_is_kind2_only_and_abi_declaration_unchanged(self):
        vert=(ROOT/"app/src/shaders/workload.vert").read_text()
        branch=vert.split('if(material.meshFlags.x>1.5){',1)[1].split('} else {',1)[0]
        self.assertIn('if(material.meshFlags.x<2.5&&material.transportFlags.z>1.5)drawable=0u;',branch)
        self.assertIn('if(drawable==0u)discard;', (ROOT/"app/src/shaders/workload.frag").read_text())
        block=vert.split('uniform MaterialUniform {',1)[1].split('}',1)[0]
        self.assertEqual(re.findall(r'vec4 (\w+);',block),['transportFlags','meshFlags','renderStyle0','renderStyle1','renderStyle2'])
        activity=source("main","SpatialWorldspaceHotloadActivity.kt")
        self.assertIn('if(BuildConfig.DEBUG&&kind==2f&&legacyDrawSuppressed)2f else if(hostGeometry)1f else 0f',activity)
    def test_local_host_adjustment_precedes_entity_transform(self):
        vert=(ROOT/"app/src/shaders/workload.vert").read_text()
        self.assertIn('world=g_PrimitiveUniform.worldFromObject*adjustPosition(vec4(app.position.xy*.025+vec2(0,1.4),1.5,1));',vert)
        self.assertNotIn('adjustPosition(g_PrimitiveUniform.worldFromObject',vert)
    def test_closed_control_stop_and_teardown_order(self):
        provider=source("debug","ProbeControlProvider.kt")
        self.assertIn('"diagnostic-isolation"->if(arg !in setOf("on","off"))closed()',provider)
        activity=source("main","SpatialWorldspaceHotloadActivity.kt")
        probe=activity.split('override fun applyProbeControl',1)[1].split('override fun applyWorkloadControl',1)[0]
        self.assertLess(probe.index('ProbeControl.STOP'),probe.index('isolationBlocksChanges'))
        self.assertIn('return "blocked:topology_quarantined"',activity)
        for method in ['applyTopology','prepareScale','configure','selectGpu','selectCpu']:
            body=activity.split('private fun '+method,1)[1].split('private fun ',1)[0]
            self.assertIn('isolationBlocksChanges()',body)
        debug=source("debug","ProbeDebugFeatures.kt")
        self.assertIn('if(target.get() === value) {isolation.close();',debug)
        release=source("release","ProbeDebugFeatures.kt")
        self.assertNotIn('DiagnosticIsolationController',release)
        self.assertIn('fun isolationBlocksChanges():Boolean=false',release)
if __name__=="__main__":unittest.main()
