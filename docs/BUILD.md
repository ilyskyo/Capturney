# 构建与环境

## 需要的东西

| 项 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | AGP 8.x 的要求。Android Studio 自带的 JBR 就可以直接用 |
| Android SDK | `compileSdk 36`（含 platform 与 build-tools） | `minSdk 26 / targetSdk 36` |
| Gradle | 用仓库里的 wrapper，别自己装 | `./gradlew` 会自动拉对应版本 |

模型权重（`efficientdet_lite0.tflite` 13.5 MB + `magic_touch.tflite` 6.1 MB）**已随仓库提交**，
克隆下来就能离线构建，不需要任何下载步骤。

## 指向一个 JDK

```bash
# Linux / macOS
export JAVA_HOME="/path/to/jdk-17"
# Windows（PowerShell）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

`gradle.properties` 里刻意**不写** `org.gradle.java.home`：那是机器特定的路径，写进去之后
别人的机器就构建不了。

## 常用命令

```bash
./gradlew assembleDebug          # debug APK，applicationId 带 .debug 后缀
./gradlew installDebug           # 装到已连接的设备
./gradlew testDebugUnitTest      # 369 个 JVM 单测，约半分钟（数量会漂，要准的看 app/build/test-results/）
./gradlew connectedDebugAndroidTest   # 33 个设备测试（2026-10-08 在 wl-ci 上全量跑过），
                                     # 要有一台已连着的设备/AVD，约 2 分钟
./gradlew assembleRelease        # R8 + shrinkResources + lintVitalRelease
```

### 模拟器能看什么、不能看什么

本机 AVD 叫 `wl`（`system-images;android-34;google_apis;x86_64`，WHPX 加速）。
debug 变体额外打一份 `x86_64` 原生库（`app/build.gradle.kts` 的 `debug.ndk`），release 仍然
arm64-only——不这么做就得让 MediaPipe 跑在 ARM→x86 转译上，实测一次安装能卡十分钟。

### 跑 `connectedDebugAndroidTest` 之前要知道的四件事

1. **CI 上没有模拟器 job**，这一套是**本地守卫**。改完界面别只看单测绿了就认为设备上没问题。
2. **跑完 AGP 会卸载被测 App**。所以「跑完之后用 `adb shell run-as` 去查文件」是查不到的——
   那条命令只会回 `unknown package`，而如果两边都取到这句报错，`diff` 会输出「一致」，
   比没验还糟。要在同一次运行内证明（见 `PreserveAppFilesTest`）。
3. **它会在真数据上落夹具**：时间轴与复习页那几条走 `diary.addEntry` / `deck.add` 写入，
   跑完各自走 `deleteEntry` / `delete` 抹掉，外层再套 `PreserveAppFiles` 还原 `filesDir`。
   磁盘还原救不了**进程内的文档态**——同一个进程会连着跑完多个方法，所以夹具必须自己收。
4. **偶发的 `No compose hierarchies found in the app` 多半不是本仓库的问题**：这台机器上还有
   另一个项目（`com.ilyskyo.blancall`）会周期性跑到前台，把正在被量的 Activity 挤下去，
   谁在那一刻量树谁就红。分辨方法很简单——**单独重跑同一套就绿**。
   要根治：跑之前 `adb shell am force-stop com.ilyskyo.blancall`，或给设备测试单开一台干净 AVD。
   本仓库一侧不加等待逻辑，那只会掩盖别人的窗口。

它**值得**用来做的：静态布局与配色——页签胶囊盖住内容、拍照键没有底盘、图标被 tint 抹成一团，
这三处都是在这里第一次被看见的，而类型检查、单测与 lint 对它们全都没有意见。
`adb -s emulator-5554 shell input tap x y` 在这里也能用（真机上要 MIUI 的
「USB 调试（安全设置）」才允许注入）。

它**不能**代替真机的：相机预览（软件渲染下是黑的，于是整条检测/抠图链路无从判断）、
触摸振动的有无与强弱、厂商 ROM 的首帧与后台策略、TTS 引擎差异、真实镜头的 EXIF 与
`rotationDegrees`。另外它的 CPU 压力常年偏高，`/proc/pressure/cpu some` 到 35 时会把
设置页这类长组合甩成一次 ANR——**看到 ANR 先怀疑模拟器，别急着当成 App 的缺陷**，
本次抓到的两处主线程栈都停在正常的组合代码上，没有 IO、没有循环。

release 产物默认**不签名**：签名四项（`release.storeFile` / `storePassword` / `keyAlias` /
`keyPassword`）写在 git-ignored 的 `local.properties` 里，缺任一项就跳过签名而不是退回 debug key。
静默用 debug key 签一个「release」，是把所有人都没法升级的包发出去的经典做法。

### release 签名的可选配置

`app/build.gradle.kts` 里这段是**可选**的：四项齐全才 `create("release")` 签名配置，缺任何一项
就整段跳过，产物变成 `app-release-unsigned.apk`（文件名会明说不签名，不会让人误以为是渠道包）。

```properties
# local.properties（已被 .gitignore 挡住，不要提交）
release.storeFile=C:/Users/<你>/Documents/keystore/capturney.p12
release.storePassword=...
release.keyAlias=capturney
release.keyPassword=...
```

**密钥必须放在工作树之外。** 这一节以前写的是 `../keystore/capturney.jks`，那个路径在
`app/build.gradle.kts` 里由 `file()` 解析，基准是 **`app/` 目录而不是仓库根**，所以它实际落在
`WordLens/keystore/` —— **仓库里面**。而 `.gitignore` 当时只有 `*.keystore`，**既不挡 `*.jks`
也不挡 `*.p12`**：任何一次 `git add -A` 都会把私钥连同仓库一起推上 GitHub，而公开仓库里的
私钥等于这个应用的签名永久失效——拿到它的人能签出「出自同一个 key」的更新包，
而这件事删掉提交也撤不回（历史里的对象还能被取出来）。现在 `.gitignore` 补了
`*.jks` / `*.p12` / `/keystore/` / `/password.txt`，但那是第二道闸，**把密钥放在仓库外是第一道**。

口令同理不要和密钥存在同一个目录里当长期备份——本机的 `keystore/password.txt` 是**过渡件**：
把它移进密码管理器之后删掉。密钥文件本身要单独备份到至少一个离线位置（丢了和泄露一样糟，
见下面那条长期承诺）。

要点：

- **不影响 R8**。签不签名，`minifyReleaseWithR8` 都会跑，`mapping.txt` 照样产出——所以下面的冒烟
  在没有 keystore 的机器上（包括 CI）能原样跑。CI 产出的仍是 `app-release-unsigned.apk`，
  这是有意的：签名口令不进 CI，而不是进不去。
- 未签名的包要装到真机，得自己签：`apksigner sign --ks … app-release-unsigned.apk`，
  或者本地补上那四项直接产出签名包。
- keystore 一旦对外发版就是**长期承诺**：换 key 等于换一个应用，老用户收不到更新。
  所以备份不只是为了「不丢」，而是为了**换了之后知道发生了什么**。

## 正式签名 key（2026-10-08 生成）

| | |
|---|---|
| 文件 | `Documents/keystore/capturney.p12`（**仓库外**，PKCS12） |
| alias | `capturney` |
| 算法 | RSA 2048，自签证书 `SHA384withRSA`；生效 2026-10-08，**失效 2056-09-30**（10950 天） |
| 证书 SHA-256 | `BE:13:63:9A:8F:8C:96:92:7C:3B:0C:A4:98:FE:6C:C2:4B:95:AB:E3:8F:45:6C:39:FB:20:17:26:67:F4:A2:9B` |
| 证书 SHA-1 | `0F:80:CB:45:38:17:7F:06:A7:71:7A:0E:73:6B:83:16:44:D1:0E:30` |

两个指纹都是 `keytool -list` 与 `apksigner verify --print-certs` **各读一次对上的**——它们本来就该
是同一个东西，对不上说明包不是这个 key 签的。签名方案实测是 **v2 only**（`v1: false`），
这是对的而不是漏配：`minSdk 26`，而 v1（JAR 签名）只服务 Android 7.0 以下的安装。

生成它用的是 `Android Studio/jbr/bin/keytool.exe`。**它不在 PATH 里**（`where keytool` 与
`command -v keytool` 都拿不到），所以敲裸命令会失败——这台机器上「没有 keytool」这个判断
就是这么来的，其实 JBR 里带着它（`javap.exe` 同理，也在，别按旧印象写脚本）。
口令走 `-storepass:file`，不从命令行参数过，避免落进 shell 历史与进程列表。

那串 SHA-256 抄在这里是因为它**不是秘密**（它就打在发布出去的 APK 的证书里），而它是唯一能回答
「这个包和三年前那个包是同一个人签的吗」的东西。装包之前先比它：

```bash
# build-tools 目录里有 35.0.0 与 36.0.0 两版，用哪版都行
"$SDK/build-tools/36.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 只编 arm64

`ndk.abiFilters` 只有 `arm64-v8a`。MediaPipe 与 ML Kit 每个 ABI 带 10–15 MB 原生库，而
`minSdk 26` 之后还在跑的机器基本都是 64 位。

后果是 **x86_64 模拟器装不上**。要在模拟器上跑，本地临时加一行 `x86_64` 就好，别提交：

```kotlin
ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
```

## 发布产物里到底声明了哪些权限

源码 `AndroidManifest.xml` 只写 5 条，而**合并**之后的 release 包里有 8 条 + 1 条自定义权限。
这多出来的一直没人记过，而它才是用户装机时看到的、也是商店审核看到的那一份。
下面这份是 2026-10-09 用 `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk`
从**签名产物本身**读出来的，不是从源码推的：

| 权限 | 来自 | 为什么留着 |
|---|---|---|
| `CAMERA` | 本仓库 | 取景。运行时申请，`required=false` 见上一节 |
| `RECORD_AUDIO` | 本仓库 | 「录一段」。按下那一刻才申请（不在启动时要） |
| `POST_NOTIFICATIONS` | 本仓库 | 每日到期提醒 |
| `VIBRATE` | 本仓库 | 评级/归档/结算三处触觉 |
| `INTERNET` | 本仓库 | 只服务「自带 key 的云端视觉」那一条可选路；默认引擎不出门 |
| `WAKE_LOCK` | `androidx.work` | WorkManager 的排期本身要用它；去掉会让提醒在部分机器上漂 |
| `RECEIVE_BOOT_COMPLETED` | `androidx.work` | 重启之后把排期捡回来 |
| `ACCESS_NETWORK_STATE` | Play services / ML Kit | 联网前读一次连接状态 |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Android 13+ 上 `registerReceiver` 要求的那条签名级权限，与本应用无关但必须随包 |

**已经拿掉的一条**：`FOREGROUND_SERVICE`。它是 WorkManager 清单连带进来的，
而本应用**没有任何 `<service>` 组件**、`DueReminderWorker` 是普通 `CoroutineWorker`、
全仓库 `setForeground` / `ForegroundInfo` 出现 **0 次**——所以那条声明是可证明用不上的。
它唯一的效果是让「本应用有前台服务」这句话出现在用户与审核面前，
而 targetSdk 34 之后声明它还要配 `foregroundServiceType` 与用途说明：
白拿的这条会直接换来发布期的一轮追问。写法是清单里的 `tools:node="remove"`。

要重读这份集合（改依赖之后必须重读，因为它是合并结果而不是源码）：

```bash
"$SDK/build-tools/36.0.0/aapt2.exe" dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/release/app-release.apk | grep -A2 uses-permission
```

## 发布前收口：R8、mapping.txt 与数据可发现性冒烟

### 为什么这件事需要一个脚本守着

release 走 R8（`isMinifyEnabled = true` + `isShrinkResources = true`）。本项目的落盘格式是明文 JSON，
读写靠 kotlinx.serialization 的 Java 反射，而**名字稳定**是它的正确性条件之一：枚举常量名
（含 `states` map 的 key）、生成的 `$$serializer`、`Companion` 上的 `serializer()`。
R8 动它们不会崩、不会报错——`CapturneyJson` 配了 `coerceInputValues = true`，读不出来的枚举名会被
**夹成默认值**。JVM 单测跑的是没混淆的 class，UI 测试跑的是 debug 包，两者都抓不到这一类回归。

补规则之前，`assembleRelease` 的 mapping.txt 里实际是这样（原样摘录）：

```
com.ilyskyo.capturney.data.model.StudyDirection -> af3:
    com.ilyskyo.capturney.data.model.StudyDirection RECOGNIZE -> T
com.ilyskyo.capturney.data.model.RatingPalette -> mq2:
    com.ilyskyo.capturney.data.model.RatingPalette WARM -> T
```

`RECOGNIZE -> T` 说的是**字段名**被改走，而且只经 `$VALUES` 用到的常量字段会被整个删掉
（改动前的 mapping.txt 里 `CardOrigin` 只剩一条 `STICKER -> U` 字段）。但实测结论要写清楚，
别照网上的说法抄：用 `dexdump` 反汇编 release 包的 `classes.dex`，那个被改名成 `r30` 的枚举里
`<clinit>` 传给 `Enum.<init>` 的 name 字符串**并没有被重写**——

```
r30.<clinit>:()V
  0002: const-string v1, "STICKER"
  000c: const-string v2, "SCENE"
  0014: const-string v3, "TEXT"
  001a: filled-new-array {v0, v1, v2}, [Lr30;   // $VALUES 三个对象都还在
```

也就是说 `.name` 在运行时仍然是 `"STICKER"`，**那一版产物读得回来，没有正在发生的数据损坏**。

那还补规则做什么：因为「读得回来」这件事现在靠的是 R8 当前版本（9.3.16）的实现细节，而不是我们
写下的契约。名字由规则钉死之后，升级 R8、打开更多 `-optimizations`、或者哪天代码里不再直接引用
某个常量，落盘的名字都不会跟着漂。反射建枚举序列化器要用的 `values()` / `valueOf(String)` 也一起
钉住——那两个被裁掉才是当场就坏的事，而 kotlinx 查不到序列化器时不抛异常，只当默认值处理。

规则的体积代价实测（同一棵树、只换 proguard 文件，`assembleRelease` 各跑一次）：
`classes.dex` 未压缩 **+1,592 B**、压缩后 **+1,850 B**，APK 总字节数没变（49,467,334）。
所以「keep 加多了会让包白胖」这个担心，在这个量级上不成立；真正该省的是那些本项目用不到的
模板条目（`<fields>` 加在 data class 上、`kotlinx.serialization.json.**` 的 Companion 之类）。

### 怎么跑

```bash
./gradlew assembleRelease
python3 tools/release_smoke.py
```

只用标准库，不需要 `dexdump` / `apkanalyzer`。退出码：

| 码 | 含义 |
|---|---|
| 0 | 落盘要用的名字都在 |
| 1 | 有名字被改名或裁掉——修 `app/proguard-rules.pro`，**不要修断言** |
| 2 | 没有 mapping.txt（或解析结果为空）。**不会静默放行**：这一步没跑就等于没验 |

脚本核对的是四件事：七个落盘枚举的**每一个常量名** + `values()`/`valueOf()`、清单里 15 个
`$$serializer` 类（产物里实测 16 个，多出来的那个是 `srs.Fsrs$State`）、`Companion` 字段、
以及 APK 里 `classes.dex` 中的 JSON 元素名与长枚举名。
最后那一项是佐证：短名字（`log`、`mood`、`AI` 这类）在 dex 字符串池里可能偶然命中，
脚本只把它标成「弱证据」，不作为通过的依据；反过来**缺失一定是真缺失**。

CI 里挂在 `assembleRelease` 之后、体积报告与 `upload-artifact` 之前——格式不稳定时不该把包传上去。

### mapping.txt 怎么读

产物目录 `app/build/outputs/mapping/release/`：

| 文件 | 是什么 | 什么时候看 |
|---|---|---|
| `mapping.txt` | `原名 -> 改名` 的全表 | 想知道某个类/成员保没保住 |
| `seeds.txt` | 被 `-keep` 规则**真正钉住**的条目 | 规则写了却没生效时，先看这里有没有它 |
| `usage.txt` | 被判定无用而删掉的类与成员 | 「这个类怎么不见了」 |
| `configuration.txt` | 合并后的最终 R8 配置（含 AGP 自带的模板） | 怀疑某条规则被上游覆盖或写错 |

读法三条：

1. 不缩进的行是类，`Foo -> Foo:` 这种**箭头两边相同**才叫「名字保住了」；`Foo -> a:` 是被改名；
   `Foo -> R8$$REMOVED$$CLASS$$123:` 是整个类被删。
2. 缩进行是成员/方法，前面可能带行号区间与 `# {"id":"com.android.tools.r8.residualsignature"...}`
   注释——注释里的签名改动不影响名字稳定性，别被它吓到。
3. 想知道某条规则命中了什么，就查 `seeds.txt`。这一步很关键：本项目就遇到过
   `-keepclassmembers` 写法在枚举上不生效（类和 `values()` 都被钉住了，常量字段却没有），
   光看 mapping.txt 会误判成「规则没写」，看 seeds.txt 才定位到是**写法**问题
   （结论写在 `app/proguard-rules.pro` 的注释里）。

## 真机验证清单

类型检查与单测能证明几何和调度是对的，证明不了「画面里的词片确实压在杯子上」。
发布前逐个打勾，每条都写清了**看什么**，不写「正常即可」：

1. **数据往返（R8 的最后一公里）**：装 release 包 → 拍一张、存词、退出进程 → 重进 →
   `adb shell run-as com.ilyskyo.capturney cat files/deck.json`。
   要看的是 `"origin": "STICKER"`、`"source": "ON_DEVICE"`、`"states": { "RECOGNIZE": {...} }`
   这三处名字**是不是原样**——出现 `"U"`、`"T"` 这种单字母就是 `tools/release_smoke.py` 该拦住的事故。
2. **覆盖安装不丢数据**：先装 debug 包造数据，再覆盖装 release 包（applicationId 不同，
   要真验就都用 release 的前后两个构建）。复习状态、心情标签、来源标记都要还在。
3. **抠图边缘**：点词片 → 推近 → 抠图，贴纸边缘干净、没有沿台阶起伏的白描边
   （`magic_touch` 的 seed point 走传感器归一化坐标，`AlphaMatte` 的双线性放大是否真的跑到贴纸上）。
4. **旋转**：横竖屏与不同 `rotationDegrees` 下，回看页把词长回原图的位置是否还准；
   详情页照片不再躺倒（`PhotoDecoder` 读 EXIF，`BitmapFactory` 自己是不读的）。
5. **时间轴滚动**：几十张照片连续滚动不 OOM（`DecodeSizing` 的两步降采样 + `ByteLruCache` 的字节上限）。
   记录**多于 40 条**时要单独验一遍：列表末尾必须出现「更早的还有 N 条 · 载入」，按下去多出下一批
   而**不重复、不跳过**（上限是往上加而不是按偏移翻页，为的就是删一条/导一批之后仍然对得上）；
   滚到第 80 条之后再回头筛某一天、然后取消筛选，列表应该还是完整的而不缺一段。
   N 这个数字要跟着减少——删掉一条之后按一次载入，句里的条数也该跟着变。
   这一条只能在真机上验，JVM 里没有位图。
6. **国产 ROM 首帧黑屏**：`PreviewView` 已按 `ImplementationMode.COMPATIBLE` 处理，要找具体机器确认。
7. **触觉与动效**：评级、归档、完成结算三处的触觉分级是否出得来（没有 `Vibrator` 的机器要静默降级，
   不许崩）；按压只有缩放 + 透明度，屏幕上**找不到任何波纹**；
   系统「设置 → 声音 → 触摸时振动」关掉之后，本 App 的按钮**不该再震**——这一族效果全部直接调
   `Vibrator`，绕过了 `View.performHapticFeedback` 那个会自动核对开关的路径，所以只能自己问；
   中途改设置要立刻生效，不需要重进 App。
8. **设置页生效**：拖保持率 → 复习页四个按钮预告的间隔要跟着变；换评级色系 → 四档按钮的色相要变。
   「我的词条」那两条也要走通：补一个 App 认不出的词（词 + 意思）→ 回搜索页或取景页手输同一个词，
   **不该再撞**「词典里还没有它，暂时不能进复习队列」；删掉它 → 那句重新出现。
   另外把 `filesDir/lexicon/user-en.json` 手动改坏（多一个逗号）→ 设置里要出现那句红字说明是哪一份，
   而不是安静地退回内置词典（那一份文件是用户自己能打开改的文本，报不出原因就等于没报错）。
9. **深色模式的两处 XML 资源**：小组件与冷启动。night 资源已经补上
   （`values-night/colors.xml` 的 `wl_background` / `widget_*`，`values-night/themes.xml` 覆盖
   `Theme.Capturney` 的父主题与 `windowLightStatusBar`），要看的是两件事：
   把系统切到深色后，小组件的底/标签/读数是否一起变暗（对比度实测 9.55:1 与 7.11:1），
   以及**杀掉进程再打开**时还有没有那一下奶油白的闪——那是 starting window 画的，
   Compose 的第一帧管不到它。
10. **备份白名单**：`adb backup` 或 Google One 备份完成后，检查备份内容只有 `deck.json` / `diary.json`，
    没有 `filesDir/photos`、`filesDir/entries`、也没有 DataStore（里面有用户自己填的 key）。
11. **日历筛选**：换到周一/周日两种起点（系统地区设置改一下就行），月历的第一列要跟着换，
    月初不能整行错位；筛到某一天之后把那天删空，空状态里必须还能退出筛选。
    在当月那个月按「下一月」应该是**灰的**：未来不可能有记录，能翻进去只会得到一整张
    一个格子都点不开的月历，而它和「这个月你还没记」在画面上完全同一个样子。
12. **「移除动画」开关**：系统设置里打开之后，呼吸提示要停在不透明 0.80、结算页四行同时到位、
    提示条只剩淡入淡出；**不该停的是**翻卡、跟手滑动的位移、进度条增长与滚动数字、
    快门不可用时的缩放——那几样是信息不是装饰，跟着停就等于功能少了。
    开关拨回来要立刻生效（`ContentObserver` 接的），不需要杀进程。
13. **相册导入走完整链路**：挑一张几个月前的原图（12MP 或更大），看四件事——
    记录落在**它拍下的那天**而不是今天（EXIF `DateTimeOriginal`；相机时钟没设对的那张会落到
    修改时间那一档，那也是对的）；时间轴上的卡片**有贴纸那一个角**，和拍照进来的形态一致；
    连续导三四张大图不 OOM（只解一次 2560，氛围词另取 160 边的小图）；
    云相册里那张还没同步下来的项（Provider 给 0 字节）要说一句「读不出这张照片」，
    而不是静默什么都不发生、也不是留下一条只有坏照片的记录。
    竖持拍的横向 EXIF 那张要重点看：词长回画面的位置准不准、贴纸裁的是不是同一个物体。
14. **语音附件的三条离开路径**：录一段 30 秒 → 播放条放得出声、拖动能跳到想听的位置（松手那一下
    **不许弹回原位**——`MediaPlayer` 的 seek 是异步的，回读会拿到旧值）。然后逐条试打断：
    录到一半**接一个电话**，要说的是「麦克风正被通话占着，等它空下来再录」，而不是笼统一句失败；
    录到一半按 Home 退到后台，回来时那一段要停在「收下 / 丢弃」上等他决定，不是凭空没了、
    也不是替他收下了；**正在录的时候转一次屏幕**，录音不该被停掉（VM 活着，界面死了不算离开）。
    录满 90 秒要自己收手并说一句。**按下之后立刻按返回**这一条要单独试：开录是异步的，
    如果在那几十毫秒里人已经离开这一条，麦克风要当场交还、`files/audio` 里不该留下那半截文件——
    留下的一段偷偷在写的人声，比少录一句严重得多。
15. **语音附件留下的文件（隐私，比体积要紧）**：删一条日记 → `adb shell run-as … ls files/audio`
    里那一份一并没了，**包括还没收下就离开的那段**；详情页「删除录音」之后磁盘上确无残留
    （只清 `audioPath` 字段是一种假装）；录一段然后丢弃 → 当场删掉，不该等到下次启动；
    冷启动之后 `files/audio` 里不该有任何不属于现存条目的 .m4a。
    顺带核对备份边界：`adb backup` 或系统云备份之后**不该**在人声目录里看到东西
    （`data_extraction_rules.xml` 是 `<include>` 白名单，只列了 deck/diary 两个 JSON，
    所以 `files/audio` 与 `files/entries` 按构造就不上云——而 diary.json 里只有文件名）。
16. **麦克风权限的三种答案**：第一次按下「录一段」要弹系统框；**答「允许」之后那一按要直接开录**
    （不该出现「权限刚给完、键还是没反应、得再按一次」）；拒绝一次之后再按要**还能再弹**；
    勾了「不再询问」之后再按，按钮要变成「去设置」并真的落到应用详情页，
    而不是每次都发一个系统不会再答的 request。永久拒绝的判别只能在用户答过之后做
    （第一次进这一页时 `shouldShowRequestPermissionRationale` 也是 false）。
    没有麦克风的机器（平板）要能装上、其余功能照常：`microphone` 声明的是 `required=false`。
    2026-10-09 起 `VoiceRecorder.start()` **第一件事**就是问 `FEATURE_MICROPHONE`，
    缺它的设备当场拿到 `TakeNotice.NO_MIC`（四语各有一句），而不是那句「出问题了，再试一次」——
    那一句在一台永远录不了的机器上是在劝人重试一件不会成的事。
    **这一条也只能真机看**：要看按下「录一段」之后出现的是那句「这台设备没有麦克风」，
    而**不是**权限对话框、也不是通用的录制失败——`VoiceMemoRow` 现在在 launch 权限之前
    就先问硬件，所以那一次毫无出路的授权根本不该出现。
    **同族的另一件硬件——无相机的设备**（2026-10-08 已接进代码，但没在真机上看过）：清单声明
    `camera.any required=false`，而取景页以前在绑定失败时只写一行 `Log.w`，留下一颗
    按下去什么都不会发生的快门。现在 `CaptureCamera` 先问 `hasSystemFeature`、绑定失败也上报，
    快门变灰并在它上方出现 `capture_no_viewfinder` 那句（四语都有），指向仍然走得通的
    相册导入与手写词。**这一条只能在无相机的真机（或关掉相机的 Android 12+ 设备）上确认画面**：
    要看的是那句是不是完整不截断、快门是不是明显灰掉、而相册那颗键**照旧能按**；
    还有两件**不该**发生的：不该先弹一次相机权限框（没镜头时授权永远不会带来取景器，
    `CaptureScreen` 现在在权限那一层之前就短路），以及不该出现「点了没反应」——
    授权键与快门都属于这一族。
17. **重录必须问一次**：已经有声音的那条按「重录」，要先出现「会换掉原来那段」的确认，
    确认之后才动旧的——文件名按条目 id 定死，新的那一段是**截断重写**，旧的没掉之前必须有过同意。
    顺序也要紧：确认之后权限没到手时不能先摘 `audioPath`，否则「点了同意 → 被拒 → 旧的没了 →
    新的没录上」这一串就成立了。
18. **搜索 / 添加页**（手写词那条路的兑现处，之前清单里一直没有它）：
    **连点两下「收下」**只进一张卡（判重查的是牌组，而写入要等一次挂起之后才回来——只看牌组挡不住）；
    **收下之后再删掉那张卡**，回搜索页同一个词必须又能收（按钮不该灰着写「已收」——那是个说谎的读数）；
    全新装机第一次点进来不能是一片空白，要有话说清楚这页同时找牌组、词典和日记；
    从别的 App 分享一段多行文字进来，取的是第一行且掐到 80 字，纯空白的那次**不打开**页面；
    牌组多于 30 张时空输入只显示「最近」，得知道那不是全部。
    **取景页的「这个词我还认不出来」面板也算这一条**：写下词典里没有的词 + 一句意思 →
    要出现「『X』收下了，也已经记进你的词典」，随即能在搜索页与复习队列里看到它；
    再拍一次同一个物体，那个词该认识（`nomatch_body` 那句承诺的全部兑现处）。
    取景页那颗「收下」**也要连点两下**：只进一张卡（`toCard` 每次发新 id，牌组按 id 判重挡不住
    自己造出来的第二个 id；而这一支有失败路径，不能靠清空输入框来防连点）。
    只写词不写意思时不该静默造一张空释义卡，而该说出去并指到设置里那一节。
19. **复习结算的三个读数**：第一张卡故意想满 20 秒再翻开、评完，结算页的「用时」要**含得到**那 20 秒
    （原来它从第一次评级起算，等于每轮都把最长的一张剪掉）；换素材（词 ↔ 事件）之后计数与用时必须归零。
    **中途把系统时间往前调一小时**（设置里关掉自动校时），用时不该跳、不该变负——那一轮的答题时长
    还会写进复习日志给 FSRS 当原料，坏掉的数当场不报错，只在几个月后的间隔里露出来。
    **故意把一张卡连错两次**（第一次评「忘了」，等一分钟它重新排进来，第二次评「好」）：
    「已完成」只能加一次（那张卡是一张，不是两张），进度条**不该往回退一格**，
    而「一次答对率」要把这张算成 0/1（它第一次没想起来）而不是 1/2。
    这一步在 2026-10-08 之前是**做不到的**：`intervalForStability` 的下限是 1 天，按「忘了」
    等于「明天再来」，那张卡根本不会在同一轮里回来——而「忘了」那颗按钮下面印的「现在」
    （`IntervalFormat` 的 `days <= 0` 一档，四语都有）一直是走不到的分支。现在它按一分钟的重学
    步长排到期，队列按到期时间升序，所以那张卡会**落到队尾**：先把别的过完再见到它，
    正好像原版的重学队列。顺带核对两处：队里还压着这张一分钟后的卡时**不该**先弹一屏「本轮完成」；
    而四颗按钮上的天数要等于真正写进 `due` 的那个数（抖动现在按状态定种子，两次调用抽同一个数）。
20. **时间轴多选**：长按卡片进入多选之后，单击是勾选而不是打开——两套语义不能同时生效；
    筛到某一天再按「全选」，选中的应该只有**那一天加载出来的那些**（顶部读数要等于屏幕上的张数，
    而不是整本日记）。**走得通的那一条**：进入多选 → 长按顶部页签随机漫步进另一条 →
    在详情页把它删掉 → 返回时间轴，「已选 N 项」里不该还指着那张已经不存在的卡片
    （原来选中集合从不跟随删除清理，留下的是一个指着空气的读数加一颗按下去什么文件都不少的删除键）。
    删除之后的确认框要点「删除」而不是取消，并核对 `filesDir/entries` 与 `files/audio` 里那几份一并没了。
    **开读屏走一遍多选**：选中那张卡片要被念成「已选中」（此前只有一层紫色遮罩和一个灰色的勾选框，
    而遮罩读屏看不见、勾选框是 disabled 的——滚过一张张卡片时用户唯一能抓到的只有顶部那句「已选 N 项」，
    而那句话说的是数量，不是**哪几张**）。
21. **详情页的长按面板与编辑**：长按大图 → 复制 / 编辑 / 删除三项各自走通；「复制这段文字」贴到
    备忘录里应该只有标题、那句话说过的话和画面上的词，**不能有 id 或文件路径**。
    编辑里改标题、改一句话、选心情 → 保存之后页面当场是新的那句；原来摘要顶着「模型整理过，请核对」
    的那一条，用户重写过之后那句话必须**消失**（§8.1 要求的是「机器生成的东西能被认出来」，
    反向误标同样是破坏）。选中的那颗心情再点一次要能取消。
    **取消之后再打开，框里必须是条目本来的样子**：打字 → 取消 → 再长按 → 编辑（原来会把那份
    已经作废的草稿原样填回来，用户看不见差别，按下保存就等于落库一句他明确取消过的话）。
    **打字打到一半转一次屏**：对话框要还开着、字要还在（原来转屏直接把对话框关掉，
    字留在一个已经关上的门上，而注释承诺的是不丢）。
    删除必须过确认框，确认之后自动离开详情页——留在一条已经不存在的记录上是看着一片空。
22. **连点两下不是两条记录**（只能在真机上验）：结果页按「保存」之后**立刻再按一下**，
    时间轴只该多一条记录，`adb shell run-as … cat files/diary.json` 里那个条目 id 只出现一次；
    详情页「记下」按两下，复习队列里只有一句。提交是挂起的（JPEG 落盘 + 两份 JSON 各一次原子写），
    窗口只有几毫秒到几百毫秒，**取决于那张照片落地要多久**——JVM 里磁盘从来不慢，所以这条一直在
    测试覆盖之外。碰到导一张 12MB 的原图时最容易试出来。
    重复的条目 id 过去不是「多一条一样的」而是**一次闪退**：时间轴拿条目 id 当 LazyColumn 的 key，
    重复 key 直接抛 `IllegalArgumentException`。
23. **没人引用的照片（冷启动那一扫）**：拍一张 → 按左上角关闭退出取景页（不保存、也不按重拍）→
    `adb shell run-as com.ilyskyo.capturney.debug ls -l files/entries`，那一张此刻**还在**——它要老满
    一小时才会被扫掉，而那道门槛是为了不删掉正在导入的那一张。一小时之后杀进程重进再看一次：
    那个文件该没了，而现存条目的照片、以及「词卡还在引用的那张贴纸」都必须还在
    （保留集是跨 `diary.json` 与 `deck.json` 两份算的，只盯一份就会删掉别人正在用的东西）。
    反向也要成立：冷启动时把一张照片**分享给见词**（导入那几十秒里文件先落盘、引用还没写），
    期间不该有任何文件被扫掉。最后一条最要紧：把 `diary.json` 改成一个坏 JSON 再启动
    （`.corrupt-*` 留证 + 从空文档起步），现存照片与录音**一张都不该少**——
    读成空文档的那一次，不允许拿它去判断「没人引用」。


### 清单里已经在模拟器上验过的部分（2026-10-06）

下面这些**不依赖相机、触觉、TTS 与厂商 ROM**，所以在 AVD 上看着真实渲染确认过，
真机那一遍可以只复核剩下的：

- **11 日历筛选**：月历翻到未来月份时下一月箭头是灰的、点不动（之前能无限翻进空格）。
- **18 搜索 / 添加页**：搜冠词（`a` / `the`）有结果；手写词真的进词典并出现在牌组里。
- **20 时间轴多选**：长按进多选、单击是勾选而不是打开、删除前有带数量的确认框；
  选中态那颗勾现在是一枚实心圆 + 白勾（原来是「白圈里一个圆角方块」）。
- **21 详情页的长按面板与编辑**：复制 / 编辑 / 删除三项各走通；编辑表单已从 alert 换成
  底部升起的 form sheet；确认键的文案已不再是「保存贴纸」。
- **复习页**：同一张卡可以反复翻回正面；翻过一次之后那行提示会改口；两面同字的卡不再进队列
  并且进度条下面写明跳过了几张（数字与单复数都对）。
- **悬浮控件不再压内容**：顶部页签与底部拍照键的避让、时间轴主干的连续性，都在截图上确认过。
- **未覆盖语种的首屏**：系统语言换成 `fr-FR` 并重启后，首屏整屏英文（详见下一段）。

**AVD 验不了、必须真机**的：1–7（相机、抠图边缘、旋转、ROM 首帧、触觉、TTS）、
9（深色小组件与冷启动）、10（`adb backup` / Google One 的白名单）、13（12MB 原图的相册导入）、
14–17（录音与麦克风权限的三种答案）、22（连点两下的真实挂起窗口）、23（一小时门槛的清扫扫描）。

**未覆盖语种回落到英文**（2026-10-07 在 AVD 上确认过）：把系统语言换成 `fr-FR` 后首屏是
整屏英文——「Moments / Words / Nothing recorded yet / Point the camera at anything…」。
前提是**重启一次**：只 `settings put system system_locales` 而不重启，这个值不传播到进程
（`get-app-locales` 回读是 `[fr-FR]`，画面仍是中文），这正是这条之前被判成「只能真机看」的原因。
命令：`adb shell settings put system system_locales fr-FR && adb reboot`，等 `sys.boot_completed=1`
再装包启动；验完换回 `zh-CN` 并再重启一次。

机器守不住它的**解析层面**：默认包不含中日韩文字这一条有结构守卫
（`LocaleConfigMatchesResourcesTest`），但 `createConfigurationContext(fr)` 取到的英文是个常量——
往源码里加一份真的 `values-fr/strings.xml`、再把 `fr` 注册进 `res/xml/locales_config.xml`、
两个一起加，三次尝试都**没有**让它变成法语。所以这条只能靠上面那个真传播的截图，进不了 CI。

### 设备测试偶发「No compose hierarchies found」的成因与解法

同一台 AVD 上如果**还装了别的应用**，那个应用周期性跑到前台时会把 Compose 树从量测底下换掉：
`connectedDebugAndroidTest` 报 `No compose hierarchies found in the app`，而**本仓库的代码没有任何问题**。
特征是三条——同一天里落在**不同的测试类**上、**单独重跑就全绿**、logcat 同一时间窗里能看到
另一个包在设置 back callback 并拿到前台窗口。

解法不是加等待（那只会掩盖别人的窗口），而是**给设备测试单开一台不装任何东西的 AVD**：

```bash
avdmanager create avd -n <名字>-ci -k "system-images;android-34;google_apis;x86_64" -d pixel_5
```

新 AVD 里只有系统应用，抢前台的那一个不会跟着装过去。第一次冷启动约一分钟，
之后 `connectedDebugAndroidTest` 只连着它跑——2026-10-07 这么跑过一次全量：**26 条、零失败、
一次 `No compose hierarchies` 都没有**（此前同一套在共享 AVD 上一天里红了五次）。

### 但 `-ci` 那台不能和别的项目的模拟器**同时**开

单开一台解决的是「抢前台」，没解决「内存」。2026-10-08 凌晨实测：另一项目的 `wl` 正在跑的时候起
`wl-ci`，qemu 在 GPU/Vulkan 初始化那一步**停住不动**——日志四分钟一字未变（停在
`supportsExternal…`），`adb devices` 里始终只出现 `wl` 那一台，而 `wl-ci` 的 qemu 已经吃掉约 1.6 GB
（整机空闲从 4.85 GB 掉到 3.18 GB）。这台机器 15.4 GB，两个模拟器 + 一次 Gradle 构建装不下；
更早两次不是「测试红」而是**JVM 根本起不来 / daemon 中途消失**，同一个原因。

所以设备闸门只有两种走法：**等 `wl` 那台关掉**再跑，或者用实体机。别指望「小一点的 memory 参数」绕过去：
`-memory 1536` 已经压到低于 AVD 自身配置了，卡的是宿主可用内存，不是 guest 配的内存。

收尾只杀自己那台，两步都别用通配：

```bash
# 1) 先看清哪些 PID 属于本次的 -ci，哪些属于在跑的别的项目（按 -avd 参数区分）
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='emulator.exe' or name='qemu-system-x86_64.exe'\" | Select-Object ProcessId,CommandLine | Format-List"
# 2) 按**显式 PID** 停，不要 taskkill /IM emulator.exe /F（那会一起杀掉别的项目那台）
powershell -NoProfile -Command "Stop-Process -Id <ci 的 qemu pid>,<ci 的 emulator pid> -Force"
```

停完必须回头确认别人那台没被波及：`adb devices` 还在、`dumpsys window` 的 `mCurrentFocus` 仍是它的包、
`pidof <它的包名>` 拿到的 pid 和动手前**是同一个**（pid 没变才说明没被重启过）。
本次起 `-ci` 时加了 `-read-only`，guest 磁盘改动只留在内存里，所以退出后 AVD 镜像回到原样——
设备测试要装应用，这个参数只适合「先试试能不能起来」，正式跑全量时不要带。


## 词典是怎么生成的

`assets/lexicon/en.json`（现 13055 条）由 `tools/build_lexicon.py` 从 ECDICT（MIT）的 CSV 生成：

```bash
curl -L -o ecdict.csv https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
# 先看影响就写到 scratch 路径（--out 指向不存在的文件时写出前的丢弃守卫不拦）。
python3 tools/build_lexicon.py ecdict.csv --out app/src/main/assets/lexicon/en.json --limit 20000
# 只想在已发布集合上追加、一条都不删：
python3 tools/build_lexicon.py ecdict.csv --out app/src/main/assets/lexicon/en.json --keep-published
```

`--limit` 与 `--keep-published` 的区别是 2026-10-08 用本地那份 65.9 MB 的 ECDICT 实测出来的，
三个数都记在这里，因为**它们推翻了这一节先前自己的解释**（旧注释说「3621 个 id 消失是因为
已发布那份是用另一个版本生成的」——不对）：

| 怎么跑 | 结果 |
|---|---|
| `--limit 20000` | **0 个已发布 id 消失，13055 条内容逐字节相同**，多出 6949 条更低频的候选 |
| 默认 `--limit 12000`（现已自动锚到现有 13055 条） | 仍会换掉 **3180** 条，被守卫拒绝写出 |
| `--keep-published`（默认 limit） | 0 丢失、0 内容差异，写出 16251 条（净增 3196） |

中间那一行才是重点：**已发布那份不是当前 ECDICT 的「前 13055 名」**，它是历史攒出来的集合
（早期版本、手补的专名、放宽过的筛选口径都留在里面）。所以「怎么生成这份词典」这句话
在以前做不到精确复现，现在靠 `--keep-published` 变成了一个明确的操作——截断只追加，
与 `gloss-*.json` 的 `--apply`「已有值优先、拒绝变小」是同一条规矩。
默认值 12000 低于现有条数这件事本身也是陷阱，代码现在会把下限锚到将被覆盖的那份文件并**说明它做了什么**；
真要换血就显式给 `--limit` 配 `--force`。

**这一节改的是生成器，`assets/lexicon/en.json` 一个字节都没动**——上面所有跑的都是 scratch 路径，
跑完已删。要把 16251 条那份发出去是另一个决定：那 3196 条新增的中文释义没经过逐条审读，
而按已经量过的缺陷率（前 ~1100 频次约 1/3、1100～2600 段约 1/8、再往下签名不再可信），
直接发等于把没核过的释义推上卡片背面。

两处筛选口径在 2026-10-08 放宽过，别再改回去，它们挡的是真实缺陷：

- **首字母大写不再一律当专名丢掉**。ECDICT 把十二个月名与七个星期名写成 `January`/`Monday`，
  旧规则一句 `first.isupper()` 就把它们连同人名一起排除，结果是**日记应用里没有 Sunday**。
  现在只在「该行既没有 `oxford=1` 也没有任何考纲 tag」时才当专名处理，所以 april/monday 进得来、
  aaron/kennedy 依旧进不来。
- **`frq`/`bnc` 记为 0 不等于不使用**。0 在 ECDICT 里是「没有排名数据」，旧规则把它当最差排名直接 skip。
  现在只要该行有 `oxford=1` 或 tag 就收下，并把 frequency 排到最后（不冒充高频词）。

这两条放宽一共放进来 1049 个核心词（`the/be/and/of/have/about/able/january/sunday`…），
其中六个的主义项仍然偏（`have` 取到 aux. 的「已经」、`but` 取到 prep. 的「除了」、
`up` 取到「起床的」），已按 `PRIMARY_SENSES` 逐条改回原行里对的那个义项。

生成物提交进仓库，这样 clone 之后不需要再跑 Python。

### 日语/韩语的背面释义：`gloss-<lang>.json` 补丁层

`en.json` 每条带 `glosses`（目前只有 `zh`）。日语/韩语用户翻到背面看到的那一行来自另外两个文件
`assets/lexicon/gloss-ja.json` / `gloss-ko.json`——它们**不是词典而是补丁**，按条目 id 叠上去，
由 `LexiconRepository` 加载（遇到不认识的 id 只记一条 warning 就跳过）。

补这批数据用 `tools/fetch_wikidata.py`（Wikidata 的标签是 **CC0**，放进 MIT 仓库没有许可摩擦，
而且是各语言社区的人写的，不是机器翻的）：

```bash
python3 tools/fetch_wikidata.py --limit 400 --out-dir tools/out   # 先落到 tools/out，可中断续跑
python3 tools/fetch_wikidata.py --limit 400 --apply               # 合并进 assets（不覆盖）
python3 tools/fetch_wikidata.py --qids map.tsv --apply            # 人指认 QID：词<TAB>QID，一行一个
```

三条不能改的规矩，都写在工具里：

- **消歧靠我们已有的中文释义**。`cup` 在 Wikidata 里有好几个同名实体，只按「英文标签相等」
  要么挑错要么全放弃（实测放弃到只剩 2/12）；改成「候选里恰好一个的中文标签出现在该词的
  ECDICT 中文释义里」之后命中率翻倍，且抽查全对。
- **宁缺勿滥**。释义是要被评级的那一行，给错比没有糟得多：值必须含假名/汉字（ja）或谚文（ko），
  判不准就留空。
- **`--apply` 只合并、且拒绝让条数变小**。已发布的那份是手工攒出来的，整文件覆盖会静默冲掉它。

`GlossOverlayCoverageTest` 守着这份数据的四条性质：每个 id 还在 `en.json` 里、ja 与 ko 覆盖同一批词、
条数不倒退、每条值真的含该语言字符。四条都各自被注入验证过会红。

### 剩下判不出来的那批词：不要再找规则

`airport`、`computer`、`bakery`、`garden` 这类最常用的具体名词，子串规则**就是**会漏——
它们没有一个候选的中文标签能落在简体 ECDICT 释义里。这轮一共试过并退回五条规则：

| 试过的依据 | 为什么错 |
| --- | --- |
| 按词频取前 N | 捞回 will/one/time/people 这类功能词 |
| 按「ECDICT 首块是 n.」筛 | 捞回 aaron/abel 这类专名 |
| 候选唯一就直接收 | 产出 `all → オール`、`good → グッド` |
| 按共享汉字打分、唯一胜出 | 产出 `airport → エアポート駅 (MARTA)`；且繁体的「機場」与简体的「飞机场」共享 0 字，正解被**系统性排在后面** |
| 用 P31 类别挡掉电影/车站/期刊 | 正解 Q1248784 的类别是 `type of aerodrome`（上位词不含 airport），两个车站的类别却含 `airport railway station`——**恰好挑反**；而 `bakery` 的正解 Q274393 一条 P31 都没有 |

五条都写在 `tools/test_lexicon_rules.py` 里，每条都有一例会红，改动规则前先跑它：

```bash
python3 -m unittest discover tools
```

所以剩下的缺口**不是规则问题**：`wbsearchentities` 的候选集两次调用还不一样，任何靠候选排序得出的
结论都不能用来生成发布数据。唯一靠得住的下一步是**由人指认实体**——把词到一个 QID 的手查表
（候选和它们的四种标签、P31 类别已经能一次性摊出来核对），标签照旧从 Wikidata 取。
指认这一步看一眼英文标签就能确认，而规则那一步不能，这是两者的全部差别。

在拿到这样一张表之前，这些词的背面**继续只显示英文**是有意为之，不是待修的 bug。
另外查过替代数据源：日语的 JMdict、韩语的 kengdic / cc-kedict 都是 CC BY-SA（含相同方式共享），
与 MIT 仓库并放需要单独的数据许可说明，而且它们解决的是「译词质量」，不解决「挑错实体」——
真要引入得先把许可这件事谈清楚，别顺手把数据拷进来。

## 三个已经踩过的坑

1. **Kotlin 编译失败时，测试仍会跑上一次的 class。** 于是你会看到一批早已删掉的断言在报错，
   而真正的原因只在 `compileDebugKotlin` 的输出里。先 `grep '^e:'` 确认没有编译错误，再信测试结果；
   结果看着不可能时，用 `./gradlew testDebugUnitTest --rerun-tasks --no-configuration-cache`。
   **同一件事还有一条更阴的版本**：词典那几个 JSON 在 `src/main/assets/` 下，而 `testDebugUnitTest`
   把它们当**普通文件路径**读（`File("src/main/assets/lexicon/…")`），**不是 Gradle 声明的输入**。
   所以改了 assets 而不加 `--rerun-tasks`，测试任务可能整个不复跑，你读到的是上一次的 XML——
   2026-10-08 就这么被骗过一次：我从 `concepts.json` 删掉一条概念后跑守卫，它「绿」了，
   我以为那条守卫是假的；其实那一轮根本没执行（同批输出里还印着 gradle 的用法帮助，
   因为我把 `--rerun` 当成了任务参数，RC=1 来自参数错误而不是断言）。
   加 `--rerun-tasks` 重跑后立刻红了四条。**证伪失败时先怀疑自己没真的重跑，再怀疑断言**。
2. **纯逻辑不许 import `android.graphics`。** `unitTests.isReturnDefaultValues = true` 会让那些类
   返回 0/空值，`RectF.equals` 又不比较内容，于是几何测试全红且报错信息完全指不到原因。
   `CameraFocusMath` 和 `OverlayGeometry` 因此各自定义 `NormBox` / `SensorCrop`。
3. **取景首帧黑屏**在部分国产 ROM 上出现过，`PreviewView` 需要 `ImplementationMode.COMPATIBLE`。
   真机问题，CI 测不出来。

## 国内网络

`settings.gradle.kts` 走的是官方 `google()` + `mavenCentral()`。要换镜像就在自己机器上加
init script，或者临时改 `settings.gradle.kts` —— 但**别把镜像地址提交进仓库**，CI 和别人的机器
都会被带偏。
