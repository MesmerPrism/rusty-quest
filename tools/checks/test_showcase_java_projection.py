"""Static closure tests; deliberately invokes no Java/Rust/Android compiler."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('producer',ROOT/'tools/produce_showcase_java.py')
PRODUCER=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(PRODUCER)

class ShowcaseProjectionTest(unittest.TestCase):
    def test_selected_closure_removes_recorders_and_preserves_acquisition(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)
            records=PRODUCER.produce(ROOT,output)
            self.assertEqual(6,len(records))
            java='\n'.join(path.read_text(encoding='utf-8') for path in output.glob('*.java'))
            for forbidden in ('openFileOutput(', 'FileOutputStream','new File(', 'nativeStartParallelBreathCapture','nativeStopParallelBreathCapture','nativeReadParallelBreathCaptureStatus','"recordings"','"breath-monitor"','ExperimentSession','NativeRendererSelfKioskApplication'):
                self.assertNotIn(forbidden,java)
            for required in ('nativeSubmitPolarAccMeasurement(', 'nativeSubmitPolarRrMeasurement(', 'startScan()', 'connectSelected()', 'RQG1-REPLY|','nonces.add(nonce)','owner.allowedHost','9a7b2101-',':8767/v1/gateway','visual-showcase'):
                self.assertIn(required,java)
            first={path.name:path.read_bytes() for path in output.iterdir()}
            PRODUCER.produce(ROOT,output)
            self.assertEqual(first,{path.name:path.read_bytes() for path in output.iterdir()})

    def test_changed_capture_producer_fails_closed(self):
        source=ROOT/'apps/native-renderer-android/panel-modules/polar/src/main/java'/PRODUCER.PACKAGE/'PolarSensorPanel.java'
        text=source.read_text(encoding='utf-8').replace('sectionTitle("Synchronized capture")','sectionTitle("Capture changed")')
        with self.assertRaises(ValueError):PRODUCER.project(source.name,text)

    def test_new_persistent_sink_fails_closed(self):
        source=ROOT/'apps/native-renderer-android/panel-modules/polar/src/main/java'/PRODUCER.PACKAGE/'PolarSensorPanel.java'
        text=source.read_text(encoding='utf-8')+'\n// unexpected openFileOutput( sink\n'
        with self.assertRaises(ValueError):PRODUCER.project(source.name,text)

    def test_native_panel_uses_same_command_authority(self):
        panel=ROOT/'apps/native-renderer-android/panel-modules/showcase/src/main/java'/PRODUCER.PACKAGE/'ShowcaseControls.java'
        text=panel.read_text(encoding='utf-8')
        self.assertIn('ShowcaseNativeBridge.command(command)',text)
        self.assertIn('expected_revision',text)
        self.assertIn('send("apply-visual",visualPayload(state.visual),true)',text)
        self.assertNotIn('polar.buildView()',text)
        self.assertNotIn('handleCommand(',text)

if __name__=='__main__':unittest.main()
