# MangoSSH 完整改进交付记录

基线：`develop` / `ee4aef2594458125468e35b04d7b12597efc3b00`。以下为实现与验证记录；现有成果按用户要求整理为本地提交，不推送、不发布。

## 实现范围

| 原计划 | 实现及主要入口 |
| --- | --- |
| 一：保留合理设计 | 保留会话与 Compose 分离、Keystore/AES-GCM 保险库、备份预览合并、WebDAV 条件写入、原生 Mosh 与独立 SSH companion。 |
| 二：传输清单、源校验、截断 | `FileTransferManager` 固定进程内清单和每文件完成状态；恢复校验源身份与临时目标长度；重新开始重建清单；SAF 使用 `wt`，不回退到 `w`。 |
| 二：取消与读取边界 | 每执行代次独立 `BlockingOperation`、SFTP 通道和状态所有权；未运行即取消也结算；查询 15 秒、扫描 60 秒、文件传输 30 秒无进展；条目、深度、引导输出和资源报告读取中限额。 |
| 二：输入与生命周期 | `TerminalTransport` 同步复制/FIFO/唯一 writer，1 MiB 包含正在发送的数据；溢出结束会话；启动片段先入队；resize 合并。`SessionLifecycle` 防止关闭后接管资源；Mosh 先经同一 writer 退出，再超时强制回收。 |
| 二：tsnet 与健康检查 | 后端代次、有效租约、待获取请求和过期回调分别结算；SSH 断开监听不随心跳关闭；带应答探测及截止时间；前台、网络变化触发检查；Mosh companion 故障不结束 UDP 终端。 |
| 二：后台工作 | PIN 派生/验证、密钥解码/生成/导出移至后台 dispatcher；忙碌状态和重复提交限制；原有密码学参数不变。 |
| 三：权限与应用锁 | agent 默认限当前选定密钥，支持允许列表、逐次确认和期限；锁定撤销临时授权。敏感操作再认证默认关闭，可逐次或 5 分钟窗口；私钥导出也经过同一入口。PIN 退避持久化；已取消的再认证请求不能把迟到的验证结果交给另一项操作。 |
| 四：架构、性能、CI | 提取 `SessionLifecycle`、`SshAuthentication`、`TerminalTransport`、`SshFeatureConnection`、`TransferSupervisor`；全局 2/每会话 1 传输、200 ms 进度节流、目录缓存、仅可见终端渲染；验证与正式签名作业隔离。 |
| 五：诊断与结束记录 | 脱敏诊断仅允许状态、时间和错误类别；配置路由不冒充测得的 VPN 路径。最近 10 个已结束终端仅驻留内存，不占活动资源；重连创建新会话，启动片段再次确认。 |
| 五：安全传输 | 冲突预览、跳过/另存/覆盖、任务内复用选择、可选 SHA-256。临时写入后校验提交；明确区分 OpenSSH POSIX 替换与普通 rename；不支持替换时直接覆盖须单独确认。 |
| 五：tmux | 可选探测/列表/创建/精确 ID 附着；独立 exec 管理，SSH PTY 或 Mosh 服务端启动方式附着，与启动片段互斥。 |
| 五：主机策略 | 应用默认值到主机覆盖，界面显示生效来源；连接和跳板在创建时固定策略快照。 |
| 五：跳板与配置导入 | 显式最多 4 跳、逐跳认证/主机密钥确认、direct-tcpip、逆序清理；Mosh 禁用跳板链。SSH 配置只解析声明式字段，预览密钥/跳板映射和不兼容项，不执行 Include/Match/ProxyCommand。 |
| 五：远端编辑 | 完整 UTF-8、128 KiB、BOM/换行保留；内存草稿和离开确认；保存前摘要/元数据检查，复用临时上传、校验、替换及直接覆盖确认。 |
| 兼容迁移 | 保险库 schema 6，继续读取 1–5；portable v3 保持不变；新增引用参加验证、冲突合并和删除依赖检查；旧数据升级保留加密恢复点。 |

SSH 来源固定为 `org.connectbot:sshlib:2.2.48:sources`；来源摘要、许可证和补丁清单见 `third_party/sshlib/README.md`。APK 包含许可证。没有冒称不存在或未经验证的上游 Git 提交。

## 验证记录

本节区分本机验证、设备验证和尚未覆盖的环境；编译成功不代表设备验收。

- JDK 17：GitHub 单元测试 253 项、F-Droid 241 项、termlib 6 项、SSH 模块 2 项通过。
- 双发行版 debug APK、AndroidTest 编译和 Lint 通过；termlib Lint 和 AndroidTest 编译通过。
- GitHub 完整设备套件 116 项通过；最后的传输/四跳/Mosh/界面增量套件 11 项通过。
- F-Droid 完整设备套件 111 项通过。最终版本分别在 GitHub、F-Droid 上通过 14 项专项（原子/非原子保存、四跳与失败清理、Mosh、SAF、界面和 PIN 持久化）；termlib 14 项设备测试通过。
- 四 ABI PTY 桥使用 NDK r27d 实际重建。两个 APK 中所有原生库通过 `check-16kb-elf.sh` 和 `zipalign -c -P 16 -v 4`，四 ABI Mosh/PTY、terminfo、GPL/BSD 材料齐全。

模拟器为原有 `emulator-5554`。安装前比较原已安装 APK、产物和 Android Studio debug keystore 的证书；使用 `install -r -t` 覆盖，手动 `am instrument` 执行经过审查的测试。没有卸载被测应用、`pm clear`、Wipe Data、强制降级或绕过签名检查。跨发行版安装后核对：原有文件无丢失，加密保险库 SHA-256 与安装前一致；唯一变化的原有文件是 Android Profile Installer 记录；全部专项结束后再次核对，结果一致。模拟器最终保留 GitHub debug 版本。

设备测试使用专属目录、SharedPreferences、Keystore 别名；PIN 输入、SSH 服务端密钥在运行时生成。没有使用真实主机、密钥、保险库、WebDAV 配置作为夹具。本轮临时 SSH/SFTP 服务已停止、私有 tmux 服务已关闭，测试端口和 ADB 反向映射已释放。布局测试通过局部 Compose 密度/大小覆盖，不修改模拟器系统显示设置。

原始设备报告保留于本机临时目录：`mangossh-github-final-device.log`、`mangossh-fdroid-final-device.log`、`mangossh-github-security-final-device.log`、`mangossh-fdroid-security-final-device.log`、`mangossh-termlib-final-device.log`。完整套件与最终专项分开报告，避免把增量验证冒称最终源码的整套重复执行。

## 协议夹具与边界

`tools/ssh-test-fixture.py` 是显式启用的临时 loopback SSH/SFTP 夹具，文件仅存于临时根目录，direct-tcpip 只允许回到它自己的端口。设备测试仅在提供 `fixturePort` 时连接它。Windows 的 `--wsl-tmux` 使用私有 tmux socket、空配置和受限命令，不安装或修改远端配置。

Mosh 测试运行真实打包客户端和 WSL mosh-server。Windows/WSL 的 localhost UDP 不直接互通，因此测试用 `ssh-fixture-udp.py` 在专用 SSH 通道中转发有界数据报，并在设备 loopback 上还原 UDP；中继不替代 Mosh 协议，不记录 Mosh key。验证收取固定输出、resize 和进程退出回收。另一个 `--no-posix-rename` 夹具专门验证不支持原子替换时的拒绝、单独确认及另存分支。

本机没有验证真实 Tailnet/TSNET 网络、物理网络切换、长时间 Doze、电池消耗、任意第三方 SAF Provider 或所有 SSH 服务端实现；这些不以本地 loopback 结果代替。安全保存不承诺消除普通 SFTP 无条件替换的外部并发竞态；目录逐文件提交；源身份元数据受提供方精度约束，可选择额外 SHA-256 校验。断网或进程死亡可能使远端临时文件无法立即清理，不会为清理而重新连接或删除原目标。

GitHub `ci-signing` 环境的受保护分支策略及环境 secrets 仍需仓库管理员配置；本轮没有修改线上设置，也没有运行签名/发布作业。参见 `docs/ci-signing.md`。


## 跳板与 tmux 交互调整（2026-09-15）

- 跳板编辑只展示已选择的有序跳板和“添加跳板”入口，候选主机在按需打开的搜索弹窗中选择；排除自身、已选项、Mosh 及含嵌套跳板的主机，保留最多 4 跳约束。
- 主机配置及终端工作区弹窗增加“创建或附着”。按完整名称查找并返回远端会话 ID；不存在则创建，创建并发冲突时重新查找。名称前缀相似的工作区不会被误附着。原有创建及按 ID 附着模式保持兼容，SSH/Mosh 共用工作区准备逻辑。
- JDK 17 下 GitHub/F-Droid 单元测试分别 254/242 项通过，termlib 6 项及 SSH 模块 2 项通过；两种发行版 Lint、debug APK、AndroidTest 编译及 termlib Lint/AndroidTest 编译通过。
- 原有 emulator-5554 上两种发行版各通过 5 项专项设备测试，覆盖候选隐藏/搜索/顺序/取消/上限、工作区操作入口、真实 tmux 精确名称复用及原有创建/列表/按 ID 选择。两种 APK 的 ZIP 16 KiB 对齐检查通过。
- 已安装应用、两种产物与 Studio debug 证书一致，使用保留数据覆盖安装，最终保留 GitHub debug。核对原有 29 个文件无丢失、加密保险库内容一致；唯一变化仍是 Profile Installer 记录。隔离 SSH/tmux 服务及本轮 ADB 映射已回收。没有提交、推送或发布。

本次增量原始报告：本机临时目录中的 `mangossh-host-workflows-final-build.log`、`mangossh-host-workflows-fdroid-final-device.log`、`mangossh-host-workflows-github-final-device.log`。


## 主机编辑页改造（2026-09-15）

先按用户指定顺序整理现有成果，再开始编辑器改造。以下三个提交已在本地 `develop` 创建，没有推送：

1. `70388df build(ssh): vendor patched sshlib 2.2.48`
2. `282fb4e feat: improve session reliability and SSH workflows`
3. `13fa9dc ci: isolate signing from Android validation`

编辑器改造独立保留为未提交差异：

- 手机全屏；窗口宽度至少 600 dp 时居中展示，最大宽度 720 dp。主页面仅保留基本字段及连接/跳板/启动/安全/高级五个摘要入口。
- 同一容器内导航，详情共用草稿；主页面保存按钮固定，适配键盘。返回详情不落库，放弃修改需要确认；保存状态恢复保留草稿、所在详情及主页面滚动位置。
- 路由、认证、终端及其他枚举改为当前值选择；密钥、片段及授权允许列表按需打开。Mosh 清除跳板、tmux 与片段替换均可取消。
- 关闭 agent forwarding 隐藏授权详情并保留策略；授权弹窗全部内容可滚动。隐藏字段的无效配置在主页面入口和保存提示中标明。
- 保留现有领域及保险库接口。表单完整携带主机元数据，仓库原有的收藏、排序、使用记录保护继续生效。新增文字提供英文和简体中文。

最终验证：

- JDK 17：GitHub 259 项、F-Droid 247 项单元测试通过；termlib 6 项、SSH 模块 2 项通过。
- 两种发行版 Lint、debug APK、AndroidTest 编译通过；termlib Lint 和 AndroidTest 编译通过。
- 两种发行版各通过 15 项编辑相关设备回归，覆盖生产路由选择器、草稿状态恢复、系统返回、双向启动互斥、Mosh 确认、缺失密钥、保存/取消、320×420 dp / 1.6 倍字体、宽屏及实际键盘展开。最后的授权弹窗滚动调整，另在两种发行版各通过 2 项增量测试。没有将该增量描述为整套设备测试重跑。
- 手机、短屏大字体、宽屏及键盘界面截图已检查。模拟器系统屏幕尺寸和字体未改；软键盘显示设置仅在键盘测试期间临时启用，结束后恢复原值。
- 最终两种 APK 的 ELF PT_LOAD 和 ZIP 16 KiB 对齐检查通过；四 ABI Mosh/PTY、terminfo、GPL/BSD 材料完整。
- 沿用原有 emulator-5554，核对已安装应用、产物及 Studio debug 证书并检查版本后，使用 `install -r -t` 保留数据覆盖安装。最终保留 GitHub debug。原有 29 个文件无丢失，保险库 SHA-256 一致，仅 Profile Installer 记录变化；只清理本轮专属截图缓存。

本轮原始记录位于本机临时目录：`mangossh-host-editor-final-build.log`、`mangossh-host-editor-{github,fdroid}-final-device.log`、`mangossh-host-editor-{github,fdroid}-agent-device.log`。该轮没有重新运行整个应用的完整设备套件，也没有新增远端网络或协议行为。


## 编辑器减少弹框（2026-09-15）

- 协议、路由、认证、tmux 模式及再认证改为详情页内直接选择，选项在窄屏和大字体下自动换行。
- 超时、心跳、后台倍率、终端类型及 agent 授权期限改为锚定当前设置行的紧凑下拉菜单。
- 登录密钥、启动片段、跳板和 agent 允许列表继续使用可搜索的长列表选择界面；放弃草稿、Mosh 清除跳板及替换启动方式保留明确确认。
- 主页面仍为基本字段和五个摘要入口，未恢复原来的长表单。新增回归直接断言简单选项没有对话框；同时覆盖长列表搜索、路由联动与必要确认。


本轮验证：JDK 17 下 GitHub/F-Droid 单元测试 259/247 项、termlib 6 项、SSH 模块 2 项通过；双发行版 Lint、debug APK、AndroidTest 编译及终端模块检查通过。两种发行版在原有 emulator-5554 上各通过 17 项编辑设备回归，连接与启动详情截图已检查。两种最终 APK 原生资产及 GPL/BSD 材料完整，ELF/ZIP 16 KiB 对齐通过。

安装前核对已安装 APK、产物和 Studio debug 证书及版本，保留数据覆盖安装，最终保留 GitHub debug。原有 29 个文件无丢失，加密保险库内容一致，原有文件仅 Profile Installer 记录变化。软键盘显示设置已恢复，本轮专属截图缓存已清理。编辑页改动仍未提交，没有推送。

报告保留于本机临时目录：`mangossh-editor-inline-build.log`、`mangossh-editor-inline-fdroid-device.log`、`mangossh-editor-inline-github-device.log`。
