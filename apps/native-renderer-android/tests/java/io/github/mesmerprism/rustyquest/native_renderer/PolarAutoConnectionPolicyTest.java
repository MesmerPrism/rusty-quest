package io.github.mesmerprism.rustyquest.native_renderer;

public final class PolarAutoConnectionPolicyTest {
    public static void main(String[] args) {
        check(
            "Polar H10 TEST0001".equals(
                PolarAutoConnectionPolicy.preferredScanName(" Polar H10 TEST0001 ", "Stale cached name")
            ),
            "fresh advertisement name wins over stale BluetoothDevice cache"
        );
        check(
            "Polar H10 cached".equals(
                PolarAutoConnectionPolicy.preferredScanName("", " Polar H10 cached ")
            ),
            "cached name remains the fallback when the advertisement has no local name"
        );
        check(
            PolarAutoConnectionPolicy.acceptsScanCandidate("Polar H10 TEST0001", false, false),
            "Polar advertisement name is accepted without visible services"
        );
        check(
            PolarAutoConnectionPolicy.acceptsScanCandidate("unrelated", false, true),
            "solicitation-only PMD visibility admits the candidate"
        );
        check(
            PolarAutoConnectionPolicy.acceptsScanCandidate("unrelated", true, false),
            "heart-rate service visibility admits the candidate"
        );
        check(
            !PolarAutoConnectionPolicy.acceptsScanCandidate("unrelated", false, false),
            "unrelated advertisement without Polar services is rejected"
        );
        check(
            PolarAutoConnectionPolicy.preflight(false, "on", "enabled", false, false, 0, 2)
                == PolarAutoConnectionPolicy.Decision.PERMISSION_REQUIRED,
            "permission gate"
        );
        check(
            PolarAutoConnectionPolicy.preflight(true, "off", "enabled", false, false, 0, 2)
                == PolarAutoConnectionPolicy.Decision.BLUETOOTH_UNAVAILABLE,
            "Bluetooth off gate"
        );
        check(
            PolarAutoConnectionPolicy.preflight(true, "on", "disabled", false, false, 0, 2)
                == PolarAutoConnectionPolicy.Decision.LOCATION_SERVICES_DISABLED,
            "location services gate"
        );
        check(
            PolarAutoConnectionPolicy.preflight(true, "on", "enabled", false, true, 0, 2)
                == PolarAutoConnectionPolicy.Decision.WAIT_FOR_IN_FLIGHT,
            "idempotent in-flight request"
        );
        check(
            PolarAutoConnectionPolicy.preflight(true, "on", "enabled", false, false, 1, 2)
                == PolarAutoConnectionPolicy.Decision.START_SCAN,
            "one bounded retry"
        );
        check(
            PolarAutoConnectionPolicy.preflight(true, "on", "enabled", false, false, 2, 2)
                == PolarAutoConnectionPolicy.Decision.RETRY_EXHAUSTED,
            "retry exhaustion"
        );
        check(
            PolarAutoConnectionPolicy.afterScan(4L, 4L, 3, 1)
                == PolarAutoConnectionPolicy.Decision.CONNECT_PAIRED,
            "preferred pairing wins among multiple candidates"
        );
        check(
            PolarAutoConnectionPolicy.afterScan(4L, 4L, 1, 0)
                == PolarAutoConnectionPolicy.Decision.CONNECT_UNIQUE,
            "unique eligible candidate"
        );
        check(
            PolarAutoConnectionPolicy.afterScan(4L, 4L, 2, 0)
                == PolarAutoConnectionPolicy.Decision.REQUIRES_SELECTION,
            "multiple unpaired candidates"
        );
        check(
            PolarAutoConnectionPolicy.afterScan(4L, 4L, 0, 0)
                == PolarAutoConnectionPolicy.Decision.NOT_FOUND,
            "no candidate"
        );
        check(
            PolarAutoConnectionPolicy.afterScan(4L, 5L, 1, 1)
                == PolarAutoConnectionPolicy.Decision.STALE_GENERATION,
            "stale scan generation"
        );
        int fallbackPageSpinnerSelection = 1;
        int exactAdmittedCandidate = PolarAutoConnectionPolicy.admittedCandidateIndex(
            PolarAutoConnectionPolicy.Decision.CONNECT_PAIRED,
            2,
            0
        );
        check(exactAdmittedCandidate == 0
                && exactAdmittedCandidate != fallbackPageSpinnerSelection,
            "automatic connect uses admitted candidate, never fallback-page spinner state");
        check(
            PolarAutoConnectionPolicy.admittedCandidateIndex(
                PolarAutoConnectionPolicy.Decision.CONNECT_UNIQUE, 1, -1
            ) == 0,
            "unique candidate admission is independent of attached activity"
        );
        check(
            PolarAutoConnectionPolicy.evidenceFresh(4L, 4L, 1_000L, 1_500L, 1_000L, 3_000L, 2_000L),
            "current callback evidence within generation-bound deadline"
        );
        check(
            !PolarAutoConnectionPolicy.evidenceFresh(4L, 5L, 1_000L, 1_500L, 1_000L, 3_000L, 2_000L),
            "old callback generation is stale"
        );
        check(
            !PolarAutoConnectionPolicy.evidenceFresh(4L, 4L, 1_000L, 1_500L, 1_000L, 1_999L, 2_000L),
            "connecting deadline expiry is stale"
        );
        long callbackEvidenceTime = 1_000L;
        check(
            !PolarAutoConnectionPolicy.evidenceFresh(
                4L, 4L, callbackEvidenceTime, 2_001L, 1_000L, 0L, 0L
            ),
            "repeated reads cannot refresh callback evidence"
        );
        check(
            PolarAutoConnectionPolicy.evidenceFresh(
                4L, 4L, 1_900L, 2_001L, 1_000L, 0L, 0L
            ),
            "a genuine live callback advances freshness for a healthy stream"
        );
        Object oldGatt = new Object();
        Object replacementGatt = new Object();
        long callbackAdmittedGeneration = 4L;
        check(
            PolarAutoConnectionPolicy.mayPublishLiveEvidence(
                oldGatt, oldGatt, callbackAdmittedGeneration, 4L, true, false
            ),
            "current GATT and generation may publish live evidence"
        );
        check(
            !PolarAutoConnectionPolicy.mayPublishLiveEvidence(
                oldGatt, replacementGatt, callbackAdmittedGeneration, 5L, true, false
            ),
            "old callback spanning disconnect/reconnect cannot refresh replacement"
        );
        check(
            !PolarAutoConnectionPolicy.mayPublishLiveEvidence(
                oldGatt, oldGatt, callbackAdmittedGeneration, 5L, true, false
            ),
            "generation change fences callback even if platform reuses identity"
        );
        PolarStatusPersistenceThreadPolicy.requireBackgroundThread(false);
        boolean rejectedMainThread = false;
        try {
            PolarStatusPersistenceThreadPolicy.requireBackgroundThread(true);
        } catch (IllegalStateException expected) {
            rejectedMainThread = true;
        }
        check(rejectedMainThread, "status persistence rejects main-thread execution");
        System.out.println("PolarAutoConnectionPolicyTest PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
