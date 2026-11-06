"""Source-only admission checks for the separately selected Vulkan showcase."""
from pathlib import Path
import json,re,unittest
ROOT=Path(__file__).resolve().parents[2]
PKG='io/github/mesmerprism/rustyquest/native_renderer'
BASE=ROOT/'apps/native-renderer-android'
class ShowcaseVulkanContract(unittest.TestCase):
    def test_selected_descriptor_has_complete_pipeline_without_old_product(self):
        p=ROOT/'fixtures/native-app-features/ui/native-mesh-panel-showcase/ui.native_mesh_panel.showcase.feature.json'
        self.assertTrue(p.exists(),'separate showcase presentation descriptor missing')
        d=json.loads(p.read_text(encoding='utf-8'));self.assertEqual(['ui.visual_showcase'],d['depends_on'])
        env={e['name']:e['value'] for e in d['build_inputs']['env']}
        for key in ['RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_HYBRID','RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE']:self.assertEqual('true',env[key])
        self.assertEqual('false',env['RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'])
    def test_genuine_host_and_controller(self):
        host=BASE/'panel-modules/showcase/src/main/java'/PKG/'ShowcaseNativeActivity.java'
        self.assertTrue(host.exists(),'genuine showcase NativeActivity missing')
        text=host.read_text();self.assertIn('extends NativeActivity',text);self.assertIn('ShowcaseControls',text)
        self.assertNotIn('new Activity(',text);self.assertNotIn('setContentView(',text)
    def test_visibility_never_launches_an_activity_from_bridge(self):
        text=(BASE/'panel-modules/showcase/src/main/java'/PKG/'ShowcaseNativeBridge.java').read_text()
        self.assertNotIn('startActivity(',text)
        self.assertIn('ControlPanelActivity.openShowcasePanel',text)
    def test_exact_reflection_and_role_are_declared(self):
        facade=(ROOT/'tools/lib/NativeRendererShowcaseFacade.psm1').read_text()
        self.assertIn('BooleanSupplier',facade);self.assertIn('attachNativeMeshSessionControls',facade)
        role=(BASE/'src/main/java'/PKG/'NativeRendererActivityComponents.java').read_text()
        self.assertIn('SHOWCASE_NATIVE',role);self.assertIn('showcase_panel_native_host',role)
    def test_page_model_is_bounded_and_complete(self):
        path=BASE/'panel-modules/showcase/src/main/java'/PKG/'ShowcaseControls.java'
        self.assertTrue(path.exists(),'shared retained controller missing')
        text=path.read_text();self.assertIn('Inputs',text);self.assertIn('Visuals',text);self.assertIn('Profiles',text);self.assertIn('Setup',text)
        self.assertIn('ShowcaseNativeBridge.command',text);self.assertIn('expected_revision',text)
if __name__=='__main__':unittest.main()
