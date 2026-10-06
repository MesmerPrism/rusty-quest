"""Extract exact incoming production methods into the separately owned host graph.
No Cargo, APK or device invocation. The graph is an explicit disposable test fixture.
"""
import argparse,json,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source-root',required=True);p.add_argument('--host-graph',required=True);p.add_argument('--output-root',required=True);a=p.parse_args()
source=Path(a.source_root);graph=Path(a.host_graph);out=Path(a.output_root)
out.mkdir(exist_ok=False)
native=source/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
text=(native/'retained_cleanup_host.rs').read_text(encoding='utf-8')
def body(marker):
 start=text.index(marker);opening=text.index('{',start);depth=0
 for i in range(opening,len(text)):
  if text[i]=='{':depth+=1
  elif text[i]=='}':
   depth-=1
   if depth==0:return text[start:i+1]
 raise ValueError('unclosed exact production body')
types=[]
for name in ['Prepare','Original','PreparedState']:
 b=body('struct '+name+' {')
 start=text.index('struct '+name+' {'); attribute_start=text.rfind('#[derive(',0,start)
 assert attribute_start>=0
 types.append(text[attribute_start:start]+b)
free='\n'.join(body('fn '+name+'(') for name in ['prepare_domain','key','digest','same_owner'])+'\n'+body('fn encode<')
methods='\n'.join(body('fn '+name+'(') for name in ['persist','require_state','projection','remote_projection'])+'\n'+body('pub(super) fn prepare_frame(')
template=(source/'tools/checks/fixtures/retained_prepare_incoming_host.rs').read_text(encoding='utf-8')
generated=template.replace('OWNER_FAILURE_SOURCE',str(native/'owner_failure.rs').replace('\\','/')).replace('PRODUCTION_TYPES','\n'.join(types)).replace('PRODUCTION_FREE_FUNCTIONS',free).replace('PRODUCTION_CLEANUP_METHODS',methods).replace('PRODUCTION_CURRENT_SOURCE',body('fn current_source('))
target=out/'incoming.rs';target.write_text(generated,encoding='utf-8',newline='\n')
original=(source/'crates/rusty-quest-broker-authority/src/coupled_renewal_tests.rs').read_text(encoding='utf-8')
anchor='let key = signer.verifying_key().to_bytes();'
assert original.count(anchor)==1
call='''native_prepare_host::exercise(receiver.clone(), sender.clone(), &cleanup, &original.0, &original.1, &request.3,
                    signer, key_id, if local=="peer.quest-a" {&beta} else {&alpha},
                    if local=="peer.quest-a" {"key.peer.quest-b.1"} else {"key.peer.quest-a.1"}, late+10);
                '''
modified=original.replace(anchor,call+anchor)+'\nmod native_prepare_host { include!("'+str(target).replace('\\','/')+'"); }\n'
(graph/'crates/rusty-quest-broker-authority/src/coupled_renewal_tests.rs').write_text(modified,encoding='utf-8',newline='\n')
(graph/'crates/rusty-quest-broker-authority/src/embedded_duplex/authority.rs').write_bytes((source/'crates/rusty-quest-broker-authority/src/embedded_duplex/authority.rs').read_bytes())
report={'scope':'Exact production incoming/replay/current_source and real separate-store authority; Java persistence, checkout and clock modeled. No physical registry effect or whole Android typecheck.',
 'generated':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'methods':['persist','require_state','projection','remote_projection','prepare_frame','current_source']}
(out/'EXTRACTION.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
