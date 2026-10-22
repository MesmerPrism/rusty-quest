package org.rusty.quest.feedback;

import org.rusty.quest.feedback.api.FeedbackModule;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous trusted-development seam. It does not sandbox arbitrary Java code. */
public final class ModuleHost {
    public static final int ABI_VERSION=1;
    private static final AtomicLong NEXT_ALLOCATION=new AtomicLong();
    private final GenerationLoader factory;
    private final int maximumLoads;
    private final byte[] buffer;
    private final long allocation=NEXT_ALLOCATION.incrementAndGet();
    private final List<ClassLoader> lifetimes=new ArrayList<>();
    private final Map<String,Generation> validated=new HashMap<>();
    private final Set<String> attemptedIds=new HashSet<>();
    private int attempts;
    private long counter;
    private Generation active;
    private boolean mutationInProgress;

    public static final class Probe {
        private final long input,counter,result;
        private final byte[] initial,expected;
        public Probe(long input,long counter,byte[] initial,long result,byte[] expected) {
            if(initial==null || expected==null || initial.length!=expected.length)throw new IllegalArgumentException("Invalid probe buffers");
            this.input=input;this.counter=counter;this.result=result;this.initial=initial.clone();this.expected=expected.clone();
        }
    }
    private static final class Generation {
        final String id;final ClassLoader loader;final FeedbackModule module;
        Generation(String id,ClassLoader loader,FeedbackModule module){this.id=id;this.loader=loader;this.module=module;}
    }
    public ModuleHost(GenerationLoader factory,int maximumLoads,byte[] initial) {
        if(factory==null || maximumLoads<1 || initial==null || initial.length==0)throw new IllegalArgumentException("Invalid host configuration");
        this.factory=factory;this.maximumLoads=maximumLoads;buffer=initial.clone();
    }
    /** Holds selection lock through initialization/selftest; publishes only after all checks pass. */
    public synchronized void activate(ArtifactStore.Artifact artifact,String entry,String id,Probe probe) {
        beginMutation();
        try { activateCandidate(artifact,entry,id,probe); }
        finally { mutationInProgress=false; }
    }
    private void beginMutation() {
        if(mutationInProgress)throw new IllegalStateException("Reentrant host mutation is forbidden");
        mutationInProgress=true;
    }
    private void activateCandidate(ArtifactStore.Artifact artifact,String entry,String id,Probe probe) {
        if(artifact==null || probe==null || id==null || !id.equals(artifact.generationId()) || !artifact.defines(entry))
            throw new IllegalArgumentException("Candidate identity/entry does not match verified artifact");
        if(attempts>=maximumLoads)throw new IllegalStateException("Generation cap reached; recycle host process");
        if(attemptedIds.contains(id))throw new IllegalArgumentException("Generation identity already attempted");
        try {
            artifact.verify();
            ClassLoader parent=FeedbackModule.class.getClassLoader();
            try { parent.loadClass(entry);throw new IllegalArgumentException("Parent already resolves implementation"); }
            catch(ClassNotFoundException expected) { /* implementation must be absent from parent */ }
            // Charge attempts before creation. An exception may have already loaded/initialized code.
            attempts++;attemptedIds.add(id);
            ClassLoader loader=factory.create(artifact.file(),parent);
            if(loader==null || loader==parent || lifetimes.contains(loader))throw new IllegalArgumentException("Factory did not create a fresh loader");
            lifetimes.add(loader);
            if(loader.getParent()!=parent)throw new IllegalArgumentException("Factory changed frozen parent loader");
            if(loader.loadClass(FeedbackModule.class.getName())!=FeedbackModule.class)throw new IllegalArgumentException("Contract loader identity differs");
            Class<?> implementation=Class.forName(entry,true,loader);
            if(implementation.getClassLoader()!=loader)throw new IllegalArgumentException("Implementation resolved from another loader");
            Object instance=implementation.getConstructor().newInstance();
            if(!(instance instanceof FeedbackModule))throw new IllegalArgumentException("Not a parent-contract implementation");
            FeedbackModule module=(FeedbackModule)instance;
            if(module.abiVersion()!=ABI_VERSION || !id.equals(module.generationId()))throw new IllegalArgumentException("ABI/compiled identity mismatch");
            byte[] scratch=probe.initial.clone();
            long result=module.compute(probe.input,probe.counter,scratch);
            if(result!=probe.result || !Arrays.equals(scratch,probe.expected))throw new IllegalArgumentException("Independent known-answer probe failed");
            Generation next=new Generation(id,loader,module);
            validated.put(id,next);active=next;
        } catch(IllegalArgumentException | IllegalStateException failure) { throw failure; }
        catch(Exception | LinkageError failure) { throw new IllegalArgumentException("Candidate load or validation failed",failure); }
    }
    public synchronized long compute(long input) {
        beginMutation();
        try {
            if(active==null)throw new IllegalStateException("No active generation");
            byte[] scratch=buffer.clone();
            long result=active.module.compute(input,counter,scratch);
            // No caller or module reference points into authority. Commit to the same allocation.
            System.arraycopy(scratch,0,buffer,0,buffer.length);counter++;
            return result;
        } finally { mutationInProgress=false; }
    }
    /** Code rollback only: host counter/buffer are unchanged. */
    public synchronized void selectRetained(String id) {
        beginMutation();
        try {
            Generation previous=validated.get(id);
            if(previous==null)throw new IllegalArgumentException("No validated retained generation");
            active=previous;
        } finally { mutationInProgress=false; }
    }
    public synchronized long counter(){return counter;}
    public synchronized byte[] bufferSnapshot(){return buffer.clone();}
    public long bufferAllocationId(){return allocation;}
    public synchronized int attemptedLoads(){return attempts;}
    public synchronized ClassLoader activeLoader(){if(active==null)throw new IllegalStateException("No active generation");return active.loader;}
    public synchronized String activeGenerationId(){if(active==null)throw new IllegalStateException("No active generation");return active.id;}
}
