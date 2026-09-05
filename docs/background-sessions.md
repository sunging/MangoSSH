# Background session reliability / 后台会话保活

## Cause and scope

`SessionForegroundService` already owns live sessions independently of the
Activity, and `SshSessionController` already sends configurable SSH keepalives.
However, the service previously never acquired a CPU wake lock, despite the
manifest declaring `android.permission.WAKE_LOCK`. Foreground priority is not a
CPU wake lock: after screen-off suspension the keepalive coroutine and embedded
transports may stop getting CPU time, allowing the remote peer or an intermediate
network device to expire an otherwise idle connection.

This is a concrete code-level gap, not a device-specific reproduction. A missing
wake lock does not prove the cause of every disconnect. Doze, OEM background
restrictions, server policy, loss of connectivity, and process termination remain
separate possibilities.

## Lifetime and battery behavior

The service now acquires one non-reference-counted `PARTIAL_WAKE_LOCK` tagged
`MangoSSH:ActiveSessions` while it owns at least one session. It does not keep the
screen on. Connecting, authentication, interactive SSH/Mosh, transfer-only, and
forwarding-only sessions use the existing aggregate session list.

Each acquisition has a 10-minute timeout and the service renews it every 5
minutes, even when the platform lock is still held. The finite lease is a
fallback if renewal stops, not a 10-minute session limit. Closing the final
session, losing foreground ownership, or destroying the service releases it
immediately. An idle embedded-tsnet foreground service without sessions does
not keep the CPU awake. Repeated state emissions do not add references.

Power-management errors use the sanitized `session.wake_lock.failed` log event
and do not escape into service startup or teardown. Existing keepalive settings,
SSH host verification, authentication, and Mosh transport behavior are unchanged.
A keepalive interval of zero still means disabled; this fix does not silently
change that preference. Keeping an active background session responsive costs
battery, so close sessions when they are no longer needed.

## Android restrictions that remain

A partial wake lock does **not** bypass Doze: Android can ignore wake locks and
restrict network access in that mode. For a user who requires long-lived
screen-off connections, check Android's app battery settings and battery
optimization exemption for MangoSSH. Names and additional background/autostart
controls vary by device. The app does not silently exempt itself or add a direct
battery-optimization exemption request permission in this change.

A foreground service and wake lock also cannot preserve an SSH TCP socket after
force-stop, process death, or every network handover. This change deliberately
retains `START_NOT_STICKY`; restarting a process does not restore its old sockets
and must not silently replay authentication or startup commands.

## Regression checks

Run with JDK 17 from the repository root:

```sh
./gradlew :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest
./gradlew :app:lintGithubDebug :app:lintFdroidDebug \
  :app:assembleGithubDebug :app:assembleFdroidDebug \
  :app:assembleGithubDebugAndroidTest :app:assembleFdroidDebugAndroidTest
```

`SessionWakeLockTest` covers immediate acquisition, foreground gating, idle
tsnet, repeated snapshots, renewal beyond the initial lease, expired-lease
recovery, final-session cleanup, foreground loss, destruction, reopening, and
acquire/release/diagnostic failures. These JVM tests use fake platform callbacks;
they do not demonstrate real-device Doze behavior.

On a test device, record Android version, device model, transport/route, battery
settings, disconnect time, and whether the foreground service remains running.
Do not publish endpoint data, credentials, private keys, or full unredacted
system dumps.

1. Connect with SSH keepalives enabled, leave an otherwise idle shell open,
   unplug the device, press Home, and turn the screen off. Test for longer than
   20 minutes so at least two lease renewals are exercised. Repeat with an active
   long-running command, Mosh, and each available route on a stable network.
2. Inspect `adb shell dumpsys power` locally. While sessions are live, check for
   the `MangoSSH:ActiveSessions` partial wake lock. It must remain present beyond
   the first 10-minute lease. Check that the same shell is usable on return, not
   a newly authenticated replacement.
3. Open two sessions, close one, then close the last. The lock must survive the
   first close and disappear after the last. Repeat while embedded tsnet remains
   enabled with no sessions, after connection/authentication failure, and after
   a remote shell exit. Close/reopen rapidly to exercise queued service starts.
4. Separately test forced Doze, both with and without a user-approved battery
   optimization exemption. Turn the screen off and use the commands below.
   Without exemption, network suspension is an expected platform limitation,
   not evidence that a partial wake lock can override Doze. Do not conflate this
   test with ordinary CPU suspension. Always restore device state afterward.

```sh
# Test device only: simulate unplugging, then force device idle.
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle

# Always run cleanup, including when a test fails.
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

## 中文说明

原实现已有前台服务和 SSH 心跳，但只声明了 `WAKE_LOCK` 权限，没有实际持有
CPU 电源锁。锁屏后 CPU 休眠可能使心跳和内嵌网络运行时无法及时运行。
本次补上与活动会话生命周期绑定的电源锁：每次有效期 10 分钟、每 5 分钟续期，
最后一个会话结束、前台服务失去所有权或服务销毁时立即释放；仅开启 tsnet
而没有会话时不持锁。不会保持屏幕常亮，也不会修改用户的心跳配置。

这解决的是代码中明确缺失的 CPU 保活，并不等于所有机型都能无限后台在线。
Doze、厂商省电策略、服务器超时、网络切换和进程被终止仍需分别排查。
需要长期锁屏连接时，应检查系统中 MangoSSH 的电池优化和后台运行设置。
请在稳定网络下锁屏超过 20 分钟验证，并分别测试多会话关闭和 Doze；
上述设备测试是回归步骤，不代表已经在真实手机上验证通过。

## Platform references

- [Choose the right API to keep the device awake](https://developer.android.com/develop/background-work/background-tasks/awake)
- [PowerManager.WakeLock API](https://developer.android.com/reference/android/os/PowerManager.WakeLock)
- [Optimize for Doze and App Standby, including device-idle testing](https://developer.android.com/training/monitoring-device-state/doze-standby)
