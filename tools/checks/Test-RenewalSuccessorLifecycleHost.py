#!/usr/bin/env python3
"""Connected renewal-successor fixture; desktop JNI/OS leaves are explicit.

The generated harness retains the complete production pair, renewal, prepared
start and lifecycle modules. Only Android/JNI platform boundaries are replaced.
It is not an Android, camera, codec or real network qualification.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tomllib
import queue
import threading
import time
import importlib.util

sys.dont_write_bytecode = True


def item_end(text, brace):
    depth, quote, escape = 0, False, False
    for n in range(brace, len(text)):
        c = text[n]
        if quote:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == '"':
                quote = False
        elif c == '"':
            quote = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return n + 1
    raise ValueError('Incomplete Rust item')


def jni_leaves(text):
    """Keep exact callback API/trait signatures; replace only JNI leaf bodies."""
    blocks = []
    for match in re.finditer(r'(?m)^impl(?:<[^\n]+>)? [^\n{]+\{', text):
        end = item_end(text, text.index('{', match.start()))
        body = text[match.start():end]
        changes = []
        for fn in re.finditer(r'(?m)^    (?:pub(?:\([^)]*\))? )?fn ([A-Za-z_][A-Za-z_0-9]*)', body):
            brace = body.index('{', fn.end())
            stop = item_end(body, brace)
            name = fn.group(1)
            replacement = 'panic!("unconfigured JNI primitive: ' + name + '")'
            if name == 'key_id':
                replacement = '&self.key_id'
            elif name == 'preparation_identity':
                replacement = '&self.prepared_identity'
            elif name == 'local_public_key':
                replacement = 'Ok(ed25519_dalek::SigningKey::from_bytes(&[41+self.peer as u8;32]).verifying_key().to_bytes())'
            elif name == 'load_bounded_cleanup_string':
                replacement = '''Ok(if method=="loadCleanupPreparations"{r#"{"originals":{},"prepared":{}}"#.to_owned()}else{serde_json::to_string(&rusty_quest_media_stream_android::RetainedCleanupReplaySnapshot::default()).unwrap()})'''
            elif name == 'current_executor_generation':
                replacement = 'self.capability.require_live()?;Ok(self.capability.generation)'
            elif name == 'persist_replay':
                original=body[brace+1:stop-1]
                prefix=original[:original.index('let mut env =')]
                replacement = prefix+'self.capability.require_live()?;super::runtime_host::fixture::persist(self.peer,method,&snapshot_json)'
            elif name == 'current_operator_original_id':
                replacement = 'super::runtime_host::fixture::original(self.peer)'
            elif name == 'sign_callback':
                original=body[brace+1:stop-1]
                # Keep the actual production pre-JNI guard/error byte-for-byte.
                guard=original[:original.index('let mut env =')]
                replacement = '''super::runtime_host::fixture::signing_boundary(self.peer,method,message,MAX_AUTHORITY_SIGNING_BYTES);'''+guard+'''use ed25519_dalek::Signer;Ok(ed25519_dalek::SigningKey::from_bytes(&[41+self.peer as u8;32]).sign(message).to_bytes())'''
            elif name == 'fresh_closure_callback':
                original=body[brace+1:stop-1]
                prefix=original[:original.index('let mut env =')]
                replacement = prefix+'''let value=if method=="persistManifoldFreshHistoryArchive"{super::runtime_host::fixture::history_ack(self.peer,input)?}else{super::runtime_host::fixture::java(self.peer,method,serde_json::json!({"input":input}))?};let text=serde_json::to_string(&value).map_err(|_|"fresh closure callback result JSON")?;if text.len()>128*1024{return Err("fresh closure callback result bounds".into())}Ok(value)'''
            elif name == 'prepared_readonly_callback':
                replacement = 'super::runtime_host::fixture::java(self.peer,method,serde_json::json!({}))'
            elif name == 'exchange':
                original=body[brace+1:stop-1]
                prefix=original[:original.index('let mut env =')]
                replacement = prefix+'''let bytes=super::runtime_host::fixture::exchange(self.peer,request_frame)?;if bytes.is_empty() || bytes.len()>max_response_bytes{return Err("java_bridge.exchange_output_bounds".to_owned())}Ok(bytes)'''
            elif name == 'owner_failure_diagnostic':
                replacement = 'Err("modeled platform diagnostic unavailable".into())'
            elif name == 'owner_transport_attempt_read':
                replacement = 'super::owner_transport_attempt_read::snapshot(r#"{"attempt_sequence":0,"phase":"NONE","outcome":"NONE","failure":"NONE"}"#)'
            elif name == 'incoming_prepared_start_stop_sequence':
                replacement = 'Ok(0)'
            elif name in ['incoming_prepared_start_stop_requested','incoming_prepared_start_stop_requested_for','peer_root_desired_stop_requested']:
                replacement = 'Ok(false)'
            elif name == 'incoming_physical':
                replacement = 'super::runtime_host::fixture::java(self.peer,method,serde_json::json!({"native":native,"phase":phase,"preparation":preparation}))'
            elif name == 'fresh_setup_physical_eligibility':
                replacement = 'super::runtime_host::fixture::java(self.peer,"freshSetupPhysicalEligibilityJson",serde_json::json!({}))'
            elif name in ['bind_fresh_setup_original','bind_incoming_fresh_setup','bind_fresh_setup_context','bind_fresh_setup_operator_phase','bind_incoming_fresh_setup_phase','bind_fresh_setup_phase_context','stage_incoming_completed_fresh_setup_successor','bind_incoming_prepared_start']:
                # These are explicit Java scheduling/RequestSpan leaves in this
                # native trace, not a replacement for the separate Java epoch
                # callback tests. The native owner invokes them in real order.
                original=body[brace+1:stop-1]
                check=original[:original.index('let mut env =')] if 'let mut env =' in original else 'self.capability.require_live()?;'
                replacement = check+'Ok(())'
            elif name == 'execute_json_callback':
                original=body[brace+1:stop-1]
                prefix=original[:original.index('let mut env =')]
                replacement = prefix+'super::runtime_host::fixture::execute(self.peer,ticket,compensate)'
            elif name == 'activate':
                replacement = '''Ok(ProductActivationReadback{schema_id:"rusty.quest.android.media.product_activation_readback.v1".into(),activation_id:activation_id.into(),route_grant_id:authority.route_grant_id.clone(),resulting_state_revision:1,activated:true})'''
            elif name in ['load_cleanup_preparations','load_retained_cleanup_replay','persist_cleanup_preparations','commit','sign','sign_validated_common_lan','sign_validated_fresh_pair_setup','sign_validated_fresh_phase_prepare','sign_fresh_setup_frame','sign_pair_ceremony','sign_prepared_start_frame','finalize_prepared_transport','prepare_sender','verify_sender','prepare_decoder','verify_decoder','prepared_registry_status','archive_completed_prepared_start','archive_completed_peer_lifetime','archive_fresh_setup_phase','execute_and_verify','execute_and_verify_java','prepared_start_eligibility','peer_start_resource_target','fresh_setup_initial_physical','fresh_setup_registered_physical','fresh_setup_preparation_physical','persist_manifold_fresh_history_archive','complete_fresh_setup_continuation_handoff','complete_incoming_fresh_setup_handoff']:
                continue
            changes.append((brace + 1, stop - 1, replacement))
        for start, stop, replacement in reversed(changes):
            body = body[:start] + replacement + body[stop:]
        blocks.append(body)
    imports = text[:text.index('const MAX_OWNER_FRAME_BYTES')]
    struct = '''#[derive(Clone)]
pub(crate) struct JavaOwnerCallbacks {
 capability: Arc<NativeCapability>,key_id:String,target_peer_id:String,prepared_identity:Arc<()>,peer:usize
}
'''
    constants=text[text.index('const MAX_OWNER_FRAME_BYTES'):text.index('/// App-owned Java callbacks')]
    helper=source_item(text,'fn prepared_registry_readback(')
    return imports + constants + struct + '\n'.join(blocks)+helper+'''
impl JavaOwnerCallbacks{
 pub(crate) fn fixture(peer:usize,capability:Arc<NativeCapability>,key_id:String,target_peer_id:String)->Self{
  Self{peer,capability,key_id,target_peer_id,prepared_identity:Arc::new(())}
 }
}
'''


def source_item(text, signature):
    start=text.index(signature)
    return text[start:item_end(text,text.index('{',start))]


def connected_scenario(root,out,executable,args,expected_red):
    scenario_started=time.monotonic()
    helper_path=root/'tools/checks/renewal_successor_java_support.py'
    spec=importlib.util.spec_from_file_location('renewal_java_owner',helper_path)
    helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
    consumer=None
    if args.projection_consumer:
        consumer_path=pathlib.Path(args.projection_consumer).resolve()
        consumer_spec=importlib.util.spec_from_file_location('actual_renewal_consumer',consumer_path)
        consumer=importlib.util.module_from_spec(consumer_spec);consumer_spec.loader.exec_module(consumer)
        (out/'ACTUAL_PROJECTION_CONSUMER.json').write_text(json.dumps({'path':str(consumer_path),
            'bytes':consumer_path.stat().st_size,'sha256':hashlib.sha256(consumer_path.read_bytes()).hexdigest()},indent=2)+'\n')

    if args.java_build:
        selected=pathlib.Path(args.java_build).resolve();selected.relative_to(root/'target')
        built=json.loads(selected.read_text())
        for pin in built['source_refs']:
            data=pathlib.Path(pin['path']).read_bytes()
            assert len(data)==pin['bytes']and hashlib.sha256(data).hexdigest()==pin['sha256'],'Retained Java source drift'
        assert pathlib.Path(built['classes']).is_dir()
        (out/'JAVA_BUILD_REUSE.json').write_text(json.dumps({'result':str(selected),'sha256':hashlib.sha256(selected.read_bytes()).hexdigest(),'scope':'Reusing exact source-bound local component classes; no new Java compile claim'},indent=2)+'\n')
    else:
        built=helper.build_java_support(quest_root=root,input_spec=pathlib.Path(args.input_spec),
            support_classes=pathlib.Path(args.support_classes),output=out/'java',json_jar=pathlib.Path(args.json_jar))
    stderr=open(out/'native.stderr','xb');raw=open(out/'native.stdout','xb')
    native=subprocess.Popen([str(executable)],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr)
    events=queue.Queue()
    def read():
        for line in iter(native.stdout.readline,b''):
            raw.write(line);raw.flush()
            try:events.put(json.loads(line))
            except (UnicodeError,json.JSONDecodeError):events.put({'kind':'nonprotocol','line':line.decode(errors='replace')})
        events.put({'kind':'eof'})
    reader=threading.Thread(target=read);reader.start()
    peers={};bridges={};trace=[];counter=0
    mode=args.scenario
    def send(value):
        native.stdin.write((json.dumps(value,separators=(',',':'))+'\n').encode());native.stdin.flush()
    def event(deadline):
        wait=deadline-time.monotonic()
        if wait<=0:raise TimeoutError('Connected owner fixture deadline exceeded')
        return events.get(timeout=wait)
    def commands(operations):
        nonlocal counter
        waiting={};results={}
        for peer,operation,original,extra in operations:
            counter+=1;waiting[counter]=peer
            bridges[peer].set_original(original)
            send(dict(kind='command',id=counter,peer=peer,operation=operation,original_id=original,**extra))
        deadline=time.monotonic()+90
        while waiting:
            value=event(deadline);trace.append(value)
            if value['kind']=='callback':
                try:
                    if mode=='archive-refusal'and value['peer']=='W'and value['method']=='archiveFreshSetupPhase':
                        result={'error':'modeled durable phase archive failure'}
                    else:
                        result=bridges[value['peer']].call(value['method'],value['payload'])
                        if value['peer']=='W'and value['method']=='archiveFreshSetupPhase':
                            if mode=='state-drift':require(commands([('W','drift',second['W'],{})]))
                            if mode=='concurrent-stop':
                                observed=require(commands([('W','request_fresh_stop',second['W'],{})]))['W']
                                assert observed['desired_stop_latched']is True and observed['transaction_outcome']=='unknown',observed
                except Exception as error:
                    result={'error':{'type':type(error).__name__,'message':str(error)}}
                    trace.append({'kind':'callback_refusal','id':value['id'],'peer':value['peer'],'result':result})
                send(dict(kind='callback_reply',id=value['id'],result=result))
            elif value['kind']=='result':
                peer=waiting.pop(value['id']);results[peer]=value['result']
            elif value['kind']=='eof':raise RuntimeError('Native fixture exited before actual command result')
        return results
    def begin(peer,operation,n):
        original=f'{n:032x}';challenge=f'{n+1000:032x}'
        result=peers[peer].call('operation_begin',dict(original_id=original,operation=operation,challenge=challenge,request={}))
        assert result['state']=='pending',result
        return original
    def require(results):
        for peer,value in results.items():assert 'error'not in value,(peer,value)
        return results
    def complete(peer,original,operation,receipt,sdk=False):
        result=peers[peer].call('sdk_complete' if sdk else 'operation_complete',
            dict(original_id=original,receipt=receipt,**({}if sdk else {'wrapper_action':operation})))
        assert result['state']=='succeeded'and result['effect_state']=='settled',result
        return result
    def ack(peer,original,receipt,kind):
        observation=peers[peer].call('operation_query',{'original_id':original})
        assert observation['state']=='succeeded'and observation['effect_state']=='settled',observation
        consumer={'schema':'rusty.quest.host.renewal_successor_consumer.v1','kind':kind,'original_id':original,
                  'actor':bridges[peer].actor,'request_sha256':observation['request_sha256'],
                  'outcome_sha256':observation['outcome_sha256'],'actual_native_receipt':receipt}
        path=out/f'{peer}-{kind}-{original}.consumer.json'
        encoded=json.dumps(consumer,sort_keys=True,separators=(',',':')).encode()
        with open(path,'xb')as stream:stream.write(encoded);stream.flush();os.fsync(stream.fileno())
        result=peers[peer].call('operation_ack',{'original_id':original,'consumer_sha256':hashlib.sha256(encoded).hexdigest()})
        assert result['retention']=='compacted'and result['effect_state']=='settled',result
        return result
    def qualify_exports(peer,value,original):
        if consumer is None:raise ValueError('Actual selected Python projection consumer required')
        expanded=consumer.expand_receipt(value)
        proofs=[]
        for wire in value.get('renewal_receipts',[]):
            restored=consumer.expand_renewal(wire)
            actual=require(commands([(peer,'projection_roundtrip',original,{'wire':wire})]))[peer]
            assert actual['full_retained_equal']is True and restored==actual['full'],actual
            proofs.append(actual)
            if wire.get('$schema')=='rusty.quest.concurrent.authority_renewal_projection.v1':
                import copy
                for kind in ['hash','reference','version']:
                    wrong=copy.deepcopy(wire)
                    if kind=='hash':wrong['receipt_sha256']='sha256:'+'0'*64
                    elif kind=='reference':wrong['receipt']['continuation_checkpoint']['baseline']={'$schema':'rusty.quest.concurrent.media_authority_reference.v99'}
                    else:wrong['$schema']='rusty.quest.concurrent.authority_renewal_projection.v99'
                    try:consumer.expand_renewal(wrong)
                    except (ValueError,KeyError,TypeError):pass
                    else:raise AssertionError('Actual consumer accepted malformed '+kind)
                    refused=commands([(peer,'projection_roundtrip',original,{'wire':wrong})])[peer]
                    assert 'error'in refused,refused
        return {'expanded':expanded,'actual_full_roundtrips':proofs}
    stages=[]
    try:
        ready=event(time.monotonic()+20);assert ready['kind']=='ready',ready
        for index,peer in enumerate(['Q','W']):
            record=ready['result']['actual_two_role_construction'][index]
            peers[peer]=helper.JavaPeerRPC(built,peer=peer,actor=record['actor'],root=out/(peer+'-private'),output=out/(peer+'-jvm'))
            bridges[peer]=helper.NativeCallbackBridge(peers[peer],actor=record['actor'],initialized=record['initialized'])
        initial={p:begin(p,'prepare_peer_start',1+i)for i,p in enumerate(peers)}
        sdk=require(commands([(p,'sdk',initial[p],{})for p in peers]));stages.append('actual_initial_sdk')
        for p in peers:
            assert sdk[p]['state']=='ready'and sdk[p]['local_start_consumed']is False,sdk[p]
            complete(p,initial[p],'prepare_peer_start',sdk[p],sdk=True);ack(p,initial[p],sdk[p],'initial-sdk')
        first_pair=begin('Q','pair_session',3)
        pair=require(commands([('Q','pair',first_pair,{})]))['Q'];stages.append('actual_initial_fresh_pair')
        complete('Q',first_pair,'pair_session',pair);ack('Q',first_pair,pair,'first-pair')
        starts={p:begin(p,'start',4+i)for i,p in enumerate(peers)}
        started=require(commands([(p,'start',starts[p],{})for p in peers]));stages.append('actual_first_start')
        if args.freshness_hooks:
            duplicate_start=require(commands([('Q','start',starts['Q'],{})]))['Q']
            assert duplicate_start['start_mutation']==started['Q']['start_mutation']
            assert duplicate_start['activation']==started['Q']['activation']
        for p in peers:
            assert started[p]['status']=='active',started[p]
            complete(p,starts[p],'start',started[p])
        # Clock is a declared OS primitive; actual AuthorityClock/sample and
        # all signed owner expiry predicates still execute on these samples.
        require(commands([('Q','advance',starts['Q'],{'wall_ms':11_000})]))
        renewal=begin('Q','renew_authority',6)
        renewed=require(commands([('Q','renew',renewal,{})]))['Q'];stages.append('actual_coupled_renewal')
        assert renewed['renewal_total_completed']==1 and renewed['renewal_pending']is False,renewed
        complete('Q',renewal,'renew_authority',renewed)
        renewal_states=require(commands([(p,'snapshot',starts[p],{})for p in peers]))
        (out/'AFTER_RENEWAL_ACTUAL_STATE.json').write_text(json.dumps(renewal_states,indent=2)+'\n')
        duplicate=require(commands([('Q','replay_renewal',renewal,{})]))['Q']
        assert duplicate.get('exact_reply')is True,duplicate
        assert renewal_states==require(commands([(p,'snapshot',starts[p],{})for p in peers])),'Final renewal retry changed actual owners'
        stages.append('exact_completed_responder_reply_retry_without_effects')
        stops={p:begin(p,'peer_stop',7+i)for i,p in enumerate(peers)}
        stopped=require(commands([(p,'stop',stops[p],{})for p in peers]));stages.append('actual_first_normal_stop')
        for p in peers:
            value=stopped[p]
            assert value['status']=='terminal'and value['termination_action']=='stop'and value['renewal_pending']is False,value
            assert len(value['media_stop_effect_receipt']['owner_receipt_ids'])==7,value
            assert value['route_cleanup']is not None and value['peer_physical_cleanup']=='terminal',value
            complete(p,stops[p],'peer_stop',value);ack(p,stops[p],value,'first-stop')
            ack(p,starts[p],started[p],'first-start-after-stop')
        ack('Q',renewal,renewed,'renewal-after-stop');stages.append('actual_deferred_original_acks')
        if mode!='retained-red':
            controls=require(commands([('W','controls',stops['W'],{'session':pair['session_id']})]))['W']
            assert controls['pair']['actual_state_preserved']and controls['lifetime']['actual_state_preserved'],controls
            (out/'NEGATIVE_CONTROLS.json').write_text(json.dumps(controls,indent=2)+'\n')
        for lifetime in range(2,4):
            cycle_trace=len(trace)
            base=9+(lifetime-2)*8
            second={p:begin(p,'prepare_peer_start',base+i)for i,p in enumerate(peers)}
            prepared=require(commands([(p,'sdk',second[p],{})for p in peers]));stages.append(f'actual_lifetime_{lifetime}_sdk_and_archive')
            for p in peers:
                assert prepared[p]['state']=='ready'and prepared[p]['preparation_id']!=sdk[p]['preparation_id'],prepared[p]
                complete(p,second[p],'prepare_peer_start',prepared[p],sdk=True);ack(p,second[p],prepared[p],'second-sdk')
            retained=renewal_states['W']['actual_pair_state']['renewal']['result']
            expiry=max(renewed['authority_projection']['expires_at_ms'],
                       retained['local_session_renewal']['expires_at_ms'],
                       retained['remote_session_renewal']['expires_at_ms'])
            require(commands([('Q','advance',second['Q'],{'wall_ms':expiry+1})]))
            before_successor=require(commands([(p,'snapshot',second[p],{})for p in peers]))
            next_pair=begin('Q','pair_session',base+2)
            successor=commands([('Q','pair',next_pair,{})])['Q']
            if expected_red:
                assert 'fresh setup cannot replace a retained renewal' in str(successor),successor
                return {'reproduced_retained_renewal':True,'stages':stages,'successor':successor,'trace':trace}
            if mode in ['archive-refusal','state-drift','concurrent-stop']:
                assert 'error'in successor,successor
                after=require(commands([('W','snapshot',second['W'],{})]))['W']
                prior=before_successor['W']['actual_pair_state'];current=after['actual_pair_state']
                assert current['renewal']['id']==prior['renewal']['id']and current['renewal']['replies']==prior['renewal']['replies']
                assert current['renewal_ledger']==prior['renewal_ledger']and current['fresh']['history']==prior['fresh']['history']
                if mode=='concurrent-stop':assert 'Stop'in successor['error'],successor
                (out/'REFUSAL_STATE.json').write_text(json.dumps({'before':before_successor['W'],'after':after},indent=2)+'\n')
                return {'passed':True,'expected_refusal':mode,'stages':stages,'successor':successor,'trace':trace,
                        'limit':'No successor completion/ACK or second Start; partial durable custody is preserved, never rolled back'}
            assert 'error'not in successor,successor
            assert successor['session_id']!=pair['session_id'],'Fresh successor reused still-current prior Pair'
            complete('Q',next_pair,'pair_session',successor);ack('Q',next_pair,successor,'successor-pair')
            after_successor=require(commands([(p,'snapshot',next_pair if p=='Q'else second[p],{})for p in peers]))
            (out/f'LIFETIME_{lifetime}_AFTER_SUCCESSOR_ACTUAL_STATE.json').write_text(json.dumps(after_successor,indent=2)+'\n')
            assert after_successor['W']['actual_pair_state']['renewal']==renewal_states['Q']['actual_pair_state']['renewal']
            assert after_successor['W']['actual_pair_state']['renewal_ledger']['ongoing_next']==renewal_states['W']['actual_pair_state']['renewal_ledger']['ongoing_next']+1
            archives=[json.loads(row['payload']['input'])for row in trace[cycle_trace:] if row.get('kind')=='callback'
                      and row.get('peer')=='W'and row.get('method')=='archiveFreshSetupPhase']
            assert len(archives)==1,archives
            transferred=archives[0]['phase_state']['completed_renewal_custody']
            assert transferred['renewal_state']==renewal_states['W']['actual_pair_state']['renewal']
            assert transferred['provider_join']['terminal_lifetime']==dict(kind='normal_peer_stop',original_id=stops['W'],receipt=stopped['W'])
            replay=require(commands([('Q','replay_renewal',next_pair,{})]))['Q']
            assert 'rejected'in replay,replay
            stages.append(f'actual_lifetime_{lifetime}_expired_pair_successor')
            second_starts={p:begin(p,'start',base+3+i)for i,p in enumerate(peers)}
            active=require(commands([(p,'start',second_starts[p],{})for p in peers]));stages.append(f'actual_lifetime_{lifetime}_start')
            for p in peers:
                assert active[p]['status']=='active',active[p]
                complete(p,second_starts[p],'start',active[p])
            # The same actual owners retain all first-lifetime/archive history.
            # This step was missing from the previous connected scenario.
            require(commands([('Q','advance',second_starts['Q'],{'wall_ms':expiry+1001})]))
            second_renewal=begin('Q','renew_authority',base+7)
            second_renewed=require(commands([('Q','renew',second_renewal,{})]))['Q']
            statuses=commands([(p,'status',second_starts[p],{})for p in peers])
            (out/f'LIFETIME_{lifetime}_RENEWAL_ACTUAL_STATE.json').write_text(json.dumps(
                {'returned':second_renewed,'statuses':statuses},indent=2)+'\n')
            boundaries={p:peers[p].call('lifecycle_boundary',{'receipt':statuses[p]}) for p in peers if 'error'not in statuses[p]}
            (out/f'LIFETIME_{lifetime}_LIFECYCLE_BOUNDARIES.json').write_text(json.dumps(boundaries,indent=2)+'\n')
            assert second_renewed['status']=='active' and second_renewed['renewal_pending']is False,second_renewed
            assert second_renewed['current_lifetime_renewal_count']==1,second_renewed
            require(statuses)
            for p in peers:
                assert boundaries[p]['validated']is True,boundaries[p]
                assert statuses[p]['status']=='active' and statuses[p]['renewal_pending']is False,statuses[p]
            stages.append(f'actual_lifetime_{lifetime}_coupled_renewal_and_lifecycle_boundary')
            proof=qualify_exports('Q',second_renewed,second_renewal)
            (out/f'LIFETIME_{lifetime}_RENEWAL_CONSUMER.json').write_text(json.dumps(proof,indent=2)+'\n')
            complete('Q',second_renewal,'renew_authority',second_renewed)
            (out/f'LIFETIME_{lifetime}_FIRST_RENEWAL.json').write_text(json.dumps(second_renewed,indent=2)+'\n')
            additional=[]
            for within in range(2,args.same_lifetime_renewals+1) if lifetime==2 else []:
                require(commands([('Q','advance',second_starts['Q'],{'wall_ms':expiry+within*1000+1})]))
                extra=begin('Q','renew_authority',100+within)
                extra_result=require(commands([('Q','renew',extra,{})]))['Q']
                assert extra_result['status']=='active' and extra_result['renewal_pending']is False,extra_result
                assert extra_result['current_lifetime_renewal_count']==within,extra_result
                per_renew_status=require(commands([(p,'status',second_starts[p],{})for p in peers]))
                per_renew_java={p:peers[p].call('lifecycle_boundary',{'receipt':per_renew_status[p]})for p in peers}
                for p in peers:
                    assert per_renew_java[p].get('validated')is True,per_renew_java[p]
                    qualify_exports(p,per_renew_status[p],second_starts[p])
                (out/f'LIFETIME_{lifetime}_RENEWAL_{within}_STATUS.json').write_text(json.dumps({
                    'actual_native_status':per_renew_status,'actual_Java_boundary':per_renew_java},indent=2)+'\n')
                extra_proof=qualify_exports('Q',extra_result,extra)
                (out/f'LIFETIME_{lifetime}_RENEWAL_{within}_CONSUMER.json').write_text(json.dumps(extra_proof,indent=2)+'\n')
                complete('Q',extra,'renew_authority',extra_result)
                additional.append((extra,extra_result))
                second_renewed=extra_result
            renewal_states=require(commands([(p,'snapshot',second_starts[p],{})for p in peers]))
            duplicate=require(commands([('Q','replay_renewal',second_renewal,{})]))['Q']
            assert duplicate.get('exact_reply')is True,duplicate
            assert renewal_states==require(commands([(p,'snapshot',second_starts[p],{})for p in peers]))
            capture_status=require(commands([(p,'status',second_starts[p],{})for p in peers]))
            capture_boundary={}
            for p in peers:
                value=capture_status[p]
                assert value['status']=='active'and value['renewal_pending']is False,value
                assert value['authority_projection']['expires_at_ms']>value['observed_at_ms'],value
                qualify_exports(p,value,second_starts[p])
                capture_boundary[p]=peers[p].call('lifecycle_boundary',{'receipt':value})
                assert capture_boundary[p].get('validated')is True,capture_boundary[p]
            (out/f'LIFETIME_{lifetime}_CAPTURE_BOUNDARY.json').write_text(json.dumps({
                'actual_statuses':capture_status,'actual_Java_validation':capture_boundary,
                'scope':'Host-only capture admission observations; no frames, GPU counters or pixel acceptance'},indent=2)+'\n')
            final_stops={p:begin(p,'peer_stop',base+5+i)for i,p in enumerate(peers)}
            terminal=require(commands([(p,'stop',final_stops[p],{})for p in peers]));stages.append(f'actual_lifetime_{lifetime}_normal_stop')
            for p in peers:
                assert terminal[p]['status']=='terminal'and terminal[p]['termination_action']=='stop'and terminal[p]['renewal_pending']is False,terminal[p]
                assert len(terminal[p]['media_stop_effect_receipt']['owner_receipt_ids'])==7 and terminal[p]['peer_physical_cleanup']=='terminal',terminal[p]
                qualify_exports(p,terminal[p],final_stops[p])
                complete(p,final_stops[p],'peer_stop',terminal[p]);ack(p,final_stops[p],terminal[p],'second-stop')
                ack(p,second_starts[p],active[p],'second-start-after-stop')
            ack('Q',second_renewal,json.loads((out/f'LIFETIME_{lifetime}_FIRST_RENEWAL.json').read_text()),f'lifetime-{lifetime}-renewal-after-stop')
            for extra,extra_result in additional:ack('Q',extra,extra_result,f'lifetime-{lifetime}-extra-{extra}-renewal-after-stop')
            stages.append(f'actual_lifetime_{lifetime}_deferred_renewal_ack')
            sizes=[{k:v for k,v in row.items()if k not in ['message_hex','raw']}for row in trace[cycle_trace:]
                   if row.get('kind')in ['signing_boundary','frame_boundary','frame_return','native_return_boundary']]
            (out/f'LIFETIME_{lifetime}_COMPONENT_SIZES.json').write_text(json.dumps(sizes,indent=2)+'\n')
            sdk,starts,stops,stopped,renewed,pair=prepared,second_starts,final_stops,terminal,second_renewed,successor
        views=require(commands([('W','view_controls',stops['W'],{})]))['W']
        (out/'ACTUAL_VIEW_GATE_CONTROLS.json').write_text(json.dumps(views,indent=2)+'\n')
        assert views['actual_state_preserved']is True,views
        if args.freshness_hooks:
            hooks=[row for row in trace if row.get('kind')=='freshness_hook']
            for peer in peers:
                completed=[row for row in hooks if row['peer']==peer and row['hook']=='completed_start']
                admitted=[row for row in hooks if row['peer']==peer and row['hook']=='admitted_start']
                assert [row['sequence'] for row in completed]==([1,1,2,3]if peer=='Q'else[1,2,3]),completed
                assert [{key:row[key]for key in ['peer','sequence','original','action','activation']}for row in admitted]==[
                    {key:row[key]for key in ['peer','sequence','original','action','activation']}for row in completed],(admitted,completed)
                for admission in admitted:
                    before=trace.index(admission)
                    matching=[n for n,row in enumerate(trace)if row.get('kind')=='physical_owner_boundary'and row['action']==admission['action']]
                    assert matching,admission
                    # The repeated completed Start has no new physical effect.
                    earliest=next(n for n,row in enumerate(trace)if row.get('kind')=='freshness_hook'and row.get('hook')=='admitted_start'and row.get('action')==admission['action'])
                    completion=next(n for n,row in enumerate(trace)if row.get('kind')=='freshness_hook'and row.get('hook')=='completed_start'and row.get('action')==admission['action'])
                    assert earliest<min(matching)<=max(matching)<completion,(admission,matching)
                actual_returns=[json.loads(row['raw'])for row in trace if row.get('kind')=='native_return_boundary'and row['peer']==peer and row['operation']=='start']
                for hook,value in zip(completed,actual_returns):
                    assert hook['action']==value['start_mutation']['mutation']['platform_action']['action_id'],(hook,value)
                    assert hook['activation']==value['activation']['activation']['activation_id'],(hook,value)
                    assert hook['original']in [f'{n:032x}'for n in ([4,12,20]if peer=='Q'else[5,13,21])],hook
                    assert 'start_observation_original_id'not in json.dumps(value)
                for hook in [row for row in hooks if row['peer']==peer and row['hook']=='stopped']:
                    assert any(row['peer']==peer and row['hook']=='stop_requested'and row['sequence']==hook['sequence']for row in hooks),hook
                assert len([row for row in hooks if row['peer']==peer and row['hook']=='stopped'])>=3
            (out/'FRESHNESS_OWNER_HOOKS.json').write_text(json.dumps({'passed':True,'hooks':hooks,
                'scope':'Actual owner callsites and tuple, telemetry leaf spy; GPU accounting tested separately'},indent=2)+'\n')
        return {'passed':True,'stages':stages,'trace':trace}
    finally:
        forced=False
        if native.poll()is None:
            native.stdin.close()
            try:native.wait(timeout=5)
            except subprocess.TimeoutExpired:forced=True;native.kill();native.wait(timeout=5)
        reader.join(timeout=5)
        (out/'NATIVE_CLOSED.json').write_text(json.dumps({'exit':native.returncode,'reaped':native.returncode is not None,
            'stdout_eof':not reader.is_alive(),'forced_client_termination':forced},indent=2)+'\n')
        raw.close();stderr.close()
        for peer in peers.values():peer.close()
        (out/'SCENARIO_TIMING.json').write_text(json.dumps({'elapsed_seconds':time.monotonic()-scenario_started,
            'requested_total_lifetimes':3,'requested_middle_lifetime_renewals':args.same_lifetime_renewals},indent=2)+'\n')
        (out/'STAGES.json').write_text(json.dumps(stages,indent=2)+'\n')
        (out/'TRACE.json').write_text(json.dumps(trace,indent=2)+'\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True)
    p.add_argument('--compile-only', action='store_true')
    p.add_argument('--manifold-root', required=True)
    p.add_argument('--scenario',choices=['retained-red','corrected','archive-refusal','state-drift','concurrent-stop'])
    p.add_argument('--input-spec')
    p.add_argument('--support-classes')
    p.add_argument('--json-jar')
    p.add_argument('--java-build')
    p.add_argument('--same-lifetime-renewals',type=int,choices=range(2,19),default=2)
    p.add_argument('--freshness-hooks',action='store_true',help='Model telemetry leaf and check actual Start/Stop hook tuples')
    p.add_argument('--projection-consumer',help='Exact current host owner retained_renewal_projection.py, pure API only')
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(a.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    native = root / 'apps/spatial-camera-panel-android/native-receipt/src'
    manifold = pathlib.Path(a.manifold_root).resolve()
    # Exact owner sources with explicit supplier selection. Wrapper manifests
    # bind source paths only; no dependency source or algorithm is altered.
    wrappers = root / 'target/renewal-successor-dependencies01'
    wrappers.mkdir(exist_ok=True)
    workspace = tomllib.loads((root/'Cargo.toml').read_text())
    selected = {}
    def dependency(path):
        path = path.resolve()
        info = tomllib.loads((path/'Cargo.toml').read_text())
        name = info['package']['name']
        if name in selected:
            return selected[name]
        destination = wrappers/name
        destination.mkdir(exist_ok=True)
        selected[name] = destination
        raw = (path/'Cargo.toml').read_text()
        # Resolve existing path dependencies, selecting the retained Manifold
        # owner for all its crates rather than the unrelated sibling checkout.
        def bind(match):
            target=(path/match.group(1)).resolve()
            if target.parent.name=='crates' and target.name.startswith('rusty-manifold-'):
                target=manifold/'crates'/target.name
            elif target.parent.name=='crates' and target.name.startswith('rusty-quest-'):
                target=dependency(target)
            return 'path = '+json.dumps(target.as_posix())
        raw=re.sub(r'path\s*=\s*"([^"]+)"',bind,raw)
        for key,value in workspace['workspace']['package'].items():
            raw=re.sub(r'(?m)^'+re.escape(key)+r'\.workspace\s*=\s*true\s*$',key+' = '+json.dumps(value),raw)
        raw=re.sub(r'(?ms)^\[lints\]\s*\nworkspace\s*=\s*true\s*(?=\[|\Z)','',raw)
        if '[lib]' not in raw:
            raw+='\n[lib]\npath = '+json.dumps((path/'src/lib.rs').as_posix())+'\n'
        put_manifest=destination/'Cargo.toml'
        if put_manifest.exists():
            assert put_manifest.read_text()==raw, 'Selected dependency manifest changed; use a fresh source fixture namespace'
        else:
            put_manifest.write_text(raw,encoding='utf-8')
        return destination
    source = native / 'embedded_duplex'
    generated = out / 'embedded_duplex'
    shutil.copytree(source, generated)
    def put(path, value):
        path.write_text(value, encoding='utf-8')
    # Production algorithms in these modules are copied byte-exact. The four
    # replaced modules below are platform leaves, never lifecycle projections.
    put(generated / 'java_bridge.rs', jni_leaves((source / 'java_bridge.rs').read_text()))
    put(generated / 'process_fence.rs', '''use std::sync::Arc;
#[derive(Clone,Debug,Eq,PartialEq)]pub struct AppBinding{pub generation:u64,pub record_sha256:String}
#[derive(Clone)]pub struct NativeCapability{pub generation:u64,pub binding:AppBinding}
impl NativeCapability{pub fn require_live(&self)->Result<(),String>{Ok(())}pub fn retire(&self)->Result<(),String>{Ok(())}}
''')
    put(generated / 'native_fence_jni.rs', '''use super::process_fence::NativeCapability;use std::sync::Arc;
pub fn active()->Result<Arc<NativeCapability>,String>{super::runtime_host::fixture::capability()}
pub fn ownership_status()->Result<serde_json::Value,String>{Err("fixture fence not configured".into())}
pub fn generation_current(_:u64)->bool{false}
''')
    for name in ['runtime_host','peer_lifecycle']:
        raw=(generated/(name+'.rs')).read_text()
        # libc's Unix UID/clock syscalls are primitive OS leaves on Windows.
        pos=raw.index('\nuse ')
        raw=raw[:pos]+'\nuse crate::fixture_os as libc;'+raw[pos:]
        if name=='runtime_host':
            raw=raw.replace('use std::time::{Instant, SystemTime, UNIX_EPOCH};','use crate::fixture_os::{Instant, SystemTime, UNIX_EPOCH};')
        put(generated/(name+'.rs'),raw)
    for name,signature,length in [('pair_ceremony','fn random_hex()',64),('peer_lifecycle','fn entropy()',64),('prepared_start','fn id()',32),('retained_cleanup_host','fn fresh()',32)]:
        path=generated/(name+'.rs');raw=path.read_text();old=source_item(raw,signature)
        prefix=old[:old.index('{')+1]
        put(path,raw.replace(old,prefix+'Ok(crate::fixture_os::entropy('+str(length)+'))}'))
    # Two explicit modeled process singletons share no lifecycle state. Real
    # HostLease/Checkout guards still run against their role's own actual slots.
    path=generated/'runtime_host.rs';raw=path.read_text()
    old=source_item(raw,"fn process() ->")
    raw=raw.replace(old,'''fn process()->&'static Mutex<ProcessState>{
static PEERS:OnceLock<[Mutex<ProcessState>;2]>=OnceLock::new();
&PEERS.get_or_init(||[Mutex::new(ProcessState::default()),Mutex::new(ProcessState::default())])[fixture::active()]
}''')
    put(path,raw)
    # The fixture constructor uses the production owner-composition suffix.
    # APK/JNI input parsing is outside this test; the exact typed configuration
    # is constructed and accepted by the real provider initializer below.
    runtime=(source/'runtime_host.rs').read_text()
    constructor=source_item(runtime,'fn build_host(')
    suffix=constructor[constructor.index('    let placements = config'):]
    capture=source_item(suffix,'let callbacks = JavaOwnerCallbacks::capture(') if False else None
    start=suffix.index('    let callbacks = JavaOwnerCallbacks::capture(')
    end=suffix.index('    let remote_public_key =',start)
    suffix=suffix[:start]+suffix[end:]
    prefix='''fn fixture_build_host(config:QuestBrokerRuntimeConfig,bootstrap:Bootstrap,route:PackagedDuplexRoute,capability:Arc<NativeCapability>,callbacks:JavaOwnerCallbacks)->Result<(Host,String),String>{
let config_json=serde_json::to_string(&config).map_err(safe_decode)?;
let expected_sha=rusty_quest_broker_authority::packaged_json_sha256(&config_json);
let entropy="12".repeat(32);let partial:Option<Arc<partial_construction::Construction>>=None;
let own_capture_lock:Option<String>=None;
let installed_signing_certificate_sha256="a1".repeat(32);
let restored_owner_replay=false;
'''
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/renewal_successor_native.rs'
    if fixture.exists():
        path=generated/'runtime_host.rs'
        fixture_body=fixture.read_text()
        if a.freshness_hooks:
            # Only instrument the explicit physical primitive, leaving the
            # actual lifecycle and signed action/receipt algorithms intact.
            signature='pub(crate) fn execute(peer:usize,ticket:&AndroidMediaExecutionTicket,compensate:bool)->Result<AuthenticatedOwnerEffect,String>{'
            assert signature in fixture_body
            fixture_body=fixture_body.replace(signature,signature+'''
emit(&json!({"kind":"physical_owner_boundary","peer":if peer==0{"Q"}else{"W"},"action":ticket.action_id,"owner_kind":format!("{:?}",ticket.owner_kind)}));
''',1)
        put(path,path.read_text()+'\n'+prefix+suffix+'\npub(crate) mod fixture{\n'+fixture_body+'\n}\n')
    diagnostic=generated/'operation_diagnostics.rs'
    put(diagnostic,diagnostic.read_text()+'''
pub(super) fn fallback(_: &mut jni::JNIEnv<'_>,_:&str) {}
''')
    tests=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex'
    for owner,filename in [('pair_renewal','renewal_successor_pair_controls.rs'),('peer_lifecycle','renewal_successor_lifetime_controls.rs')]:
        path=generated/(owner+'.rs')
        put(path,path.read_text()+'\npub(crate) mod fixture_controls{\n'+(tests/filename).read_text().replace('//!','//')+'\n}\n')
    path=generated/'fresh_pair_setup.rs'
    put(path,path.read_text()+'\npub(crate) mod fixture_view_controls {\n'+(tests/'renewal_successor_view_controls.rs').read_text().replace('//!','//')+'\n}\n')
    path=generated/'pair_ceremony.rs'
    put(path,path.read_text()+'''\npub(super) fn fixture_view_controls(host:&Host,frames:&[(Vec<u8>,Vec<u8>)])->Result<Value,String>{fresh_pair_setup::fixture_view_controls::run(host,frames)}
pub(super) fn fixture_archive_plan(host:&Host,session:&str)->Result<Option<Value>,String>{pair_renewal::completed_for_archive(host,session)}
pub(super) fn fixture_archive_controls(host:&Host,session:&str)->Result<Value,String>{pair_renewal::fixture_controls::run(host,session)}
pub(super) fn fixture_archive_drift(host:&Host)->Result<Value,String>{pair_renewal::fixture_controls::drift(host)}
''')
    modules = ['cleanup_failure','common_lan_signing','frame_identity','history_ids','identity_jni',
               'incoming_fresh_phase','java_bridge','owner_diagnostic_read','owner_failure',
               'operation_diagnostics','owner_latest_attempt_read','owner_transport_attempt_read',
               'packaged_config','packaged_route','pair_lifetime_policy','preparation_clock',
               'preparation_native_guard','remote_owner_close','runtime_host','runtime_slot',
               'start_transaction','native_fence_jni','process_fence']
    put(generated / 'mod.rs', '\n'.join('pub(crate) mod '+n+';' for n in modules) +
        '\npub(crate) use runtime_host::{prepared_renderer_identity,PreparedRendererIdentity};\n')
    broker=(root/'crates/rusty-quest-broker-authority/src/runtime.rs').read_text()
    roots=(root/'crates/rusty-quest-broker-authority/src/source_bound_roots_tests.rs').read_text()
    imports='''use rusty_quest_broker_authority::*;
use rusty_quest_broker_admission::*;
use rusty_quest_broker_contracts::*;
use rusty_manifold_model::*;
use rusty_manifold_admission::*;
use rusty_manifold_broker_adapter::*;
use rusty_manifold_broker_product::*;
use rusty_manifold_media_session::*;
use rusty_manifold_runtime_host::*;
use rusty_manifold_peer::*;
use ed25519_dalek::{Signer,SigningKey};
use sha2::{Digest,Sha256};
use std::collections::BTreeSet;
'''
    pieces=[source_item(broker,s) for s in ['fn id(value:', 'fn identity()','fn caller()',
        'fn test_media_binding()', 'fn config(', 'fn schema(value:', 'fn sha256_hex(',
        'fn derive_grant_capabilities(']]
    pieces += ['#[derive(serde::Serialize,serde::Deserialize)]\n'+source_item(broker,'struct QuestBrokerClientLockSpec {')]
    for constant in ['QUEST_BROKER_CLIENT_LOCK_SCHEMA','QUEST_BROKER_ADMISSION_PERMISSION']:
        pieces += [re.search(r'const '+constant+r'[^;]+;',broker).group(0)]
    pieces += [source_item(roots,s) for s in ['fn keys()', 'pub(super) fn root_config(']]
    config_source=imports+'\n'.join(pieces)
    coupled=(root/'crates/rusty-quest-broker-authority/src/coupled_renewal_tests.rs').read_text()
    config_begin=coupled.index('    let mut client: QuestBrokerClientLockSpec =')
    config_end=coupled.index('    let placements =',config_begin)
    config_source+='\npub(super) fn with_renewal(mut runtime_config:QuestBrokerRuntimeConfig)->QuestBrokerRuntimeConfig{\n'+coupled[config_begin:config_end]+'runtime_config\n}\n'
    # The include remains the exact public fixture asset; its path alone is
    # relocated because this test-owned module is generated under target/.
    asset=root/'fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json'
    config_source=config_source.replace('"../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"',json.dumps(asset.as_posix()))
    put(out/'fixture_config.rs',config_source)
    app_stubs = '''
pub use rusty_quest_broker_authority::QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA;
mod own_stereo_capture_runtime {
 pub fn capture_route_selected()->bool{true}pub fn capture_claimed()->bool{true}
 pub fn own_capture_provider_requested()->bool{true}
 pub fn prepare_capture_bootstrap()->Result<bool,String>{Ok(true)}
 pub fn select_local_after_terminal(_:bool)->Result<(),String>{Ok(())}
 pub fn require_construction_physical_terminal()->Result<(),String>{Ok(())}
}
mod fixture_os{
 use std::sync::atomic::{AtomicU64,Ordering};
 pub static NOW:AtomicU64=AtomicU64::new(1_000);
 static NEXT:AtomicU64=AtomicU64::new(100);
 pub fn entropy(width:usize)->String{format!("{:0width$x}",NEXT.fetch_add(1,Ordering::SeqCst),width=width)}
 #[derive(Clone,Copy,PartialEq,Eq,PartialOrd,Ord)]pub struct Instant(u64);
 impl Instant{pub fn now()->Self{Self(NOW.load(Ordering::SeqCst))}pub fn elapsed(&self)->std::time::Duration{std::time::Duration::from_millis(NOW.load(Ordering::SeqCst)-self.0)}}
 impl std::ops::Add<std::time::Duration> for Instant{type Output=Self;fn add(self,value:std::time::Duration)->Self{Self(self.0+value.as_millis()as u64)}}
 pub struct SystemTime(u64);pub const UNIX_EPOCH:SystemTime=SystemTime(0);
 impl SystemTime{pub fn now()->Self{Self(NOW.load(Ordering::SeqCst))}pub fn duration_since(&self,prior:Self)->Result<std::time::Duration,()>{self.0.checked_sub(prior.0).map(std::time::Duration::from_millis).ok_or(())}}
 #[allow(non_camel_case_types)]pub struct timespec{pub tv_sec:i64,pub tv_nsec:i64}
 pub const CLOCK_MONOTONIC:i32=1;
 pub unsafe fn clock_gettime(_:i32,t:*mut timespec)->i32{let n=NOW.load(Ordering::SeqCst)*1_000_000;unsafe{(*t).tv_sec=(n/1_000_000_000)as i64;(*t).tv_nsec=(n%1_000_000_000)as i64;}0}
 pub unsafe fn getuid()->u32{10123}
}
mod camera_hwb_marker{pub fn log_camera_hwb_marker(_:String){}}
mod camera_hwb_probe{
 pub fn begin_prepared_peer_renderer(id: &str)->Result<(),String>{crate::embedded_duplex::prepared_renderer_identity(id)?;Ok(())}
 pub fn prepared_peer_renderer_dormant_for(id: &str)->Result<serde_json::Value,String>{crate::embedded_duplex::runtime_host::fixture::renderer(id,false)}
 pub fn prepared_peer_renderer_terminal_for(id: &str)->Result<serde_json::Value,String>{crate::embedded_duplex::runtime_host::fixture::renderer(id,true)}
}
mod prepared_peer_renderer{
 pub fn dormant_for(_: &str)->Result<serde_json::Value,String>{Err("fixture renderer not configured".into())}
 pub fn terminal_for(_: &str)->Result<serde_json::Value,String>{Err("fixture renderer not configured".into())}
}
mod embedded_duplex;
mod fixture_config;
fn main(){if let Err(error)=embedded_duplex::runtime_host::fixture::run(){panic!("{error}")}}
'''
    put(out / 'main.rs', app_stubs)
    if a.freshness_hooks:
        spy=r'''
mod spatial_stereo_qualification {
 static SEQUENCES:std::sync::Mutex<[u64;2]>=std::sync::Mutex::new([0;2]);
 fn peer()->(usize,&'static str){let n=crate::embedded_duplex::runtime_host::fixture::active();(n,if n==0{"Q"}else{"W"})}
 pub fn admitted_media_start(s:Option<u64>,o:Option<&str>,a:Option<&str>,i:Option<&str>){
  let (_,p)=peer();
  println!("{}",serde_json::json!({"kind":"freshness_hook","peer":p,"hook":"admitted_start","sequence":s,"original":o,"action":a,"activation":i}));
 }
 pub fn completed_media_start(s:Option<u64>,o:Option<&str>,a:Option<&str>,i:Option<&str>){
  let (n,p)=peer();SEQUENCES.lock().unwrap()[n]=s.unwrap_or(0);
  println!("{}",serde_json::json!({"kind":"freshness_hook","peer":p,"hook":"completed_start","sequence":s,"original":o,"action":a,"activation":i}));
 }
 pub fn media_stop_requested(){let(n,p)=peer();println!("{}",serde_json::json!({"kind":"freshness_hook","peer":p,"hook":"stop_requested","sequence":SEQUENCES.lock().unwrap()[n]}));}
 pub fn media_stopped(){let(n,p)=peer();println!("{}",serde_json::json!({"kind":"freshness_hook","peer":p,"hook":"stopped","sequence":SEQUENCES.lock().unwrap()[n]}));}
}
'''
        put(out / 'main.rs',app_stubs+spy)
    deps = ['rusty-quest-broker-authority','rusty-quest-media-stream-android',
            'rusty-quest-media-stream','rusty-quest-feature-activation','rusty-quest-broker-admission',
            'rusty-quest-broker-contracts']
    cargo = '''[package]
name="renewal-successor-production-host"
version="0.0.0"
edition="2021"
[workspace]
[dependencies]
ed25519-dalek={version="=2.1.1",default-features=false,features=["std","zeroize"]}
serde={version="1",features=["derive"]}
serde_json="1"
sha2="0.10"
jni={version="0.21"}
libc="0.2"
'''
    for name in deps:
        cargo += name + '={path=' + json.dumps(dependency(root/'crates'/name).as_posix()) + (',features=["test-support"]' if name=='rusty-quest-media-stream-android' else '')+'}\n'
    for name in ['rusty-manifold-peer','rusty-manifold-model','rusty-manifold-peer-runtime-host',
                 'rusty-manifold-admission','rusty-manifold-broker-adapter',
                 'rusty-manifold-broker-product','rusty-manifold-media-session','rusty-manifold-runtime-host']:
        cargo += name + '={path=' + json.dumps((manifold/'crates'/name).as_posix()) + '}\n'
    cargo += '[['+'bin]]\nname="renewal-successor-production-host"\npath="main.rs"\n'
    put(out/'Cargo.toml', cargo)
    argv = ['cargo','build','--offline','--manifest-path',str(out/'Cargo.toml'),
            '--target-dir',str(root/'target/renewal-successor-cargo')]
    result = subprocess.run(argv,cwd=root,capture_output=True)
    (out/'compile.stdout').write_bytes(result.stdout)
    (out/'compile.stderr').write_bytes(result.stderr)
    def ref(path):
        b=path.read_bytes()
        return {'path':str(path),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
    report={'passed':result.returncode==0,'exit':result.returncode,'argv':argv,
            'stdout':ref(out/'compile.stdout'),'stderr':ref(out/'compile.stderr'),
            'source_pins':[ref(f) for f in source.glob('*.rs')],
            'scope':__doc__,'configured_lifecycle_executed':False}
    report['fixture_sources']=[ref(path)for path in [pathlib.Path(__file__).resolve(),fixture,
        tests/'renewal_successor_pair_controls.rs',tests/'renewal_successor_lifetime_controls.rs',tests/'renewal_successor_view_controls.rs',
        tests/'RenewalSuccessorLifecycleHost.kt',root/'tools/checks/renewal_successor_java_support.py',
        root/'docs/EMBEDDED_DUPLEX_R5_CONTINUITY.md',
        root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexDisplayCoordinator.kt',
        root/'crates/rusty-quest-broker-authority/src/runtime.rs',
        root/'crates/rusty-quest-broker-authority/src/source_bound_roots_tests.rs',
        root/'crates/rusty-quest-broker-authority/src/coupled_renewal_tests.rs',asset]]
    report['selected_manifold']={'root':str(manifold),
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=manifold,text=True).strip(),
        'tree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=manifold,text=True).strip()}
    put(out/'RESULT.json',json.dumps(report,indent=2)+'\n')
    if result.returncode==0 and a.scenario:
        assert all([a.input_spec,a.support_classes,a.json_jar]),'Explicit authenticated Java fixture dependencies required'
        executable=root/'target/renewal-successor-cargo/debug/renewal-successor-production-host.exe'
        try:
            scenario=connected_scenario(root,out,executable,a,a.scenario=='retained-red')
        except Exception as error:
            scenario={'passed':False,'error_type':type(error).__name__,'error':str(error),
                      'scope':'Connected fixture failure, not a reproduced production guard result'}
            report['passed']=False
            report['exit']=1
        put(out/'SCENARIO_RESULT.json',json.dumps(scenario,indent=2)+'\n')
        report['scenario']=ref(out/'SCENARIO_RESULT.json')
        report['configured_lifecycle_attempted']=True
        report['configured_lifecycle_executed']=scenario.get('passed')is True and a.scenario=='corrected'
        put(out/'RESULT.json',json.dumps(report,indent=2)+'\n')
    elif result.returncode==0 and not a.compile_only:
        executable=root/'target/renewal-successor-cargo/debug/renewal-successor-production-host.exe'
        run=subprocess.run([str(executable)],cwd=root,capture_output=True)
        (out/'run.stdout').write_bytes(run.stdout)
        (out/'run.stderr').write_bytes(run.stderr)
        report['run']={'exit':run.returncode,'stdout':ref(out/'run.stdout'),'stderr':ref(out/'run.stderr')}
        report['passed']=run.returncode==0
        report['exit']=run.returncode
        put(out/'RESULT.json',json.dumps(report,indent=2)+'\n')
    print(json.dumps({'exit':report['exit'],'result':ref(out/'RESULT.json')}))
    return report['exit']

if __name__=='__main__':
    raise SystemExit(main())
