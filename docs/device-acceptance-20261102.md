# 真机验收清单：先做 20–30 分钟 smoke，再谈长录音

状态：**全部真机项目 NOT RUN**。依据公开源码 `8ec132e926c6640c1d2b22ec5cd2e45849706030` 及其六层检查点；不是旧 APK 的验收结果。源码版本号仍是 `0.1.0-dev13`，仅看版本号不能证明 APK 含最新补丁。

## 前置门槛

- 仅在用户明确选定的测试手机上，由用户确认安装/更新一个**已签名且可追溯到上述源码**的构建；核对 APK SHA256 和签名证书。现有 Nov1 产物是 unsigned，不能直接安装。本清单不生成密钥、不安装、不要求卸载或清除旧数据。
- Android 10/API29+；Android13+额外覆盖通知权限。只用本人同意的短合成音/提示音，不录旁人谈话、电话内容或敏感环境。已有珍贵录音的设备先停下，不做故障注入。
- 在 `Local Recorder Dev` 主界面设置 `Sample rate (Hz)=16000`、`Channels=1`、`Segment duration (seconds)=10`、`Spool maximum (MiB)=64`，留足系统自由空间（至少数百 MiB，不能只依赖应用的32 MiB底线）。输入选手机内置麦克风。
- `Experimental interruption recovery...`、手机麦克风 fallback、automatic enrollment、verified-transfer local cleanup 均保持 OFF。不要保存 FTPS 凭据、不要排队上传。测试时只在明确步骤中开启恢复选项。
- 记录：手机型号/系统build/API、APK hash/签名/来源提交、电池和电量策略、开始/结束时间。任何未确认的状态记 BLOCKED/UNKNOWN，不勾 PASS。

## 最小操作与预期

| 步骤 | 操作 | 必须观察/判定 |
|---|---|---|
| 1 安静启动 | 打开应用；改设置并点 `Save settings`；返回/旋转/重开页面 | 不开始录音，不出现增长的 PCM frames；保存设置不授予恢复/上传权限。 |
| 2 首次权限 | 点 `Start / resume`，先拒绝麦克风；API33+另做通知拒绝 | 不采音；明确提示需权限和可见界面。去系统设置恢复权限后，**必须再点 Start**。若权限弹窗期间退出页面，回来不得偷偷启动。 |
| 3 可见录音 | 可见页面点 Start 并按需授权；播放约25秒短合成音 | 有 microphone FGS/系统麦克风提示（按设备UI）、状态正常且 PCM frames 增长；Start 不再可重复启动，10秒分段不应创建第二录音会话。异常应有明确状态，不算录音成功。 |
| 4 页面与通知 | 录音中旋转、返回桌面、重新打开；测试系统允许的通知滑除 | 不因 Activity 重建重复 Start。滑除 ongoing 通知本身**不是 Stop**，不得强制重发循环；用应用或通知的 Pause/Stop 控制。 |
| 5 Pause / Start / Stop | 点 `Pause`，等收尾完成，再点 Start；随后点 `Pause` 紧接 `Stop`，观察收尾后再点 Start | Pause 真正结束旧 AudioRecord；同进程干净暂停才可延续capture，但新run/不确定epoch。Stop优先，晚到Pause不得恢复旧token；收尾期间Start应禁用/拒绝，无第二录音机。身份细节如无可见元数据证据应记未核验，不能仅凭按钮推断。 |
| 6 导出证据 | Stop并等收尾；`Load / refresh first page` →选已完成段→ `Save selected WAV…`，选**本机**文件夹 | 显示 VERIFIED（或明确的其它结果）；完整10秒段应为16kHz/单声道/PCM16、约10秒。检查声音/时长、缺段和接缝；计数增长或全零音频不证明实际采音。原件应仍在列表。云盘provider可能上传，勿选；取消可留下外部部分文件，不自动删。 |
| 7 撤权 | 新的短合成录音中，到系统设置撤回麦克风权限；另一次关闭应用通知权限/录音channel；再恢复 | 录音应停止或明确fail-closed，不能换录音机绕过权限；恢复设置后不自动重启，须回可见页面手动Start。权限变更可能导致系统杀进程，不把未执行的清理回调当证据。 |
| 8 系统停止/进程边界 | 新的短合成录音中使用系统 Active apps 的 Stop（如支持）；另一次用系统设置 Force stop；重新打开应用 | 不自动录音/恢复；旧文件保留，异常结束可有不确定提示；手动Start产生新capture，不从偏好恢复进程内token。已完成段可导出，未完成段不可假装完整；不卸载、不清数据。 |
| 9 锁屏 smoke | 默认电量策略下新录音，锁屏10分钟，再解锁Stop并导出抽查 | 有无停止、时长短缺、热/耗电及系统提示均记录；短测通过不等于Doze、全天或OEM验收。不要为“通过”先申请电池白名单。 |

### 有合适设备时的单独项目

- **可恢复中断**：只在一次新的Start前勾实验恢复；用同意的USB/有线输入断连等受控场景，先测fallback关闭，再单独测开启。应报告路由不确定边界；仅仍存活且被授权的服务在资源/存储/日志已确认后有限重试（最多8次，最长单次backoff30秒）。Stop/Pause应取消等待，恢复checkbox使用后复位。没有受控中断或不可见重试证据就标 NOT RUN，勿用真实电话录音凑测试。
- **低缓存边界**：专用合成录音可把应用逻辑spool设16 MiB、保持手机大量真实空闲，等逻辑上限触发；应可见停止、保留既有文件、不循环删除。不是“填满手机”测试；勿创建磁盘填充文件。空spool纯PCM理论容量约8.7分钟，metadata、既有录音及预留会更早触限；若已留有smoke文件，可能很快触限。先核算专用测试环境的剩余容量，不删珍贵录音。此项独立于20–30分钟smoke。
- **长时/OEM验收**：smoke通过后在目标手机做12小时，再24小时；使用者可以随时停止。16kHz单声道PCM16仅PCM就约110 MiB/小时，默认512 MiB不足约4.7小时，不能把cache-full当后台故障。24小时PCM约2.58 GiB，需另计metadata/余量/系统空间；先核算并明确设置spool，不自动上传/清理来绕过容量。记录屏幕/充电/省电状态、温度、电量、实际文件时长和缺口。停止并冷却任何过热/严重耗电设备，不做断电破坏测试。

## 可选 ADB（用户自己的已授权测试机；本次未执行）

不需要ADB也能完成上述UI步骤。若使用，先确认唯一目标；所有命令显式带序列号，不能直接向未核对的默认设备发操作。以下每行单独执行，替换 `SERIAL`；Windows PowerShell同样可用这些直接命令，不需要Bash变量。

```text
adb devices -l
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell dumpsys package io.github.headmaster218.recorder.android
adb -s SERIAL shell am start -n io.github.headmaster218.recorder.android/.MainActivity
adb -s SERIAL shell dumpsys activity services io.github.headmaster218.recorder.android
```

前两项确认目标/API；package仅用于本机核对包信息，不足以证明源码hash；`am start`只打开可见Activity，不开始录音。`dumpsys activity services`对照UI记录录音中/Stop完成后的服务状态，不能证明实际音频质量。

只在步骤8、确认当前为可丢弃的合成录音后，可用官方等价命令测试 Active apps Stop（API33+），不要与Force stop混为一谈：

```text
adb -s SERIAL shell cmd activity stop-app io.github.headmaster218.recorder.android
```

不直接 `am startservice`/广播控制非导出RecordingService；不使用 `pm clear`、uninstall、reboot、root、run-as、自动安装、批量抓logcat/录音。日志可能含身份/路由信息，仅在本地保留最小必要摘录；不要把原始日志、音频或设备序列号提交公开Git。

## 结论模板

`来源提交/APK hash/设备系统（本地记录） | 步骤 | PASS/FAIL/BLOCKED/NOT RUN | 预期/实际差异 | 缺口/停止原因`。

安全失败（保留文件且停止）不等于连续录音通过；“没有崩溃”不等于录到了音；模拟器通过也不等于物理麦克风/OEM/耐久通过。

依据：当前 MainActivity/InputControls/ExportControls/RecordingSettings/Manifest，以及现有 [恢复约束](recovery-service-integration-draft.md)、[手动导出](manual-export.md)、[Nov1 SDK/TLS边界](../checkpoints/sdk-wire-readiness-20261101/docs/unsigned-sdk-wire-validation-20261101.md)。官方参考：[ADB](https://developer.android.com/tools/adb)、[系统停止FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)。
