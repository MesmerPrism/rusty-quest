package io.github.mesmerprism.rustyquest.fleetagent;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class FleetAgentPrivateKey {
    private FleetAgentPrivateKey() {
    }

    static byte[] load(Context context) throws IOException {
        File seedFile = new File(
                new File(context.getFilesDir(), "fleet-agent"),
                "signing-seed.bin");
        byte[] seed = Files.readAllBytes(seedFile.toPath());
        if (seed.length != 32) {
            throw new IOException("signing_seed_size_invalid");
        }
        return seed;
    }
}
