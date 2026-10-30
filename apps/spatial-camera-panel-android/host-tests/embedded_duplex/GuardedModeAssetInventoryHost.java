package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Executes the actual loader's inventory boundary; no Android/key/native leaf is entered. */
public final class GuardedModeAssetInventoryHost {
    private static final String REACHED_MANIFEST = "fixture-manifest-open";
    private static final class Reader implements EmbeddedDuplexPackagedInputs.AssetReader {
        final Set<String> names; int opens;
        Reader(Set<String> names) { this.names = names; }
        public String[] list() { return names.toArray(new String[0]); }
        public InputStream open(String name) throws IOException {
            opens++;
            throw new IOException(REACHED_MANIFEST);
        }
    }
    private static void boundary(Set<String> names, boolean accepted) throws Exception {
        Reader reader = new Reader(names);
        try {
            EmbeddedDuplexPackagedInputs.load(reader, "0".repeat(64), "fixture.package",
                    "0".repeat(64), EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
            throw new AssertionError("Fixture manifest was not opened");
        } catch (IOException reached) {
            if (!accepted || !REACHED_MANIFEST.equals(reached.getMessage()) || reader.opens != 1)
                throw new AssertionError("Unexpected manifest boundary", reached);
        } catch (IllegalStateException rejected) {
            if (accepted || !"packaged asset inventory differs".equals(rejected.getMessage()) || reader.opens != 0)
                throw new AssertionError("Default inventory changed", rejected);
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Field files = EmbeddedDuplexPackagedInputs.class.getDeclaredField("FILES");
        files.setAccessible(true);
        Set<String> baseline = new HashSet<>((Set<String>)files.get(null));
        Field manifest = EmbeddedDuplexPackagedInputs.class.getDeclaredField("MANIFEST");
        manifest.setAccessible(true); baseline.add((String)manifest.get(null));
        boundary(baseline, true);
        Set<String> packaged = new HashSet<>(baseline);
        Path directory = Path.of(args[0]).resolve("embedded-duplex");
        if (Files.isDirectory(directory)) {
            try (var children = Files.list(directory)) {
                children.forEach(path -> packaged.add(path.getFileName().toString()));
            }
        }
        boundary(packaged, true);
        Set<String> oldMarker = new HashSet<>(baseline); oldMarker.add("operator-capabilities.json");
        boundary(oldMarker, false);
        Set<String> unexpected = new HashSet<>(baseline); unexpected.add("unexpected.json");
        boundary(unexpected, false);
        System.out.println("PASS actual default loader inventory with debug overlay; old/unknown embedded entries rejected before open");
    }
}
