# Using MangoSSH

Details of behavior that the [README](../README.md) only summarizes.

## Port forwarding

Local, remote, and SOCKS5 rules live on the **Forwarding** page. Starting a rule
reuses an open SSH terminal or a Mosh terminal's authenticated companion SSH
connection for the same host. Otherwise it opens a connection dedicated to the
forward, so a tunnel does not require keeping a terminal open. A dedicated
connection closes as soon as its last forward stops, and its host-key or
password prompt appears over the page that started it. If a Mosh companion SSH
disconnects, its forwards are marked failed without ending the UDP terminal;
start a failed rule again to reconnect it.

## File transfers

Browse a host over SFTP from the **Files** button inside an open SSH or Mosh
terminal, which reuses that session's authenticated SSH connection, or from a
host's overflow-menu **Files** action, which opens a connection dedicated to
transfers. Single files and whole folders can be downloaded and uploaded. Mosh
terminals use the same SSH companion for the server-resource report in their
terminal toolbar.

Running transfers appear behind the transfer icon in the host list top bar, with
combined progress. That sheet can pause, resume, cancel, and retry a transfer,
open a finished download in another app, and clear finished records. Pausing
stops at a chunk boundary and keeps the byte offset, so resuming continues from
there. When the connection drops, an interrupted transfer pauses and keeps its
confirmed bytes. After you reconnect to the same host, with the same verified
host key, you can continue it from the transfer sheet; it never restarts on its
own. A transfer whose source changed or cannot be verified has to be restarted.

A file's **⋮** menu, or the open button in its preview, can also hand it to
another app. **Open with…** downloads the whole file into private app storage
first, which suits documents and images; downloads older than an hour are
removed. **Stream with…** gives a video player or viewer a seekable read-only
file whose bytes are fetched over SFTP only as that app reads them. Tapping an
audio or video file streams it directly. Streamed data is cached in memory only;
**Settings › Connection › Stream cache size** sets how much (64 MB by default),
capped at a quarter of the device's RAM. A stream stays readable until its
connection closes, and a browser-owned connection stays open for 30 seconds
after the player closes the file.

Small UTF-8 text files (up to 128 KiB) can be edited in place. Before saving,
the editor shows a diff of your changes; unsaved drafts are kept encrypted on
the device and can be restored later.

## Terminal appearance

**Settings › Terminal appearance** controls the shared font, base size, and
color theme used by every SSH and Mosh terminal. The default is Cascadia Mono PL
at 12sp with Mango Dark. JetBrains Mono NL and Fira Code are also bundled; each
font is an open-source static Regular TTF.

Choose Mango Dark, Dracula, Nord, Solarized Dark, or Solarized Light. The
chosen theme is fixed and does not follow the Android system light/dark mode.
Custom mode starts from one of those presets, preserving its ANSI palette and
selection colors while letting you choose opaque `#RRGGBB` foreground,
background, and cursor colors that meet a 3:1 contrast ratio.

The preference is stored locally on the device outside the encrypted vault and
portable backup archive. Pinch-to-zoom inside a terminal only changes that
terminal's display. Font and palette licenses, versions, and SHA-256 values are
recorded in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Language

MangoSSH includes English and Simplified Chinese. The language card at the top
of **Settings** can follow the Android system/app-language preference (the
default), or explicitly select **English** or **简体中文**. Changing it recreates
the UI but keeps application-scoped SSH/Mosh sessions and transfers running.

## Network routes

Each profile picks one route:

- **Direct** uses the Android network selected by the system.
- **Tailnet** uses the device's system Tailscale VPN.
- **tsnet** uses an embedded, process-scoped userspace Tailscale node. It does
  not request Android VPN access or depend on the Tailscale app, and supports
  official Tailscale browser enrollment and one-time Auth Key enrollment.
  Headscale, custom control servers, exit nodes, and device-wide VPN routing are
  intentionally out of scope. See [embedded-tsnet.md](embedded-tsnet.md).

Once enrolled, **Settings → Embedded Tailscale** shows this device's Tailnet
name and addresses and lists the other devices on the Tailnet, with their
platform, online state, and whether Tailscale SSH is on. **Connect** on a
device reuses the saved tsnet host that already targets it; otherwise, or
when you tap the row, a quick-connect dialog asks for a username, protocol,
and authentication (Tailscale SSH by default where the device offers it).
Quick connect does not save a host; **Save as host** opens the host editor
prefilled with the same settings.

## In-app updates

A sideloaded install of the `github` distribution can check **Settings** for a
newer signed release from
`https://github.com/sunging/MangoSSH/releases/latest`, download its APK to
app-private cache storage, verify the download's SHA-256 against the release's
published `SHA256SUMS` asset, and hand the verified file to the system package
installer. A release with no `SHA256SUMS` cannot be installed in-app; MangoSSH
links to the release page instead. Checking is manual ("Check now") plus an
opt-in, throttled automatic check (at most once every 24 hours) on app start.

No request is made to GitHub until you open the update check or an automatic
check is due, and no GitHub token is ever sent. The feature is hidden at runtime
when `PackageManager` reports that a store already owns updates. The `fdroid`
distribution excludes the GitHub client, download and archive-verification
code, installer handoff, `REQUEST_INSTALL_PACKAGES` permission, and update
`FileProvider` at compile time.
