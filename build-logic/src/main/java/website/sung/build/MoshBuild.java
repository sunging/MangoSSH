package website.sung.build;

import java.util.Map;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Produces the executable packaging directory and architecture-independent assets. */
@DisableCachingByDefault(because = "Uses verified local component cache")
public abstract class MoshBuild extends NativeTask {
    @OutputDirectory public abstract DirectoryProperty getJniDirectory();
    @OutputDirectory public abstract DirectoryProperty getAssetsDirectory();
    @OutputFile public abstract RegularFileProperty getManifestFile();

    @TaskAction public void build() {
        invoke("mosh", Map.of(
            "MANGOSSH_JNI_DIR", getJniDirectory().get().getAsFile().getAbsolutePath(),
            "MANGOSSH_ASSETS_DIR", getAssetsDirectory().get().getAsFile().getAbsolutePath(),
            "MANGOSSH_MANIFEST", getManifestFile().get().getAsFile().getAbsolutePath()));
    }
}
