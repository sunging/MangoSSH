package website.sung.build;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exercises selectors through a real Gradle configuration, including cache invalidation. */
public class NativeSettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String REVISION = "27.3.13750724";
    private final String host = System.getProperty("os.name").startsWith("Windows")
        ? "windows-x86_64" : System.getProperty("os.name").startsWith("Mac")
        ? "darwin-x86_64" : "linux-x86_64";

    private File fixture() throws Exception {
        File root = temporary.newFolder();
        Files.writeString(new File(root, "settings.gradle").toPath(), "rootProject.name = 'ndk-test'\n");
        new File(root, "native").mkdirs();
        Files.writeString(new File(root, "native/toolchains.json").toPath(), "{\"ndk\":\"" + REVISION + "\"}");
        Files.writeString(new File(root, "build.gradle").toPath(), """
            plugins { id 'mangossh.native-tools' }
            def selection = website.sung.build.NativeSettings.ndk(project, file('sdk'))
            println 'SELECTED=' + selection.directory()
            println 'AGP=' + selection.agpPath()
            """);
        ndk(root, "sdk/ndk/" + REVISION, REVISION, host);
        return root;
    }

    private File ndk(File root, String path, String revision, String platform) throws Exception {
        File dir = new File(root, path);
        new File(dir, "toolchains/llvm/prebuilt/" + platform).mkdirs();
        Files.writeString(new File(dir, "source.properties").toPath(), "Pkg.Revision = " + revision + "\n");
        return dir;
    }

    private void local(File root, String path) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("ndk.dir", path);
        try (var output = Files.newOutputStream(new File(root, "local.properties").toPath())) {
            properties.store(output, "NDK fixture");
        }
    }

    private BuildResult run(File root, File environment, boolean failure) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("ANDROID_NDK_HOME");
        if (environment != null) env.put("ANDROID_NDK_HOME", environment.getAbsolutePath());
        GradleRunner runner = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withEnvironment(env).withArguments("help", "--configuration-cache", "--stacktrace");
        return failure ? runner.buildAndFail() : runner.build();
    }

    @Test public void localSelectorWinsAndDoesNotSetAgpPath() throws Exception {
        File root = fixture();
        File local = ndk(root, "local ndk", REVISION, host);
        File env = ndk(root, "environment", REVISION, host);
        local(root, local.getAbsolutePath());
        String output = run(root, env, false).getOutput();
        assertTrue(output, output.contains("SELECTED=" + local.getAbsolutePath()));
        assertTrue(output, output.contains("AGP=null"));
        assertTrue(run(root, env, false).getOutput().contains("Reusing configuration cache"));
    }

    @Test public void environmentAndSdkFallbackShareExplicitAgpPath() throws Exception {
        File root = fixture();
        File env = ndk(root, "environment", REVISION, host);
        String output = run(root, env, false).getOutput();
        assertTrue(output, output.contains("AGP=" + env.getAbsolutePath()));
        output = run(root, null, false).getOutput();
        assertTrue(output, output.contains("AGP=" + new File(root, "sdk/ndk/" + REVISION).getAbsolutePath()));
    }

    @Test public void localFileCreationChangeAndRemovalInvalidateCache() throws Exception {
        File root = fixture();
        run(root, null, false);
        File first = ndk(root, "first", REVISION, host);
        local(root, "first");
        assertTrue(run(root, null, false).getOutput().contains("SELECTED=" + first.getAbsolutePath()));
        File second = ndk(root, "second", REVISION, host);
        local(root, "second");
        assertTrue(run(root, null, false).getOutput().contains("SELECTED=" + second.getAbsolutePath()));
        Files.delete(new File(root, "local.properties").toPath());
        assertTrue(run(root, null, false).getOutput().contains("AGP=" + new File(root, "sdk/ndk/" + REVISION).getAbsolutePath()));
    }

    @Test public void revisionChangeInvalidatesCacheAndFails() throws Exception {
        File root = fixture();
        File env = ndk(root, "environment", REVISION, host);
        run(root, env, false);
        Files.writeString(new File(env, "source.properties").toPath(), "Pkg.Revision = 28.0.0\n");
        assertTrue(run(root, env, true).getOutput().contains("ANDROID_NDK_HOME must select NDK " + REVISION));
    }

    @Test public void invalidLocalSelectorsFailEvenWithValidEnvironment() throws Exception {
        for (String path : new String[] {"", "missing", "wrong-version", "wrong-host"}) {
            File root = fixture();
            File env = ndk(root, "environment", REVISION, host);
            ndk(root, "wrong-version", "28.0.0", host);
            ndk(root, "wrong-host", REVISION, "unsupported-host");
            local(root, path);
            assertTrue(run(root, env, true).getOutput().contains("ndk.dir must"));
        }
    }

    @Test public void invalidEnvironmentDoesNotSilentlyUseSdk() throws Exception {
        for (String path : new String[] {"missing", "wrong-version", "wrong-host"}) {
            File root = fixture();
            ndk(root, "wrong-version", "28.0.0", host);
            ndk(root, "wrong-host", REVISION, "unsupported-host");
            assertTrue(run(root, new File(root, path), true).getOutput().contains("ANDROID_NDK_HOME must"));
        }
    }

    @Test public void onlyWindowsCanFallBackFromLockedLinuxEnvironment() throws Exception {
        File root = fixture();
        File linux = ndk(root, "linux", REVISION, "linux-x86_64");
        String output = run(root, linux, host.equals("darwin-x86_64")).getOutput();
        if (host.equals("windows-x86_64")) {
            assertTrue(output, output.contains("AGP=" + new File(root, "sdk/ndk/" + REVISION).getAbsolutePath()));
        } else if (host.equals("linux-x86_64")) {
            assertTrue(output, output.contains("AGP=" + linux.getAbsolutePath()));
        }
    }
}
