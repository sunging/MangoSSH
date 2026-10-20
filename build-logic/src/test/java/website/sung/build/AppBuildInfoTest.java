package website.sung.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exercises real Git metadata and Gradle cache reuse without Android or the developer's checkout. */
public class AppBuildInfoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File fixture() throws Exception {
        return fixture(temporary.newFolder());
    }

    private File fixture(File root) throws Exception {
        Files.createDirectories(root.toPath());
        Files.writeString(new File(root, "settings.gradle").toPath(), "rootProject.name = 'build-info-test'\n");
        Files.writeString(new File(root, "README.txt").toPath(), "Synthetic source fixture\n");
        Files.writeString(new File(root, "build.gradle").toPath(), """
            plugins { id 'mangossh.native-tools' }
            def revision = providers.gradleProperty('mangosshBuildRevision')
                .map { website.sung.build.GitBuildRevision.validateOverride(it) }
                .orElse(providers.of(website.sung.build.GitBuildRevision) {
                    parameters.rootDirectory.set(layout.projectDirectory)
                }).get().take(12)
            ['github', 'fdroid'].each { channel ->
                ['debug', 'release'].each { kind ->
                    def variant = channel + kind.capitalize()
                    tasks.register('generate' + variant.capitalize() + 'AppBuildInfo',
                        website.sung.build.GenerateAppBuildInfo) {
                        gitRevision.set(revision)
                        distribution.set(channel)
                        outputDirectory.set(layout.buildDirectory.dir('identity/' + variant))
                    }
                }
            }
            """);
        return root;
    }

    private void initializeGit(File root) throws Exception {
        git(root, "init", "--quiet");
        git(root, "add", "settings.gradle", "build.gradle", "README.txt");
        commit(root);
    }

    private void commit(File root) throws Exception {
        git(root, "-c", "user.name=Build fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "commit.gpgSign=false", "-c", "core.hooksPath=", "commit", "--quiet", "--allow-empty",
            "-m", "Synthetic build identity fixture");
    }

    private String git(File root, String... arguments) throws Exception {
        var command = new ArrayList<>(Arrays.asList("git"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).directory(root).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(output, 0, process.waitFor());
        return output;
    }

    private BuildResult run(File root, boolean failure, String... properties) {
        return run(root, null, failure, properties);
    }

    private BuildResult run(File root, Map<String, String> environment, boolean failure, String... properties) {
        var arguments = new ArrayList<>(Arrays.asList(
            "generateGithubDebugAppBuildInfo", "generateGithubReleaseAppBuildInfo",
            "generateFdroidDebugAppBuildInfo", "generateFdroidReleaseAppBuildInfo",
            "--configuration-cache", "--parallel", "--max-workers=2", "--stacktrace"));
        arguments.addAll(Arrays.asList(properties));
        GradleRunner runner = GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(arguments);
        if (environment != null) runner.withEnvironment(environment);
        return failure ? runner.buildAndFail() : runner.build();
    }

    private void assertIdentity(File root, String revision) throws Exception {
        for (String channel : new String[] {"github", "fdroid"}) {
            for (String kind : new String[] {"Debug", "Release"}) {
                String source = Files.readString(new File(root,
                    "build/identity/" + channel + kind + "/website/sung/mangossh/core/AppBuildInfo.kt").toPath());
                assertTrue(source, source.contains("GIT_REVISION: String = \"" + revision + "\""));
                assertTrue(source, source.contains("DISTRIBUTION: String = \"" + channel + "\""));
            }
        }
    }

    @Test public void variantsKeepSeparateChannelsAndHeadChangesInvalidateConfigurationCache() throws Exception {
        File root = fixture();
        initializeGit(root);
        String first = git(root, "rev-parse", "HEAD").substring(0, 12);
        run(root, false);
        assertIdentity(root, first);
        Files.writeString(new File(root, "README.txt").toPath(), "Uncommitted synthetic change\n");
        assertTrue(run(root, false).getOutput().contains("Reusing configuration cache"));
        assertIdentity(root, first);
        commit(root);
        String second = git(root, "rev-parse", "HEAD").substring(0, 12);
        assertNotEquals(first, second);
        run(root, false);
        assertIdentity(root, second);
        assertTrue(run(root, false).getOutput().contains("Reusing configuration cache"));
    }

    @Test public void shallowCloneUsesTheSameRevision() throws Exception {
        File source = fixture();
        initializeGit(source);
        commit(source);
        File clone = temporary.newFolder();
        git(source, "clone", "--quiet", "--depth=1", source.toPath().toUri().toString(), clone.getAbsolutePath());
        assertEquals("true", git(clone, "rev-parse", "--is-shallow-repository"));
        run(clone, false);
        assertIdentity(clone, git(source, "rev-parse", "HEAD").substring(0, 12));
    }

    @Test public void gitWorktreeReadsItsOwnHeadFromItsGitFile() throws Exception {
        File source = fixture();
        initializeGit(source);
        File worktree = new File(temporary.newFolder(), "worktree");
        git(source, "worktree", "add", "--quiet", "--detach", worktree.getAbsolutePath(), "HEAD");
        assertTrue(new File(worktree, ".git").isFile());
        run(worktree, false);
        assertIdentity(worktree, git(source, "rev-parse", "HEAD").substring(0, 12));
    }

    @Test public void archivesDoNotBorrowTheContainingRepositoryRevision() throws Exception {
        File parent = fixture();
        initializeGit(parent);
        File archive = fixture(new File(parent, "archive"));
        run(archive, false);
        assertIdentity(archive, "");
        String override = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
        run(archive, false, "-PmangosshBuildRevision=" + override);
        assertIdentity(archive, "abcdef012345");
        initializeGit(archive);
        run(archive, false);
        assertIdentity(archive, git(archive, "rev-parse", "HEAD").substring(0, 12));
    }

    @Test public void explicitRevisionOverridesHeadAndSupportsSha256() throws Exception {
        File root = fixture();
        initializeGit(root);
        run(root, false, "-PmangosshBuildRevision=" + "AB".repeat(32));
        assertIdentity(root, "abababababab");
    }

    @Test public void invalidExplicitRevisionFailsRatherThanFallingBackToGit() throws Exception {
        File root = fixture();
        initializeGit(root);
        for (String value : new String[] {"", "339054b293f5", "v0.7.0", "\"injected\""}) {
            assertTrue(run(root, true, "-PmangosshBuildRevision=" + value).getOutput()
                .contains("mangosshBuildRevision must be a full"));
        }
    }

    @Test public void missingGitExecutableLeavesAUsableUnknownIdentity() throws Exception {
        File root = fixture();
        initializeGit(root);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("PATH"));
        environment.put("PATH", temporary.newFolder().getAbsolutePath());
        run(root, environment, false);
        assertIdentity(root, "");
    }
}
