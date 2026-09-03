package website.sung.mangossh.core

/** Fixed, non-sensitive updater diagnostics compiled only into the GitHub distribution. */
internal enum class AppUpdateLogEvent(override val code: String) : MangoFlavorLogEvent {
    CHECK_STARTED("app_update.check.started"),
    CHECK_SUCCEEDED("app_update.check.succeeded"),
    CHECK_FAILED("app_update.check.failed"),
    DOWNLOAD_STARTED("app_update.download.started"),
    DOWNLOAD_SUCCEEDED("app_update.download.succeeded"),
    DOWNLOAD_FAILED("app_update.download.failed"),
    DOWNLOAD_CANCELLED("app_update.download.cancelled"),
    VERIFY_FAILED("app_update.verify.failed"),
    INSTALL_HANDOFF("app_update.install.handoff"),
    INSTALL_HANDOFF_FAILED("app_update.install.handoff.failed"),
}
