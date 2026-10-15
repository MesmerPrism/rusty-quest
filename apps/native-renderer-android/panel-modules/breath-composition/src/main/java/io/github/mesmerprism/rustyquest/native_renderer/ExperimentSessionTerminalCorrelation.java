package io.github.mesmerprism.rustyquest.native_renderer;

/** Read-only join of an existing remote request to the canonical terminal result.
 * It creates no command, retry, writer, clock or terminal admission. */
final class ExperimentSessionTerminalCorrelation {
    final String requestId;
    final String operatorOrigin;
    final long carrierGeneration,nativeOwnerIdentity,nativeOwnerEpoch,runtimeEpoch;
    volatile long sessionGeneration;
    private final boolean requiresAdmission;
    private String admittedOperation="";
    private String admittedPredecessor="";
    private Result settled;
    ExperimentSessionTerminalCorrelation(String id,long carrier,long owner,long ownerEpoch,long runtime,long session,String origin) {
        this(id,carrier,owner,ownerEpoch,runtime,session,origin,false);
    }
    static ExperimentSessionTerminalCorrelation pending(String id,long carrier,long owner,long ownerEpoch,long runtime,String origin) {
        return new ExperimentSessionTerminalCorrelation(id,carrier,owner,ownerEpoch,runtime,-1L,origin,true);
    }
    private ExperimentSessionTerminalCorrelation(String id,long carrier,long owner,long ownerEpoch,long runtime,long session,String origin,boolean pending) {
        if(id==null||!id.matches("[0-9a-f]{16}")||carrier<=0||owner<=0||ownerEpoch<=0||runtime<=0||session<(pending?-1L:0L))
            throw new IllegalArgumentException("terminal-observation-origin-invalid");
        operatorOrigin="shell-experiment-operator-v1".equals(origin)?origin:"";
        requestId=id;carrierGeneration=carrier;nativeOwnerIdentity=owner;nativeOwnerEpoch=ownerEpoch;runtimeEpoch=runtime;sessionGeneration=session;
        requiresAdmission=pending;
    }
    /** Only the existing shell's authoritative command observation calls this. */
    synchronized boolean admit(long runtime,long session,String operation,String rejectedPredecessor) {
        if(!requiresAdmission||settled!=null||runtime!=runtimeEpoch||session<0||operation==null
                ||!operation.matches("panel-save-and-exit-[1-9][0-9]*")||rejectedPredecessor==null)return false;
        if(admittedOperation.isEmpty()) {
            if(!rejectedPredecessor.isEmpty())return false;
            sessionGeneration=session;admittedOperation=operation;return true;
        }
        if(session!=sessionGeneration)return false;
        if(operation.equals(admittedOperation))return rejectedPredecessor.equals(admittedPredecessor);
        if(!admittedOperation.equals(rejectedPredecessor))return false;
        admittedPredecessor=rejectedPredecessor;admittedOperation=operation;return true;
    }
    static final class Result {
        final String operationId;final long shutdownAckRevision;
        Result(String operation,long ack){operationId=operation;shutdownAckRevision=ack;}
    }
    synchronized Result observe(long carrier,long owner,long ownerEpoch,long runtime,long session,
            String operation,long ack,boolean shouldFinish,boolean saved) {
        if(requiresAdmission&&(admittedOperation.isEmpty()||!admittedOperation.equals(operation)))return null;
        if(carrier!=carrierGeneration||owner!=nativeOwnerIdentity||ownerEpoch!=nativeOwnerEpoch
                ||runtime!=runtimeEpoch||session!=sessionGeneration||!shouldFinish||!saved||ack<=0
                ||operation==null||!operation.matches("panel-save-and-exit-[1-9][0-9]*"))return null;
        if(settled!=null)return settled.operationId.equals(operation)&&settled.shutdownAckRevision==ack?settled:null;
        settled=new Result(operation,ack);return settled;
    }
}
