# 闹钟可靠性验证

验证日期：2026-10-01。本次修复随 v1.9 发布，版本码为 10；v1.8 安装包作为历史版本保留。

## 测试策略

项目采用 Java、原生 Android Views 和系统 AlarmManager。本次修复前没有自动测试；现增加 JUnit 4.13.2 与 Robolectric 4.17，只用于本地单元测试，不增加应用运行时依赖。

测试优先覆盖容易导致漏响、重复响铃或无法停止的状态变化：重启恢复、稍后提醒消费与取消、多个闹钟合并、权限变化及响铃时长。通过模拟系统广播、PendingIntent、服务和 Activity 生命周期验证实际代码。大多数测试使用 API 28，另覆盖 API 26 锁屏兼容和 API 35 精确闹钟权限恢复。

## 已完成的自动验证

| 测试组 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| AlarmRecoveryTest | 8 | 一次性闹钟的稍后提醒、过期提醒时间校验、普通与稍后提醒独立恢复、应用更新、解锁前存储及原数据迁移 |
| AlarmReceiverReliabilityTest | 5 | 过期或缺失时间标记、重复投递、服务启动失败保留提醒、组件可见性及解锁前可用性 |
| MergedAlarmReliabilityTest | 12 | 页面状态同步、旧操作过滤、仅振动模式、API 26 锁屏、合并响铃时长、渐强音量和部分稍后提醒失败 |
| AlarmPermissionReliabilityTest | 3 | 通知总开关、通知渠道关闭及 API 35 精确闹钟权限恢复 |

共 28 项测试：0 失败、0 错误、0 跳过。调试 APK 构建通过；Android Lint 为 0 错误、28 警告，仍有目标版本、资源、国际化等提示。

修复前先运行回归用例：最初 19 项有 15 项失败；随后针对取消后的旧提醒、通知开关和合并响铃时长补充测试并确认问题。修复后完整检查通过。渐强音量测试使用可播放铃声的测试替身，只验证音量变化逻辑。

在项目根目录使用已安装的 Android Studio JBR 执行：

```powershell
$env:JAVA_HOME = 'D:\Android\Android Studio\jbr'
.\gradlew.bat --offline :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

首次在新环境执行需要联网下载测试依赖，可去掉 `--offline`。JUnit、Robolectric 和 Android 插件版本固定在项目中。

生成的测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`，静态检查报告位于 `app/build/reports/lint-results-debug.html`。正式安装包使用原发布签名；调试 APK 不用于覆盖官方签名的安装包。

## v1.9 安装包验证

使用同样的 JBR 执行 `:app:assembleRelease :app:lintRelease :app:testDebugUnitTest`，构建和 28 项测试全部通过，发布版静态检查为 0 错误、28 警告。

- 文件：`releases/SimpleAlarm-v1.9.apk`，大小 44,636 字节。
- 包名：`com.example.simplealarm`；版本：1.9（10）；最低 API 26，目标 API 35。
- `apksigner verify --verbose --print-certs` 验证通过；与 v1.8 的签名证书完全一致。
- `zipalign -c -P 16 -v 4` 验证通过；正式包不可调试。
- APK SHA-256：`b1032e923fae47029a493644f2a84cd43e8c6b1d8e59fd906c2fe039921d8a2f`。
- 签名证书 SHA-256：`ddf6792283bb5cc2d414ec34c5a9a0c78454d2610815a25cdf0aefcf973f8398`。

## 仍需设备验证

当前没有连接的 Android 设备或可用模拟器。Robolectric 不能证明设备扬声器出声、厂商后台策略或系统实际投递时间正常。以下步骤需要使用同签名的新测试包或独立测试设备验证：

1. 设置普通闹钟后重启，保持屏幕锁定且不首次解锁，确认响铃、全屏页面、停止和震动可用。
2. 一次性闹钟进入稍后提醒后重启，确认提醒恢复；到期后才首次解锁时，确认没有重复响铃。
3. 从已有安装升级，核对闹钟、备份和新建编号；重启后再次核对。
4. 取消稍后提醒，等待原定触发时间并重启，确认不再响铃。
5. 两个闹钟先后加入响铃，核对页面数量、通知操作、部分调度失败时继续响铃，以及较晚加入的闹钟完整响铃时长。
6. 关闭再恢复精确闹钟权限，核对待响提醒；分别关闭应用通知及闹钟通知渠道，核对权限页提示和设置入口。
7. 在 Android 8 锁屏验证显示和亮屏；在 Android 9 及以上验证渐强音量。自定义铃声可能在首次解锁前不可访问，需确认系统铃声回退能够出声。
8. 在锁屏和省电状态下测量稍后提醒延迟，结合 `SimpleAlarmTiming` 日志区分系统投递和应用处理耗时。日志中的播放请求时间不能替代实际出声时间。

本次修复消除了已复现的应用状态和恢复问题；此前反馈的十几秒延迟尚未在设备上定位。
