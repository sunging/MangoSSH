package website.sung.build;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Test-only native task; a rendezvous fails if Gradle serializes independent producers. */
public abstract class NativeInputProbe extends NativeTask {
    @OutputFile public abstract RegularFileProperty getResult();
    @Input public abstract Property<Boolean> getConcurrentProbe();

    @TaskAction public void probe() throws Exception {
        var result = getResult().get().getAsFile().toPath();
        Files.createDirectories(result.getParent());
        if (getConcurrentProbe().get()) {
            var state = getStateDirectory().get().getAsFile().toPath();
            Files.createDirectories(state);
            Files.writeString(state.resolve(getName()), "started");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(state.resolve("moshProbe")) || !Files.exists(state.resolve("tsnetProbe"))) {
                if (System.nanoTime() > deadline) throw new GradleException("Native producers were serialized");
                Thread.sleep(25);
            }
        }
        Files.writeString(result, "verified synthetic output");
    }
}
