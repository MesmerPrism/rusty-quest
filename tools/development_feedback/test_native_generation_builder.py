"""Actual helper controls with modeled compiler/ELF tools; no Android library build."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

MODEL = r'''
use std::{env,fs,io::Write};
fn main(){
 let a:Vec<String>=env::args().skip(1).collect();
 let role=env::current_exe().unwrap().file_name().unwrap().to_string_lossy().to_string();
 let fault=env::var("NATIVE_BUILDER_MODEL_FAULT").unwrap_or_default();
 if role=="rustc-model.exe"{
  let out=std::path::PathBuf::from(&a[a.iter().position(|v|v=="-o").unwrap()+1]);
  fs::write(&out,b"MODELED compiler output, not an ELF library").unwrap();
  fs::write(out.parent().unwrap().join("compiler-called"),b"yes").unwrap();
  if fault=="wrapper-drift"{let p=out.parent().unwrap().join("generation.rs");let mut f=fs::OpenOptions::new().append(true).open(p).unwrap();writeln!(f,"// modeled drift").unwrap();}
  if fault=="tool-drift"{let p=env::var("NATIVE_BUILDER_MODEL_TOOL").unwrap();let mut f=fs::OpenOptions::new().append(true).open(p).unwrap();f.write_all(b"drift").unwrap();}
 }else{
  let p=std::path::PathBuf::from(a.last().unwrap());
  let scissor=fs::read_to_string(p.parent().unwrap().join("generation.rs")).unwrap().contains("mod scissor_abi");
  let getter=if scissor&&fault!="getter"{"rq_scissor_api"}else{"rm030_feedback_api"};
  println!("Class: ELF64\nData: 2's complement, little endian\nType: DYN\nMachine: {}",if fault=="machine"{"ARM"}else{"AArch64"});
  println!("(SONAME) Library soname: [{}]",if fault=="soname"{"wrong.so".into()}else{p.file_name().unwrap().to_string_lossy()});
  println!("(NEEDED) Shared library: [{}]",if fault=="platform"{"foreign.so"}else{"libc.so"});
  println!(" 1: 0000000000001000 12 FUNC GLOBAL DEFAULT 1 {}",getter);
  if fault=="extra-export"{println!(" 2: 0000000000002000 12 FUNC GLOBAL DEFAULT 1 foreign_export");}
  if fault=="protected-export"{println!(" 2: 0000000000002000 12 FUNC GLOBAL PROTECTED 1 foreign_export");}
 }
}'''


class NativeGenerationBuilder(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.name != 'nt' or not shutil.which('rustc') or not shutil.which('pwsh'):
            raise unittest.SkipTest('Windows, existing host rustc and pwsh7.6 required')
        cls.repo = Path(__file__).resolve().parents[2]
        cls.helper = cls.repo/'tools/development_feedback/Build-NativeGeneration.ps1'
        chosen = os.environ.get('NATIVE_GENERATION_HOST_TEST_OUTPUT')
        if chosen:
            cls.root = Path(chosen).resolve()
            cls.root.relative_to(cls.repo/'target')
            cls.root.mkdir(parents=True, exist_ok=False)
            cls.temporary = None
        else:
            cls.temporary = tempfile.TemporaryDirectory(prefix='native-generation-helper ')
            cls.root = Path(cls.temporary.name)
        source = cls.root/'modeled-tools.rs'
        source.write_text(MODEL, encoding='utf-8')
        cls.model = cls.root/'model.exe'
        result = subprocess.run(['rustc','--edition','2021',str(source),'-o',str(cls.model)],capture_output=True)
        (cls.root/'host-model-compile.log').write_bytes(result.stdout+result.stderr)
        if result.returncode: raise RuntimeError(result.stderr.decode())
        cls.calls = []

    @classmethod
    def tearDownClass(cls):
        (cls.root/'CONTROL_CALLS.json').write_text(json.dumps(dict(modeled_tools=True,
            android_library_built=False, calls=cls.calls),indent=2)+'\n')
        if cls.temporary: cls.temporary.cleanup()

    def run_helper(self, name, extra=(), fault='', exists=False):
        case = self.root/name
        case.mkdir()
        ndk = case/'ndk'
        prebuilt = ndk/'toolchains/llvm/prebuilt/windows-x86_64'
        (prebuilt/'bin').mkdir(parents=True)
        (prebuilt/'sysroot/usr/lib/aarch64-linux-android/26').mkdir(parents=True)
        for file in ['clang.exe','llvm-readelf.exe']: shutil.copyfile(self.model,prebuilt/'bin'/file)
        (ndk/'source.properties').write_text('Modeled NDK')
        (prebuilt/'sysroot/usr/lib/aarch64-linux-android/26/libc.so').write_bytes(b'modeled libc')
        rustc = case/'rustc-model.exe'
        shutil.copyfile(self.model,rustc)
        output = case/'output'
        if exists: output.mkdir()
        args = ['pwsh','-NoProfile','-File',str(self.helper),'-Rustc',str(rustc),'-AndroidNdk',str(ndk),
                '-Generation','3','-Variant','B','-OutputRoot',str(output),*extra]
        result = subprocess.run(args,capture_output=True,env={**os.environ,
            'NATIVE_BUILDER_MODEL_FAULT':fault,'NATIVE_BUILDER_MODEL_TOOL':str(prebuilt/'bin/clang.exe')})
        (case/'helper.log').write_bytes(result.stdout+result.stderr)
        self.calls.append(dict(case=name,args=args,exit_code=result.returncode,fault=fault))
        return result, output

    def test_feedback_default_keeps_its_contract_and_b_configuration(self):
        result,out = self.run_helper('feedback')
        self.assertEqual(result.returncode,0,(result.stdout+result.stderr).decode())
        receipt = json.loads((out/'result.json').read_text(encoding='utf-8-sig'))
        wrapper = (out/'generation.rs').read_text(encoding='utf-8')
        self.assertIn('const GENERATION: u64 = 3;',wrapper)
        self.assertNotIn('mod scissor_abi',wrapper)
        self.assertIn('phase_b',receipt['arguments'])
        self.assertNotIn('contract',receipt)
        self.assertEqual(set(receipt),{'schema','generation','variant','artifact','bytes',
            'source_inputs','source_inputs_unchanged','tools','arguments','compile_exit',
            'inspection_exit','seconds','scope'})
        self.assertEqual(receipt['arguments'][4:6],['--crate-name','rq_feedback_generation'])
        self.assertEqual(len(receipt['source_inputs']),2)
        self.assertIn('/src/plugin.rs',wrapper)
        self.assertTrue(Path(receipt['artifact']['path']).name.startswith('librq_feedback_3_'))

    def test_scissor_b3_selects_owned_modules_existing_generation_and_closed_getter(self):
        result,out = self.run_helper('scissor',('-Contract','Scissor'))
        self.assertEqual(result.returncode,0,(result.stdout+result.stderr).decode())
        receipt = json.loads((out/'result.json').read_text(encoding='utf-8-sig'))
        wrapper = (out/'generation.rs').read_text(encoding='utf-8')
        for token in ['const GENERATION: u64 = 3;','mod abi;','mod scissor_abi;','mod policy;','scissor/plugin.rs']:
            self.assertIn(token,wrapper)
        self.assertEqual(receipt['contract'],'Scissor')
        self.assertEqual(receipt['getter'],'rq_scissor_api')
        self.assertEqual(receipt['expected_header']['generation'],3)
        self.assertEqual(receipt['expected_header']['contract_hex'],'5251534349535331')
        self.assertFalse(receipt['embedded_header_inspected'])
        self.assertEqual(len(receipt['source_inputs']),4)
        self.assertTrue(receipt['source_inputs_unchanged'])
        self.assertTrue(receipt['tool_inputs_unchanged'])
        self.assertIn('phase_b',receipt['arguments'])

    def test_contract_is_closed_and_scissor_cannot_override_owned_plugin(self):
        for name,extra in [('unknown',('-Contract','Other')),('override',('-Contract','Scissor','-PluginSource',str(self.repo/'examples/development-feedback-native/scissor/plugin.rs')))]:
            result,out = self.run_helper(name,extra)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse((out/'compiler-called').exists())

    def test_scissor_elf_export_soname_machine_and_platform_mismatches_refuse(self):
        for fault in ['getter','extra-export','protected-export','soname','machine','platform']:
            with self.subTest(fault=fault):
                result,out = self.run_helper('elf-'+fault,('-Contract','Scissor'),fault)
                self.assertNotEqual(result.returncode,0)
                self.assertFalse((out/'result.json').exists())

    def test_scissor_generated_source_and_tool_drift_refuse(self):
        for fault in ['wrapper-drift','tool-drift']:
            with self.subTest(fault=fault):
                result,out = self.run_helper(fault,('-Contract','Scissor'),fault)
                self.assertNotEqual(result.returncode,0)
                self.assertFalse((out/'result.json').exists())

    def test_existing_output_refuses_before_dispatch(self):
        result,out = self.run_helper('exists',exists=True)
        self.assertNotEqual(result.returncode,0)
        self.assertFalse((out/'compiler-called').exists())


if __name__ == '__main__': unittest.main()
