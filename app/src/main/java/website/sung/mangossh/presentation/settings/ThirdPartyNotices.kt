package website.sung.mangossh.presentation.settings

/**
 * One bundled dependency shown on the About page.
 *
 * [name], [license], and [url] are ASCII proper nouns / SPDX identifiers and
 * are deliberately not routed through string resources. [licenseAsset] is a
 * path under `assets/` when the full license text ships in the APK; `null`
 * when it does not (the entry still records the attribution).
 */
internal data class ThirdPartyNotice(
    val name: String,
    val license: String,
    val url: String,
    val licenseAsset: String?,
)

/**
 * Mirrors the top-level `THIRD_PARTY_NOTICES.md`. Keep the two in sync when a
 * bundled dependency, its license, or its license asset path changes.
 */
internal val thirdPartyNotices = listOf(
    ThirdPartyNotice(
        name = "ConnectBot mosh4android (mosh-client)",
        license = "GPL-3.0-or-later",
        url = "https://github.com/connectbot/mosh4android",
        licenseAsset = "licenses/GPL-3.0-or-later.txt",
    ),
    ThirdPartyNotice(
        name = "ConnectBot termlib",
        license = "Apache-2.0",
        url = "https://github.com/connectbot/termlib",
        licenseAsset = "licenses/Apache-2.0-ConnectBot-Terminal.txt",
    ),
    ThirdPartyNotice(
        name = "Tailscale tsnet",
        license = "BSD-3-Clause",
        url = "https://github.com/tailscale/tailscale",
        licenseAsset = "licenses/tailscale-BSD-3-Clause.txt",
    ),
    ThirdPartyNotice(
        name = "Tailscale tsnet dependency notices",
        license = "Multiple",
        url = "https://github.com/tailscale/tailscale",
        licenseAsset = "licenses/tsnet-third-party-notices.txt",
    ),
    ThirdPartyNotice(
        name = "Cascadia Code",
        license = "OFL-1.1",
        url = "https://github.com/microsoft/cascadia-code",
        licenseAsset = "licenses/OFL-1.1-Cascadia-Code.txt",
    ),
    ThirdPartyNotice(
        name = "JetBrains Mono",
        license = "OFL-1.1",
        url = "https://github.com/JetBrains/JetBrainsMono",
        licenseAsset = "licenses/OFL-1.1-JetBrains-Mono.txt",
    ),
    ThirdPartyNotice(
        name = "Fira Code",
        license = "OFL-1.1",
        url = "https://github.com/tonsky/FiraCode",
        licenseAsset = "licenses/OFL-1.1-Fira-Code.txt",
    ),
    ThirdPartyNotice(
        name = "Dracula (Windows Terminal)",
        license = "MIT",
        url = "https://github.com/dracula/windows-terminal",
        licenseAsset = "licenses/MIT-Dracula-Windows-Terminal.txt",
    ),
    ThirdPartyNotice(
        name = "Nord (Terminal App)",
        license = "MIT",
        url = "https://github.com/nordtheme/terminal-app",
        licenseAsset = "licenses/MIT-Nord-Terminal-App.txt",
    ),
    ThirdPartyNotice(
        name = "Solarized",
        license = "MIT",
        url = "https://github.com/altercation/solarized",
        licenseAsset = "licenses/MIT-Solarized.txt",
    ),
)
