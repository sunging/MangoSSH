package website.sung.build;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;

/** Declares the source files actually consumed by the offline native adapters.
 * Keep these patterns aligned with tools/native/build.py's component identities.
 * Test helpers and Python bytecode are not build inputs: including them would
 * send an otherwise unchanged Windows build through WSL and hash all Go sources.
 */
public final class NativeInputs {
    private NativeInputs() { }

    /** Shared adapter implementation, launcher and pinned compiler contract. */
    public static ConfigurableFileTree common(Project root) {
        var tree = root.fileTree(root.getProjectDir());
        tree.include(
            "native/toolchains.json",
            "tools/native/build.py", "tools/native/state.py", "tools/native/artifacts.py",
            "tools/native/gradle-entry.sh");
        return tree;
    }

    /** Recipes and lock file identifying all external Mosh source checkouts. */
    public static ConfigurableFileTree mosh(Project root) {
        var tree = root.fileTree(root.getProjectDir());
        tree.include(
            "native/mosh/**", "tools/fdroid-sources.lock");
        return tree;
    }

    /** Vendored Go inputs and every script hashed by build_tsnet, including notices. */
    public static ConfigurableFileTree tsnet(Project root) {
        var tree = root.fileTree(root.getProjectDir());
        tree.include(
            "native/tsnetbridge/**",
            "tools/build-tsnet-android.sh", "tools/normalize-tsnet-aar.py",
            "tools/generate-tsnet-notices.py", "tools/lib/*.sh", "tools/patches/tailscale-*.patch"
        ).exclude("native/tsnetbridge/.build/**");
        return tree;
    }
}
