// Inert exact-runtime preparation: hashes and parses local files only. No proxy,
// device discovery, forward, environment mutation, or input effect.
import {readFile,open} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {requiredTools,schemaHash,validateCapsule} from './lib/meta-xr-operator-backend.mjs';
const sha = value => createHash('sha256').update(value).digest('hex');
export async function bindOperatorCapsule(prepared,binding) {
  if(!binding || Object.keys(binding).sort().join()!==['identity','origin','plan','owner_route'].sort().join()) throw Error('Only runtime binding fields permitted');
  if(prepared.schema!=='rusty.quest.meta_xr_input.prepared.v1' || prepared.enabled!==true || prepared.runtime_bound!==false) throw Error('Source-prepared debug package required');
  if(sha(await readFile(prepared.proxy.path))!==prepared.proxy.sha256) throw Error('Proxy changed');
  const bytes=await readFile(prepared.registry.path);
  if(sha(bytes)!==prepared.registry.sha256) throw Error('Reviewed registry changed');
  const registry=JSON.parse(bytes), tools=registry.result?.tools;
  const pins={};
  for(const name of requiredTools) {
    const entries=tools?.filter(x=>x.name===name)??[];
    if(entries.length!==1 || /\[OFFLINE\]/.test(entries[0].description??'')) throw Error('Reviewed online registry required');
    pins[name]=schemaHash(entries[0].inputSchema);
  }
  return validateCapsule({schema:'rusty.quest.meta_xr_input.capsule.v1',enabled:true,selection:prepared.selection,proxy:prepared.proxy,
    schema_pins:pins,...binding});
}
// Import from a source-owned consumer. The CLI only binds a capsule; it cannot run it.
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  const [preparedPath,bindingPath,outputPath]=process.argv.slice(2);
  const capsule=await bindOperatorCapsule(JSON.parse(await readFile(preparedPath)),JSON.parse(await readFile(bindingPath)));
  const output=await open(outputPath,'wx');
  try{await output.writeFile(JSON.stringify(capsule,null,2));await output.sync();}finally{await output.close();}
}
