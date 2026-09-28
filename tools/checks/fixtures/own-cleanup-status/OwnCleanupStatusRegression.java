package io.github.mesmerprism.rustyquest.media;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Allocates fixture instances without Android/EGL constructors; executes the actual owner getters. */
public final class OwnCleanupStatusRegression {
    private static Object fixture(Class<?> type) throws Exception {
        Class<?> factory = Class.forName("sun.reflect.ReflectionFactory");
        Object instance = factory.getMethod("getReflectionFactory").invoke(null);
        Constructor<?> constructor = (Constructor<?>) factory
                .getMethod("newConstructorForSerialization", Class.class, Constructor.class)
                .invoke(instance, type, Object.class.getDeclaredConstructor());
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
    private static void set(Object object, String field, Object value) throws Exception {
        Field member = object.getClass().getDeclaredField(field);
        member.setAccessible(true); member.set(object, value);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        PackedStereoCaptureOwner owner = (PackedStereoCaptureOwner) fixture(PackedStereoCaptureOwner.class);
        Class<?> endpoint = Class.forName(PackedStereoCaptureOwner.class.getName()+"$Endpoint");
        Constructor<?> make = endpoint.getDeclaredConstructor(PackedStereoCaptureOwner.class,String.class,String.class);
        make.setAccessible(true); Object left=make.newInstance(owner,"fixture-left","left");
        set(owner,"left",left);
        check(owner.cleanupStatus().leftCallbackBarrier.equals("STOP_NOT_REQUESTED"));
        set(left,"closeRequested",true); set(left,"openRequested",true);
        check(owner.cleanupStatus().leftCallbackBarrier.equals("OPEN_CALLBACK_PENDING"));
        set(left,"openSettled",true);set(left,"sessionRequested",true);
        check(owner.cleanupStatus().leftCallbackBarrier.equals("SESSION_CALLBACK_PENDING"));
        set(left,"sessionSettled",true);
        check(owner.cleanupStatus().leftCallbackBarrier.equals("TERMINAL"));
        check(!owner.pollStopped());
        set(owner,"stopRequested",true);set(owner,"startupSettled",true);
        PackedStereoGlCompositor compositor=(PackedStereoGlCompositor)fixture(PackedStereoGlCompositor.class);
        set(compositor,"thread",new Thread());set(owner,"compositor",compositor);
        set(compositor,"cleanupBarrier",PackedStereoGlCompositor.CleanupBarrier.NATIVE_POOL_PENDING);
        check(owner.cleanupStatus().compositorBarrier.equals("NATIVE_POOL_PENDING"));
        check(!owner.cleanupStatus().compositorPhysicallyRetired);check(!owner.pollStopped());
        set(compositor,"cleanupRejected",true);
        check(owner.cleanupStatus().compositorCleanupRejected);check(!owner.pollStopped());
        set(compositor,"cleanupBarrier",PackedStereoGlCompositor.CleanupBarrier.CAMERA_CALLBACKS_PENDING);
        check(owner.cleanupStatus().compositorBarrier.equals("CAMERA_CALLBACKS_PENDING"));
        check(!owner.pollStopped());
        set(compositor,"physicallyRetired",true);set(compositor,"cleanupBarrier",PackedStereoGlCompositor.CleanupBarrier.TERMINAL);
        check(owner.cleanupStatus().compositorPhysicallyRetired);check(owner.pollStopped());
        System.out.println("Actual owner callback/pool/compositor classification and independent terminal guard: PASS (fixture observations only)");
    }
}
