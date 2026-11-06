"""Execute the actual shader's pure layout predicate; no GPU/pixel claim."""
import pathlib
import re
import unittest
import json
import sys
import struct

ROOT = pathlib.Path(__file__).parent
ACTUAL_HEADER=None
if '--header' in sys.argv:
    index=sys.argv.index('--header');ACTUAL_HEADER=json.loads(pathlib.Path(sys.argv[index+1]).read_text())
    del sys.argv[index:index+2]


def predicate(selected, words):
    source = (ROOT / 'app/src/shaders/workload.vert').read_text()
    body = re.search(r'bool packetLayoutValid\(\)\s*\{(.*?)\n\}', source, re.S)
    if body is None:
        raise AssertionError('shader lacks explicit selected public layout admission')
    branches = re.search(r'#if RQ_DEVELOPMENT_FEEDBACK\s+(.*?)#else\s+(.*?)#endif', body[1], re.S)
    if branches is None:
        raise AssertionError('shader lacks closed development/default branches')
    expression = re.search(r'return\s+(.*?);', branches[1 if selected else 2], re.S)[1]
    expression = re.sub(r'(\d+)u\b', r'\1', expression).replace('&&', ' and ').replace('||', ' or ')
    return eval(' '.join(expression.split()), {'__builtins__': {}}, {'header': lambda i: words[i]})


def header_envelope(selected, words):
    source=(ROOT/'app/src/shaders/workload.vert').read_text()
    expression=re.search(r'bool valid=(.*?);',source,re.S)[1]
    expression=expression.replace('all(equal(textureSize(atlas,0),ivec2(1024)))','True')
    expression=re.sub(r'(0x[0-9a-fA-F]+|\d+)u\b',r'\1',expression).replace('&&',' and ').replace('||',' or ')
    bindings={'header':lambda i:words[i],'packetLayoutValid':lambda:predicate(selected,words),
              'expectedGeneration':1,'sourceAndHigh':0,
              'floatBitsToUint':lambda value:struct.unpack('<I',struct.pack('<f',value))[0]}
    return eval(' '.join(expression.split()),{'__builtins__':{}},bindings)


class DevelopmentShaderTests(unittest.TestCase):
    def public(self):
        if ACTUAL_HEADER is not None:
            return ACTUAL_HEADER.copy()
        words = [0] * 48
        words[7], words[10], words[25] = 512, 512, 64
        for key,value in {0:0x34415752,1:4,2:1024,3:1024,4:1,11:512,12:512,13:1024,15:1024,
                          29:1,30:0x40000000,35:1,39:0x41800000,40:1024,41:2,42:1026,43:2,46:1,47:1}.items():
            words[key]=value
        return words

    def test_actual_atlas_header_envelope(self):
        self.assertTrue(header_envelope(True,self.public()))
        for field in (0,1,2,3,4,9,11,12,13,15,29,30,35,39,40,41,42,43,46,47):
            with self.subTest(field=field):
                words=self.public();words[field]+=1
                self.assertFalse(header_envelope(True,words))

    def genuine(self, retained=0):
        words = [0] * 48
        words[7], words[8], words[10], words[25], words[26] = 2562, 17934, 20496 + retained, 2562, 17934
        return words

    def test_selection_closes_both_layouts(self):
        self.assertTrue(predicate(True, self.public()))
        self.assertFalse(predicate(False, self.public()))
        for retained in (0, 2):
            self.assertTrue(predicate(False, self.genuine(retained)))
            self.assertFalse(predicate(True, self.genuine(retained)))
        for visible in (0,64,512):
            words=self.public();words[25]=visible;self.assertTrue(predicate(True,words))

    def test_public_malformed_and_gpu_or_rig_records_refused(self):
        for field, value in ((6, 8), (7, 2562), (8, 1), (10, 511), (14, 3), (16, 3), (25, 513), (26, 1), (27, 1), (28, 1)):
            with self.subTest(field=field):
                words = self.public(); words[field] = value
                self.assertFalse(predicate(True, words))

    def test_default_genuine_original_limits_preserved(self):
        for field, value in ((7, 512), (8, 0), (10, 20497), (25, 2563), (26, 17935)):
            with self.subTest(field=field):
                words = self.genuine(); words[field] = value
                self.assertFalse(predicate(False, words))


if __name__ == '__main__':
    unittest.main()
