package website.sung.build;

import java.util.Map;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Produces the vendored Go bridge AAR with the requested Android ABI set. */
@DisableCachingByDefault(because = "Uses verified local component cache")
public abstract class TsnetBuild extends NativeTask {
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    /** The bridge and its scripts are declared source inputs; no external worktree is read. */
    @Override protected boolean gradleTracksAllSources() {
        return true;
    }

    @TaskAction public void build() {
        invoke("tsnet", Map.of("MANGOSSH_TSNET_OUTPUT_DIR", getOutputDirectory().get().getAsFile().getAbsolutePath()));
    }
}
