package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.reflect.Method;

/** Pure production window identity/lifetime fixture; no simulated Android windows. */
public final class NativePageWindowLifetimeTest {
    private static int assertions;
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] arguments)throws Exception {
        Class<?> type;
        try {type=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.NativeMeshUiProducer$WindowRegistry");}
        catch(ClassNotFoundException missing){throw new AssertionError("modal lifetime registry absent",missing);}
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object registry=constructor.newInstance();
        Method opened=type.getDeclaredMethod("opened",Object.class),closed=type.getDeclaredMethod("closed",Object.class),get=type.getDeclaredMethod("get",Object.class);
        opened.setAccessible(true);closed.setAccessible(true);get.setAccessible(true);
        Object first=new Object(),second=new Object();
        check(get.invoke(registry,first)==null,"unknown window acquired identity");
        check((Boolean)opened.invoke(registry,first),"new window did not invalidate content");
        long original=(Long)get.invoke(registry,first);check(original>0,"modal identity collided with root zero");
        check(!(Boolean)opened.invoke(registry,first),"repeat open manufactured another transition");
        check((Long)get.invoke(registry,first)==original,"live window identity changed");
        check((Boolean)opened.invoke(registry,second),"second window lost transition");
        long other=(Long)get.invoke(registry,second);check(other>original,"different window reused older identity");
        check((Boolean)closed.invoke(registry,first),"dismiss did not invalidate captured content");
        check(get.invoke(registry,first)==null,"dismissed window retained identity");
        check(!(Boolean)closed.invoke(registry,first),"repeat dismissal manufactured transition");
        check((Long)get.invoke(registry,second)==other,"closing one window retired its sibling");
        check((Boolean)opened.invoke(registry,first),"reopened window lost transition");
        check((Long)get.invoke(registry,first)>other,"reopened window reused stale capture identity");
        Object equalA=new Object(){public boolean equals(Object other){return true;}},equalB=new Object(){public boolean equals(Object other){return true;}};
        check((Boolean)opened.invoke(registry,equalA),"equal-valued first window rejected");
        check((Boolean)opened.invoke(registry,equalB),"identity-distinct equal-valued window rejected");
        check(!get.invoke(registry,equalA).equals(get.invoke(registry,equalB)),"value equality merged actual window owners");
        System.out.println("NativePageWindowLifetimeTest PASS assertions="+assertions+" scope=stable-live-window-identity-exact-open-dismiss-transitions");
    }
}
