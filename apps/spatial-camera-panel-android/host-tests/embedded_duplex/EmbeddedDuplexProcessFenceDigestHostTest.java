package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;

/** Exact digest bytes and relative host allocation controls; no ART timing claim. */
public final class EmbeddedDuplexProcessFenceDigestHostTest {
    private static volatile String retainedDigest;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String reference(String exact) throws Exception {
        if (exact == null) return "-";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(exact.getBytes(StandardCharsets.UTF_8));
        char[] encoded = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            encoded[2 * i] = HEX[(hash[i] & 255) >>> 4];
            encoded[2 * i + 1] = HEX[hash[i] & 15];
        }
        return new String(encoded);
    }

    private static void equivalent(String exact) throws Exception {
        String expected = reference(exact), actual = EmbeddedDuplexProcessFence.digest(exact);
        if (!expected.equals(actual)) throw new AssertionError("Fence digest exact UTF-8 differs");
        if (exact != null && !actual.matches("[0-9a-f]{64}"))
            throw new AssertionError("Fence digest is not fixed lowercase SHA-256");
    }

    private static void byteControls() throws Exception {
        equivalent(null); equivalent(""); equivalent("abc");
        if (!"-".equals(EmbeddedDuplexProcessFence.digest(null))) throw new AssertionError("Null sentinel changed");
        if (!"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                .equals(EmbeddedDuplexProcessFence.digest(""))) throw new AssertionError("Empty golden changed");
        if (!"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                .equals(EmbeddedDuplexProcessFence.digest("abc"))) throw new AssertionError("ASCII golden changed");
        equivalent("Gr\u00fc\u00dfe \ud83d\udc41 \u03bb \u4e2d\u6587\n\u0000");
        equivalent("\ud800unpaired\udfff"); // Preserve String.getBytes UTF-8 replacement, too.
        Random random = new Random(0x51a256L);
        for (int test = 0; test < 256; test++) {
            char[] chars = new char[random.nextInt(2049)];
            for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(65536);
            equivalent(new String(chars));
        }
        equivalent("record\n".repeat(32768));
        System.out.println("PASS exact digest: null/empty/ASCII goldens, Unicode, replacement, seeded random and long records");
    }

    private static String canonical(String exact) throws Exception {
        return EmbeddedDuplexCanonicalJson.sha256Bytes(exact.getBytes(StandardCharsets.UTF_8));
    }

    private static long allocated(com.sun.management.ThreadMXBean bean, String[] records,
            boolean production, int count) throws Exception {
        long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < count; i++) {
            String exact = records[i % records.length];
            retainedDigest = production ? EmbeddedDuplexProcessFence.digest(exact) : canonical(exact);
        }
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        if (bytes <= 0) throw new AssertionError("Per-thread allocation accounting unavailable");
        return bytes;
    }

    private static void allocationControl() throws Exception {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        if (!(raw instanceof com.sun.management.ThreadMXBean))
            throw new AssertionError("Run allocation control with the supported host JDK ThreadMXBean");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) raw;
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("Host allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        String[] records = {"checkpoint:" + "a".repeat(192), "evidence:" + "\u03bb".repeat(96), "record\n".repeat(32)};
        for (int i = 0; i < 4096; i++) retainedDigest = canonical(records[i % records.length]);
        for (int i = 0; i < 2048; i++) retainedDigest = EmbeddedDuplexProcessFence.digest(records[i % records.length]);
        long helper = allocated(bean, records, false, 2048);
        long production = allocated(bean, records, true, 2048);
        System.out.println("digest allocated bytes: production=" + production + " canonical=" + helper
                + " ratio=" + ((double) production / helper) + " budget=8x; host allocations, not ART throughput");
        if (production > helper * 8L)
            throw new AssertionError("Fence digest allocation exceeds 8x exact canonical SHA-256 helper");
    }

    public static void main(String[] args) throws Exception {
        byteControls(); allocationControl();
        System.out.println("PASS bounded relative digest allocation; no wall-clock performance threshold");
    }
}
