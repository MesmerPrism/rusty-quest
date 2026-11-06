"""Host transaction/ABI rejection checks; no SDK/device execution."""
import importlib.util, pathlib, tempfile, unittest, sys
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('catalog',pathlib.Path(__file__).with_name('Prepare-SdkHotloadCatalog.py'))
catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)

def material_layout(rows=10):
    layout='''OpDecorate %material DescriptorSet 3
OpDecorate %material Binding 0
%float = OpTypeFloat 32
%row = OpTypeVector %float 4
%block = OpTypeStruct ROWS
%ptr = OpTypePointer Uniform %block
%material = OpVariable %ptr Uniform'''.replace('ROWS',' '.join(['%row']*rows))
    return layout+'\n'+'\n'.join(f'OpMemberDecorate %block {index} Offset {index*16}' for index in range(rows))

class CatalogTests(unittest.TestCase):
    def test_material_accepts_exact_ten_row_hand_appearance_layout(self):
        self.assertEqual(catalog.MATERIAL_UNIFORM_ROWS,10)
        self.assertEqual(catalog.MATERIAL_UNIFORM_BYTES,160)
        self.assertEqual(catalog.material_uniform_bytes(material_layout()),160)

    def test_material_rejects_old_extra_and_shifted_uniform_layout(self):
        for rows in [2,5,6,9,11]:
            with self.subTest(rows=rows),self.assertRaises(ValueError):
                catalog.material_uniform_bytes(material_layout(rows))
        layout=material_layout()
        for damaged in [layout.replace('9 Offset 144','9 Offset 160'),
                        layout.replace('\nOpMemberDecorate %block 9 Offset 144',''),
                        layout.replace('8 Offset 128','8 Offset 112')]:
            with self.assertRaises(ValueError):catalog.material_uniform_bytes(damaged)

    def test_material_rejects_wrong_type_storage_and_descriptor_identity(self):
        layout=material_layout()
        for damaged in [layout.replace('OpTypeFloat 32','OpTypeFloat 64'),
                        layout.replace('OpTypeVector %float 4','OpTypeVector %float 3'),
                        layout.replace('Uniform','StorageBuffer'),
                        layout.replace('DescriptorSet 3','DescriptorSet 2'),
                        layout.replace('Binding 0','Binding 1'),
                        layout+'\nOpDecorate %other DescriptorSet 3\nOpDecorate %other Binding 0\n%other = OpVariable %ptr Uniform']:
            with self.assertRaises(ValueError):catalog.material_uniform_bytes(damaged)
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

if __name__=='__main__':unittest.main()
