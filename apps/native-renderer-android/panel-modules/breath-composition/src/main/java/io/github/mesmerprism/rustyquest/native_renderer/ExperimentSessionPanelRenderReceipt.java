package io.github.mesmerprism.rustyquest.native_renderer;

/** Exact command-owned rendered-content completion; never a foreground/XR observation. */
final class ExperimentSessionPanelRenderReceipt {
    final String commandId;
    final long serverGeneration, navigationRevision;
    final Object panel, content;
    private boolean closed;
    ExperimentSessionPanelRenderReceipt(String id,long generation,Object panel,Object content,long revision) {
        if(id==null || !id.matches("[0-9a-f]{16}") || generation<=0 || panel==null || content==null || revision<=0)
            throw new IllegalArgumentException("exact command/render identity required");
        commandId=id;serverGeneration=generation;this.panel=panel;this.content=content;navigationRevision=revision;
    }
    boolean complete(String id,long generation,Object panel,Object content,long revision,String topic,
            boolean postDraw,boolean resumed,boolean attached,boolean shown,boolean windowVisible,boolean destroyed) {
        if(closed || !commandId.equals(id) || generation!=serverGeneration || this.panel!=panel || this.content!=content ||
                revision!=navigationRevision || !"polar".equals(topic) || !postDraw || !resumed || !attached ||
                !shown || !windowVisible || destroyed) return false;
        closed=true;return true;
    }
    void cancel(){closed=true;}
}
