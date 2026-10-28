# OverDrive 中文版精简版（SLIM-CN）说明

> 分支：`slim-cn`（由中文版 `main` @ a63356d 切出，未 push）。
> 底本：komes2018/Overdrive-release-cn（上游 OverDrive 的国内优化专版 fork）。
> 精简口径：用户 2026-10-28 原定——只留哨兵、行车记录、远程查看、SIM 绕过，其余全砍。
> 方法：本地无 Android SDK、无法本地编译验证，因此**不为删而删**：优先砍 UI 入口
> 与启动链路；只有 grep 证明无其他引用时才删代码；删不干净的一律保留代码、只
> 禁用入口，并在下面逐项注明。

## 一、保留清单

| 功能 | 状态 | 说明 |
|---|---|---|
| 哨兵模式 | 保留 | 停车监控、移动侦测触发、事前缓冲；本体是 daemon/SentryDaemon 与 AccSentryDaemon，未动 |
| 行车记录模式 | 保留 | 四路循环录像；摄像头映射诊断页保留（手动映射入口） |
| 远程查看 | 保留 | 局域网看板（车机 IP:8080）＋ Tailscale 隧道（用户启用才启动，默认关闭） |
| VLESS 代理（SIM 绕过） | 保留 | GlobalProxyDaemon / SingboxLauncher，用户在设置里自填服务器信息；仍需自备境外 VPS，不省流量、不省服务器钱 |
| 车辆控制、充电、座椅位置、比亚迪云 | 保留 | 不在砍掉清单内，未动 |
| Cloudflared / Zrok / WireGuard 隧道 | 保留 | 不在砍掉清单内；用户启用才启动，默认关闭 |
| 仪表盘、设置、诊断、ADB 控制台等基础面 | 保留 | 未动 |
| 中文翻译 | 保留 | values-zh-rCN 一个字未动；新增字符串只写默认 values/（见第三节） |

## 二、砍掉清单（每项注明已删 / 仅禁用、代码保留）

侦察结论先说在前面：**全部被砍模块在包外均有引用**（CameraDaemon、HttpServer、
surveillance、byd、community、genai、mqtt、telegram、daemon 等互相引用），因此
**没有任何一个包可整包删除**。下面全部是“入口已删、代码保留”，除非另有说明。

| 功能 | 处理 | 具体改动 |
|---|---|---|
| 语音助手 genai/ai | 仅禁用、代码保留 | nav_graph 删 genAiFragment；主界面 Rail 删 ASSISTANT 行并 GONE；Dashboard 的 AI 洞察区改为 GONE；2 个单元测试含其字符串字面量（见第五节） |
| 停车智能 parking | 仅禁用、代码保留 | nav_graph 删 parkingFragment；Rail 删 PARKING 行并 GONE；判定见下方 |
| 仪表盘投影 ProjectionFragment | 仅禁用、代码保留 | nav_graph 删 projectionFragment；Rail 删 PROJECTION 行并 GONE |
| 行程 trips | 仅禁用、代码保留 | nav_graph 删 tripsFragment；Rail 删 TRIPS 行并 GONE；Dashboard 的 quickTrips 改 GONE 并删点击监听；新手导览删 TRIPS 章节 |
| 自动化 automation | 仅禁用、代码保留 | nav_graph 删 automationsFragment；Rail 删 AUTOMATIONS 行并 GONE；rail_menu 删 automations 项 |
| 按键映射 | 入口已删＋触发已关、代码保留 | nav_graph 删 keyMappingFragment；Rail 删 KEY_MAPPING 行并 GONE；KeyMapDispatcher.fire() 改为空实现（原实现见 git 历史），此前已配映射亦不再触发 |
| Telegram（含 TelegramBotDaemon） | 自启已删、代码保留 | nav_graph 删 telegramSettingsFragment；Integrations 页 cardTelegram 改 GONE 并删监听；DaemonStartupManager 两处 Telegram Bot 自启块（ViewModel 路径与 ADB 路径）改注释删除；**残留见第五节** |
| Home Assistant / MQTT | 仅禁用、代码保留 | nav_graph 删 mqttFragment；Integrations 页 cardMqtt 改 GONE 并删监听 |
| ADAS / RoadSense | 入口与自启已删、代码保留 | nav_graph 删 roadSenseFragment；Rail 删 RoadSense 行并 GONE；新手导览删 ROADSENSE 章节及相关方法；BootReceiver 两处 armBlindSpotIfEnabled 调用及方法本身删除；DaemonKeepaliveService 的 RoadSense 覆盖层自启块删除；rail_menu 删 roadSense 项；navmap 里的 RoadSense 活动（RoadSenseMapActivity 等）仅由 RoadSense 网页经 JS 桥拉起，车机入口已断，见第五节 |
| 社区 community | 仅禁用、代码保留 | 无独立导航入口（此前经 server/HttpServer 与 genai 引用）；车机界面上已无入口 |
| 天气 weather | 仅禁用、代码保留 | UI 层本就无引用（仅 server/LauncherApiHandler 引用）；无入口可删，代码保留 |
| 企业微信 wecom | 仅禁用、代码保留 | 无独立导航入口；通知 sink 代码保留，不再有界面入口触发 |
| ABRP abrp | 仅禁用、代码保留 | nav_graph 删 abrpSettingsFragment；Integrations 的 ownedDestinationIds 缩减为仅 bydCloudFragment（cardAbrp 本就 GONE） |
| Telenav telenav | 自启已删、代码保留 | OverdriveApplication 删 TelenavIpcServer.start 调用；daemon 侧调试端点随之失效，telenav 包代码保留 |
| 统计 analytics | 仅禁用、代码保留 | 无独立导航入口；daemon 引用代码保留 |

### parking 的判定（按任务要求写明）

`parking/ParkingController.java` 自述为“Parking Intelligence：停车会话生命周期——
在哪停的、回来了、旁边是谁”，含 arrived stills、车库标牌 OCR（`parking/signage/`）、
ParkingNeighbourObserver。**这是“停车智能（记车位/OCR 车库）”，不是哨兵本体。**
哨兵本体是独立的 `daemon/SentryDaemon.java` 与 `daemon/AccSentryDaemon.java`。
另有佐证：`ParkingConfig` 默认 `enabled=false`，daemon 钩子均为 parking.enabled
门控的空检查。因此砍 UI 入口即足够，未改 daemon 代码。

### 附带修复

- `activity_main_new.xml` 里一行此前已无绑定的空行（railDestMap）会显示为空白
  56dp 可点行，本次在 GONE 块里顺带隐藏。
- `res/menu/rail_menu.xml` 经 grep 确认无任何代码引用，属历史遗留菜单（主界面
  实际用 activity_main_new.xml＋MainActivity 绑定）；仍同步删了其中 parking、
  trips、automations、roadSense 四项，留注释说明。

## 三、新增的两项（来自迪加 DiPlus 实测分析）

### 1. “停用原厂记录仪”开关（新增）

- 位置：设置 → 守护进程页，Wi-Fi 卡片下方一张新卡片，默认**关闭**。
- 标题：`停用原厂记录仪（释放摄像头）`；副标题说明停用比亚迪自带记录仪
  `com.byd.cdr`、关闭后恢复原厂记录仪、需要无线 ADB 与守护进程已启动。
- 原理：DiPlus 静态分析发现它带 `pm disable-user com.byd.cdr` 开关；比亚迪
  自带记录仪疑似占用摄像头 HAL 的单客户端通道，停掉它才能让第三方应用接管
  摄像头。这是 DiPlus 在汉上能用、OverDrive 不能用的头号嫌疑（未全量反编译，
  属推断，见第五节）。
- 执行通路：`cdr/CdrController.kt`（新增），经应用已有的无线 ADB 通路
  `AdbDaemonLauncher.executeShellCommand` → 本地 5555 无线 ADB（shell UID 2000，
  与电脑上 `adb shell pm disable-user` 同等权限）。开执行 `pm disable-user
  com.byd.cdr`，关执行 `pm enable com.byd.cdr`。
- 语义：`pm disable-user` 是系统级持久状态，重启后仍保持停用；开关本身状态
  只存应用私有偏好（`slim_cdr_prefs`）用于界面回显，系统真源以
  `pm list packages -d` 为准。**仅命令成功时才持久化开关状态**，失败时 UI
  自动回弹并 Toast 提示。
- 前置条件（缺一不可，否则命令失败、开关回弹）：无线 ADB 已开启并授权；
  OverDrive 守护进程已起来。停用后需重启摄像头守护进程或重进摄像头映射，
  让 HAL 重新释放/认领摄像头；本开关不自动重启守护进程。
- 字符串：三个新 key（`slim_cdr_disable_title`、`slim_cdr_disable_subtitle`、
  `slim_cdr_apply_failed`）只写在默认 `res/values/strings.xml`，**未动
  values-zh-rCN**（任务要求）。Android 资源解析对缺失 key 会回落默认 values，
  因此中文用户仍看到中文；合入 main 前需把同名 key 补进 values-zh-rCN，
  否则 i18n 质量闸会报警（该闸独立运行、永不阻塞 APK 构建）。

### 2. 汉 DiLink 4 摄像头默认值（新增）

- 位置：`camera/CameraProfiles.java`，新增 `PROFILE_HAN_DILINK4 = "han_dilink4"`。
- 映射（只放实测确认的两路）：
  - 左 = `CameraSourceRef.direct(2)`
  - 右 = `CameraSourceRef.direct(3)`
  - 来源：2026-10-28 用户在汉 DM-i 第一代尊荣版（DiLink 4.0）真车上实测确认
    （中文版诊断→摄像头映射页逐个试 ID 得出）。
  - panoCameraId = 1、尺寸 5120×960：与该车诊断页实测到的那路流
    （“摄像头 1 · 5120x960”）一致；其余尺寸参数沿用 legacy。
- **前/后故意不写进默认映射**，保持自动/手动：解析器把 profile 默认与持久化
  覆盖（`unified.camera.roleMappings`）合并，缺失角色即不预设，未映射就是没有，
  绝不拿前摄顶替。前/后请在诊断→摄像头映射里手动指定。
- `infer()` 增加分支：车型字符串含 "han" 时返回汉 profile。比亚迪车机常只暴露
  "BYD AUTO" 这类通用型号，推断不到时仍走 legacy 默认，用户需在摄像头映射里
  手动选 han_dilink4。
- **绝不编造**：全景流在用户汉上未找到、后摄 ID 至今未知，本文件与本文档均未
  写任何后摄编号；DiLink 4 时全景相机选择仍由 HAL 发现逻辑覆盖（见
  CameraConfigResolver），manual 标志被强制 false，这是原有行为，未动。

## 四、云端打包步骤（Actions 才是验证步）

1. 把本仓库 fork 到自己的 GitHub 账号（或直接在已有 fork 里切 `slim-cn` 分支
   并 push 本分支——**本次任务未 push，push 由用户自己决定**）。
2. 两种签名路径（工作流已内置 fallback，不会因缺 secret 而中断）：
   - 有 `RELEASE_KEYSTORE_BASE64` secret：走中文版原签名路径，产物可
     `pm install -r` 覆盖升级中文版（指纹校验步骤正常执行）。
   - 没有该 secret：自动改用标准 debug keystore（`androiddebugkey` / `android`）
     打包，指纹校验步骤自动跳过。**此时签名与中文版不同，装精简版前必须先卸载
     中文版**，否则安装会报签名冲突。
3. 触发：push 到 `slim-cn` 分支（工作流触发分支已加 `slim-cn`），或手动
   workflow_dispatch。
4. 取包：Actions → 该次运行 → Artifacts（保留 14 天），文件名为
   `Overdrive-v${VERSION}-cn-sec.apk`。Release 发布步骤仍仅限 main 分支，
   slim-cn 只走 Artifacts，不发 Release。
5. 包名未变：仍为 `com.overdrive.app`；Gradle 依赖坐标、IPC 端口
   （19876–19880）、targetSdk 25 均未动。

## 五、未核实清单（必须如实说明）

1. **本地未编译**：本次任务本地无 Android SDK，未跑过 `./gradlew
   assembleDebug`。Actions 云端构建才是第一验证步；若构建报错，按报错修，
   不要凭感觉改。
2. **汉上实测待上车**：汉 profile 的 direct(2)/direct(3) 在拼接管线里的实际
   表现未验证；CDR 开关在真车 shell UID 2000 下的实际权限未验证；停 CDR 后
   全景流是否出现、后摄 ID 是多少，仍未知。
3. **DiPlus 推断未证实**：迪加分析是静态字符串分析，未全量反编译；“停 CDR＋
   独立守护进程走 HAL”是推断，不是证实。精简版只实现了“停 CDR”这一招，
   未复现迪加的守护进程（闭源，无法等价复现）；原版 OverDrive 的守护进程
   结构未动。
4. **Telegram 手动开关残留**：DaemonStartupManager 的两处 Telegram 自启已删，
   但守护进程页的 Telegram 手动开关仍在（DaemonType.values() 驱动行、
   OPTIONAL_DAEMONS 仍含 TELEGRAM_DAEMON）：用户主动打开才会跑，默认关闭。
   彻底禁掉需再删 OPTIONAL_DAEMONS 与 DaemonsViewModel 控制器映射里的
   TELEGRAM_DAEMON 一项，因涉及健康检查共用逻辑，未在无编译验证的情况下动，
   留待有编译环境时第二遍处理。
5. **Web 资产残留**：`assets/web/local/` 里被砍功能的页面（automations.html、
   trips.html、parking.html、road-sense.html、telegram.html、mqtt.html、
   abrp.html、key-mapping.html、assistant.html 等）仍在 APK 内；车机界面入口
   已断，但局域网看板下经直接 URL 仍可打开。RoadSenseMapActivity 等 navmap
   活动同理（仅由 RoadSense 网页经 JS 桥拉起，车机入口已断）。如需彻底清除，
   须另起一遍删除 web 资产并验证。
6. **i18n 门禁**：新字符串 key 未补进 values-zh-rCN（任务要求不动），合入
   main 后 i18n 质量闸会报缺失，届时补 key 即可；该闸不阻塞 APK 构建。
7. **单元测试**：2 个测试以字符串字面量引用被砍目的地，跑测试时会失败，
   assembleDebug 不受影响：
   `genai/GenAiAssetContractTest.java:213`、`ui/dashboard/DashboardLayoutContractTest.kt:125`
   （均含 `findNavController().navigate(R.id.genAiFragment` 字面量）。
8. **未动清单**：未改 Gradle 依赖坐标、包名、values-zh-rCN、IPC 端口、
   targetSdk；未 push；未动 `~/workspace/overdrive-lite*`、
   `~/workspace/overdrive-lite-upstream/`、`~/workspace/android-sdk/`。

## 六、改动文件清单（slim-cn 分支，git diff --stat）

- `app/src/main/res/navigation/nav_graph.xml`：删 10 个目的地（genAi、parking、
  projection、trips、automations、keyMapping、telegramSettings、abrpSettings、
  mqtt、roadSense），XML 已校验合法。
- `app/src/main/java/com/overdrive/app/ui/MainActivity.kt`：删 6 个 RailItem、
  AppBarConfiguration 顶层集合删 7 项、handleNavigateExtra 删 4 个分支、
  Integrations ownedDestinationIds 缩减为仅 bydCloudFragment、新增 GONE 块
  （含附带修复的 railDestMap 空行）。
- `app/src/main/res/menu/rail_menu.xml`：删 4 项（历史遗留菜单，见第二节）。
- `app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt`：
  quickTrips 改 GONE 并删监听；AI 洞察区末尾可见性改 GONE。
- `app/src/main/res/layout/fragment_dashboard.xml`、
  `app/src/main/res/layout-land/fragment_dashboard.xml`：仅改注释，注明
  tripsFragment 已删（按钮本身已在 DashboardFragment 里 GONE）。
- `app/src/main/java/com/overdrive/app/onboarding/ExpertTourCoach.kt`：删
  TRIPS、ROADSENSE 章节枚举、dispatch 与 3 个方法。
- `app/src/main/java/com/overdrive/app/ui/fragment/IntegrationsFragment.kt`：
  cardTelegram、cardMqtt 改 GONE 并删监听。
- `app/src/main/java/com/overdrive/app/OverdriveApplication.kt`：删
  TelenavIpcServer.start 调用。
- `app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt`：
  RoadSense 覆盖层自启块删除。
- `app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt`：删两处
  armBlindSpotIfEnabled 调用及方法本身。
- `app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt`：
  两处 Telegram Bot 自启块改注释删除。
- `app/src/main/java/com/overdrive/app/services/KeyMapDispatcher.kt`：fire()
  改为空实现（原实现见 git 历史）。
- `app/src/main/java/com/overdrive/app/camera/CameraProfiles.java`：新增
  han_dilink4 profile 与 infer() 的 han 分支。
- `app/src/main/java/com/overdrive/app/cdr/CdrController.kt`：新增（CDR 开关
  执行层）。
- `app/src/main/java/com/overdrive/app/ui/fragment/DaemonsFragment.kt`：新增
  CDR 开关字段、绑定、刷新与持久化方法。
- `app/src/main/res/layout/fragment_daemons.xml`：Wi-Fi 卡片后新增 CDR 卡片。
- `app/src/main/res/values/strings.xml`：新增 3 个 CDR 字符串 key（仅默认
  values/，未动 values-zh-rCN）。
- `.github/workflows/build-apk.yml`：触发分支加 slim-cn；keystore 步骤加
  debug-keystore fallback；指纹校验步骤 fallback 时跳过。
- 本文件 `SLIM_NOTES.md`：新增。

终验 grep 结果：10 个被砍目的地 id 在 java/kotlin/xml 里除注释外零引用；
TelenavIpcServer.start、armBlindSpotIfEnabled、RoadSense 覆盖层自启、Telegram
自启在启动链路里零残留（roadsense 包内部自调用与守护进程页手动开关除外，
见第五节第 4、5 条）。
