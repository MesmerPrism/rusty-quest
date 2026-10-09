package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ExperimentSessionStatusObservationTest {
    private static int controls;
    private static final List<String> fixtures = new ArrayList<>();
    private static void check(boolean yes, String why) {
        controls++; if (!yes) throw new AssertionError(why);
    }
    private static ExperimentSessionPanelCoordinator.NativeReceipt receipt(long g, long r) {
        return new ExperimentSessionPanelCoordinator.NativeReceipt("", true, true, g, r,
            "idle", "none", false, false, "ready", true, 0L, 0L, 0L, 0L,
            true, true, false, 0L, 0L, 0L, "none", 0L, "host control");
    }
    private static long sequence(String packet) {
        return Long.parseLong(packet.split("\"sequence\":")[1].split(",")[0]);
    }
    private static void unknown(String packet, String why) {
        check(packet.contains("\"source_state\":\"unknown\",\"source_age_ms\":null,\"status\":null"), why);
        fixtures.add(packet);
    }
    public static void main(String[] args) throws Exception {
        ExperimentSessionPanelCoordinator c = new ExperimentSessionPanelCoordinator();
        ExperimentSessionStatusObservation p = new ExperimentSessionStatusObservation("trial", 7L);
        unknown(p.project(c.nativeStatusSnapshot(1L)), "initial state is not a native observation");
        c.acceptNativeReadback(7L, receipt(1, 2), 1000000L, 1000000L);
        String first = p.project(c.nativeStatusSnapshot(1999999L)); fixtures.add(first);
        check(first.contains("\"source_state\":\"fresh\"") && first.contains("\"source_age_ms\":0"), "actual atomic producer and floor");
        String poll = p.project(c.nativeStatusSnapshot(3000000L)); fixtures.add(poll);
        check(sequence(first) == sequence(poll) && poll.contains("\"source_age_ms\":2"), "poll never allocates new source sequence");
        c.openDeveloper(c.allocateRouteEvent());
        String missing = p.project(c.nativeStatusSnapshot(4000000L));
        unknown(missing, "local state change removes native observation");
        check(sequence(missing) > sequence(first), "unavailable retires old browser value");
        unknown(p.project(null), "null producer does not restore status");
        // Replay the previously authentic atomic object after unavailable.
        ExperimentSessionPanelCoordinator d = new ExperimentSessionPanelCoordinator();
        d.acceptNativeReadback(7L, receipt(1, 2), 1000000L, 1000000L);
        unknown(p.project(d.nativeStatusSnapshot(5000000L)), "same read identity cannot restore after unknown");
        c.acceptNativeReadback(7L, receipt(1, 3), 6000000L, 6000000L);
        String next = p.project(c.nativeStatusSnapshot(6000000L)); fixtures.add(next);
        check(sequence(next) > sequence(missing) && next.contains("\"source_state\":\"fresh\""), "new real read can restore");
        unknown(p.project(c.nativeStatusSnapshot(5999999L)), "clock regression removes observation");
        c.admitTrustedColdLaunch(1L);
        c.acceptNativeReadback(8L, receipt(1, 1), 7000000L, 7000000L);
        unknown(p.project(c.nativeStatusSnapshot(7000000L)), "new runtime cannot enter old selected subscription");
        ExperimentSessionStatusObservation replacement = new ExperimentSessionStatusObservation("trial", 8L);
        check(replacement.project(c.nativeStatusSnapshot(8000000L)).contains("\"source_state\":\"fresh\""), "explicit replacement epoch scope");
        // Actual immutable state and typed witness adapter exercise all closed enums.
        for (ExperimentSessionPanelState.Completion completion : ExperimentSessionPanelState.Completion.values()) {
            ExperimentSessionPanelState s = state(1L, 1L, ExperimentSessionPanelState.Phase.IDLE, completion);
            String value = new ExperimentSessionStatusObservation("trial", 7L).project(snapshot(s, 1L, 0L));
            check(value.contains("\"completion\":\"" + (completion.name().equals("NOT_REACHED") ? "NONE" : completion.name()) + "\""), "explicit completion " + completion);
            fixtures.add(value);
        }
        for (ExperimentSessionPanelState.Phase phase : ExperimentSessionPanelState.Phase.values()) {
            String value = new ExperimentSessionStatusObservation("trial", 7L).project(snapshot(state(1, 1, phase, ExperimentSessionPanelState.Completion.UNKNOWN), 1, 0));
            if (phase == ExperimentSessionPanelState.Phase.UNAVAILABLE) unknown(value, "unavailable phase is not fresh");
            else { check(value.contains("\"phase\":\"" + phase + "\""), "closed phase " + phase); fixtures.add(value); }
        }
        ExperimentSessionStatusObservation bounds = new ExperimentSessionStatusObservation("trial", 7L);
        unknown(bounds.project(snapshot(state(9007199254740992L, 1, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 1, 0)), "unsafe g denied");
        unknown(bounds.project(snapshot(state(1, 9007199254740992L, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 1, 0)), "unsafe revision denied");
        ExperimentSessionPanelState unsafeActive = new ExperimentSessionPanelState(ExperimentSessionPanelState.Route.EXPERIMENTER,
            ExperimentSessionPanelState.Phase.IDLE, 1, 1, "none", "", 0L, null, null, false,
            ExperimentSessionPanelState.Completion.UNKNOWN, Long.MAX_VALUE, false, "unknown", false, "");
        unknown(bounds.project(snapshot(unsafeActive, 1, 0)), "unsafe active time denied");
        unknown(bounds.project(snapshot(state(1, 1, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 1, -1)), "negative age denied");
        unknown(bounds.project(snapshot(state(1, 1, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 0, 0)), "zero read denied");
        ExperimentSessionPanelState s = state(1, 9, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN);
        bounds.project(snapshot(s, 1, 5));
        unknown(bounds.project(snapshot(s, 1, 4)), "age rollback denied");
        unknown(bounds.project(snapshot(state(1, 8, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 2, 0)), "revision rollback denied");
        check(bounds.project(snapshot(state(2, 1, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 3, 0)).contains("\"source_state\":\"fresh\""), "new session may reset revision");
        unknown(bounds.project(snapshot(state(2, 1, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN), 3, 0)), "same read cannot project a different immutable state object");
        java.lang.reflect.Field seq = ExperimentSessionStatusObservation.class.getDeclaredField("sequence"); seq.setAccessible(true); seq.setLong(bounds, 9007199254740990L);
        ExperimentSessionPanelState saturated = state(2, 2, ExperimentSessionPanelState.Phase.IDLE, ExperimentSessionPanelState.Completion.UNKNOWN);
        unknown(bounds.project(snapshot(saturated, 4, 0)), "sequence exhaustion retires source");
        unknown(bounds.project(snapshot(saturated, 5, 0)), "exhaustion remains closed");
        String worst = new ExperimentSessionStatusObservation(new String(new char[64]).replace('\0','a'), Long.MAX_VALUE).project(
            new ExperimentSessionPanelCoordinator.NativeStatusSnapshot(state(9007199254740991L,9007199254740991L,ExperimentSessionPanelState.Phase.PAUSED,ExperimentSessionPanelState.Completion.PERSISTENCE_PENDING), "unused", true, Long.MAX_VALUE, Long.MAX_VALUE, 9007199254740991L));
        check(worst.getBytes(StandardCharsets.UTF_8).length <= 1024, "maximum closed projection fits bound"); fixtures.add(worst);
        try { new ExperimentSessionStatusObservation("bad\"channel", 7); throw new AssertionError(); } catch (IllegalArgumentException expected) { controls++; }
        Files.write(Paths.get(args[0]), fixtures, StandardCharsets.UTF_8);
        System.out.println("ExperimentSessionStatusObservationTest PASS controls=" + controls + " fixtures=" + fixtures.size() + "; actual coordinator / modeled native receipts; no transport or physical proof");
    }
    private static ExperimentSessionPanelState state(long g, long r, ExperimentSessionPanelState.Phase phase, ExperimentSessionPanelState.Completion completion) {
        return new ExperimentSessionPanelState(ExperimentSessionPanelState.Route.EXPERIMENTER,phase,g,r,"none","",0L,null,null,false,completion,0L,false,"unknown",false,"");
    }
    private static ExperimentSessionPanelCoordinator.NativeStatusSnapshot snapshot(ExperimentSessionPanelState s,long read,long age) {
        return new ExperimentSessionPanelCoordinator.NativeStatusSnapshot(s,"unused",true,7L,read,age);
    }
}
