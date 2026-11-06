package io.github.mesmerprism.rustymanifold.broker;

import java.util.*;

/** Actual descriptor validation, with synthetic OS identity evidence only. */
public final class HubReadOnlySurfaceTest {
    private static int controls;
    private static void check(boolean value, String reason) {
        controls++; if (!value) throw new AssertionError(reason);
    }
    private static final HubProviderIdentity ID = new HubProviderIdentity(10001, "io.github.example.observer",
        new String(new char[64]).replace('\0','a'));
    private static HubSurfaceDescriptor descriptor(List<HubSurfaceDescriptor.Command> commands, String hash) {
        return new HubSurfaceDescriptor(1, "surface.example.observation", "Observation", "Read-only host fixture", ID, commands, hash);
    }
    private static String hash(List<HubSurfaceDescriptor.Command> commands) {
        return HubSurfaceDescriptor.contractSha256("surface.example.observation", "Observation", "Read-only host fixture", commands);
    }
    private static void denied(Runnable action, String reason) {
        try { action.run(); } catch (RuntimeException expected) { controls++; return; }
        throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        List<HubSurfaceDescriptor.Command> empty = Collections.emptyList();
        HubSurfaceDescriptor readOnly = descriptor(empty, hash(empty));
        check(readOnly.commands().isEmpty(), "read-only surface has no commands");
        for (String command : Arrays.asList("status", "command.start", "command.pause", "", "command.example.play"))
            check(!readOnly.permits(command), "read-only permits nothing: " + command);
        check(readOnly.providerIdentity() == ID, "identity remains server-substituted");
        denied(() -> descriptor(null, hash(empty)), "null is not an explicit empty allowlist");
        denied(() -> descriptor(empty, "sha256:" + new String(new char[64]).replace('\0','b')), "read-only contract hash still bound");
        HubSurfaceDescriptor.Command c = new HubSurfaceDescriptor.Command("command.example.play", "Play", "capability.example.play");
        List<HubSurfaceDescriptor.Command> one = Collections.singletonList(c);
        check(descriptor(one, hash(one)).permits(c.commandId()), "ordinary command remains admitted by descriptor");
        denied(() -> descriptor(Arrays.asList(c,c), hash(Arrays.asList(c,c))), "duplicate commands denied");
        HubSurfaceDescriptor.Command a = new HubSurfaceDescriptor.Command("command.example.a", "A", "capability.example.play");
        denied(() -> descriptor(Arrays.asList(c,a), hash(Arrays.asList(c,a))), "unordered commands denied");
        List<HubSurfaceDescriptor.Command> oversized = new ArrayList<>();
        for (int i=0;i<=ConnectionHubProtocol.MAX_COMMANDS;i++) oversized.add(new HubSurfaceDescriptor.Command(String.format("command.example.c%03d",i),"Command","capability.example.play"));
        denied(() -> descriptor(oversized, hash(oversized)), "maximum command count unchanged");
        denied(() -> new HubSurfaceDescriptor(1,"surface.example.observation","Observation","Read-only host fixture",null,empty,hash(empty)), "missing OS identity denied");
        denied(() -> new HubSurfaceDescriptor.Command("command.example.play","Play","bad capability"), "malformed capability denied");
        System.out.println("HubReadOnlySurfaceTest PASS controls="+controls+" actual descriptor; synthetic OS identity; no Binder/socket/grant");
    }
}
