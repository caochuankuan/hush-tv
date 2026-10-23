# 留白 · HUSH TV

> 少一点，刚刚好。

一个为电视遥控器设计的极简 Android TV 桌面。整个界面由一个自定义 `Canvas` 绘制，没有 AndroidX、Compose 或其它第三方运行时依赖，Release APK 约 **90 KB**。

![HUSH TV 桌面](screenshots/home.png)

## 效果

| 系统快捷入口 | 应用操作菜单 |
| --- | --- |
| ![桌面设置](screenshots/settings.png) | ![应用操作菜单](screenshots/menu.png) |

- 左侧是时间、日期和问候语；右侧是应用卡片。
- 应用不超过 4 个时自动变成 **2 × 2 大卡片**；5 个及以上变成 **三列网格**；超过 9 个可以纵向滚动。
- 焦点卡片使用暖白底色、轻微放大、柔光与琥珀色圆点；其余卡片保持低对比度。
- 时间按整分钟刷新，不会出现秒级跳动。

## 功能

- 读取 `LEANBACK_LAUNCHER` 与 `LAUNCHER` 应用，按组件去重；置顶应用优先，其余按中文名称排序。
- 应用图标在后台线程加载，不阻塞首帧。
- 完整方向键导航：`← ↑ ↓ →` 移动，`OK / Enter / A` 打开。
- 选中应用后按 `MENU` 打开操作菜单：**打开 / 置顶 / 信息 / 卸载**。
- 置顶应用优先展示，并在卡片左上角显示小圆点；置顶状态保存在本地，重启后仍然生效。
- 第一行按 `↑` 进入右上角「设置」，内含开发者选项、关于设备、应用管理、系统设置四个系统快捷入口。
- 长按 `MENU`（或按 `INFO`）打开默认桌面 / Home 设置。
- 应用启动失败时自动回退到普通 Launcher intent，并给出提示。
- 注册了 `HOME` 与 `LEANBACK_LAUNCHER`，可作为电视默认桌面；带 TV banner。
- 启动器内按 `BACK` 不会退出桌面。
- 支持鼠标/触摸点击，方便在模拟器里调试。
- `minSdk 23`，`targetSdk 36`，无危险权限。

## 操作

- `← ↑ ↓ →`：移动焦点
- `OK / Enter / A`：打开应用
- `MENU`：打开当前应用的操作菜单
- 菜单内 `↑ ↓` 选择操作，`OK` 执行，`BACK` 关闭
- 第一行按 `↑`：进入右上角「设置」，打开开发者选项 / 关于设备 / 应用管理 / 系统设置
- 长按 `MENU` / `INFO`：默认桌面与 Home 设置（Home 被占用时查看 ADB 命令）

## 直接安装

```bash
adb install -r dist/HUSH-TV-1.3.1.apk

# 如果之前装过 v1.2.0 的预编译 APK，1.3.x 使用了不同的本地调试签名，
# 需要先卸载旧包再安装：
# adb uninstall com.hush.tv

# 把留白设为 HOME
adb shell cmd role add-role-holder android.app.role.HOME com.hush.tv

# 部分电视/模拟器自带的桌面是常驻应用，会抢在 HOME 前面。
# 如果按 Home 仍然回到原桌面，可以停用它（包名视设备而定）：
adb shell pm disable-user --user 0 com.google.android.tvlauncher

adb shell input keyevent KEYCODE_HOME
```

恢复原桌面：

```bash
adb shell pm enable com.google.android.tvlauncher
```

也可以使用脚本：

```bash
./scripts/install.sh
DISABLE_STOCK_LAUNCHER=1 ./scripts/install.sh   # 需要停用 Google TV Launcher 时
```

## 桌面没图标 / Home 键没反应？

你遇到的通常是下面三类情况。1.1 增加了普通桌面图标和设置引导：

1. **桌面没图标**
   1.0 的 Activity 只注册了 `LEANBACK_LAUNCHER` 和 `HOME`，没有普通 `LAUNCHER`。所以在手机式桌面、部分电视盒的“全部应用”里看不到它。1.1 增加了 `CATEGORY_LAUNCHER`，重新安装后应用列表里会出现「留白」。

2. **按 Home 没有弹出“选择桌面”**
   Android TV 不会因为安装了一个 HOME 应用就自动弹选择框，需要手动设置。打开「留白」后会出现设置提示，点「去设置」，在系统界面里选择「留白」并确认。之后长按 `MENU`（或按 `INFO`）可以随时重新打开默认桌面提示。短按 `MENU` 是应用操作菜单；右上角「设置」是系统快捷入口。

3. **已经选了留白，Home 还是回到原桌面**
   很多电视/盒子的原桌面是厂商常驻应用，会继续抢占 Home。用 ADB 找到它并临时停用：

   ```bash
   # 查看当前真正接管 HOME 的包名
   adb shell cmd package resolve-activity \
     -a android.intent.action.MAIN \
     -c android.intent.category.HOME

   # 以 Google TV 的 com.google.android.tvlauncher 为例
   adb shell pm disable-user --user 0 com.google.android.tvlauncher

   adb shell input keyevent KEYCODE_HOME
   ```

   留白内的「还需要停用原桌面」提示会自动显示检测到的包名，直接照着执行即可。

   ![停用原桌面提示](screenshots/setup.png)

   需要恢复时：

   ```bash
   adb shell pm enable com.google.android.tvlauncher
   ```

如果执行后 Home 仍然没有反应，重启一次电视/盒子，或检查系统设置里的「默认应用 / 主屏幕」选项。部分机型不允许普通应用修改默认桌面，只能通过 ADB 停用原桌面。

## 演示应用（可选）

`screenshots/` 里的五个中文应用是预览用的独立演示 APK，不是桌面的一部分：

```bash
adb install -r dist/HUSH-TV-demo-1.0.apk
# 卸载
adb uninstall com.hush.demo
```

## 构建

要求：JDK 17+、Android SDK Platform 36、Build Tools 35.0.0。

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

仓库不包含 `local.properties` 和签名文件。Gradle 会使用 `ANDROID_SDK_ROOT`；也可以在本地创建 `local.properties` 指定 `sdk.dir`。Release 使用本机 Android 默认 debug 签名，仅用于侧载演示；上架应用商店前请换成正式签名。`dist/` 里的 APK 使用另一份本地调试签名，覆盖安装自行构建的版本前先卸载旧包。

## 用 Android CLI 创建 TV 虚拟机

本次验证使用 Android TV 14（API 34）arm64 镜像、1920 × 1080、320 dpi。

```bash
# 1. 安装 TV 系统镜像
sdkmanager "system-images;android-34;android-tv;arm64-v8a"

# 2. 创建一个 1080p TV AVD
echo no | avdmanager create avd \
  -n HushTV \
  -k "system-images;android-34;android-tv;arm64-v8a" \
  -d tv_1080p

# 3. 启动（Android CLI 可用时）
android emulator start HushTV

# 或者直接使用 emulator
emulator @HushTV

# 无窗口运行
android emulator start --headless HushTV
```

本仓库也提供了：

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./scripts/create-tv-avd.sh
./scripts/run-tv-emulator.sh
```

启动后确认：

```bash
adb devices
adb shell wm size          # Physical size: 1920x1080
adb shell wm density       # Physical density: 320
```

> 如果在受限沙箱里启动模拟器时遇到 `TemporaryItems` / `Operation not permitted`，把 `HOME` 指到一个可写目录，或给模拟器进程对应的临时目录写权限即可。本次是在工作区内用独立的 `HOME` 完成启动的。

## 系统快捷入口

右上角「设置」里的四个跳转，参考了 [ADBHelper](https://github.com/ChiKaWa3077/ADBHelper)（MIT）的四个入口：

- **开发者选项**：`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`
- **关于设备**：`Settings.ACTION_DEVICE_INFO_SETTINGS`
- **应用管理**：`Settings.ACTION_APPLICATION_SETTINGS`
- **系统设置**：`Settings.ACTION_SETTINGS`

和 ADBHelper 一样，这里只负责跳转，不直接修改系统设置；USB / 网络 ADB 开关仍需要在开发者选项里手动打开。

## 项目结构

```text
hush-tv/
├── app/                         # 桌面本体
│   └── src/main/java/com/hush/tv/
│       ├── MainActivity.java     # 全屏 Activity / HOME 入口
│       ├── LauncherView.java     # 单 Canvas 桌面：绘制、焦点、动画、按键
│       ├── AppRepository.java    # 后台查询应用与图标
│       └── AppEntry.java
├── demo/                        # 五个独立演示应用（影院/音乐/直播/相册/游戏）
├── screenshots/                 # 实机模拟器截图
├── dist/                        # 可安装 APK
├── scripts/                     # 安装 / 创建 AVD / 启动模拟器
└── README.md
```

## 已验证

- Android TV 14 arm64 模拟器，1920 × 1080 / 320 dpi。
- 9 个应用以三列网格展示，方向键移动焦点正常。
- `OK` 成功启动演示应用，`BACK` 返回桌面。
- `MENU` 菜单中的打开、置顶、信息、卸载入口均验证通过。
- 置顶后应用移动到首位，`force-stop` / 重启后置顶状态仍保留。
- 第一行按 `↑` 进入右上角「设置」，四个系统快捷入口都能正常拉起。
- 开发者选项、关于设备、系统设置直接进入对应页面；应用管理在部分电视上会先弹出系统组件选择器。
- 四个入口都做了异常捕获：页面不存在、被隐藏或抛出 `SecurityException` 时只提示 Toast，不会闪退。
- 按 `HOME` 进入留白；重启模拟器后仍自动进入留白。
- 卸载演示应用后，4 个系统应用自动切换为 2 × 2 大卡片布局。

## 目前的取舍

- 置顶按“最近置顶优先”排列；没有收藏分组、拖拽排序或自定义顺序。
- 不显示天气、推荐内容或网络信息，完全离线。
- 如果应用很多，需要纵向翻页；没有做首字母索引或全部应用抽屉。

这些都可以在现有 `LauncherView` / `AppRepository` 上继续扩展。
