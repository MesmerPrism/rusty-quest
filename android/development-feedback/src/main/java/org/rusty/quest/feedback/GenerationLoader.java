package org.rusty.quest.feedback;

import java.io.File;

/** A new loader per attempt. ModuleHost retains it even if class initialization or validation fails. */
public interface GenerationLoader {
    ClassLoader create(File artifact, ClassLoader contractParent) throws Exception;
}
