"""Source-only diagnostic contract checks. No compiler, SDK or device invocation."""
import ast,json,pathlib,re,struct,unittest
BASE=pathlib.Path(__file__).parent
SHADER=(BASE/'app/src/shaders/atlas_diagnostic.vert').read_text()
PROTOCOL=(BASE/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_worldspace_hotload/AtlasDiagnosticProtocol.kt').read_text()
GRID=json.loads((BASE/'atlas-diagnostic-grid.json').read_text())
def bits(value):return struct.unpack('<I',struct.pack('<f',value))[0]
def expression(value):
    value=' '.join(value.split())
    value=value.replace('all(equal(dimensions,ivec2(1024)))','(width==1024 and height==1024)')
    value=value.replace('width==1024&&height==1024','width==1024 and height==1024')
    value=re.sub(r'(2|16)f\.toBits\(\)\.toUInt\(\)',lambda m:str(bits(float(m[1]))),value)
    value=re.sub(r'floatBitsToUint\((2|16)\.0\)',lambda m:str(bits(float(m[1]))),value)
    value=re.sub(r'\b(0x[0-9a-fA-F]+|[0-9]+)u\b',r'\1',value).replace('&&',' and ').replace('||',' or ')
    tree=ast.parse(value,mode='eval')
    allowed=(ast.Expression,ast.BoolOp,ast.And,ast.Or,ast.Compare,ast.Eq,ast.NotEq,ast.Lt,ast.LtE,ast.Gt,ast.GtE,ast.BinOp,ast.Add,ast.Mod,ast.BitAnd,ast.Name,ast.Load,ast.Subscript,ast.Constant)
    assert all(isinstance(n,allowed) for n in ast.walk(tree)),value
    return compile(tree,'actual-source-predicate','eval')
shader_cases={int(i):expression(v) for i,v in re.findall(r'case (\d+)u:return ([^;]+);',SHADER.split('bool predicate(',1)[1].split('void main()',1)[0])}
kotlin_cases=[expression(v.strip()) for v in PROTOCOL.split('return booleanArrayOf(',1)[1].split(')\n',1)[0].split(',')]
def predicates(h,width=1024,height=1024):return [eval(shader_cases[i],{'__builtins__':{}},{'h':h,'width':width,'height':height}) for i in range(31)]
def expected_header():
    h=[0]*48
    for index,value in {0:0x34415752,1:4,2:1024,3:1024,4:1,7:2562,8:17934,10:20496,11:20496,12:20496,13:40992,14:13884,15:54876,16:69420,25:2562,26:17934,29:1,30:bits(2),35:1,39:bits(16),40:124296,41:2,42:124298,43:2,46:1,47:1}.items():h[index]=value
    return h
class SourceContract(unittest.TestCase):
    def test_packet_independent_fiducials(self):
        branch=SHADER.split('if(row>=100u&&row<104u){',1)[1].split('ivec2 dimensions=textureSize',1)[0]
        self.assertNotIn('texelFetch',branch);self.assertNotIn('header(',branch)
        self.assertIn('return;',branch)
        colors=re.findall(r'vec3\(([^)]+)\)',branch.split('vec3 colors[4]=vec3[4](',1)[1].split(');',1)[0])
        self.assertEqual([[float(x) for x in c.split(',')] for c in colors],[f['rgb'] for f in GRID['calibration']['fiducials']])
        plane=GRID['physicalSdkPlane'];left=plane['origin'][0]-plane['halfCellWidth']
        right=plane['origin'][0]+31*plane['columnStep'][0]+plane['halfCellWidth']
        for marker in GRID['calibration']['fiducials']:
            self.assertLess(marker['halfWidth'],marker['blackGuardOuterHalfWidth'])
            self.assertLess(marker['blackGuardOuterHalfWidth'],marker['whiteGuardOuterHalfWidth'])
            x=marker['center'][0];half=marker['whiteGuardOuterHalfWidth']
            self.assertTrue(x+half<left or x-half>right)
    def test_retained_layout(self):
        h=expected_header()
        for i in (10,11,12):h[i]=20498
        for i in (13,15,40,42):h[i]+=4
        self.assertTrue(all(predicates(h)))
    def test_actual_predicates_and_wrong_fields(self):
        h=expected_header();self.assertEqual(len(shader_cases),31);self.assertEqual(len(kotlin_cases),31)
        self.assertTrue(all(predicates(h)))
        for width,height in ((1024,1024),(16,4)):
            self.assertEqual(predicates(h,width,height),[eval(v,{'__builtins__':{}},{'h':h,'width':width,'height':height}) for v in kotlin_cases])
        for word in range(48):
            changed=h.copy();changed[word]^=1
            self.assertEqual(predicates(changed),[eval(v,{'__builtins__':{}},{'h':changed,'width':1024,'height':1024}) for v in kotlin_cases])
        for word,value,index in ((14,131064,14),(16,131064,15),(15,54877,16),(16,80000,17),(14,13885,18),(16,69421,19),(25,2563,20),(26,17935,21)):
            changed=h.copy();changed[word]=value;self.assertFalse(predicates(changed)[index])
    def test_grid_and_boundaries(self):
        self.assertEqual((GRID['columns'],GRID['rows']),(32,56))
        self.assertEqual([r['row'] for r in GRID['rowMap']],list(range(56)))
        self.assertEqual([r['word'] for r in GRID['rowMap'][12:17]],[4,5,35,17,47])
        self.assertEqual(len(GRID['rowMap'][3]['columns']),32)
        sample=SHADER.split('vec3 sampleAt(',1)[1].split('vec3 sampleTexel',1)[0]
        self.assertLess(sample.index('if(!available)return'),sample.index('texelFetch'))
        self.assertNotIn('discard',(BASE/'app/src/shaders/atlas_diagnostic.frag').read_text())
        self.assertNotIn('drawable',SHADER)
        for bit in range(32):self.assertEqual(((1<<bit)>>bit)&1,1)
        for page in range(6):self.assertEqual([page*8+row-4 for row in range(4,12)],list(range(page*8,page*8+8)))
    def test_individual_predicate_fetch_dependencies(self):
        availability=SHADER.split('bool predicateAvailable(',1)[1].split('bool predicate(',1)[0]
        dependencies={int(i):[int(n) for n in re.findall(r'a\[(\d+)\]',value)] for i,value in re.findall(r'case (\d+)u:return ([^;]+);',availability)}
        expected=[[],[0],[1],[2],[3],[29],[35],[7],[8],[9],[10],[11,10],[12,10],[13,10],[14],[16],[15,13,14],[15,16],[14],[16],[25],[26],[4],[40,15,16],[41],[42,40],[43],[30],[39],[46],[47,4]]
        self.assertEqual([dependencies[i] for i in range(31)],expected)

    def test_new_public_cohort_gate_and_addresses(self):
        # Evaluate the actual GLSL cohort-return expression, composed with its
        # actual existing31 predicates; this remains a source-only check.
        body=SHADER.split('bool publicPacket(',1)[1].split('uint publicWordOffset(',1)[0]
        gate=body.rsplit('return ',1)[1].split(';',1)[0].replace('uint(round(material.publicCohort.z))','primaryOffset')
        code=expression(gate)
        def admitted(h,generation=1,offset=124296):
            return generation!=0 and all(predicates(h)) and eval(code,{'__builtins__':{}},{'h':h,'expected':generation,'primaryOffset':offset})
        h=expected_header();h[6]=8;h[44]=1
        self.assertTrue(admitted(h))
        for word,value in ((6,0),(35,0),(41,0),(43,3),(44,0),(45,3),(40,0xffffffff),(42,0xffffffff),(47,2)):
            bad=h.copy();bad[word]=value;self.assertFalse(admitted(bad))
        self.assertFalse(admitted(h,generation=0));self.assertFalse(admitted(h,generation=2))
        alias=h.copy()
        for word,value in {14:0,15:40992,16:0,40:40992,42:40994}.items():alias[word]=value
        self.assertTrue(all(predicates(alias)));self.assertFalse(admitted(alias))
        high=h.copy();high[4]=high[47]=0x80001234
        self.assertTrue(admitted(high,generation=0x80001234))
        self.assertIn('if(material.publicCohort.w<.5||expected==0u)return false;',body)
        self.assertIn('if(!available)return false;',body)
        self.assertIn('return 192u+24u*(h[42]+slot/4u)+4u*(slot%4u)',SHADER)
        self.assertIn('return 192u+24u*(h[40]+slot/3u)+4u*(slot%3u)',SHADER)
        self.assertIn('if(page>=6u)offset=publicWordOffset(page,0u,available);',SHADER)
        self.assertIn('if(available)rgb=sampleTexel(offset/3u,available);',SHADER)
        for page in (6,7):
            rows=GRID['pageOverrides'][str(page)]['rowMap'][:8]
            for slot,r in enumerate(rows):
                self.assertEqual(r['row'],slot+4)
                if page==7 and slot>=6:self.assertEqual(r['word'],44+slot-6)
                else:
                    stride=4 if page==6 else 3
                    self.assertEqual((r['recordIndex'],r['recordWord']),(slot//stride,slot%stride))
                    self.assertEqual(r['offsetHeaderWord'],42 if page==6 else 40)
        self.assertEqual(GRID['pages'],8);self.assertEqual(GRID['headerPages'],6)

if __name__=='__main__':unittest.main()
