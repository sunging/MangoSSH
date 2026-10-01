package website.sung.build;

import java.util.*;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/** Runs the local content cache, which also validates external sources and toolchains.
 * Gradle output caching is deliberately disabled until cross-directory reproducibility
 * is verified. Always consulting the adapter prevents stale external worktrees or
 * corrupted outputs from being hidden by a Gradle UP-TO-DATE decision.
 */
@DisableCachingByDefault(because = "Verified component cache owns external source/toolchain identity")
public abstract class NativeTask extends DefaultTask {
    @Internal public abstract DirectoryProperty getRootDirectory();
    @Input public abstract ListProperty<String> getAbis();
    @Input public abstract Property<String> getSourceMode();
    @Input public abstract Property<Boolean> getOffline();
    @Input public abstract MapProperty<String, String> getBuildEnvironment();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceInputs();
    @LocalState public abstract DirectoryProperty getStateDirectory();
    @Inject protected abstract ExecOperations getExecOperations();

    public NativeTask() {
        getOutputs().upToDateWhen(task -> false);
        getSourceMode().convention("locked");
        getOffline().convention(true);
        getBuildEnvironment().convention(Collections.emptyMap());
    }

    protected void invoke(String component, Map<String, String> paths) {
        Map<String, String> env = new LinkedHashMap<>(getBuildEnvironment().get());
        env.values().removeIf(String::isEmpty);
        env.put("MANGOSSH_PROJECT_DIR", getRootDirectory().get().getAsFile().getAbsolutePath());
        env.put("MANGOSSH_NATIVE_STATE", getStateDirectory().get().getAsFile().getAbsolutePath());
        env.put("MANGOSSH_OFFLINE_BUILD", "1"); // Builds never provision or download inputs.
        env.put("ABIS", String.join(" ", getAbis().get()));
        env.put("MANGOSSH_NATIVE_SOURCE_MODE", getSourceMode().get());
        env.putAll(paths);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows) {
            List<String> translated = new ArrayList<>();
            translated.add("MANGOSSH_PROJECT_DIR/p");
            translated.add("MANGOSSH_NATIVE_STATE/p");
            for (String key : paths.keySet()) translated.add(key + "/p");
            // Linux SDK/NDK/JDK identities must not be replaced with Windows tools.
            env.remove("JAVA_HOME");
            env.remove("ANDROID_HOME");
            env.remove("ANDROID_NDK_HOME");
            for (String key : env.keySet()) {
                if (!key.equals("WSLENV") && translated.stream().noneMatch(v -> v.equals(key + "/p"))) translated.add(key);
            }
            String inherited = System.getenv("WSLENV");
            env.put("WSLENV", (inherited == null || inherited.isBlank() ? "" : inherited + ":") + String.join(":", translated));
        }
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(getRootDirectory().get().getAsFile());
            spec.environment(env);
            if (windows) {
                spec.commandLine("wsl.exe", "bash", "-lc", "cd \"$MANGOSSH_PROJECT_DIR\" && bash tools/native/gradle-entry.sh " + component);
            } else {
                spec.commandLine("bash", "tools/native/gradle-entry.sh", component);
            }
        }).assertNormalExitValue();
    }
}
