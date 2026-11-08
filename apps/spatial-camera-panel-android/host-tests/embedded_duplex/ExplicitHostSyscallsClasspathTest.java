package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host-only witness for explicit syscall model selection, not Android evidence. */
public final class ExplicitHostSyscallsClasspathTest {
    @Test public void actualJvmSelectsDeclaredSyscallLeaf() throws Exception {
        String expected=System.getProperty("rusty.quest.host.syscall.classes");
        assertNotNull("Explicit host model directory required",expected);
        Class<?> leaf=Class.forName("android.system.Os");
        URL actual=leaf.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(new File(expected).getCanonicalFile().toURI(),new File(actual.toURI()).getCanonicalFile().toURI());
        assertNotNull(leaf.getMethod("blockNextRead"));
        System.out.println("HOST_OS_CODE_SOURCE="+actual);
    }
}
