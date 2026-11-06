import org.rusty.quest.feedback.host.ResidentFeedbackHost;
import org.rusty.quest.feedback.HostUrlLoader;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Actual compiled Java generations and real selected native images; no loader doubles. */
public final class ResidentFeedbackHostTest {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static Map<String,String> fields(String... pairs){Map<String,String> result=new LinkedHashMap<>();for(int n=0;n<pairs.length;n+=2)result.put(pairs[n],pairs[n+1]);return result;}
    private static String hash(Path path)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void refuse(ResidentFeedbackHost host,String method,Map<String,String> fields)throws Exception{
        Map<String,String> before=host.snapshot();boolean denied=false;
        try{host.execute(method,fields);}catch(IllegalArgumentException|IllegalStateException|IOException expected){denied=true;}
        check(denied,"expected refusal "+method);check(before.equals(host.snapshot()),"refusal retains state "+method);
    }
    private static void received(Path source,Path target)throws Exception{Files.copy(source,target);check(target.toFile().setReadOnly(),"readonly received input");}
    private static String javaGeneration(Path incoming,String id,int delta)throws Exception{
        Path root=Files.createDirectory(incoming.getParent().resolve("compile-"+id));Path pkg=Files.createDirectory(root.resolve("feedbackplugin"));Path source=pkg.resolve("Module.java");
        String code="package feedbackplugin; public final class Module implements org.rusty.quest.feedback.api.FeedbackModule { public int abiVersion(){return 1;} public String generationId(){return \""+id+"\";} public long compute(long input,long counter,byte[] scratch){scratch[0]=(byte)(scratch[0]+"+delta+");return input+counter+"+delta+";} }";
        Files.write(source,code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-cp",System.getProperty("java.class.path"),"-d",root.toString(),source.toString())==0,"fresh Java compilation");
        Path jar=incoming.resolve(id+".jar");try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new JarEntry("feedbackplugin/Module.class"));output.write(Files.readAllBytes(pkg.resolve("Module.class")));output.closeEntry();}return hash(jar);
    }
    private static Map<String,String> dexActivation(String id,String hash,int delta){return fields("generation",id,"sha256",hash,"probe_input","5","probe_counter","3","probe_initial","00000000000000000000000000000000","probe_result",Integer.toString(8+delta),"probe_expected",String.format(Locale.ROOT,"%02x",delta)+"000000000000000000000000000000");}
    public static void main(String[] args)throws Exception{
        check(args.length==4,"output, actual bridge, trusted bridge sha256, actual native manifest");
        Path files=Files.createDirectory(Paths.get(args[0]).resolve("files"));
        ResidentFeedbackHost host=new ResidentFeedbackHost(files.toFile(),new HostUrlLoader(),false,"actual-host-process");
        Map<String,String> initial=host.snapshot();String owner=initial.get("application_token");String allocation=initial.get("buffer_allocation");
        check("uninitialized".equals(initial.get("native_state")),"native explicit initialization");
        refuse(host,"native_compute",fields("input","4"));refuse(host,"native_compute",fields("input","-0"));
        refuse(host,"compute",fields("input","0","path","untrusted"));refuse(host,"arbitrary",fields());
        Path session=files.resolve("development-feedback").resolve(initial.get("host_session_id"));
        Path nativeSession=session.resolve("native-feedback").resolve(initial.get("native_session_id"));
        received(Paths.get(args[1]),nativeSession.resolve("bridge.so"));
        host.execute("native_init",fields("sha256",args[2]));String nativeOwner=host.snapshot().get("native_owner_token");String nativeAllocation=host.snapshot().get("native_allocation");
        Map<String,String[]> images=new HashMap<>();for(String line:Files.readAllLines(Paths.get(args[3]))){String[] row=line.split("\\t",4);images.put(row[0],row);}
        int completion=0;
        for(String name:new String[]{"a1","b2","a3"}){
            String[] image=images.get(name);received(Paths.get(image[3]),nativeSession.resolve("native-incoming/"+image[1]+".so"));
            host.execute("native_activate",fields("generation",image[1],"sha256",image[2],"probe_result","b2".equals(name)?"19":"13"));
            Map<String,String> value=host.execute("native_compute",fields("input","4"));completion++;
            check(Integer.toString(completion).equals(value.get("native_completed_unsigned")),"native state continues");
            check(nativeOwner.equals(value.get("native_owner_token"))&&nativeAllocation.equals(value.get("native_allocation")),"same native owner/buffer");
            check(owner.equals(value.get("application_token")),"same resident Java host");
        }
        check("20".equals(host.snapshot().get("native_value")),"actual A/B/fresh A native result");
        String[] bad=images.get("wrong-version");received(Paths.get(bad[3]),nativeSession.resolve("native-incoming/"+bad[1]+".so"));
        Map<String,String> before=host.snapshot();boolean denied=false;try{host.execute("native_activate",fields("generation",bad[1],"sha256",bad[2],"probe_result","13"));}catch(IllegalArgumentException|IllegalStateException expected){denied=true;}
        check(denied,"real incompatible native image refused");check(before.get("native_generation").equals(host.snapshot().get("native_generation"))&&before.get("native_buffer_hex").equals(host.snapshot().get("native_buffer_hex")),"prior native state preserved");
        Path incoming=session.resolve("incoming");int count=0;
        for(int delta:new int[]{1,2,1}){String id="java"+(++count);String digest=javaGeneration(incoming,id,delta);host.execute("activate",dexActivation(id,digest,delta));Map<String,String> value=host.execute("compute",fields("input","5"));check(Long.toString(5L+(count-1)+delta).equals(value.get("result")),"fresh Java behavior");check(allocation.equals(value.get("buffer_allocation")),"same Java buffer");check(nativeOwner.equals(value.get("native_owner_token"))&&"3".equals(value.get("native_completed_unsigned")),"DEX replacement retains native state");}
        check("04".equals(host.snapshot().get("buffer_hex").substring(0,2)),"Java accumulated buffer");
        long stagedCount;try(java.util.stream.Stream<Path> entries=Files.list(session.resolve("generations"))){stagedCount=entries.count();}
        for(int n=0;n<5;n++)refuse(host,"activate",dexActivation("java3",hash(incoming.resolve("java3.jar")),1));
        for(int n=3;n<64;n++){
            String id="rejected"+n;Files.copy(incoming.resolve("java3.jar"),incoming.resolve(id+".jar"));
            boolean rejected=false;try{host.execute("activate",dexActivation(id,String.join("",Collections.nCopies(64,"0")),1));}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"bad digest charged before copy and refused");
        }
        check("64".equals(host.snapshot().get("staging_attempts")),"failed staging consumes finite budget");
        Files.copy(incoming.resolve("java3.jar"),incoming.resolve("overcap.jar"));
        refuse(host,"activate",dexActivation("overcap",hash(incoming.resolve("overcap.jar")),1));
        try(java.util.stream.Stream<Path> entries=Files.list(session.resolve("generations"))){check(stagedCount==entries.count(),"duplicate and exhausted staging retain no extra files");}
        check("9".equals(host.execute("compute",fields("input","5")).get("result")),"prior generation remains usable after staging exhaustion");
        try{host.snapshot().put("counter","spoof");throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){assertions++;}
        System.out.println("PASS ResidentFeedbackHost actual Java/JNI generations assertions="+assertions);
    }
}
