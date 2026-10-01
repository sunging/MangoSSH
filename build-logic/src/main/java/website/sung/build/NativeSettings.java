package website.sung.build;

import groovy.json.JsonSlurper;
import java.io.File;
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
}
