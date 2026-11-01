"""Compile the actual LAN owner/recording adapter; inert Android/native/socket effects."""
import test_experiment_remote_join as owner
from test_experiment_gateway_dedupe import method

original=owner.HARNESS
prefix=original[:original.index(' public static void main')]
setup=original[original.index('  android.content.Context context='):original.index('  g.nativeOwnerChanged(1,7,true);')]
owner.STUBS['android/os/Bundle.java']='''package android.os;public class Bundle {public static boolean export;public boolean getBoolean(String k,boolean d){return k.equals("rusty.quest.experiment.command_gateway.enabled")||k.equals("rusty.quest.feature.recording_export")&&export;}public String getString(String k,String d){return k.endsWith(".origins")?"https://controller.example":d;}}'''
owner.BOUNDARIES=owner.BOUNDARIES.replace('static String applyExperimentSessionCommand(String s){return "{}";}', '''static int reads;static String last;static Runnable during;static String applyExperimentSessionCommand(String s){reads++;last=s;if(android.os.Looper.myLooper()!=null)throw new AssertionError("filesystem query on main");if(during!=null)during.run();return "{\\"v\\":1,\\"state\\":\\"preparing\\"}";}''')
owner.HARNESS=prefix+r'''
 static JSONObject network(ExperimentSessionCommandGateway g,JSONObject r)throws Exception {
  java.util.concurrent.FutureTask<JSONObject> f=new java.util.concurrent.FutureTask<>(()->g.exchangeLan(r));Thread t=new Thread(f,"network");t.start();
  long until=System.nanoTime()+3000000000L;while(!f.isDone()&&System.nanoTime()<until){Runnable task=android.os.Handler.queue.poll(20,java.util.concurrent.TimeUnit.MILLISECONDS);if(task!=null)task.run();}t.join(1000);check(!t.isAlive(),"export blocked main or IO lane");return f.get();
 }
 public static void main(String[] args)throws Exception {
'''+setup+r'''
  g.nativeOwnerChanged(1,7,true);JSONObject credentials=join(g,g.identity());JSONObject q=new JSONObject().put("query","list").put("before","").put("limit",16);
  deny(()->network(g,auth(credentials,"recordings",q)));check(ControlPanelActivity.reads==0,"gateway alone gained filesystem access");
  android.os.Bundle.export=true;
  deny(()->g.exchange(auth(credentials,"recordings",q)));check(ControlPanelActivity.reads==0,"relay acquired recording access");
  JSONObject reply=network(g,auth(credentials,"recordings",q));check(new JSONObject(reply.getString("payload")).getString("state").equals("preparing")&&ControlPanelActivity.reads==1,"selected LAN export unavailable");
  JSONObject sent=new JSONObject(ControlPanelActivity.last);check(sent.length()==4&&sent.getString("operation").equals("recordings")&&sent.getJSONObject("query").getInt("limit")==16,"read-only native seam widened");
  check(!sent.getString("binding").contains(credentials.getString("key")),"binding carries controller secret");
  for(JSONObject bad:new JSONObject[]{new JSONObject(q.toString()).put("limit",17),new JSONObject(q.toString()).put("limit","1"),new JSONObject(q.toString()).put("extra",true),new JSONObject().put("query","describe").put("id","../x"),new JSONObject().put("query","read").put("manifest_id",new String(new char[64]).replace('\0','a')).put("file","events.jsonl").put("offset",0).put("length",32769)}){int reads=ControlPanelActivity.reads;try{network(g,auth(credentials,"recordings",bad));throw new AssertionError("query admitted");}catch(Exception expected){}check(ControlPanelActivity.reads==reads,"invalid query reached filesystem");}
  idle=false;deny(()->network(g,auth(credentials,"recordings",q)));idle=true;check(ControlPanelActivity.reads==1,"active recording reached IO");
  ControlPanelActivity.during=()->mode.packed=37;deny(()->network(g,auth(credentials,"recordings",q)));check(ControlPanelActivity.reads==2,"mode changed before IO was exercised");ControlPanelActivity.during=null;mode.packed=46;g.controlModeChanged();
  credentials=join(g,g.identity());final JSONObject current=credentials;
  ControlPanelActivity.during=()->g.nativeOwnerChanged(1,7,false);deny(()->network(g,auth(current,"recordings",q)));check(ControlPanelActivity.reads==3,"owner retirement IO not exercised");check(dispatches==0,"recording request mutated experiment");
  System.out.println("PASS "+checks+" actual gateway export selection, LAN-only route, closed queries, no experiment dispatch, IO outside main, post-IO mode/owner retirement");
 }
}
'''
# Java lambda captures must remain immutable.
owner.HARNESS=owner.HARNESS.replace('JSONObject credentials=join', 'final JSONObject credentials=join').replace('  credentials=join(g,g.identity());final JSONObject current=credentials;', '  final JSONObject current=join(g,g.identity());')
if __name__=='__main__':owner.main()
