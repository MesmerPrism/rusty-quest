#!/usr/bin/env python3
"""Execute production WSI semaphore allocation/selection with a modeled present engine.

The real rollback owner and exact allocation/selection statements are compiled.
Vulkan/device calls are modeled; this is not GPU or presentation proof.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def block(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, quoted, escaped = 0, False, False
    for end in range(brace, len(text)):
        char = text[end]
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError('Unclosed production block')


def pin(path):
    raw = path.read_bytes()
    return dict(path=str(path.resolve()), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    source = root / 'apps/spatial-camera-panel-android/native-receipt/src'
    probe = (source / 'camera_hwb_probe.rs').read_text(encoding='utf-8')
    source_set = (source / 'camera_hwb_source_set.rs').read_text(encoding='utf-8')
    paths = [source / name for name in ('camera_hwb_probe.rs', 'camera_hwb_source_set.rs', 'prepared_renderer_gate.rs')]
    pins = [pin(path) for path in paths]
    create = block(probe, 'unsafe fn create_with_startup(')
    allocation = create[create.index('let semaphore_info ='):create.index('let frame_fence =')]
    local = block(probe, 'unsafe fn render_camera_hwb_probe(')
    functions = [('source_set', source_set), ('local_camera', local),
                 ('peer_camera', block(probe, 'unsafe fn render_peer_common_graph_resources('))]
    host = r'''
use std::cell::{Cell,RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;
#[path=ROLLBACK]mod prepared_renderer_gate;
#[derive(Default)]struct State{next:Cell<usize>,fail_at:Cell<usize>,destroyed:RefCell<Vec<usize>>}
#[derive(Clone)]struct Device(Rc<State>);
mod vk{#[derive(Default)]pub struct SemaphoreCreateInfo;}
impl Device{
 unsafe fn create_semaphore(&self,_:&vk::SemaphoreCreateInfo,_:Option<()>)->Result<usize,&'static str>{
  let next=self.0.next.get()+1;self.0.next.set(next);
  if next==self.0.fail_at.get(){Err("allocation-failure")}else{Ok(next)}
 }
 unsafe fn destroy_semaphore(&self,value:usize,_:Option<()>){self.0.destroyed.borrow_mut().push(value);}
}
struct Wsi<T>{render_finished:T}
fn allocation_failure(fail_at:usize)->(Result<(),String>,Vec<usize>){
 let state=Rc::new(State::default());state.fail_at.set(fail_at);let device=Device(state.clone());
 let result=(||->Result<(),String>{unsafe{let images=vec![0u32,1,2];
 let mut partial=prepared_renderer_gate::ConstructionRollback::new();
 ALLOCATION
 let _=render_finished;let _=image_available;Ok(())}})();
 let destroyed=state.destroyed.borrow().clone();(result,destroyed)
}
'''.replace('ROLLBACK', json.dumps((source / 'prepared_renderer_gate.rs').as_posix())).replace('ALLOCATION', allocation)
    selectors = {}
    for name, text in functions:
        found = re.search(r'let render_finished = ([^;\n]+);', text)
        # Local camera previously carried the scalar through struct destructuring.
        expression = found.group(1) if found else 'wsi.render_finished'
        selectors[name] = expression
        if found and 'render_finished_semaphores[' in expression:
            acquire = text.index('let image_index = match swapchain_loader.acquire_next_image(')
            if found.start() <= acquire:
                raise ValueError('Present semaphore must be selected after actual image acquisition: '+name)
        if not re.search(r'let (?:signals|signal_semaphores) = \[render_finished\];', text):
            raise ValueError('Direct Native submission must signal the selected semaphore: '+name)
        enqueue = text.index('enqueue_spatial_submit_present(')
        if 'render_finished.as_raw()' not in text[enqueue:text.index(');', enqueue)]:
            raise ValueError('SDK queue request must carry the same selected semaphore: '+name)
        host += r'''
fn NAME(image_count:usize)->Result<(),String>{unsafe{
 let state=Rc::new(State::default());let device=Device(state.clone());let images=(0..image_count as u32).collect::<Vec<_>>();
 let mut partial=prepared_renderer_gate::ConstructionRollback::new();
 ALLOCATION
 let wsi=Wsi{render_finished};let render_finished_semaphores=&wsi.render_finished;
 let mut present_waits=BTreeMap::<u32,usize>::new();
 // Submission fences are already retired at every iteration. Presentation
 // consumes its wait only when that SAME swapchain image is reacquired.
 for next in [0u32,1,2,0,2,1,1,0,image_count as u32-1]{
  let image_index=next%image_count as u32;
  present_waits.remove(&image_index);
  let render_finished=EXPRESSION;
  assert!(!present_waits.values().any(|value|*value==render_finished),
   "NAME: VUID00067: signal semaphore still retained by another present image; acquired={image_index}, pending={present_waits:?}");
  present_waits.insert(image_index,render_finished);
 }
 assert_eq!(state.next.get(),images.len()+1,"one acquire semaphore plus one present semaphore per actual swapchain image");
 let _=image_available;Ok(())
}}
'''.replace('NAME', name).replace('ALLOCATION', allocation).replace('EXPRESSION', expression)
    # Execute both exact normal retirement loops as well as partial rollback.
    for name, text, owner in (
            ('shared_destroy', block(probe, 'unsafe fn destroy_after_idle('), 'render_finished'),
            ('local_destroy', local, 'render_finished_semaphores')):
        destruction = block(text, 'for semaphore in '+owner+' {')
        host += r'''
fn NAME()->Result<(),String>{unsafe{
 let state=Rc::new(State::default());let device=Device(state.clone());let images=vec![0u32,1,2];
 let mut partial=prepared_renderer_gate::ConstructionRollback::new();
 ALLOCATION
 partial.committed();assert!(state.destroyed.borrow().is_empty());
 OWNER_BINDING
 DESTRUCTION
 device.destroy_semaphore(image_available,None);
 assert_eq!(*state.destroyed.borrow(),vec![2,3,4,1],"all present semaphores and acquire semaphore destroyed once");Ok(())
}}
'''.replace('NAME', name).replace('ALLOCATION', allocation).replace('DESTRUCTION', destruction).replace(
            'OWNER_BINDING', 'let render_finished_semaphores=render_finished;' if owner.endswith('_semaphores') else '')
    host += r'''
fn main(){
 for count in [1usize,2,3,5]{source_set(count).unwrap();local_camera(count).unwrap();peer_camera(count).unwrap();}
 for failed in 1..=4{
  let (result,destroyed)=allocation_failure(failed);assert!(result.is_err());
  assert_eq!(destroyed,(1..failed).rev().collect::<Vec<_>>(),"every successful partial allocation retired once in reverse order");
 }
 shared_destroy().unwrap();local_destroy().unwrap();
 println!("3 actual WSI allocation/selection paths x 4 image counts (1/2/3/5), acquired 0/1/2/reordered/repeated; 4 partial-allocation rollback points; 2 normal destruction paths passed. Modeled Vulkan leaves; no GPU proof.");
}
'''
    (out / 'main.rs').write_text(host, encoding='utf-8')
    exe = out / 'present-semaphore-host.exe'
    subprocess.run(['rustc', '--edition=2021', '-Awarnings', str(out / 'main.rs'), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
    if [pin(path) for path in paths] != pins:
        raise ValueError('Production source changed during qualification')
    (out / 'RESULT.json').write_text(json.dumps(dict(status='pass', production=pins, selectors=selectors,
        scope='actual allocation and selector statements with modeled Vulkan leaves; no device proof'), indent=2)+'\n', encoding='utf-8')


if __name__ == '__main__':
    main()
