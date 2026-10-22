"""Execute actual native eligibility bodies with explicit modeled host dependencies.

This fixture certifies observation/error ordering, not signed authority, Android,
physical cleanup, native linking, an APK, or a device. Crypto/callback dependencies
are models; the guard bodies and bounded journal are the production source.
"""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,pathlib,subprocess

def function(text,signature):
    start=text.index(signature);opening=text.index('{',start);depth=0
    for index in range(opening,len(text)):
        if text[index]=='{':depth+=1
        elif text[index]=='}':
            depth-=1
            if depth==0:return text[start:index+1]
    raise ValueError('Production function incomplete')

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False)
    app=root/'apps/spatial-camera-panel-android';native=app/'native-receipt/src/embedded_duplex'
    peer=(native/'peer_lifecycle.rs').read_text();remote=(native/'remote_owner_close.rs').read_text();cleanup=(native/'retained_cleanup_host.rs').read_text()
    replacements={
        'GUARD_SOURCE':json.dumps((native/'preparation_native_guard.rs').as_posix()),
        'JOURNAL_SOURCE':json.dumps((native/'preparation_diagnostic.rs').as_posix()),
        'PEER_BODY':'\n'.join(function(peer,signature) for signature in ['pub(super) fn fresh_setup_eligibility_observed(','fn fresh_setup_eligibility_inner(']),
        'RECEIVER_BODY':'\n'.join(function(remote,signature) for signature in ['fn verify_receiver_history_observed(','pub(crate) fn fresh_setup_receiver_history_observed(']),
        'CLEANUP_BODY':function(cleanup,'pub(super) fn fresh_setup_quiescence_observed('),
    }
    template=(app/'host-tests/embedded_duplex/preparation_native_guard_host.rs').read_text()
    for key,value in replacements.items():assert template.count(key)==1,key;template=template.replace(key,value)
    (out/'src').mkdir();(out/'src/lib.rs').write_text(template,encoding='utf-8')
    (out/'Cargo.toml').write_text('[package]\nname="preparation-native-guard-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\nserde={version="1",features=["derive"]}\nserde_json="1"\n',encoding='utf-8')
    argv=['cargo','test','--offline','--manifest-path',str(out/'Cargo.toml'),'--target-dir',str(out/'cargo'),'--','--test-threads=1']
    result=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (out/'rust.stdout').write_bytes(result.stdout);(out/'rust.stderr').write_bytes(result.stderr)
    def pin(path):
        raw=path.read_bytes();return dict(path=str(path),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    receipt=dict(passed=result.returncode==0,exit_code=result.returncode,argv=argv,
        production_bodies_executed=True,dependencies='modeled host slots/callbacks/crypto; real serde and production guards/journal',
        device_effects=0,apk_or_android_native_build=False,
        source_pins=[pin(native/n) for n in ['preparation_native_guard.rs','preparation_diagnostic.rs','peer_lifecycle.rs','remote_owner_close.rs','retained_cleanup_host.rs']])
    (out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    print(result.stdout.decode(errors='replace'));print(json.dumps(receipt));sys.exit(result.returncode)
if __name__=='__main__':main()
