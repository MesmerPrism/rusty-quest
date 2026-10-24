import importlib.util
from pathlib import Path
import unittest
import os

spec = importlib.util.spec_from_file_location('phase_cli', Path(__file__).with_name('strength_phase_hotload.py'))
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class DescriptorTests(unittest.TestCase):
    def descriptor(self):
        return dict(schema='rusty.quest.hotload_current_session.v1', host_kind='bilateral-strength-phase',
                    session_id='123-abcdef', owner_token='strength-owner-123-abcdef', pid=123, directory='123-abcdef')

    def test_actual_closed_descriptor(self):
        value = self.descriptor()
        self.assertEqual(cli.validate_descriptor(value, 123, '123-abcdef', value['owner_token']), value)

    def test_refuses_wrong_process_owner_kind_directory_or_extra_fields(self):
        for field, replacement in [('pid',124),('pid',True),('session_id','different'),('owner_token','other'),
                                   ('host_kind','native-openxr'),('directory','../escape'),('schema','unknown'),('extra','x')]:
            value = self.descriptor()
            value[field] = replacement
            with self.subTest(field=field, value=replacement), self.assertRaises(ValueError):
                cli.validate_descriptor(value, 123, '123-abcdef', self.descriptor()['owner_token'])


class ObservationTests(unittest.TestCase):
    def observation(self):
        return dict(schema='rusty.quest.bilateral_strength_phase_observation.v1',request_id='request-1',
            session_id='123-abcdef',owner_token='owner',pid=123,status='ok',error=None,host={'completed':'900'},
            phase=dict(source='module',effective_phase=.25,adopted_module_generation='1',phase_call_count='4'))

    def test_accepts_separate_phase_evidence(self):
        value=self.observation()
        self.assertEqual(cli.validate_observation(value,'request-1',123,'123-abcdef','owner'),value)

    def test_abi_counter_does_not_override_invalid_semantic_output(self):
        value=self.observation()
        value['phase'].update(source='invalid_output',effective_phase=.5,adopted_module_generation='0')
        self.assertEqual(cli.validate_observation(value,'request-1',123,'123-abcdef','owner')['phase']['source'],'invalid_output')

    def test_refuses_unjoined_identity_or_invalid_phase(self):
        cases=[]
        for field,replacement in [('request_id','other'),('pid',True),('owner_token','other'),('session_id','other'),('schema','other'),('extra',1)]:
            value=self.observation(); value[field]=replacement; cases.append(value)
        for field,replacement in [('source','native_completed'),('effective_phase',float('nan')),('effective_phase',1.0),
                                  ('effective_phase',True),('adopted_module_generation','0'),('phase_call_count','-1'),('phase_call_count',True)]:
            value=self.observation(); value['phase'][field]=replacement; cases.append(value)
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                cli.validate_observation(value,'request-1',123,'123-abcdef','owner')


class CommandTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.common=cli.load_common(Path(os.environ['PHASE_COMMON_SOURCE']))

    def test_accepts_only_real_phase_operations(self):
        for method,args in [('native_status',{}),('native_activate',dict(generation='1',sha256='a'*64,probe_result='1048576005'))]:
            value=dict(schema='rusty.quest.hotload_command.v1',request_id='request-1',method=method,arguments=args)
            self.assertEqual(cli.validate_command(self.common,value),value)

    def test_refuses_arbitrary_compute_paths_duplicate_fields_or_replay_names(self):
        for method,args in [('native_compute',{'input':'1'}),('native_activate',dict(generation='0',sha256='a'*64,probe_result='5')),
                            ('native_activate',dict(generation='1',sha256='a'*64,probe_result='5',path='/other'))]:
            value=dict(schema='rusty.quest.hotload_command.v1',request_id='request-1',method=method,arguments=args)
            with self.subTest(method=method,args=args),self.assertRaises(ValueError):
                cli.validate_command(self.common,value)
        with self.assertRaises(ValueError):
            self.common.decode('{"request_id":"one","request_id":"two"}')
        with self.assertRaises(ValueError):
            cli.validate_command(self.common,dict(schema='rusty.quest.hotload_command.v1',request_id='../other',method='native_status',arguments={}))


if __name__ == '__main__':
    unittest.main()
