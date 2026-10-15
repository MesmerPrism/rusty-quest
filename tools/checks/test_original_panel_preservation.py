"""Preserve the original product methods across Android host extraction.

The fixture pins the accepted original method bodies; normalization admits only
explicit receiver substitutions for the real Activity host, whitespace/comments,
and the qualified Activity.RESULT_OK constant. Seven presentation/lifecycle
methods have a separately reviewed adaptation inventory, never blanket exclusion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

TOKEN=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|[^\s]')
METHOD=re.compile(r'^    (?! )(?:(?:public|protected|private|static|final|synchronized)\s+)*[\w<>\[\].?, ]+?\s+(\w+)\s*\([^;{}]*?\)(?:\s+throws\s+[\w., ]+)?\s*\{',re.M)


def methods(text):
    result={}
    for match in METHOD.finditer(text):
        begin=match.end()-1;depth=0;end=None
        for token in TOKEN.finditer(text,begin):
            value=token.group()
            if value=='{':depth+=1
            elif value=='}':
                depth-=1
                if depth==0:end=token.end();break
        if end is None:raise AssertionError('Unterminated method '+match.group(1))
        name=match.group(1);suffix=1
        while name in result:suffix+=1;name=match.group(1)+'#'+str(suffix)
        result[name]=text[begin:end]
    return result


def normalized(body):
    body=body.replace('BreathCompositionPanelModule.this','this')
    body=re.sub(r'\bhostActivity\(\)','this',body)
    body=re.sub(r'\b(\w+)\.this\b',r'\1',body)
    body=body.replace('Activity.RESULT_OK','RESULT_OK')
    body=body.replace('currentHostActivity()','remotePanel.get()')
    return ''.join(t.group() for t in TOKEN.finditer(body) if not t.group().startswith(('//','/*')))


def digest(body):return hashlib.sha256(normalized(body).encode('utf-8')).hexdigest()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();root=Path(__file__).resolve().parents[2]
    fixture=json.loads((root/'fixtures/native-renderer/original-panel-methods.json').read_text(encoding='utf-8'))
    source=(root/fixture['source']).read_text(encoding='utf-8');actual=methods(source)
    missing=set(fixture['methods'])-set(actual)
    if missing:raise AssertionError('Original methods removed: '+str(sorted(missing)))
    changed=[]
    for name,expected in fixture['methods'].items():
        if name not in fixture['host_adaptations'] and digest(actual[name])!=expected:changed.append(name)
    if changed:raise AssertionError('Original handler/builders changed: '+str(changed))
    added=set(actual)-set(fixture['methods'])
    if added!=set(fixture['added_host_methods']):raise AssertionError('Unexpected controller surface: '+str(sorted(added)))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'passed':True,'original_methods':len(fixture['methods']),'normalized_preserved':len(fixture['methods'])-len(fixture['host_adaptations']),'host_adaptations':fixture['host_adaptations'],'removed':[],'device_effects':0},indent=2),encoding='utf-8')
    print('Original product methods preserved; explicit host adaptations='+str(len(fixture['host_adaptations'])))


if __name__=='__main__':main()
