"""Behavioral controls for selected source/compiler/class/DEX joins."""
import sys
sys.dont_write_bytecode = True
import importlib.util, json, pathlib, subprocess, tempfile, unittest
MODULE = pathlib.Path(__file__).parents[1] / "lib/android_component_closure.py"
spec = importlib.util.spec_from_file_location("closure", MODULE)
closure = importlib.util.module_from_spec(spec); spec.loader.exec_module(closure)

class SelectedSourceTests(unittest.TestCase):
    def test_feedback_selection_is_exact_and_payload_sources_stay_out(self):
        root=self.root/'feedback';root.mkdir()
        for relative in closure.DEVELOPMENT_FEEDBACK_SOURCES:
            path=root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('package fixture; class Selected {}')
        for relative in ['host/java/HostUrlLoader.java','android/java/org/rusty/quest/feedback/NativeFeedbackBridge.java','fixtures/Policy.java']:
            path=root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('class Excluded {}')
        rows=closure.inventory_sources({'development_feedback':root},'debug')
        self.assertEqual(set(closure.DEVELOPMENT_FEEDBACK_SOURCES),{x['path'] for x in rows})
        self.assertEqual(7,len(rows))
        original=rows[0]['sha256'];(root/rows[0]['path']).write_text('class Changed {}')
        changed=closure.inventory_sources({'development_feedback':root},'debug')
        self.assertNotEqual(original,changed[0]['sha256'])
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name); self.sources = self.root / "private"; self.sources.mkdir()
        (self.sources / "Owner.kt").write_text("package fixture\nobject Owner { @JvmStatic fun status() {} }\n")
        self.lock = self.root / "lock.json"
        self.lock.write_text(json.dumps({"selected_features":["feature.owner"],"features":[
            {"feature_id":"feature.owner","module_id":"owner","owner_lane":"private","selected":True}]}))
        self.contract = self.root / "contract.json"
        self.value = {"schema":"rusty.quest.android_component_contract.v1","components":[
            {"component_id":"owner", "module_id":"owner", "owner_lane":"private", "definitions":[
                {"root":"private","source":"Owner.kt","class":"fixture.Owner", "methods":[
                    {"name":"status", "descriptor":"()V", "public":True, "static":True}]}]}]}
        self.contract.write_text(json.dumps(self.value))
    def prepare(self):
        self.contract.write_text(json.dumps(self.value))
        return closure.prepare(self.contract,self.lock,closure.file_sha(self.lock),{"private":self.sources},"debug")
    def test_selected_requirements_and_warm_add_change_update_inventory(self):
        first=self.prepare(); self.assertEqual(["owner"],first["selected_components"])
        self.assertEqual("fixture.Owner",first["definitions"][0]["class"])
        added=self.sources/"Added.kt";added.write_text("package fixture\nclass Added\n")
        second=self.prepare(); self.assertNotEqual(first["fingerprint"],second["fingerprint"])
        self.assertEqual(2,len(second["sources"]))
        added.write_text("package fixture\nclass Added { fun changed() = 7 }\n")
        self.assertNotEqual(second["fingerprint"],self.prepare()["fingerprint"])
    def test_unselected_component_adds_no_requirements_but_selected_module_must_be_covered(self):
        other=dict(self.value["components"][0],component_id="other",module_id="disabled")
        self.value["components"].append(other)
        self.assertEqual(["owner"],self.prepare()["selected_components"])
        self.value["components"]=[]
        with self.assertRaisesRegex(ValueError,"component-coverage"):self.prepare()
    def test_duplicate_mapping_or_missing_source_or_escape_refuses(self):
        self.value["components"].append(self.value["components"][0].copy())
        with self.assertRaises(ValueError):self.prepare()
        self.value["components"]=self.value["components"][:1]
        d=self.value["components"][0]["definitions"][0]
        for path in ["Missing.kt","../Owner.kt","/Owner.kt"]:
            d["source"]=path
            with self.assertRaises(ValueError):self.prepare()
    def test_actual_gradle_input_join_refuses_missing_or_stale_private_source(self):
        inventory=self.prepare()
        actual={"schema":"rusty.quest.android_component_gradle_inputs.v1",
            "inventory_sha256":closure.json_sha(inventory),"sources":[],"class_roots":[]}
        with self.assertRaisesRegex(ValueError,"compiler-input"):closure.verify_inputs(inventory,actual)
        actual["sources"]=[dict(inventory["sources"][0])]
        closure.verify_inputs(inventory,actual)
        actual["sources"][0]["sha256"]="0"*64
        with self.assertRaisesRegex(ValueError,"compiler-input"):closure.verify_inputs(inventory,actual)
    def test_source_drift_after_compiler_receipt_refuses(self):
        inventory=self.prepare();(self.sources/"Owner.kt").write_text("package fixture\nobject Owner\n")
        with self.assertRaisesRegex(ValueError,"source-drift"):closure.verify_current_sources(inventory)
    def test_source_only_cli_keeps_original_raw_binding_and_scoped_currentness(self):
        inventory=self.prepare(); path=self.root/'inventory.json';path.write_bytes(closure.canonical(inventory))
        command=[sys.executable,'-B',str(MODULE),'verify-sources','--inventory',str(path),
            '--expected-inventory-sha256',closure.file_sha(path),'--output','-']
        result=subprocess.run(command,capture_output=True)
        self.assertEqual(0,result.returncode,result.stderr)
        value=json.loads(result.stdout)
        self.assertEqual('selected-source-currentness-only',value['scope'])
        self.assertFalse(value['runtime_authority']);self.assertNotIn('class_outputs',value)
        path.write_bytes(closure.canonical(inventory)+b'\n')
        result=subprocess.run(command,capture_output=True)
        self.assertNotEqual(0,result.returncode);self.assertIn(b'prepared-inventory-changed',result.stderr)
        path.write_bytes(closure.canonical(inventory))
        (self.sources/'Owner.kt').write_text('package fixture\nobject Changed\n')
        result=subprocess.run(command,capture_output=True)
        self.assertNotEqual(0,result.returncode);self.assertIn(b'source-drift',result.stderr)
    def test_duplicate_feature_id_cannot_replace_missing_selected_feature(self):
        rows=[{"feature_id":feature,"module_id":module,"owner_lane":"private","selected":True}
            for feature,module in [("a","first"),("a","second"),("c","third")]]
        self.lock.write_text(json.dumps({"selected_features":["a","b","c"],"features":rows}))
        definition=self.value["components"][0]["definitions"]
        self.value["components"]=[{"component_id":row["module_id"],"module_id":row["module_id"],"owner_lane":"private","definitions":definition} for row in rows]
        with self.assertRaisesRegex(ValueError,"feature-selection"):self.prepare()

if __name__ == "__main__": unittest.main()
