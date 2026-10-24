package website.sung.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.process.ExecOperations;

/**
 * Reads only the root repository's HEAD, including shallow clones and Git worktrees.
 * Gradle rechecks this value before reusing configuration that consumed it, so a
 * checkout change cannot leave an old revision embedded in the next APK.
 */
public abstract class GitBuildRevision implements ValueSource<String, GitBuildRevision.Parameters> {
    /** Identifies the application repository rather than a containing checkout. */
    public interface Parameters extends ValueSourceParameters {
        /** Repository root; an absent .git marker means a source archive. */
        DirectoryProperty getRootDirectory();

        /** Optional Git executable; defaults to Git on the process search path. */
        Property<String> getGitExecutable();
    }

    @Inject protected abstract ExecOperations getExecOperations();

    /** Returns the full lowercase commit ID, or an empty value when Git metadata is unavailable. */
    @Override public String obtain() {
        File root = getParameters().getRootDirectory().get().getAsFile();
        if (!new File(root, ".git").exists()) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            var result = getExecOperations().exec(spec -> {
                spec.workingDir(root);
                spec.commandLine(getParameters().getGitExecutable().getOrElse("git"),
                    "rev-parse", "--verify", "HEAD");
                spec.setStandardOutput(output);
                spec.setErrorOutput(OutputStream.nullOutputStream());
                spec.setIgnoreExitValue(true);
            });
            String revision = output.toString(StandardCharsets.UTF_8).trim();
            return result.getExitValue() == 0 && isFullRevision(revision)
                ? revision.toLowerCase(Locale.ROOT) : "";
        } catch (GradleException unavailable) {
            // Archives and developer machines without Git can still build.
            return "";
        }
    }

    /** Rejects ambiguous or unsafe override text before it can enter generated Kotlin. */
    public static String validateOverride(String value) {
        String revision = value.trim();
        if (!isFullRevision(revision)) {
            throw new IllegalArgumentException(
                "mangosshBuildRevision must be a full 40- or 64-character hexadecimal commit SHA");
        }
        return revision.toLowerCase(Locale.ROOT);
    }

    private static boolean isFullRevision(String value) {
        return value.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    }
}
