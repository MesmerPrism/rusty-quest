"""Source patterns only. No GLSL compiler, GPU, or Surface proof."""
import json,math,re,struct,unittest
from pathlib import Path
ROOT=Path(__file__).parent/'app/src/debug/fixtures'
PATTERNS=json.loads((ROOT/'hotload_word_patterns.json').read_text())
def as_float(word):return struct.unpack('<f',struct.pack('<I',int(word,16)))[0]
class PublicWordPatterns(unittest.TestCase):
    def test_actual_source_literals_and_abi_values(self):
        for name in ('finite','quietNaN'):
            expected=PATTERNS[name];source=(ROOT/expected['source']).read_text()
            branches={name:[a,b] for name,a,b in re.findall(r'uint (x|z|w)=i==0\?0x([0-9a-f]{8})u:0x([0-9a-f]{8})u;',source)}
            yz=re.search(r'vec4\(uintBitsToFloat\(x\),uintBitsToFloat\(0x([0-9a-f]{8})u\),uintBitsToFloat\(0x([0-9a-f]{8})u\),\.035\)',source)
            self.assertIsNotNone(yz)
            for i in range(2):
                actual=[branches['x'][i],*yz.groups()]
                self.assertEqual(actual,expected['primaryXYZ'][i])
                self.assertTrue(all(math.isfinite(as_float(w)) and abs(as_float(w))<=16 for w in actual))
                self.assertEqual(['00000000','00000000',branches['z'][i],branches['w'][i]],expected['auxXYZW'][i])
            self.assertLess(as_float(branches['x'][0]),0);self.assertGreater(as_float(branches['x'][1]),0)
            self.assertIn('vec4(0,0,uintBitsToFloat(z),uintBitsToFloat(w))',source)
            self.assertIn('if(i>=2)return;',source);self.assertIn('uint r=i*4;',source)
            for i in range(2):
                for word in expected['auxXYZW'][i][2:]:
                    bits=int(word,16)
                    if name=='finite':self.assertTrue(math.isfinite(as_float(word)))
                    else:
                        self.assertTrue(math.isnan(as_float(word)));self.assertEqual(bits&0x7fc00000,0x7fc00000)
    def test_qnan_variant_only_changes_unused_payload_literals(self):
        finite=(ROOT/PATTERNS['finite']['source']).read_text().splitlines()
        nan=(ROOT/PATTERNS['quietNaN']['source']).read_text().splitlines()
        changes=[(a,b) for a,b in zip(finite,nan) if a!=b]
        self.assertEqual(len(finite),len(nan));self.assertEqual(len(changes),3)
        self.assertTrue(changes[0][0].startswith('//'))
        self.assertTrue(all(a.strip().startswith(('uint z=','uint w=')) for a,b in changes[1:]))
if __name__=='__main__':unittest.main()
