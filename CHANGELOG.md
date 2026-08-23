# Changelog

## [0.4.0](https://github.com/sunging/MangoSSH/compare/v0.3.0...v0.4.0) (2026-08-21)


### Features

* keep an authenticated SSH companion for Mosh sessions ([a2061f4](https://github.com/sunging/MangoSSH/commit/a2061f428917d9e933850162ab34a36cd78b81b4))
* **security:** make the app auto-lock delay configurable ([cc55d50](https://github.com/sunging/MangoSSH/commit/cc55d5044b3f9e065138a949c20842869d6dac26))
* **settings:** add an about page ([c034abf](https://github.com/sunging/MangoSSH/commit/c034abf6d11b85880dcec75a058743c0421cac5c))
* **settings:** add app theme mode and dynamic color ([1fc2e5f](https://github.com/sunging/MangoSSH/commit/1fc2e5f5fe4d41da2eb9cd7935cd7aa67565597b))
* **settings:** add connection defaults ([1d32865](https://github.com/sunging/MangoSSH/commit/1d328654e7fc9934b4c960688243d7e424d65adf))
* **settings:** add terminal behavior and keyboard input preferences ([5f662a0](https://github.com/sunging/MangoSSH/commit/5f662a06ac732cb24ac94c622cf095668c755bbe))
* **settings:** keep an off-grid persisted value in its picker ([af02890](https://github.com/sunging/MangoSSH/commit/af02890014b2b3eceea9472c23bfcf1b10d1e4aa))
* show the embedded Tailscale build version in Settings ([9a6b67c](https://github.com/sunging/MangoSSH/commit/9a6b67c45855adb4015b6a281b3209b9a4fd202d))
* **terminal:** make emulator scrollback and pinch range configurable ([fd3c73f](https://github.com/sunging/MangoSSH/commit/fd3c73fccf89d5769b39901651060497ce689b67))
* **ui:** add shared settings components and a two-pane tablet layout ([71c9f2e](https://github.com/sunging/MangoSSH/commit/71c9f2e6057e99a9ee024122797a66ed96ae2bd4))


### Bug Fixes

* address develop review findings ([dd002a0](https://github.com/sunging/MangoSSH/commit/dd002a0c8ee385743c6b8ef0286db068fed8b8be))
* harden session failure handling ([7679c13](https://github.com/sunging/MangoSSH/commit/7679c130b432bc61f716f0418166d5923ef7057d))
* **settings:** resolve duplicate preferences property in AppThemeStore ([dba5ed7](https://github.com/sunging/MangoSSH/commit/dba5ed739679bf3c4f201956df5b5b9bbf9daa0d))
* **test:** repair the instrumented test source set ([fb04964](https://github.com/sunging/MangoSSH/commit/fb049640265a3cd81bebfa3b7b7d56ded5cdbc24))

## [0.3.0](https://github.com/sunging/MangoSSH/compare/v0.2.0...v0.3.0) (2026-08-07)


### Features

* redesign host list and add connection usage stats ([bcf835e](https://github.com/sunging/MangoSSH/commit/bcf835e5ff4ff55a046086cf818a115f0be7b6a8))
* redesign host list with overflow menu, manual reorder, and search ([4a8419c](https://github.com/sunging/MangoSSH/commit/4a8419c0a5a5d265183a386974ca876bc2a5b393))
* show connection count and last-connected time on host cards ([6228fbe](https://github.com/sunging/MangoSSH/commit/6228fbe9fde4c782464f5ed6034101b7d07048b6))


### Bug Fixes

* address CodeQL findings for biometric auth, PendingIntents, and Zip Slip ([7a3b2a9](https://github.com/sunging/MangoSSH/commit/7a3b2a9fe41807f2cd0d5354d9e14c7c811e939c))

## [0.2.0](https://github.com/sunging/MangoSSH/compare/v0.1.0...v0.2.0) (2026-08-05)


### Features

* add a GitHub release client and update file store ([590ba94](https://github.com/sunging/MangoSSH/commit/590ba943b2e9ce2839f3dce8e7d03256d460a4b3))
* add a home shortcut to the remote file browser ([c4b96c2](https://github.com/sunging/MangoSSH/commit/c4b96c23c3ac5577962bc216ffb003888a3d44cd))
* add an in-app update settings card ([0824498](https://github.com/sunging/MangoSSH/commit/0824498dabc5ad7aa2c13aa2753dae1fe5ede320))
* add an SFTP remote file browser with text preview ([4134348](https://github.com/sunging/MangoSSH/commit/4134348451ee4ba19dbddd1ec7e9c5d3ff2d5381))
* add self-update version and preference models ([b8d0558](https://github.com/sunging/MangoSSH/commit/b8d05584700b7b609388ce33608278c753cb6bef))
* browse and transfer remote files without a shell session ([56e8bf1](https://github.com/sunging/MangoSSH/commit/56e8bf1604cb61595df31f60b233e877dbb5c6df))
* control file transfers from the host list and transfer folders ([1e44484](https://github.com/sunging/MangoSSH/commit/1e44484bd3e9a894dd9c5e2a568468d26364faf6))
* SFTP browsing, file transfers, port forwarding, and in-app updates ([05614e8](https://github.com/sunging/MangoSSH/commit/05614e85c67ee4deb11bf24350557bcf9464e3b8))
* start a port forward without a terminal session ([6410809](https://github.com/sunging/MangoSSH/commit/64108096cd1e092a5faa8ec3893eff8d4665e5fd))


### Bug Fixes

* drop the duplicated separator after the breadcrumb root ([55b1b60](https://github.com/sunging/MangoSSH/commit/55b1b606e753c7400bc7e1eb498d941e868fa810))
* hide ready vault banner on home screen ([ae511e8](https://github.com/sunging/MangoSSH/commit/ae511e8b3ac9ac5ba6fa26dbcc8262cc55f5f68a))
* keep terminal below status bar ([93e9600](https://github.com/sunging/MangoSSH/commit/93e96005af8b1560ef00de5ba5166e8900c34810))
* match session bars to the terminal palette ([93f5e7f](https://github.com/sunging/MangoSSH/commit/93f5e7fe969da7f9e4b2a209167647711630c7ed))

## [0.1.0](https://github.com/sunging/MangoSSH/compare/v0.0.1...v0.1.0) (2026-08-03)


### Features

* add customizable terminal shortcuts ([3395635](https://github.com/sunging/MangoSSH/commit/3395635b298bf64da0aaefd129f19accc4669fce))
* add terminal appearance settings ([9e0a046](https://github.com/sunging/MangoSSH/commit/9e0a046a9581c16b823b0c20c9bf709ecef69dfd))
* expand SSH key generation options ([eb05b5c](https://github.com/sunging/MangoSSH/commit/eb05b5cced59ad796a4a69c1bd72d80db2e0c804))
* prepare MangoSSH v0.1.0 ([07f455f](https://github.com/sunging/MangoSSH/commit/07f455fdbcacf70b69f1cccb8bc6728fa988c180))


### Bug Fixes

* hide redundant security banners ([644406c](https://github.com/sunging/MangoSSH/commit/644406c8e546cb1555c4edd4bd6564de67994dc9))
* invoke sdkmanager from Android SDK ([a06b6e2](https://github.com/sunging/MangoSSH/commit/a06b6e29b2002870f6dc15f734b27daf2da2fe7d))
* invoke sdkmanager from Android SDK ([f14233b](https://github.com/sunging/MangoSSH/commit/f14233b11dce008e8225847d4ab0655bae38f9e6))
* keep idle SSH sessions alive ([fb22a19](https://github.com/sunging/MangoSSH/commit/fb22a192bcda4aa065adc57c57a651fff76e88ea))
* keep orderly session exits silent ([97adb78](https://github.com/sunging/MangoSSH/commit/97adb783d8b24bb061db836d8878807c8a8284bb))
* keep terminal shortcuts above keyboard ([700c9de](https://github.com/sunging/MangoSSH/commit/700c9def719493de7c7ea25b6de0b5415211f4dc))
* rebuild terminal display after resize ([c1b51d0](https://github.com/sunging/MangoSSH/commit/c1b51d0bb161bce892ff0774dcafb87df0f928ae))
* reopen terminal keyboard on tap ([92f18d3](https://github.com/sunging/MangoSSH/commit/92f18d3b4796240fa145622c0fe7543da1e3d503))
* use 16 KiB-aware zipalign for releases ([c91a716](https://github.com/sunging/MangoSSH/commit/c91a7165b60d14cba054ea8c6ab80853444bdf5f))
* use 16 KiB-aware zipalign for releases ([ad065a3](https://github.com/sunging/MangoSSH/commit/ad065a3516856b77de8e586fc7382aa87368bb08))

## 0.0.1 (2026-08-01)

### Features

- Initial public release with SSH and native Mosh sessions, encrypted profiles
  and keys, WebDAV backups, port forwarding, SCP, app lock, and optional
  Tailscale routing.
