"""Create opt-in placeholder MP3 tones and 4-second box timelines; never instruction speech.
Output is a NEW directory. MP3 bytes and codec/tool identity are retained; timeline uses
native official-active-time-ms, never playback position. No headset/build effects.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--ffmpeg',required=True);p.add_argument('--output',required=True);p.add_argument('--cycles',type=int,default=30);a=p.parse_args()
 if not 1<=a.cycles<=900:raise ValueError('cycles must fit closed 4096 segment limit')
 out=Path(a.output).resolve();out.mkdir(exist_ok=False,parents=True)
 (out/'executed-generator.py').write_bytes(Path(__file__).read_bytes())
 identities={}
 for name,freq,duration,volume in [('condition-a',110,16,0.08),('condition-b',130,16,0.08),('inhale',440,0.4,0.3),('retention',660,0.4,0.3),('exhale',330,0.4,0.3)]:
  f=out/(name+'.mp3');subprocess.run([a.ffmpeg,'-nostdin','-hide_banner','-loglevel','error','-n','-f','lavfi','-i','sine=frequency='+str(freq)+':sample_rate=44100','-t',str(duration),'-af','volume='+str(volume),'-ac','1','-codec:a','libmp3lame','-b:a','128k',str(f)],check=True)
  raw=f.read_bytes();identities[name]={'logical_destination':('session-audio/' if name.startswith('condition-') else 'phase-audio/')+name+'.mp3','source_sha256':hashlib.sha256(raw).hexdigest(),'source_bytes':len(raw),'media_type':'audio/mpeg'}
 timelines={}
 for condition in ('condition-a','condition-b'):
  segments=[{'start_ms':i*4000,'end_ms':(i+1)*4000,'phase':('inhale','hold','exhale','hold')[i%4]} for i in range(a.cycles*4)]
  f=out/(condition+'-breath-guidance.json');f.write_text(json.dumps({'schema':'rusty.quest.breath_guidance_timeline.v1','timebase':'official-active-time-ms','audio_source_sha256':identities[condition]['source_sha256'],'segments':segments},indent=2)+'\n')
  raw=f.read_bytes();timelines[condition]={'asset':{'logical_destination':'breath-guidance/'+condition+'.json','source_sha256':hashlib.sha256(raw).hexdigest(),'source_bytes':len(raw),'media_type':'application/json'},'default_bias_percent':100}
 manifest={'placeholder_tones_only':True,'cycle_ms':16000,'phase_ms':4000,'cycles':a.cycles,'condition_audio':{k:identities[k] for k in ('condition-a','condition-b')},'breath_guidance':timelines,'phase_audio':{'enabled':True,**{k:identities[k] for k in ('inhale','retention','exhale')}},'ffmpeg_sha256':hashlib.sha256(Path(a.ffmpeg).read_bytes()).hexdigest(),'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
 (out/'fixture-identities.json').write_text(json.dumps(manifest,indent=2)+'\n')
if __name__=='__main__':main()
