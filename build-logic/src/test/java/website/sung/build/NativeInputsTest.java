package website.sung.build;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Checks real Gradle invalidation without invoking WSL, compilers or external sources. */
public class NativeInputsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File fixture() throws Exception {
        File root = temporary.newFolder();
        write(root, "settings.gradle", "rootProject.name = 'native-inputs-test'\n");
        write(root, "build.gradle", """
            plugins { id 'mangossh.native-tools' }
            ['mosh', 'tsnet'].each { component ->
                tasks.register(component + 'Probe', website.sung.build.NativeInputProbe) {
                    rootDirectory.set(layout.projectDirectory)
                    stateDirectory.set(layout.buildDirectory.dir('native'))
                    abis.set(['x86_64'])
                    concurrentProbe.set(providers.gradleProperty('concurrentProbe').map { it.toBoolean() }.orElse(false))
                    sourceInputs.from(website.sung.build.NativeInputs.common(project))
                    sourceInputs.from(component == 'mosh'
                        ? website.sung.build.NativeInputs.mosh(project)
                        : website.sung.build.NativeInputs.tsnet(project))
                    result.set(layout.buildDirectory.file(component + '.txt'))
                }
            }
            """);
        return root;
    }

    @Test public void sharedAdapterCacheAllowsIndependentProducersToRunConcurrently() throws Exception {
        BuildResult result = runner(fixture()).withArguments(
            "moshProbe", "tsnetProbe", "--configuration-cache", "--max-workers=2", "-PconcurrentProbe=true", "--stacktrace"
        ).build();
        assertOutcomes(result, TaskOutcome.SUCCESS, TaskOutcome.SUCCESS);
    }

    private void write(File root, String name, String content) throws Exception {
        var path = new File(root, name).toPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private GradleRunner runner(File root) throws Exception {
        var runner = GradleRunner.create().withProjectDir(root).withPluginClasspath();
        var classpath = new ArrayList<File>(runner.getPluginClasspath());
        // A compiled fixture task gives configuration-cache deserialization a
        // stable classloader, unlike a script-local subclass of an injected plugin.
        classpath.add(new File(NativeInputProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        return runner.withPluginClasspath(classpath);
    }

    private BuildResult run(File root) throws Exception {
        return runner(root).withArguments(
            "moshProbe", "tsnetProbe", "--configuration-cache", "--max-workers=2", "--stacktrace"
        ).build();
    }

    private void assertOutcomes(BuildResult result, TaskOutcome mosh, TaskOutcome tsnet) {
        assertEquals(result.getOutput(), mosh, result.task(":moshProbe").getOutcome());
        assertEquals(result.getOutput(), tsnet, result.task(":tsnetProbe").getOutcome());
    }

    @Test public void unrelatedToolsAndGeneratedPythonFilesDoNotEnterTheNativeGraph() throws Exception {
        File root = fixture();
        assertOutcomes(run(root), TaskOutcome.SUCCESS, TaskOutcome.SUCCESS);
        for (String path : new String[] {
            "tools/ssh-test-fixture.py", "tools/run-device-regressions.sh",
            "tools/native/test_native.py", "tools/native/verify-apk.py",
            "tools/native/__pycache__/state.cpython-312.pyc",
            "native/tsnetbridge/.build/generated.go"
        }) write(root, path, "unrelated generated or test file");
        BuildResult result = run(root);
        assertTrue(result.getOutput().contains("Reusing configuration cache"));
        assertOutcomes(result, TaskOutcome.UP_TO_DATE, TaskOutcome.UP_TO_DATE);
    }

    @Test public void producerInputsInvalidateOnlyTheirConsumers() throws Exception {
        File root = fixture();
        run(root);
        for (String path : new String[] {
            "native/tsnetbridge/bridge.go", "native/tsnetbridge/vendor/example/source.go",
            "tools/build-tsnet-android.sh", "tools/normalize-tsnet-aar.py",
            "tools/generate-tsnet-notices.py", "tools/lib/go-toolchain.sh",
            "tools/patches/tailscale-example.patch"
        }) {
            write(root, path, "first version");
            assertOutcomes(run(root), TaskOutcome.UP_TO_DATE, TaskOutcome.SUCCESS);
        }
        for (String path : new String[] {"native/mosh/recipes/mosh.sh", "tools/fdroid-sources.lock"}) {
            write(root, path, "locked input");
            assertOutcomes(run(root), TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE);
        }
        write(root, "tools/lib/go-toolchain.sh", "changed version");
        assertOutcomes(run(root), TaskOutcome.UP_TO_DATE, TaskOutcome.SUCCESS);
        Files.delete(new File(root, "tools/patches/tailscale-example.patch").toPath());
        assertOutcomes(run(root), TaskOutcome.UP_TO_DATE, TaskOutcome.SUCCESS);
    }

    @Test public void adapterAndToolchainChangesInvalidateBothProducers() throws Exception {
        File root = fixture();
        run(root);
        for (String path : new String[] {
            "tools/native/build.py", "tools/native/state.py", "tools/native/artifacts.py",
            "tools/native/gradle-entry.sh", "native/toolchains.json"
        }) {
            write(root, path, "changed adapter input");
            assertOutcomes(run(root), TaskOutcome.SUCCESS, TaskOutcome.SUCCESS);
        }
        assertOutcomes(run(root), TaskOutcome.UP_TO_DATE, TaskOutcome.UP_TO_DATE);
    }
}
