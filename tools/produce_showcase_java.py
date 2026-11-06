"""Closed, fail-closed projection of maintained acquisition/discovery sources.

The original producer files remain canonical for experiment products. This
projection physically removes capture entrypoints and persistent sensor sinks.
Its receipt binds every input/output; it is not a runtime permission switch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

PACKAGE = 'io/github/mesmerprism/rustyquest/native_renderer'

def block_end(text, opening):
    depth = 0
    state = 'code'
    i = opening
    while i < len(text):
        c, pair = text[i], text[i:i+2]
        if state == 'line':
            if c == '\n': state = 'code'
        elif state == 'comment':
            if pair == '*/': state = 'code'; i += 1
        elif state in ('"', "'"):
            if c == '\\': i += 1
            elif c == state: state = 'code'
        elif pair == '//': state = 'line'; i += 1
        elif pair == '/*': state = 'comment'; i += 1
        elif c in ('"', "'"): state = c
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return i + 1
        i += 1
    raise ValueError('unbalanced Java producer')

def method(text, name, body=None):
    pattern = re.compile(r'^    (?:private |public |protected |static |synchronized |final )*[\w<>.\[\]]+\s+' + re.escape(name) + r'\s*\([^;{]*\)\s*(?:throws [^{]+)?\{', re.M)
    hits = list(pattern.finditer(text))
    if len(hits) != 1: raise ValueError(f'expected one method {name}, got {len(hits)}')
    hit = hits[0]; opening = text.index('{', hit.start()); end = block_end(text, opening)
    replacement = '' if body is None else text[hit.start():opening+1] + body + '\n    }'
    return text[:hit.start()] + replacement + text[end:]

def between(text, start, end):
    if text.count(start) != 1 or end not in text: raise ValueError('producer boundary changed: '+start)
    a = text.index(start); b = text.index(end, a)
    return text[:a] + text[b:]

def project(name, text):
    if name == 'PolarSensorPanel.java':
        text = between(text, '        root.addView(sectionTitle("Synchronized capture"));', '        root.addView(sectionTitle("Streams"));')
        for op in ('start_capture','stop_capture'):
            marker = '        if ("'+op+'".equals(command)) {'
            if text.count(marker) != 1: raise ValueError('capture command producer changed')
            a = text.index(marker); b = block_end(text, text.index('{',a)); text = text[:a]+text[b:]
        for name_ in ('startParallelCapture','stopParallelCapture','refreshCaptureCompletion'):
            text = method(text,name_)
        for name_ in ('nativeStartParallelBreathCapture','nativeStopParallelBreathCapture','nativeReadParallelBreathCaptureStatus'):
            text, count = re.subn(r'    private static native String '+name_+r'\([^;]*;','',text)
            if count != 1: raise ValueError('capture native declaration changed')
        text = text.replace('new JSONObject(nativeReadParallelBreathCaptureStatus())', 'new JSONObject()')
        text = between(text,'        File events = new File(appContext.getFilesDir(), STREAM_EVENTS_FILE);','        updateCounters();')
        text = method(text,'appendStreamEvent','\n        // Sensor events are processed in memory; this composition has no history sink.')
        text = method(text,'writeStatus','\n        // Current state is projected on demand; no diagnostic file is produced.')
        text = method(text,'persistStatusOnBackgroundThread')
        text = text.replace('import java.io.File;\n','').replace('import java.io.FileOutputStream;\n','')
        text = re.sub(r'NativeRendererSelfKioskApplication\.(?:beginSystemPrompt|endSystemPrompt)\([^;]*\);',';',text)
    elif name == 'PolarSensorRuntime.java':
        text = method(text,'enqueueReceipt','\n        // No file receipt sink in the visual showcase composition.')
        text = method(text,'persistReceiptOnBackgroundThread')
        text = between(text,'            final byte[] snapshot = response.toString().getBytes(StandardCharsets.UTF_8);','        } catch (Exception failed) { Log.i(TAG, MARKER_PREFIX + " channel=polar-sensor-runtime status=projected-receipt-unavailable"); }')
        text = text.replace('import java.io.FileOutputStream;\n','')
        marker='    private PolarSensorRuntime(Context context) {'
        if text.count(marker)!=1: raise ValueError('runtime lifecycle producer changed')
        text=text.replace(marker,'''    /** Selected projection has no queued file sinks. Retire synchronously before
     * any successor is admitted; late callbacks retain the old closed owner. */
    static synchronized void closeShowcaseFromOwner() {
        PolarSensorRuntime current=instance;
        if(current==null)return;
        synchronized(current){
            current.closed=true;
            try{current.panel.shutdown();}
            finally{
                current.operatorReceiptWriter.shutdown();
                current.cleanupComplete=true;
                instance=null;
            }
        }
    }

'''+marker)
    elif name == 'ExperimentSessionDiscoveryServer.java':
        text = text.replace('ExperimentSessionDiscoveryServer','ShowcaseDiscoveryServer')
        text = text.replace('9a7b2001-','9a7b2101-').replace(':8766/', ':8767/')
        text = text.replace('serial.length==0||','')
        text = text.replace('value.length()!=7','value.length()!=8||!"visual-showcase".equals(value.getString("product"))')
        return 'ShowcaseDiscoveryServer.java',text
    elif name in ('ExperimentSessionGatewayProtocol.java','ExperimentSessionGatewayHttpServer.java','ExperimentSessionGuestRegistry.java'):
        if name == 'ExperimentSessionGatewayProtocol.java':
            text = method(text,'monitorQuery')
            old='||"breath-monitor".equals(action)||lan&&"recordings".equals(action)'
            if text.count(old)!=1: raise ValueError('gateway action admission changed')
            text=text.replace(old,'')
        if name == 'ExperimentSessionGatewayHttpServer.java':
            marker='            String origin=headers.get("origin");'
            if text.count(marker)!=1: raise ValueError('HTTP header admission changed')
            text=text.replace(marker,'            if(!owner.allowedHost(headers.get("host"))){reply(connection,403,"","{}");return;}\n'+marker)
        text=text.replace('ExperimentSessionGatewayProtocol','ShowcaseGatewayProtocol').replace('ExperimentSessionGatewayHttpServer','ShowcaseGatewayHttpServer').replace('ExperimentSessionGuestRegistry','ShowcaseGuestRegistry').replace('ExperimentSessionCommandGateway','ShowcaseGateway').replace('experiment-command-lan','showcase-command-lan')
        if 'ExperimentSession' in text or '"recordings"' in text or '"breath-monitor"' in text: raise ValueError('experiment route leaked')
        return name.replace('ExperimentSession','Showcase'),text
    else: raise ValueError('unlisted source producer')
    forbidden = ('openFileOutput(', 'openFileInput(', 'getFilesDir(', 'getExternalFilesDir(', 'FileOutputStream', 'FileWriter', 'java.nio.file', 'java.io.', 'new File(', '.write(', 'nativeStartParallelBreathCapture', 'nativeStopParallelBreathCapture', 'nativeReadParallelBreathCaptureStatus', '"start_capture"', '"stop_capture"', 'Start recording', 'Stop recording')
    for token in forbidden:
        if token in text: raise ValueError(f'forbidden generated capability {token}')
    if name == 'PolarSensorPanel.java':
        allowed_native={'nativeSubmitPolarAccMeasurement','nativeSubmitPolarEcgMeasurement','nativeSubmitPolarPmdFrame','nativeSubmitPolarHeartRateMeasurement','nativeSubmitPolarRrMeasurement','nativeSetPolarAccPresentationMode','nativeReadPolarAccPresentationStatus'}
        if set(re.findall(r'\bnative\s+\w+\s+(\w+)\s*\(',text)) != allowed_native: raise ValueError('acquisition JNI inventory changed')
        for token in ('connectSelected()', 'startScan()', 'nativeSubmitPolarAccMeasurement(', 'nativeSubmitPolarRrMeasurement(', 'ensureAutoConnection'):
            if token not in text: raise ValueError('lost acquisition capability '+token)
    return name,text

def produce(repo, output):
    base = repo/'apps/native-renderer-android/panel-modules'
    sources = [base/'polar/src/main/java'/PACKAGE/name for name in ('PolarSensorPanel.java','PolarSensorRuntime.java')]
    sources += [base/'breath-composition/src/main/java'/PACKAGE/name for name in ('ExperimentSessionDiscoveryServer.java','ExperimentSessionGatewayProtocol.java','ExperimentSessionGatewayHttpServer.java','ExperimentSessionGuestRegistry.java')]
    output.mkdir(parents=True,exist_ok=True); records=[]
    for source in sources:
        raw=source.read_bytes(); name,text=project(source.name,raw.decode('utf-8-sig').replace('\r\n','\n'))
        data=text.encode('utf-8'); (output/name).write_bytes(data)
        records.append({'input':source.relative_to(repo).as_posix(),'input_sha256':hashlib.sha256(raw).hexdigest(),'output':name,'output_sha256':hashlib.sha256(data).hexdigest()})
    (output/'projection.json').write_text(json.dumps({'schema':'rusty.quest.visual_showcase.java_projection.v1','sources':records},indent=2)+'\n',encoding='utf-8')
    return records

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--repo',type=Path,required=True); parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(); produce(args.repo,args.output)
