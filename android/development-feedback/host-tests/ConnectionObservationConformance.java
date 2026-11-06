import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import org.rusty.quest.feedback.*;
import org.rusty.quest.feedback.api.FeedbackModule;
import org.rusty.quest.feedback.ConnectionObservationController.*;

/** Independent resident consumer. Effects and durable product UNKNOWN are outside this fixture. */
public final class ConnectionObservationConformance {
    private static Path templates, output;
    private static String javac;
    private static int assertions;
    private static final byte[] ZERO = new byte[4];
    private static final class HeldOwner {
        final Object resource = new Object();
        final byte[] key = {7, 3, 9};
        final CompletableFuture<String> pending = new CompletableFuture<>();
        final String unresolvedOriginal = "fixture-original-unknown";
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static Snapshot sample(long owner, long operation, long version, long now, long last,
                                   boolean current, boolean closed, boolean cancelled, boolean observing) {
        return new Snapshot(owner, operation, version, now, last, current, closed, cancelled, observing, true);
    }
    private static Snapshot ready() { return sample(11, 23, 7, 1500, 1000, true, false, false, false); }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b: MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static void jar(Path classes, Path file) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file));
             java.util.stream.Stream<Path> files = Files.walk(classes)) {
            for (Path path: (Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));
                out.write(Files.readAllBytes(path)); out.closeEntry();
            }
        }
    }
    private static ArtifactStore.Artifact candidate(String id, int abi, boolean expanded, String fault) throws Exception {
        Path dir = Files.createDirectory(output.resolve(id));
        Path src = Files.createDirectories(dir.resolve("src/connectionfeedback"));
        String text = new String(Files.readAllBytes(templates.resolve("Policy.java.template")), StandardCharsets.UTF_8)
            .replace("@GENERATION@",id).replace("@ABI@",Integer.toString(abi))
            .replace("@EXTRA@",expanded ? "|| PollDecision.due(event)" : "")
            .replace("@FAULT@",fault);
        Path policy=src.resolve("Policy.java"); Files.write(policy,text.getBytes(StandardCharsets.UTF_8));
        Path classes=Files.createDirectory(dir.resolve("classes"));
        List<String> command=new ArrayList<>(Arrays.asList(javac,"--release","8","-encoding","UTF-8",
            "-Xlint:all","-Werror","-classpath",output.resolve("contract.jar").toString(),"-d",classes.toString(),policy.toString()));
        if (expanded) {
            Path helper=src.resolve("PollDecision.java"); Files.copy(templates.resolve("PollDecision.java.template"),helper);
            command.add(helper.toString());
        }
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("compile.log").toFile()).start();
        check(process.waitFor()==0,"Candidate compilation: "+id);
        Path file=dir.resolve(id+".jar"); jar(classes,file); byte[] bytes=Files.readAllBytes(file);
        return ArtifactStore.stage(output.resolve("staged").toFile(),id,new ByteArrayInputStream(bytes),digest(bytes),false);
    }
    private static void activate(ConnectionObservationController controller,String id,boolean expanded,String fault) throws Exception {
        controller.activate(candidate(id,1,expanded,fault),"connectionfeedback.Policy",id,
            new ModuleHost.Probe(0,19,ZERO,0,ZERO));
    }
    private static Decision status(ConnectionObservationController c) {
        Decision decision=c.evaluate(ConnectionObservationController.OBSERVATION_FAILED,ready());
        check(decision.action==Action.REQUEST_STATUS,"A1 failure must request status"); return decision;
    }
    private static void reject(ConnectionObservationController c,Snapshot snapshot,String message) {
        check(!c.claimStatus(status(c),snapshot),message);
    }
    public static void main(String[] args) throws Exception {
        templates=Paths.get(args[0]); output=Files.createDirectory(Paths.get(args[1])); javac=args[2];
        Path contract=Files.createDirectories(output.resolve("contract/org/rusty/quest/feedback/api"));
        try (InputStream in=FeedbackModule.class.getResourceAsStream("FeedbackModule.class")) {
            if(in==null)throw new AssertionError("Parent interface absent");
            Files.copy(in,contract.resolve("FeedbackModule.class"));
        }
        jar(output.resolve("contract"),output.resolve("contract.jar"));
        HeldOwner held=new HeldOwner(); Object resource=held.resource; byte[] key=held.key;
        CompletableFuture<String> pending=held.pending;
        ConnectionObservationController c=new ConnectionObservationController(new HostUrlLoader(),8);
        long allocation=c.bufferAllocationId();
        activate(c,"A1",false,"");
        Decision first=status(c);
        check(c.claimStatus(first,ready()),"Current due status proposal rejected");
        check(!c.claimStatus(first,ready()),"Decision replay accepted");
        Decision concurrent=status(c);
        CountDownLatch start=new CountDownLatch(1);
        AtomicInteger claims=new AtomicInteger();
        List<Thread> workers=new ArrayList<>();
        for(int i=0;i<12;i++) {
            Thread worker=new Thread(() -> {
                try { start.await(); if(c.claimStatus(concurrent,ready()))claims.incrementAndGet(); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            }); workers.add(worker); worker.start();
        }
        start.countDown(); for(Thread worker:workers)worker.join();
        check(claims.get()==1,"Concurrent decision replay accepted");
        check(c.evaluate(1,ready()).action==Action.WAIT,"A unexpectedly polls on due-only event");
        for (Snapshot blocked: Arrays.asList(
            sample(11,23,7,1499,1000,true,false,false,false),
            sample(11,23,7,999,1000,true,false,false,false),
            sample(11,23,7,1500,1000,false,false,false,false),
            sample(11,23,7,1500,1000,true,true,false,false),
            sample(11,23,7,1500,1000,true,false,true,false),
            sample(11,23,7,1500,1000,true,false,false,true))) {
            long count=c.computationCount();
            check(c.evaluate(2,blocked).action==Action.WAIT,"Resident guard bypassed");
            check(c.computationCount()==count,"Ineligible observation entered module");
            reject(c,blocked,"Late guard change accepted");
        }
        reject(c,sample(12,23,7,1500,1000,true,false,false,false),"Changed owner accepted");
        reject(c,sample(11,24,7,1500,1000,true,false,false,false),"Changed operation accepted");
        reject(c,sample(11,23,8,1500,1000,true,false,false,false),"Changed sample accepted");
        reject(c,sample(11,23,7,2000,1500,true,false,false,false),"Changed prior poll accepted");
        reject(c,sample(11,23,7,1400,900,true,false,false,false),"Backwards completion clock accepted");
        reject(c,new Snapshot(11,23,7,1500,1000,true,false,false,false,false),"Changed unresolved sample accepted");
        Decision stale=status(c);
        long retainedCount=c.computationCount(); byte[] retainedBuffer=c.bufferSnapshot();
        activate(c,"B2",true,"");
        check(c.computationCount()==retainedCount && Arrays.equals(c.bufferSnapshot(),retainedBuffer),"Fresh B activation changed host state");
        check(!c.claimStatus(stale,ready()),"Selection change accepted old decision");
        check(c.evaluate(1,ready()).action==Action.REQUEST_STATUS,"B helper edit not executed");
        activate(c,"A3",false,"");
        check(c.evaluate(1,ready()).action==Action.WAIT,"Fresh A3 did not restore A behavior");
        retainedCount=c.computationCount(); retainedBuffer=c.bufferSnapshot();
        c.selectRetained("B2");
        check(c.computationCount()==retainedCount && Arrays.equals(c.bufferSnapshot(),retainedBuffer),"Retained rollback changed host state");
        check(c.evaluate(1,ready()).action==Action.REQUEST_STATUS,"Retained B rollback failed");
        long before=c.computationCount(); byte[] buffer=c.bufferSnapshot();
        Decision beforeReject=status(c); before=c.computationCount();
        try {
            c.activate(candidate("BadABI",2,false,""),"connectionfeedback.Policy","BadABI",new ModuleHost.Probe(0,19,ZERO,0,ZERO));
            throw new AssertionError("Invalid ABI admitted");
        } catch (IllegalArgumentException expected) { check(c.activeGenerationId().equals("B2"),"Rejection changed active code"); }
        check(!c.claimStatus(beforeReject,ready()),"Selection attempt left old proposal usable");
        check(c.computationCount()==before && Arrays.equals(buffer,c.bufferSnapshot()),"Activation rejection changed host state");
        activate(c,"Throw",true,"if(input!=0){scratch[0]=99;throw new IllegalStateException(\"fixture failure\");}");
        before=c.computationCount(); buffer=c.bufferSnapshot();
        Decision failed=c.evaluate(2,ready());
        check(failed.action==Action.WAIT && failed.reason==Reason.MODULE_FAILURE,"Thrown call not surfaced as safe wait");
        check(c.computationCount()==before && Arrays.equals(buffer,c.bufferSnapshot()),"Thrown call committed host state");
        check(c.activeGenerationId().equals("Throw"),"Adapter pretended automatic rollback");
        c.selectRetained("A1"); check(c.claimStatus(status(c),ready()),"Explicit fallback unusable");
        activate(c,"Malformed",false,"if(input!=0)return 9;");
        Decision invalid=c.evaluate(2,ready());
        check(invalid.action==Action.WAIT && invalid.reason==Reason.INVALID_RESULT,"Open result value accepted");
        check(!c.claimStatus(invalid,ready()),"Malformed result became a status claim");
        activate(c,"Blocked",false,"if((input&255)==3){java.util.concurrent.CountDownLatch entered=(java.util.concurrent.CountDownLatch)System.getProperties().get(\"feedback.conformance.entered\");java.util.concurrent.CountDownLatch release=(java.util.concurrent.CountDownLatch)System.getProperties().get(\"feedback.conformance.release\");entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}");
        Decision beforeBlock=status(c);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        System.getProperties().put("feedback.conformance.entered",entered);
        System.getProperties().put("feedback.conformance.release",release);
        AtomicReference<Throwable> backgroundFailure=new AtomicReference<>();
        Thread computing=new Thread(() -> { try { c.evaluate(3,ready()); } catch(Throwable error) { backgroundFailure.set(error); } });
        computing.start();
        FutureTask<Boolean> independentClaim=new FutureTask<>(() -> c.claimStatus(beforeBlock,ready()));
        Thread claiming=new Thread(independentClaim);
        try {
            check(entered.await(2,TimeUnit.SECONDS),"Module did not enter bounded blocking control");
            claiming.start();
            check(independentClaim.get(2,TimeUnit.SECONDS),"Resident acceptance waited for module lock");
        } finally {
            release.countDown(); computing.join(); claiming.join();
            System.getProperties().remove("feedback.conformance.entered");
            System.getProperties().remove("feedback.conformance.release");
        }
        check(backgroundFailure.get()==null,"Blocking control failed unexpectedly");
        c.selectRetained("A1");
        ConnectionObservationController other=new ConnectionObservationController(new HostUrlLoader(),1);
        check(!other.claimStatus(status(c),ready()),"Cross-controller decision accepted");
        Decision consumed=status(c);
        check(!c.claimStatus(consumed,sample(12,23,7,1500,1000,true,false,false,false)),"Changed owner accepted");
        check(!c.claimStatus(consumed,ready()),"Invalidated decision resurrected");
        try { c.evaluate(99,ready()); throw new AssertionError("Unknown event accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(c.bufferAllocationId()==allocation && Arrays.equals(c.bufferSnapshot(),ZERO),"Swap changed resident host allocation/buffer");
        check(held.resource==resource && held.key==key && Arrays.equals(key,new byte[]{7,3,9}),"Resident resource/key changed");
        check(held.pending==pending && !pending.isDone() && held.unresolvedOriginal.equals("fixture-original-unknown"),"Pending/UNKNOWN fixture state changed");
        System.out.println("PASS "+assertions+" assertions: guarded connection hints, A/B/A, rejection, explicit rollback, stale/one-use claims and held resident state.");
        System.out.println("Host JVM only; no transport, durable product UNKNOWN, Android/ART, APK or device claim.");
    }
}
