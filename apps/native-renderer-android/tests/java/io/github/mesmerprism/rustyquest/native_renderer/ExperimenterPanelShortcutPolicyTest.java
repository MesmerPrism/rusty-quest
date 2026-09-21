package io.github.mesmerprism.rustyquest.native_renderer;

public final class ExperimenterPanelShortcutPolicyTest {
    public static void main(String[] args) {
        keyTriplePressTriggersOnlyAfterReleasedEdges();
        mixedAndroidRoutesCannotDoubleCountOnePress();
        staleAndRepeatedInputCannotCloseThePanel();
        cancelFencesASequenceAcrossPanelLifecycle();
        System.out.println("ExperimenterPanelShortcutPolicyTest PASS");
    }

    private static void keyTriplePressTriggersOnlyAfterReleasedEdges() {
        ExperimenterPanelShortcutPolicy policy = new ExperimenterPanelShortcutPolicy(5_000L);
        check(!policy.onKeySecondary(true, false, 100L), "first B down stays open");
        check(!policy.onKeySecondary(false, false, 120L), "first B release stays open");
        check(!policy.onKeySecondary(true, false, 200L), "second B down stays open");
        check(!policy.onKeySecondary(false, false, 220L), "second B release stays open");
        check(policy.onKeySecondary(true, false, 300L), "third B down closes panel");
        check(!policy.onKeySecondary(true, true, 320L), "held B cannot immediately retrigger");
    }

    private static void mixedAndroidRoutesCannotDoubleCountOnePress() {
        ExperimenterPanelShortcutPolicy policy = new ExperimenterPanelShortcutPolicy(5_000L);
        check(!policy.onKeySecondary(true, false, 10L), "key route counts first edge");
        check(!policy.onMotionSecondary(true, 11L), "motion duplicate does not count twice");
        check(!policy.onKeySecondary(false, false, 12L), "one route release is insufficient");
        check(!policy.onMotionSecondary(false, 13L), "aggregate release rearms");
        check(!policy.onMotionSecondary(true, 20L), "second physical edge");
        check(!policy.onKeySecondary(true, false, 21L), "key duplicate stays deduplicated");
        check(!policy.onMotionSecondary(false, 22L), "motion release keeps key aggregate down");
        check(!policy.onKeySecondary(false, false, 23L), "key release rearms");
        check(policy.onMotionSecondary(true, 30L), "third physical edge closes panel");
    }

    private static void staleAndRepeatedInputCannotCloseThePanel() {
        ExperimenterPanelShortcutPolicy policy = new ExperimenterPanelShortcutPolicy(100L);
        check(!policy.onKeySecondary(true, true, 0L), "repeat without first down is ignored");
        check(!policy.onKeySecondary(false, false, 1L), "release rearms after repeat");
        check(!policy.onKeySecondary(true, false, 10L), "fresh first edge");
        check(!policy.onKeySecondary(false, false, 11L), "fresh first release");
        check(!policy.onKeySecondary(true, false, 200L), "stale second edge starts a new sequence");
        check(!policy.onKeySecondary(false, false, 201L), "stale release");
        check(!policy.onKeySecondary(true, false, 220L), "new sequence second edge");
    }

    private static void cancelFencesASequenceAcrossPanelLifecycle() {
        ExperimenterPanelShortcutPolicy policy = new ExperimenterPanelShortcutPolicy(5_000L);
        check(!policy.onKeySecondary(true, false, 10L), "first edge");
        check(!policy.onKeySecondary(false, false, 20L), "first release");
        check(!policy.onKeySecondary(true, false, 30L), "second edge");
        policy.cancel();
        check(!policy.onKeySecondary(true, false, 40L), "post-pause edge starts fresh");
        check(!policy.onKeySecondary(false, false, 50L), "post-pause release");
        check(!policy.onKeySecondary(true, false, 60L), "post-pause second edge");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
