"""Fixed cached-reader caller; all provider/actor values are offline fixtures."""
import argparse,base64,copy,importlib.util,json,pathlib,sys,unittest
sys.dont_write_bytecode=True
parser=argparse.ArgumentParser();parser.add_argument('--response',type=pathlib.Path,required=True);args=parser.parse_args()
spec=importlib.util.spec_from_file_location('cached_reader',pathlib.Path(__file__).resolve().parents[1]/'embedded_duplex_cached_inputs.py')
reader=importlib.util.module_from_spec(spec);spec.loader.exec_module(reader)
GOLDEN=json.loads(args.response.read_text());ACTOR=GOLDEN['process'];RUN=GOLDEN['requested_run_id'];NONCE=GOLDEN['challenge']

def bundle(value):
    raw=value if type(value) is bytes else json.dumps(value).encode()
    return (b'Result: Bundle[{schema=rusty.quest.embedded_duplex.operator_transport.v3, status=observed, challenge='
            +NONCE.encode()+b', run_id='+RUN.encode()+b', receipt_base64='+base64.b64encode(raw)+b'}]\n')


class CachedCallerTests(unittest.TestCase):
    def test_outer_transport_frame_and_closed_envelope(self):
        framed=bundle(GOLDEN)
        cases=[
            framed.replace(b'operator_transport.v3',b'operator_transport.v1'),
            framed.replace(b'status=observed',b'status=rejected'),
            framed.replace(b'status=observed',b'status=observed, status=observed'),
            framed.replace(b'status=observed',b'status=rejected, status=observed'),
            framed.replace(b'status=observed, ',b''),
            framed.replace(b'challenge='+NONCE.encode(),b'challenge='+b'c'*32),
            framed.replace(b'run_id='+RUN.encode(),b'run_id='+b'c'*32),
            framed.replace(b'}]',b', private_key=not-allowed}]'),
            b'prefix\n'+framed,
            framed+b'trailing',framed+b'\n',framed.rstrip(b'\n')+b' ',
            framed[len(b'Result: Bundle[{'):-3]+b'\n',
            framed.replace(b', ',b','),
        ]
        encoded=base64.b64encode(json.dumps(GOLDEN).encode())
        cases += [
            b'Result: Bundle[{schema=wrong, status=rejected, secret=unexpected, receipt_base64='+encoded+b'}]',
            b'Result: Bundle[{status=observed, status=rejected, receipt_base64='+encoded+b'}]',
            b'not a bundle receipt_base64='+encoded+b' } trailing bytes',
        ]
        for raw in cases:
            with self.subTest(raw=raw[:100]):
                with self.assertRaises(ValueError):reader.decode(raw,RUN,ACTOR,NONCE)
        fields=framed.removeprefix(b'Result: Bundle[{').removesuffix(b'}]\n').split(b', ')
        for field in fields:
            for modified in ([part for part in fields if part != field], fields+[field]):
                with self.assertRaises(ValueError):
                    reader.decode(b'Result: Bundle[{'+b', '.join(modified)+b'}]\n',RUN,ACTOR,NONCE)
        with self.assertRaises(ValueError):
            reader.decode(framed.replace(b'receipt_base64=',b'receipt_base64='+b'A'*8193),RUN,ACTOR,NONCE)

    def test_transport_field_order_and_only_actual_line_endings(self):
        frame=bundle(GOLDEN).removesuffix(b'\n')
        body=frame[len(b'Result: Bundle[{'):-2]
        reordered=b'Result: Bundle[{'+b', '.join(reversed(body.split(b', ')))+b'}]'
        for raw in (frame,reordered,reordered+b'\n',reordered+b'\r\n'):
            self.assertEqual(GOLDEN,reader.decode(raw,RUN,ACTOR,NONCE))

    def test_actual_java_projection_roundtrip_and_fixed_single_call(self):
        calls=[]
        def invoke(argv):calls.append(argv);return bundle(GOLDEN)
        self.assertEqual(GOLDEN,reader.observe('fixture.app',RUN,ACTOR,NONCE,invoke))
        self.assertEqual(1,len(calls));self.assertEqual('cached_effective_inputs',calls[0][calls[0].index('--method')+1])
        self.assertEqual('content://fixture.app.embedded-duplex-operator',calls[0][calls[0].index('--uri')+1])
        self.assertNotIn('original_id',str(calls));self.assertNotIn('capture',str(calls))

    def test_uninitialized_is_returned_without_retry_or_capture(self):
        empty=copy.deepcopy(GOLDEN);empty.update(phase='uninitialized',selected_run_id=None,run_selection_generation=0,selection=None,native_configuration=None)
        calls=[]
        def invoke(argv):calls.append(argv);return bundle(empty)
        self.assertEqual('uninitialized',reader.observe('fixture.app',RUN,ACTOR,NONCE,invoke)['phase']);self.assertEqual(1,len(calls))

    def test_actor_nonce_run_and_claims_are_bound(self):
        for key,wrong in [('challenge','c'*32),('requested_run_id','c'*32),('selected_run_id','c'*32),
                          ('socket_liveness_observed',True),('phase','ready')]:
            value=copy.deepcopy(GOLDEN);value[key]=wrong
            with self.assertRaises(ValueError):reader.decode(bundle(value),RUN,ACTOR,NONCE)
        for key,wrong in [('pid',43),('uid',10002),('start_ticks','2346'),('boot_id','2'*8+'-2222-3333-4444-555555555555')]:
            value=copy.deepcopy(GOLDEN);value['process'][key]=wrong
            with self.assertRaises(ValueError):reader.decode(bundle(value),RUN,ACTOR,NONCE)

    def test_whitelist_excludes_secret_fields_and_bounds(self):
        for destination in ([],['selection'],['selection','lineage'],['selection','peers',0],['native_configuration']):
            value=copy.deepcopy(GOLDEN);target=value
            for field in destination:target=target[field]
            target['private_key']='never-forward'
            with self.assertRaises(ValueError):reader.decode(bundle(value),RUN,ACTOR,NONCE)
        with self.assertRaises(ValueError):reader.decode(b'x'*131073,RUN,ACTOR,NONCE)
        with self.assertRaises(ValueError):reader.decode(bundle(b' '*65537),RUN,ACTOR,NONCE)
        raw=json.dumps(GOLDEN).encode().replace(b'"challenge":',b'"challenge":"'+NONCE.encode()+b'","challenge":',1)
        with self.assertRaises(ValueError):reader.decode(bundle(raw),RUN,ACTOR,NONCE)
        with self.assertRaises(ValueError):reader.decode(bundle(GOLDEN)+bundle(GOLDEN),RUN,ACTOR,NONCE)

    def test_invalid_request_never_calls_transport_and_errors_do_not_retry(self):
        calls=[]
        for package,run,actor,nonce in [('bad/path',RUN,ACTOR,NONCE),('fixture.app','bad',ACTOR,NONCE),
                                      ('fixture.app',RUN,dict(ACTOR,pid=True),NONCE),('fixture.app',RUN,ACTOR,'bad')]:
            with self.assertRaises(ValueError):reader.observe(package,run,actor,nonce,lambda argv:calls.append(argv))
        self.assertEqual([],calls)
        def unavailable(argv):calls.append(argv);raise RuntimeError('modeled capture unavailable')
        with self.assertRaises(RuntimeError):reader.observe('fixture.app',RUN,ACTOR,NONCE,unavailable)
        self.assertEqual(1,len(calls))

unittest.main(argv=[sys.argv[0]])
