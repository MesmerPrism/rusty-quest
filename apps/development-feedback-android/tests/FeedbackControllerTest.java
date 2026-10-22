import io.github.mesmerprism.rustyquest.development_feedback.FeedbackController;
import org.rusty.quest.feedback.HostUrlLoader;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

public final class FeedbackControllerTest {
    private static int assertions;
    private static void check(boolean value,String message) {
        assertions++; if(!value)throw new AssertionError(message);
    }
    private static Map<String,String> fields(String... values) {
        Map<String,String> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put(values[i],values[i+1]);return result;
    }
    private static void reject(FeedbackController controller,String method,Map<String,String> args) throws Exception {
        String before=controller.execute("status",fields()).toString();
        boolean rejected=false;
        try { controller.execute(method,args); } catch(IllegalArgumentException | IllegalStateException expected) { rejected=true; }
        check(rejected,"closed input must reject: "+method+args);
        check(before.equals(controller.execute("status",fields()).toString()),"refusal changed host state");
    }
    private static String generation(Path root,String id,int delta) throws Exception {
        Path build=Files.createDirectory(root.resolve("compile-"+id));
        Path pkg=Files.createDirectory(build.resolve("feedbackplugin"));
        Path source=pkg.resolve("Module.java");
        String code="package feedbackplugin; public final class Module implements org.rusty.quest.feedback.api.FeedbackModule { public int abiVersion(){return 1;} public String generationId(){return \""+id+"\";} public long compute(long input,long counter,byte[] scratch){scratch[0]=(byte)(scratch[0]+"+delta+");return input+counter+"+delta+";} }";
        Files.write(source,code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int exit=ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-cp",System.getProperty("java.class.path"),"-d",build.toString(),source.toString());
        check(exit==0,"fresh plugin compile failed");
        Path jar=root.resolve("incoming").resolve(id+".jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("feedbackplugin/Module.class"));
            out.write(Files.readAllBytes(pkg.resolve("Module.class")));out.closeEntry();
        }
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    private static Map<String,String> activate(String id,String digest,int delta) {
        return fields("generation",id,"sha256",digest,"probe_input","5","probe_counter","3","probe_initial","00000000000000000000000000000000","probe_result",Integer.toString(8+delta),"probe_expected",String.format(Locale.ROOT,"%02x",delta)+"000000000000000000000000000000");
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createDirectory(Paths.get(args[0]).resolve("controller-data"));Files.createDirectory(root.resolve("incoming"));
        FeedbackController controller=new FeedbackController(root.toFile(),new HostUrlLoader(),false,"process-test");
        Map<String,String> initial=controller.execute("status",fields());
        check("none".equals(initial.get("generation")),"unexpected bundled implementation");
        String app=initial.get("application_token"),allocation=initial.get("buffer_allocation"),process=initial.get("process_token");
        reject(controller,"compute",fields("input","1"));
        reject(controller,"status",fields("anything","1"));
        reject(controller,"shell",fields());reject(controller,"select",fields("generation","../A1"));
        reject(controller,"compute",fields("input","99999999999999999999999999"));
        reject(controller,"compute",fields("input","1","path","other"));
        reject(controller,"activate",activate("../A1",String.join("",Collections.nCopies(64,"a")),1));
        String digestA=generation(root,"A1",1);
        reject(controller,"activate",activate("A1",String.join("",Collections.nCopies(64,"0")),1));
        Path aFile=root.resolve("incoming/A1.jar");byte[] original=Files.readAllBytes(aFile);
        byte[] tampered=Arrays.copyOf(original,original.length+1);tampered[tampered.length-1]=7;Files.write(aFile,tampered);
        reject(controller,"activate",activate("A1",digestA,1));Files.write(aFile,original);
        check("A1".equals(controller.execute("activate",activate("A1",digestA,1)).get("generation")),"first activation missing");
        String activity1=controller.activityCreated();
        check("6".equals(controller.execute("compute",fields("input","5")).get("result")),"A computation wrong");
        String activity2=controller.activityCreated();
        Map<String,String> afterRecreation=controller.execute("status",fields());
        check(!activity1.equals(activity2),"Activity token reused");
        check("2".equals(afterRecreation.get("activity_creations")),"creation count wrong");
        check(app.equals(afterRecreation.get("application_token"))&&process.equals(afterRecreation.get("process_token")),"resident tokens changed");
        check(allocation.equals(afterRecreation.get("buffer_allocation"))&&"1".equals(afterRecreation.get("counter")),"Activity recreation reset state");
        check(afterRecreation.get("buffer_hex").startsWith("01"),"host buffer not committed");
        String digestB=generation(root,"B2",2);
        Map<String,String> badProbe=activate("B2",digestB,2);badProbe.put("probe_result","999");
        boolean probeRejected=false;try{controller.execute("activate",badProbe);}catch(IllegalArgumentException expected){probeRejected=true;}
        check(probeRejected,"wrong independent probe accepted");
        Map<String,String> afterFailure=controller.execute("status",fields());
        check("A1".equals(afterFailure.get("generation"))&&"1".equals(afterFailure.get("counter")),"candidate failure replaced live state");
        reject(controller,"activate",activate("B2",digestB,2));
        String digestB3=generation(root,"B3",2);
        controller.execute("activate",activate("B3",digestB3,2));
        check("8".equals(controller.execute("compute",fields("input","5")).get("result")),"fresh B computation wrong");
        controller.execute("select",fields("generation","A1"));
        Map<String,String> reverted=controller.execute("status",fields());
        check("2".equals(reverted.get("counter"))&&reverted.get("buffer_hex").startsWith("03"),"code rollback rewound state");
        check(allocation.equals(reverted.get("buffer_allocation")),"generation replacement changed host allocation");
        check("8".equals(controller.execute("compute",fields("input","5")).get("result")),"retained A computation wrong");
        FeedbackController restarted=new FeedbackController(root.toFile(),new HostUrlLoader(),false,"new-process");
        check(!app.equals(restarted.execute("status",fields()).get("application_token")),"new Application reused resident token");
        check("0".equals(restarted.execute("status",fields()).get("counter")),"new process accidentally imported live state");
        System.out.println("PASS: "+assertions+" controller assertions; actual fresh A1/B2-failure/B3 and retained A1");
    }
}
