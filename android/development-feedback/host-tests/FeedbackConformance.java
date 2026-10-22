import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** Real fresh javac generations. Reflection keeps the initial missing-feature RED executable. */
public final class FeedbackConformance {
    private static Class<?> hostType, artifactType, probeType, factoryType;
    private static Path output;
    private static String javac;
    private static int checks;
    private FeedbackConformance() { }
    private interface Checked { void run() throws Exception; }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Object invoke(Object receiver, String method, Class<?>[] types, Object... args) throws Exception {
        try { return receiver.getClass().getMethod(method, types).invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw (Error)cause;
        }
    }
    private static void rejects(Checked action, String reason) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Accepted invalid candidate: " + reason);
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT,"%02x",b & 255));
        return text.toString();
    }
    private static Object stage(String id, byte[] bytes, String digest, boolean dex) throws Exception {
        try {
            return Class.forName("org.rusty.quest.feedback.ArtifactStore")
                .getMethod("stage",File.class,String.class,InputStream.class,String.class,boolean.class)
                .invoke(null,output.resolve("staged").toFile(),id,new ByteArrayInputStream(bytes),digest,dex);
        } catch (InvocationTargetException failure) { throw (Exception)failure.getCause(); }
    }
    private static Object stage(String id) throws Exception {
        byte[] bytes = Files.readAllBytes(output.resolve(id + ".jar"));
        return stage(id,bytes,hash(bytes),false);
    }
    private static Object probe(boolean b) throws Exception {
        // Independently hand-checked: input=11, counter=7, bytes 0/1/127/255.
        return probeType.getConstructor(long.class,long.class,byte[].class,long.class,byte[].class)
            .newInstance(11L,7L,new byte[]{0,1,127,(byte)255},b ? 62L : 40L,
                b ? new byte[]{7,10,(byte)132,4} : new byte[]{1,2,(byte)128,0});
    }
    private static Object host(int cap) throws Exception {
        Object factory = Class.forName("org.rusty.quest.feedback.HostUrlLoader").getConstructor().newInstance();
        return hostType.getConstructor(factoryType,int.class,byte[].class).newInstance(factory,cap,new byte[]{0,1,127,(byte)255});
    }
    private static void activate(Object host, Object artifact, String id, boolean b) throws Exception {
        invoke(host,"activate",new Class<?>[]{artifactType,String.class,String.class,probeType},artifact,"feedbackplugin.Module",id,probe(b));
    }
    private static Object get(Object host,String name) throws Exception { return invoke(host,name,new Class<?>[0]); }
    private static long compute(Object host,long input) throws Exception { return (Long)invoke(host,"compute",new Class<?>[]{long.class},input); }
    private static void buffer(Object host, int... expected) throws Exception {
        byte[] actual=(byte[])get(host,"bufferSnapshot");
        byte[] wanted=new byte[expected.length]; for(int i=0;i<expected.length;i++)wanted[i]=(byte)expected[i];
        require(Arrays.equals(actual,wanted),"Host buffer continuity or failure isolation lost");
    }
    private static byte[] jar(Path classes, boolean duplicate) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(JarOutputStream out=new JarOutputStream(bytes); java.util.stream.Stream<Path> files=Files.walk(classes)) {
            for(Path file:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));out.write(Files.readAllBytes(file));out.closeEntry();
            }
            if(duplicate) {
                String name="org/rusty/quest/feedback/api/FeedbackModule.class";
                out.putNextEntry(new JarEntry(name));
                try(InputStream in=FeedbackConformance.class.getClassLoader().getResourceAsStream(name)) {
                    if(in==null)throw new AssertionError("Contract missing"); byte[] chunk=new byte[4096];int count;
                    while((count=in.read(chunk))!=-1)out.write(chunk,0,count);
                }out.closeEntry();
            }
        } return bytes.toByteArray();
    }
    private static void compile(String id, boolean b, int abi, String declaredId, boolean brokenProbe, boolean failure) throws Exception {
        Path directory=Files.createDirectories(output.resolve("build-"+id));
        Path src=Files.createDirectories(directory.resolve("src/feedbackplugin"));
        String body=b ? "return Helper.value(input)+counter+offset;" : "return 3L*input+counter;";
        String transform=b ? "scratch[i]=(byte)(3*(scratch[i]&255)+7);" : "scratch[i]=(byte)((scratch[i]&255)+1);";
        String failureBody=failure ? "if(input==999L){scratch[0]=99;throw new IllegalStateException(\"deliberate\");}" : "";
        String source="package feedbackplugin; import org.rusty.quest.feedback.api.FeedbackModule; public final class Module implements FeedbackModule {"
            +(b ? "private final long offset=0; private long added(long input){return 5L*input;}" : "")
            +"public int abiVersion(){return "+abi+";} public String generationId(){return \""+declaredId+"\";}"
            +"public long compute(long input,long counter,byte[] scratch){"+failureBody
            +"for(int i=0;i<scratch.length;i++){"+transform+"}"+(brokenProbe ? "return -1;" : body)+"}}";
        Files.write(src.resolve("Module.java"),source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(b)Files.write(src.resolve("Helper.java"),"package feedbackplugin; public final class Helper { public static long value(long x){return 5L*x;} }".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path classes=Files.createDirectories(directory.resolve("classes"));
        List<String> command=new ArrayList<>(Arrays.asList(javac,"--release","8","-Xlint:all","-Werror","-classpath",output.resolve("contract.jar").toString(),"-d",classes.toString(),src.resolve("Module.java").toString()));
        if(b)command.add(src.resolve("Helper.java").toString());
        long start=System.nanoTime();
        Files.write(directory.resolve("command.txt"),command);
        Files.write(directory.resolve("source.sha256"),Collections.singletonList(hash(Files.readAllBytes(src.resolve("Module.java")))));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("javac.txt").toFile()).start();
        require(process.waitFor()==0,"Fresh generation javac failed: "+id);
        Files.write(directory.resolve("compile-ms.txt"),Collections.singletonList(Double.toString((System.nanoTime()-start)/1_000_000.0)));
        byte[] bytes=jar(classes,false); Files.write(output.resolve(id+".jar"),bytes);
        Files.write(directory.resolve("artifact.sha256"),Collections.singletonList(hash(bytes)));
        if(id.equals("A1"))Files.write(output.resolve("duplicate.jar"),jar(classes,true));
    }
    private static void setup() throws Exception {
        // Contract only: plugin javac cannot accidentally link host implementation or test classes.
        Path empty=Files.createDirectories(output.resolve("empty"));
        Files.write(output.resolve("contract.jar"),jar(empty,true));
        compile("A1",false,1,"A1",false,false);
    }
    private static void testFreshSwap() throws Exception {
        Object host=host(20);Object allocation=get(host,"bufferAllocationId");
        Object firstArtifact=stage("A1");
        File stagedFile=(File)invoke(firstArtifact,"file",new Class<?>[0]);
        require(!stagedFile.canWrite(),"Staged artifact remains writable");
        activate(host,firstArtifact,"A1",false);Object aLoader=get(host,"activeLoader");
        require((Long)get(host,"counter")==0L,"Activation changed counter");buffer(host,0,1,127,255);
        require(compute(host,13)==39L,"A1 held-out arithmetic");buffer(host,1,2,128,0);
        compile("B2",true,1,"B2",false,false);activate(host,stage("B2"),"B2",true);
        require(aLoader!=get(host,"activeLoader"),"B reused old defining loader");
        require(compute(host,17)==86L,"B2 fresh helper arithmetic");buffer(host,10,13,135,7);
        compile("A3",false,1,"A3",false,false);activate(host,stage("A3"),"A3",false);
        require(compute(host,19)==59L,"Fresh A3 arithmetic");buffer(host,11,14,136,8);
        invoke(host,"selectRetained",new Class<?>[]{String.class},"A1");
        require(get(host,"activeLoader")==aLoader,"Retained rollback did not retain lifetime");
        require(compute(host,23)==72L,"Code rollback reset state");buffer(host,12,15,137,9);
        require(get(host,"bufferAllocationId").equals(allocation),"Host allocation replaced");
        require((Long)get(host,"counter")==4L,"Expected one counter increment per successful call");
        require(!Arrays.equals(Files.readAllBytes(output.resolve("A1.jar")),Files.readAllBytes(output.resolve("A3.jar"))),"A3 was cached A1");
        try { Class.forName("feedbackplugin.Module",false,FeedbackConformance.class.getClassLoader());throw new AssertionError("Parent resolves plugin"); }
        catch(ClassNotFoundException expected) { checks++; }
    }
    private static void testRejections() throws Exception {
        Object host=host(12);activate(host,stage("A1"),"A1",false);
        byte[] good=Files.readAllBytes(output.resolve("A1.jar"));
        rejects(()->stage("wronghash",good,String.join("",Collections.nCopies(64,"0")),false),"hash");
        rejects(()->stage("../escape",good,hash(good),false),"unsafe identity");
        byte[] malformed={1,2,3};rejects(()->stage("malformed",malformed,hash(malformed),false),"malformed archive");
        rejects(()->stage("duplicate"),"duplicate resident namespace");
        Object tampered=stage("tampered",good,hash(good),false);
        File tamperedFile=(File)invoke(tampered,"file",new Class<?>[0]);
        require(tamperedFile.setWritable(true),"Cannot prepare real tamper control");
        Files.write(tamperedFile.toPath(),new byte[]{1,2,3});
        rejects(()->activate(host,tampered,"tampered",false),"artifact changed after staging");
        require((Integer)get(host,"attemptedLoads")==1,"Hash failure consumed a load or was checked too late");
        Object writable=stage("writable",good,hash(good),false);
        File writableFile=(File)invoke(writable,"file",new Class<?>[0]);
        require(writableFile.setWritable(true),"Cannot prepare writable control");
        rejects(()->activate(host,writable,"writable",false),"immutable artifact made writable after staging");
        require((Integer)get(host,"attemptedLoads")==1,"Writable artifact reached class loading");
        compile("wrongabi",false,2,"wrongabi",false,false);
        rejects(()->activate(host,stage("wrongabi"),"wrongabi",false),"ABI");
        compile("wrongid",false,1,"other",false,false);
        rejects(()->activate(host,stage("wrongid"),"wrongid",false),"compiled identity");
        compile("badprobe",false,1,"badprobe",true,false);
        rejects(()->activate(host,stage("badprobe"),"badprobe",false),"independent selftest");
        // Correct numeric result but wrong bytes must also reject.
        Object badBytes=probeType.getConstructor(long.class,long.class,byte[].class,long.class,byte[].class)
            .newInstance(11L,7L,new byte[]{0,1,127,(byte)255},40L,new byte[]{9,9,9,9});
        // Use a distinct compiled id so the byte check is reached rather than identity rejection.
        compile("bytes",false,1,"bytes",false,false);
        final Object checkedBytes=stage("bytes");
        rejects(()->invoke(host,"activate",new Class<?>[]{artifactType,String.class,String.class,probeType},checkedBytes,"feedbackplugin.Module","bytes",badBytes),"independent byte-transform probe");
        require(get(host,"activeGenerationId").equals("A1"),"Rejected candidate published");
        require((Long)get(host,"counter")==0L,"Rejected candidate changed counter");buffer(host,0,1,127,255);
        compile("failure",false,1,"failure",false,true);activate(host,stage("failure"),"failure",false);
        rejects(()->compute(host,999),"compute exception after scratch mutation");
        require((Long)get(host,"counter")==0L,"Failure changed counter");buffer(host,0,1,127,255);
        require(compute(host,5)==15L,"Host unusable after compute failure");
        // A caller's snapshot is not authority.
        byte[] snapshot=(byte[])get(host,"bufferSnapshot");snapshot[0]=55;buffer(host,1,2,128,0);
        Object capped=host(2);activate(capped,stage("A1"),"A1",false);
        rejects(()->activate(capped,stage("wrongabi"),"wrongabi",false),"failed load consumes retention budget");
        rejects(()->activate(capped,stage("A3"),"A3",false),"cap");
        require((Integer)get(capped,"attemptedLoads")==2,"Invalid loads absent from cap accounting");
        require(compute(capped,7)==21L,"Cap damaged active dispatch");
    }
    private static void testWrongParent() throws Exception {
        Object factory=Proxy.newProxyInstance(factoryType.getClassLoader(),new Class<?>[]{factoryType},(proxy,method,args)->{
            ClassLoader parent=(ClassLoader)args[1];
            ClassLoader intermediate=new java.net.URLClassLoader(new java.net.URL[0],parent);
            return new java.net.URLClassLoader(new java.net.URL[]{((File)args[0]).toURI().toURL()},intermediate);
        });
        Object host=hostType.getConstructor(factoryType,int.class,byte[].class).newInstance(factory,3,new byte[]{1,2});
        rejects(()->activate(host,stage("A1"),"A1",false),"unverified intermediate parent loader");
    }
    private static void testReentry() throws Exception {
        Object delegate=Class.forName("org.rusty.quest.feedback.HostUrlLoader").getConstructor().newInstance();
        Object[] host={null};int[] created={0};boolean[] rejected={false,false,false};
        Object factory=Proxy.newProxyInstance(factoryType.getClassLoader(),new Class<?>[]{factoryType},(proxy,method,args)->{
            if(created[0]++==1) {
                try { compute(host[0],31); } catch(IllegalStateException expected) { rejected[0]=true; }
                try { invoke(host[0],"selectRetained",new Class<?>[]{String.class},"A1"); } catch(IllegalStateException expected) { rejected[1]=true; }
                try { activate(host[0],stage("A3"),"A3",false); } catch(IllegalStateException expected) { rejected[2]=true; }
            }
            return invoke(delegate,"create",new Class<?>[]{File.class,ClassLoader.class},args);
        });
        host[0]=hostType.getConstructor(factoryType,int.class,byte[].class).newInstance(factory,8,new byte[]{0,1,127,(byte)255});
        activate(host[0],stage("A1"),"A1",false);
        rejects(()->activate(host[0],stage("wrongabi"),"wrongabi",false),"outer activation rejects after callback");
        require(rejected[0] && rejected[1] && rejected[2],"Activation callback reentered compute, selection or activation");
        require((Long)get(host[0],"counter")==0L,"Rejected activation callback mutated counter");buffer(host[0],0,1,127,255);
        require(get(host[0],"activeGenerationId").equals("A1"),"Rejected callback switched active code");
        require(compute(host[0],7)==21L,"Mutation guard did not reset after rejected activation");
    }
    private static void testExternalManifestDependency() throws Exception {
        compile("external",true,1,"external",false,false);
        Path classes=output.resolve("build-external/classes/feedbackplugin");
        Path helper=output.resolve("external-unstaged-helper.jar");
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(helper))) {
            jar.putNextEntry(new JarEntry("feedbackplugin/Helper.class"));jar.write(Files.readAllBytes(classes.resolve("Helper.class")));jar.closeEntry();
        }
        Manifest manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,helper.toUri().toString());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(JarOutputStream jar=new JarOutputStream(bytes,manifest)) {
            jar.putNextEntry(new JarEntry("feedbackplugin/Module.class"));jar.write(Files.readAllBytes(classes.resolve("Module.class")));jar.closeEntry();
        }
        rejects(()->stage("external-manifest",bytes.toByteArray(),hash(bytes.toByteArray()),false),"manifest imports actual unstaged helper");
    }
    private static void dex() throws Exception {
        for(String id:Arrays.asList("A1","B2","A3","duplicate")) {
            byte[] dex=Files.readAllBytes(output.resolve("dex-"+id).resolve("classes.dex"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(JarOutputStream jar=new JarOutputStream(bytes)){jar.putNextEntry(new JarEntry("classes.dex"));jar.write(dex);jar.closeEntry();}
            if(id.equals("duplicate"))rejects(()->stage("dex-"+id,bytes.toByteArray(),hash(bytes.toByteArray()),true),"resident contract in actual DEX definitions");
            else stage("dex-"+id,bytes.toByteArray(),hash(bytes.toByteArray()),true);
        }
        byte[] truncated={100,101,120,10,48,51,56,0};
        ByteArrayOutputStream invalid=new ByteArrayOutputStream();
        try(JarOutputStream jar=new JarOutputStream(invalid)){jar.putNextEntry(new JarEntry("classes.dex"));jar.write(truncated);jar.closeEntry();}
        rejects(()->stage("truncated-dex",invalid.toByteArray(),hash(invalid.toByteArray()),true),"malformed actual DEX");
        System.out.println("PASS actual D8 DEX definitions and duplicate-contract rejection");
    }
    public static void main(String[] args) throws Exception {
        boolean dex=args[0].equals("--dex");output=Paths.get(args[dex ? 1 : 0]);
        try {
            hostType=Class.forName("org.rusty.quest.feedback.ModuleHost");
            artifactType=Class.forName("org.rusty.quest.feedback.ArtifactStore$Artifact");
            probeType=Class.forName("org.rusty.quest.feedback.ModuleHost$Probe");
            factoryType=Class.forName("org.rusty.quest.feedback.GenerationLoader");
        } catch(ClassNotFoundException missing) { throw new AssertionError("Fresh-generation activation and failure isolation seam is missing",missing); }
        if(dex){dex();return;}javac=args[1];setup();testFreshSwap();testRejections();testWrongParent();testReentry();testExternalManifestDependency();
        System.out.println("PASS "+checks+" assertions: fresh A1/B2/A3, independent known answers, lifetime, rollback and negative controls; host JVM only");
    }
}
