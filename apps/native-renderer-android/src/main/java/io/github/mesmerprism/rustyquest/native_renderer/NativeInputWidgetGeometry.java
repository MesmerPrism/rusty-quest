package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.Arrays;

/** Pure measured-layout revision owner; never reads or invokes widget content. */
final class NativeInputWidgetGeometry {
    private String signature;
    private long revision;
    static final class Snapshot {
        final long generation, revision;
        final int width, height;
        final int[] bounds, clipped;
        final boolean enabled, visible;
        Snapshot(long generation,long revision,int width,int height,int[] bounds,int[] clipped,boolean enabled,boolean visible) {
            this.generation=generation;this.revision=revision;this.width=width;this.height=height;
            this.bounds=bounds.clone();this.clipped=clipped.clone();this.enabled=enabled;this.visible=visible;
        }
    }
    static int[] clip(int[] bounds,int[] visible,int width,int height) {
        if(bounds==null||visible==null||bounds.length!=4||visible.length!=4)throw new IllegalArgumentException("Measured rectangles required");
        int left=Math.max(0,Math.max(bounds[0],visible[0])), top=Math.max(0,Math.max(bounds[1],visible[1]));
        int right=Math.min(width,Math.min(bounds[2],visible[2])), bottom=Math.min(height,Math.min(bounds[3],visible[3]));
        return right>left&&bottom>top?new int[]{left,top,right,bottom}:new int[4];
    }
    Snapshot observe(boolean selected,boolean current,long generation,int width,int height,int[] bounds,int[] visible,boolean enabled,boolean shown) {
        if(!selected||!current||generation<=0||width<=0||height<=0||width>8192||height>8192) return null;
        int[] clipped=shown?clip(bounds,visible,width,height):new int[4];
        String next=generation+":"+width+":"+height+":"+Arrays.toString(bounds)+":"+Arrays.toString(clipped)+":"+enabled+":"+shown;
        if(!next.equals(signature)) {if(revision>=9007199254740991L)return null;signature=next;revision++;}
        return new Snapshot(generation,revision,width,height,bounds,clipped,enabled,shown);
    }
}
