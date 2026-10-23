"""Transaction controls for the real fresh preparer; compiler process edges modeled."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fresh', pathlib.Path(__file__).with_name('Prepare-FreshSdkHotload.py'))
fresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fresh)
DIS = '''OpDecorate %material DescriptorSet 3
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


class FreshTests(unittest.TestCase):
    def fixture(self, root):
        source = root/'source';source.mkdir()
        for stage in ('vert', 'frag'):
            (source/('workload.'+stage)).write_text('reviewed fresh '+stage)
        tools = {n: root/n for n in ('glslc', 'spirv-val', 'spirv-dis')}
        for name, path in tools.items():path.write_text('tool '+name)
        headers = root/'headers';headers.mkdir();(headers/'sdk.glsl').write_text('header')
        config = root/'shader_config.json'
        config.write_text(json.dumps(dict(hsr_flags=['GRAPHICS_HSR'],
            vertex_flags=[[n[len('workload.vert'):-4]] for n in fresh.VERTEX],
            fragment_flags=[[n[len('workload.frag'):-4]] for n in fresh.FRAGMENT],
            flag_map={n:n for n in ('', '.mv', '.2uv', '.2uv.mv', '.tangent', '.tangent.mv', '.skin', '.skin.mv')})))
        catalog = root/'hotload/catalog.json';catalog.parent.mkdir();(catalog.parent/'baseline').mkdir()
        files = []
        for name in fresh.VERTEX+fresh.FRAGMENT:
            path=catalog.parent/'baseline'/name;path.write_bytes(b'baseline words'*3)
            files.append(dict(path=name, **fresh.record(path), abiSha256=fresh.catalog.digest(fresh.catalog.abi(DIS).encode())))
        catalog.write_text(json.dumps(dict(schema='rusty.quest.sdk_hotload.catalog.v1', sdkVersion='0.14.0',
            headers=fresh.headers(headers), tools={n:fresh.record(p)['sha256'] for n,p in tools.items()},
            shaderConfigSha256=fresh.record(config)['sha256'], batches=[dict(name='baseline', files=files,
                vertexPaths=fresh.VERTEX, fragmentPaths=fresh.FRAGMENT)])))
        return source, catalog, root/'candidate', tools, headers, config

    def compiler(self, dis=DIS):
        def run(command):
            if command[0].endswith('glslc'):
                pathlib.Path(command[-1]).write_bytes(b'changed valid fixture words'*2)
            return dis if command[0].endswith('spirv-dis') else ''
        return run

    def test_fresh_fixed_batch_and_exact_baseline_revert(self):
        with tempfile.TemporaryDirectory() as directory:
            values=self.fixture(pathlib.Path(directory))
            with patch.object(fresh.catalog, 'run', side_effect=self.compiler()):result=fresh.prepare(*values)
            self.assertEqual(len(result['changed']),12)
            self.assertEqual({p.name for p in values[2].iterdir()}, set(fresh.VERTEX+fresh.FRAGMENT+['MANIFEST.json']))
            with self.assertRaisesRegex(ValueError,'output exists'):fresh.prepare(*values)
            revert=list(values);revert[2]=values[2].parent/'revert'
            with patch.object(fresh.catalog,'run',side_effect=AssertionError('revert must not recompile')):
                proof=fresh.prepare(*revert,revert=True)
            self.assertEqual(proof['changed'],[])
            for name in fresh.VERTEX+fresh.FRAGMENT:
                self.assertEqual((revert[2]/name).read_bytes(),(values[1].parent/'baseline'/name).read_bytes())

    def test_resource_change_or_compiler_failure_never_publishes(self):
        for failure in ('resource','compile'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as directory:
                values=self.fixture(pathlib.Path(directory));old=values[2].parent/'last-good';old.write_bytes(b'accepted')
                action=self.compiler(DIS.replace('Binding 0','Binding 4')) if failure=='resource' else RuntimeError('syntax error')
                with patch.object(fresh.catalog,'run',side_effect=action),self.assertRaises((ValueError,RuntimeError)):
                    fresh.prepare(*values)
                self.assertFalse(values[2].exists());self.assertEqual(old.read_bytes(),b'accepted')
                self.assertEqual(list(values[2].parent.glob('candidate-prepare-*')),[])

    def test_drift_and_incomplete_or_escaping_variant_refused(self):
        for failure in ('binary','header','tool','name','duplicate'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as directory:
                values=self.fixture(pathlib.Path(directory))
                if failure=='binary':(values[1].parent/'baseline'/fresh.VERTEX[0]).write_bytes(b'drift')
                elif failure=='header':(values[4]/'sdk.glsl').write_text('drift')
                elif failure=='tool':values[3]['glslc'].write_text('drift')
                else:
                    catalog=json.loads(values[1].read_text())
                    if failure=='name':catalog['batches'][0]['vertexPaths'][0]='../escape.spv'
                    else:catalog['batches'][0]['files'][-1]=catalog['batches'][0]['files'][0]
                    values[1].write_text(json.dumps(catalog))
                with patch.object(fresh.catalog,'run',side_effect=AssertionError('reject before compiler')),self.assertRaises(ValueError):
                    fresh.prepare(*values)
                self.assertFalse(values[2].exists())

    def test_debug_registration_is_opt_in_and_release_inert(self):
        root=pathlib.Path(__file__).parent
        prefix='app/src/{}/java/io/github/mesmerprism/rustyquest/spatial_worldspace_hotload/ProbeDebugFeatures.kt'
        debug=(root/prefix.format('debug')).read_text();release=(root/prefix.format('release')).read_text()
        self.assertIn('sdkGraphicsHotReloadFeatures(activity, BuildConfig.SDK_GRAPHICS_HOT_RELOAD)',debug)
        self.assertNotIn('HotReloadFeature',release)
        self.assertIn('sdk_graphics_hot_reload_selected=[bool]$SdkGraphicsHotReload',(root/'Build-SpatialWorldspaceHotload.ps1').read_text())


if __name__=='__main__':unittest.main()
