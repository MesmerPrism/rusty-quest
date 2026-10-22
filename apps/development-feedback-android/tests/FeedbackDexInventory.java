package org.rusty.quest.feedback;

import java.nio.file.*;
import java.util.*;

/** Reuses the owner class_def parser on actual D8 output; does not assemble or inspect an APK. */
public final class FeedbackDexInventory {
    public static void main(String[] args) throws Exception {
        Set<String> definitions=DexDefinitions.read(Files.readAllBytes(Paths.get(args[0])));
        String prefix="Lio/github/mesmerprism/rustyquest/development_feedback/";
        for(String name:Arrays.asList("FeedbackApplication","FeedbackActivity","FeedbackController","FeedbackOperatorProvider")) {
            if(!definitions.contains(prefix+name+";"))throw new AssertionError("Missing Android consumer definition: "+name);
        }
        for(String definition:definitions) {
            if(definition.startsWith("Lfeedbackplugin/")||definition.contains("HostUrlLoader")||definition.contains("FeedbackControllerTest"))
                throw new AssertionError("Plugin or host-test class included in Android source output: "+definition);
        }
        System.out.println("PASS: actual D8 class_defs include consumer/core only; "+definitions.size()+" definitions, no plugin/host-loader/test classes");
    }
}
