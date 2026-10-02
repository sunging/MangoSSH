package website.sung.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
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

    /** One validated host NDK; a null AGP path lets AGP consume local.properties itself. */
    public record NdkSelection(String directory, String agpPath) { }

    /**
     * Honors AGP's legacy ndk.dir without also setting ndkPath (CXX1100). The
     * selected directory is forwarded to Linux native producers as well. Explicit
     * invalid inputs fail instead of silently switching compilers. Windows/WSL
     * may use separate host packages, but both must have the locked revision.
     * Provider-backed file reads participate in configuration-cache invalidation.
     */
    public static NdkSelection ndk(Project project, File sdk) {
        String revision = read(project.getRootDir()).get("ndk").toString();
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String host = os.startsWith("windows") ? "windows-x86_64" : os.startsWith("mac") ? "darwin-x86_64" : "linux-x86_64";
        Properties local = properties(project.getProviders().fileContents(
            project.getRootProject().getLayout().getProjectDirectory().file("local.properties")
        ).getAsText().getOrElse(""));
        String localPath = local.getProperty("ndk.dir");
        String environmentPath = project.getProviders().environmentVariable("ANDROID_NDK_HOME").getOrNull();
        File directory;
        if (localPath != null) {
            if (localPath.isBlank()) throw new IllegalArgumentException("ndk.dir must not be empty");
            directory = project.getRootProject().file(localPath);
            validateNdk(project, directory, revision, host, "ndk.dir");
            return new NdkSelection(directory.getAbsolutePath(), null);
        }
        if (environmentPath != null && !environmentPath.isBlank()) {
            directory = project.getRootProject().file(environmentPath);
            validateRevision(project, directory, revision, "ANDROID_NDK_HOME");
            if (host.equals("windows-x86_64")
                    && !new File(directory, "toolchains/llvm/prebuilt/" + host).isDirectory()
                    && new File(directory, "toolchains/llvm/prebuilt/linux-x86_64").isDirectory()) {
                project.getLogger().warn("ANDROID_NDK_HOME is the locked Linux NDK; AGP uses the locked Windows SDK NDK");
            } else {
                validateNdk(project, directory, revision, host, "ANDROID_NDK_HOME");
                return new NdkSelection(directory.getAbsolutePath(), directory.getAbsolutePath());
            }
        }
        directory = new File(sdk, "ndk/" + revision);
        validateNdk(project, directory, revision, host, "SDK NDK");
        return new NdkSelection(directory.getAbsolutePath(), directory.getAbsolutePath());
    }

    private static Properties properties(String text) {
        Properties result = new Properties();
        try {
            result.load(new StringReader(text));
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read NDK properties", error);
        }
        return result;
    }

    private static void validateRevision(Project project, File directory, String revision, String selector) {
        String source = project.getProviders().fileContents(project.getLayout().file(
            project.getProviders().provider(() -> new File(directory, "source.properties"))
        )).getAsText().getOrElse("");
        if (!revision.equals(properties(source).getProperty("Pkg.Revision"))) {
            throw new IllegalArgumentException(selector + " must select NDK " + revision + " with readable source.properties");
        }
    }

    private static void validateNdk(Project project, File directory, String revision, String host, String selector) {
        validateRevision(project, directory, revision, selector);
        if (!new File(directory, "toolchains/llvm/prebuilt/" + host).isDirectory()) {
            throw new IllegalArgumentException(selector + " must contain the NDK toolchain for " + host);
        }
    }
}
