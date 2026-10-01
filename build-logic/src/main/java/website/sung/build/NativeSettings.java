package website.sung.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import org.gradle.api.Project;

/** Reads the single toolchain contract used by CMake, Gradle and shell adapters. */
public final class NativeSettings {
    private NativeSettings() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(File root) {
        return (Map<String, Object>) new JsonSlurper().parse(new File(root, "native/toolchains.json"));
    }

    @SuppressWarnings("unchecked")
    public static List<String> abis(Project project) {
        List<String> supported = (List<String>) read(project.getRootDir()).get("abis");
        String requested = project.getProviders().gradleProperty("mangosshAbis").getOrElse(String.join(",", supported));
        List<String> selected = Arrays.asList(requested.split("[,\\s]+"));
        if (selected.isEmpty() || new HashSet<>(selected).size() != selected.size() || !supported.containsAll(selected)) {
            throw new IllegalArgumentException("mangosshAbis must select distinct values from " + supported);
        }
        return supported.stream().filter(selected::contains).toList();
    }

    /**
     * Returns ANDROID_NDK_HOME for AGP only when it is the pinned NDK for this host.
     * The same variable selects the Linux NDK for the Mosh/tsnet scripts, so a
     * Linux NDK on Windows or another revision falls back to the SDK's ndk/ directory.
     */
    public static String agpNdkPath(Project project) {
        String path = project.getProviders().environmentVariable("ANDROID_NDK_HOME").getOrNull();
        if (path == null || path.isBlank()) return null;
        String revision = read(project.getRootDir()).get("ndk").toString();
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String host = os.startsWith("windows") ? "windows-x86_64" : os.startsWith("mac") ? "darwin-x86_64" : "linux-x86_64";
        File ndk = new File(path);
        boolean matches = false;
        try {
            matches = Files.readAllLines(new File(ndk, "source.properties").toPath()).stream()
                .anyMatch(line -> line.matches("^Pkg\\.Revision\\s*=\\s*" + Pattern.quote(revision) + "\\s*$"))
                && new File(ndk, "toolchains/llvm/prebuilt/" + host).isDirectory();
        } catch (IOException ignored) {
            // A missing or unreadable NDK is reported below like any other mismatch.
        }
        if (!matches) {
            project.getLogger().warn("Ignoring ANDROID_NDK_HOME for AGP: {} is not NDK {} for {}", path, revision, host);
            return null;
        }
        return ndk.getAbsolutePath();
    }
}
