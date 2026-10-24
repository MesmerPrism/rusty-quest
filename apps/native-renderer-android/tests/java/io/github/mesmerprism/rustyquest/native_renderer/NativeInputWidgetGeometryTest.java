package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.Arrays;

public final class NativeInputWidgetGeometryTest {
    static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    public static void main(String[] args){
        NativeInputWidgetGeometry owner=new NativeInputWidgetGeometry();
        int[] bounds={40,-10,984,58},visible={40,0,984,58};
        check(owner.observe(false,true,7,1024,768,bounds,visible,true,true)==null,"unselected observer active");
        check(owner.observe(true,false,7,1024,768,bounds,visible,true,true)==null,"stale producer active");
        NativeInputWidgetGeometry.Snapshot first=owner.observe(true,true,7,1024,768,bounds,visible,true,true);
        check(first.revision==1,"disabled/stale observations consumed revision");
        check(Arrays.equals(first.clipped,new int[]{40,0,984,58}),"off-surface widget not clipped");
        check(owner.observe(true,true,7,1024,768,bounds,visible,true,true).revision==1,"stable layout revision changed");
        check(owner.observe(true,true,7,1024,768,bounds,visible,false,true).revision==2,"disabled state not revisioned");
        check(Arrays.equals(owner.observe(true,true,7,1024,768,bounds,visible,true,false).clipped,new int[4]),"hidden widget retains a target");
        bounds[0]=77;check(first.bounds[0]==40,"snapshot bounds mutated by caller");
        System.out.println("NativeInputWidgetGeometryTest PASS selected/current/clipped/revision/hidden/snapshot");
    }
}
