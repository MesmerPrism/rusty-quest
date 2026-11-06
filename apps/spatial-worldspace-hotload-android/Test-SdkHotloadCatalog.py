"""Host transaction/ABI rejection checks; no SDK/device execution."""
import importlib.util, pathlib, tempfile, unittest, sys
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('catalog',pathlib.Path(__file__).with_name('Prepare-SdkHotloadCatalog.py'))
catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)

class CatalogTests(unittest.TestCase):
    def test_material_rejects_old_or_shifted_uniform_layout(self):
        layout='''OpDecorate %material DescriptorSet 3
OpDecorate %material Binding 0
%float = OpTypeFloat 32
%row = OpTypeVector %float 4
%block = OpTypeStruct %row %row %row %row %row
%ptr = OpTypePointer Uniform %block
%material = OpVariable %ptr Uniform
OpMemberDecorate %block 0 Offset 0
OpMemberDecorate %block 1 Offset 16
OpMemberDecorate %block 2 Offset 32
OpMemberDecorate %block 3 Offset 48
OpMemberDecorate %block 4 Offset 64'''
        self.assertEqual(catalog.material_uniform_bytes(layout),80)
        with self.assertRaises(ValueError):catalog.material_uniform_bytes(layout.replace('4 Offset 64','4 Offset 80'))
        old=layout.replace('%block = OpTypeStruct %row %row %row %row %row','%block = OpTypeStruct %row %row')
        with self.assertRaises(ValueError):catalog.material_uniform_bytes(old)
    def test_abi_ignores_ids_but_keeps_offsets_locations_and_bindings(self):
        original='''OpDecorate %v Binding 1
OpDecorate %v DescriptorSet 3
%f = OpTypeFloat 32
%vec = OpTypeVector %f 4
%ptr = OpTypePointer Input %vec
%v = OpVariable %ptr Input'''
        changed=original.replace('%v','%different').replace('%f','%scalar').replace('%ptr','%pointer')
        self.assertEqual(catalog.abi(original),catalog.abi(changed))
        self.assertNotEqual(catalog.abi(original),catalog.abi(original.replace('Binding 1','Binding 2')))
    def test_failed_compiler_preserves_previous_catalog(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);output=root/'assets';output.mkdir();previous=output/'catalog';previous.write_bytes(b'accepted immutable bytes')
            config=root/'config.json';config.write_text('{"hsr_flags":["GRAPHICS_HSR"],"vertex_flags":[[],[],[],[],[],[],[],[]],"fragment_flags":[[],[],[],[]],"flag_map":{}}')
            argv=['prepare','--glslc',str(config),'--spirv-val',str(config),'--spirv-dis',str(config),'--headers','unused','--shader-config',str(config),'--output',str(output)]
            with patch.object(sys,'argv',argv),patch.object(catalog,'run',side_effect=RuntimeError('compile syntax failure')):
                with self.assertRaises(ValueError):catalog.main()
            self.assertEqual(previous.read_bytes(),b'accepted immutable bytes')
            self.assertEqual(list(root.glob('assets-prepare-*')),[])
            fresh=root/'fresh';argv[-1]=str(fresh)
            with patch.object(sys,'argv',argv),patch.object(catalog,'run',side_effect=RuntimeError('compile syntax failure')):
                with self.assertRaises(RuntimeError):catalog.main()
            self.assertFalse(fresh.exists())
            self.assertEqual(previous.read_bytes(),b'accepted immutable bytes')
            self.assertEqual(list(root.glob('fresh-prepare-*')),[])
if __name__=='__main__':unittest.main()
