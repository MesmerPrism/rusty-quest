package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EmbeddedDuplexIdentityPublicationTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void simultaneousPublishersKeepOneCompleteIdentity() throws Exception {
        File directory = temporaryFolder.newFolder("race");
        File record = new File(directory, "identity");
        File first = new File(directory, "first.pending");
        File second = new File(directory, "second.pending");
        byte[] firstBytes = new byte[92];
        byte[] secondBytes = new byte[92];
        Arrays.fill(firstBytes, (byte) 0x31);
        Arrays.fill(secondBytes, (byte) 0x52);
        Files.write(first.toPath(), firstBytes);
        Files.write(second.toPath(), secondBytes);
        File lock = new File(directory, "publication.lock");
        Files.write(lock.toPath(), new byte[0]);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try (FileChannel firstLock = FileChannel.open(lock.toPath(), StandardOpenOption.WRITE);
                FileChannel secondLock = FileChannel.open(lock.toPath(), StandardOpenOption.WRITE)) {
            Future<Boolean> firstResult = workers.submit(() -> {
                start.await();
                return EmbeddedDuplexIdentity.publishCompleteRecord(firstLock, first, record, () -> {});
            });
            Future<Boolean> secondResult = workers.submit(() -> {
                start.await();
                return EmbeddedDuplexIdentity.publishCompleteRecord(secondLock, second, record, () -> {});
            });
            start.countDown();
            boolean firstWon = firstResult.get(10, TimeUnit.SECONDS);
            boolean secondWon = secondResult.get(10, TimeUnit.SECONDS);
            assertTrue(firstWon ^ secondWon);
            assertArrayEquals(firstWon ? firstBytes : secondBytes, Files.readAllBytes(record.toPath()));
            assertFalse((firstWon ? first : second).exists());
            assertTrue((firstWon ? second : first).exists());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void interruptedPublicationLeavesNoPartialRecordAndCannotBeReplaced() throws Exception {
        File directory = temporaryFolder.newFolder("interruption");
        File record = new File(directory, "identity");
        File lock = new File(directory, "publication.lock");
        Files.write(lock.toPath(), new byte[0]);
        File staged = new File(directory, "staged.pending");
        byte[] complete = new byte[92];
        Arrays.fill(complete, (byte) 0x61);
        Files.write(staged.toPath(), complete);
        assertFalse(record.exists()); // A crash before rename leaves only the staging file.
        try (FileChannel channel = FileChannel.open(lock.toPath(), StandardOpenOption.WRITE)) {
            try {
                EmbeddedDuplexIdentity.publishCompleteRecord(channel, staged, record,
                        () -> { throw new IOException("simulated crash after rename"); });
                fail("directory sync interruption must propagate");
            } catch (IOException expected) {
                assertArrayEquals(complete, Files.readAllBytes(record.toPath()));
            }
            File later = new File(directory, "later.pending");
            byte[] different = new byte[92];
            Arrays.fill(different, (byte) 0x72);
            Files.write(later.toPath(), different);
            assertFalse(EmbeddedDuplexIdentity.publishCompleteRecord(channel, later, record, () -> {}));
            assertArrayEquals(complete, Files.readAllBytes(record.toPath()));
            assertTrue(later.exists());
        }
    }
}
