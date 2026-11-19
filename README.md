# 恋爱军师 · 键盘助手（LoveGenius）

安卓悬浮窗小工具：在微信 / QQ 聊天界面点一下悬浮球，自动读出对方刚发的那句话，
生成几条回复建议，点「填入」直接写回聊天输入框。

- 包名：`com.lovegenius.assistant`
- 当前版本：**v1.3.2**（versionCode 7）
- 目标系统：Android 10+（真机测试：OPPO PKB110 / Android 15，1256×2760 @2.75）

## 功能

| 功能 | 说明 |
|---|---|
| 读对方的话 | 面板打开时自动读剪贴板 / 输入框，抓出对方上一句；也可手动「读取」 |
| 生成建议 | 调 LLM（DeepSeek / 豆包 / 智谱 / 通义 / Kimi / 硅基流动 / 自定义），也带本地话术库兜底 |
| 一键填入 | 写剪贴板 → 点输入栏 → 长按 → 点「粘贴」，自动回填到微信 / QQ 输入框 |
| 悬浮球 | 键盘弹出时出现在右侧，可上下拖动，位置会记住 |

## 构建

环境（本机已就绪）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.13+9'
$env:ANDROID_HOME='C:\Android\sdk'
```

编译：

```powershell
cd LoveGenius
.\gradlew.bat assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

安装到手机：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
powershell -NoProfile -ExecutionPolicy Bypass -File ..\toggle_a11y.ps1
```

> 每次重装后必须关一次、开一次无障碍服务（`toggle_a11y.ps1`），否则服务还绑着旧代码。

## 代码结构

```
app/src/main/java/com/lovegenius/assistant/
├── MainActivity.kt              引导页、供应商/模型选择
├── service/KbAccessibilityService.kt   读输入框、填文字、点坐标、找键盘顶边
├── overlay/OverlayManager.kt           悬浮球 + 面板的窗口管理与位置逻辑
├── overlay/GeniusPanelView.kt          面板 UI、生成、填入
├── overlay/SpeakBook.kt                本地话术库
├── llm/                                供应商配置、提示词、HTTP 客户端
└── data/Prefs.kt                       本地设置存储
```

## 关键实现说明（改代码前先看）

1. **不抢焦点**：球和面板都用 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，否则搜狗键盘立刻收起。
2. **读剪贴板要先借焦点**：面板不可获焦时系统会返回空串；先 `onImeRequest(true)` → 220ms → 再读。
3. **填入**：微信 / QQ 的输入框节点读不到，只能「写剪贴板 → 长按输入栏 → 点第一个菜单项『粘贴』」。
4. **键盘顶边会抖**：ColorOS 的 IME 窗口 bounds 会报 140 / 1181 / 1711 / 2750 等怪值，
   只采信落在屏幕 50%~80% 的值，并取稳定后的较小值。
5. **球位置**：`键盘顶 − 输入栏预留 − 球高`，并按 App 微调；键盘变化事件做了 320ms 防抖 +
   小于约 30px 的位移直接忽略，避免键盘动画期间球来回跳。用户拖过之后存的是**屏幕绝对 y**。

## 版本

- **v1.3.2**：球整体上移，避开输入栏/工具栏；键盘动画期间球不再上下跳；拖动位置改为绝对坐标记忆。
- **v1.3.1**：填入改走剪贴板 → 长按 → 粘贴；点「填入」后面板自动关闭。

`release/恋爱军师_v1.3.2.apk` 是手机上正在用的那份安装包。
