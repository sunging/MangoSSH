package website.sung.build;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Makes native producer types available without configuring unrelated projects. */
public final class NativeToolsPlugin implements Plugin<Project> {
    @Override public void apply(Project project) { }
}
