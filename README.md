# OAutoPIN

ColorOS 开机自动输入 SIM 卡 PIN，让手机重启后不必手动输密码就能注册到蜂窝网络。

本仓库是 [achyuki/OAutoPIN](https://github.com/achyuki/OAutoPIN)（基线 `275367d`）的修改版，
基于 **libxposed 公共 API 102** 重写，与上游的主要差异见下一节。原作者声明「仅适配单 SIM
或双卡单锁、仅在 ColorOS 16 测试过、自用不维护」，本修改版在此基础上扩展。

## 与上游的差异

| 项目 | 上游 | 本修改版 |
| --- | --- | --- |
| Xposed API | legacy `de.robv.android.xposed:api:82` | libxposed API **102**（`META-INF/xposed/`，支持热重载） |
| 解锁时机 | 重启后 **BFU** 阶段即自动填 PIN | **默认等设备首次解锁（AFU）之后**才填，可用开关切回 BFU |
| SIM 卡数 | 单卡 / 双卡单锁 | 卡1、卡2 可分别设置 PIN |
| PIN 存储 | `XSharedPreferences` + `MODE_WORLD_READABLE` | 框架自有 **RemotePreferences**，PIN 只落 CE 存储 |
| 界面 | 英文 | 中文 |

### 为什么要 AFU 门控

上游的行为是：手机一开机、还停在 BFU（Before First Unlock，任何人没输过密码）阶段，
模块就把 SIM 卡 PIN 填了进去。结果是**捡到手机的人重启一次，就得到了一个能用蜂窝网络、
能收短信的设备**——短信验证码、来电、蜂窝定位全部落到对方手里。

本修改版默认反过来做：BFU 阶段什么都不提交，只记下当前弹出的 PIN 界面；等用户用密码或
指纹完成首次解锁（`ACTION_USER_UNLOCKED`）之后，才补填 PIN。这样捡到手机的人即使重启，
拿到的仍然是一张锁定状态的 SIM 卡。

代价是：重启后到首次解锁之前，手机没有蜂窝数据，纯 Wi-Fi 之外定位能力下降。如果你更看重
「重启后立刻能被找到」，可以在设置页打开 **「允许在 BFU 阶段自动解锁 SIM」** 开关，行为与
上游一致。

> 注意：如果你的 Bootloader 已解锁（KernelSU / 自定义内核等），防盗体系本身已经失效——
> 对方可以直接刷入自定义镜像。此时建议保持 AFU 门控关闭状态（即不填 BFU），不要为了
> 「蜂窝定位」把 SIM PIN 暴露出去。

## 工作原理

1. hook `com.oplus.keyguard.security.view.OplusKeyguardSimInputViewController#onResume(int)`
   （仅当实际类名以 `SimPinViewController` 结尾）。
2. 读取配置后判断 `UserManager.isUserUnlocked()`：
   - 未解锁且未开启 BFU 开关 → 记下这个控制器，注册 `ACTION_USER_UNLOCKED` 监听后返回；
   - 否则进入提交路径。
3. 提交路径：从 `mView` 找到安全输入框（`getMEditInputWidget()` → `getSecurityEditText()`），
   `setText(pin)` 后调用 `checkSimPikOrPuk()`。`isChecking` 为真时跳过，避免重复提交。
4. 每个控制器实例**只提交一次**（弱引用集合去重，热重载不清空），避免一次锁屏反复重试把 SIM
   打成 PUK。
5. 槽位判定依次尝试：控制器上的 `mSlotId`/`mSlotIndex`/`slotId`/`mPhoneId` 等字段 →
   `mSubId`/`subId` 等字段（用 `SubscriptionManager` 映射到槽位）→ `getSlotId()` 类访问器 →
   兜底「只有一张卡处于 `SIM_STATE_PIN_REQUIRED` 时用它」。判定不出来且两卡 PIN 不同时**不提交**，
   只写日志。
6. 设备解锁瞬间，若当时的 PIN 界面还挂着（`isAttachedToWindow`），直接补填；否则退化为
   `TelephonyManager.supplyPin()`（需要 `MODIFY_PHONE_STATE`，SystemUI 通常没有，失败只记日志）。

## 安装

1. 安装 APK，在 LSPosed / 对应框架中启用模块，作用域勾选 `com.android.systemui`。
2. 打开模块设置页：
   - **卡1 PIN**：必填，4–8 位数字；
   - **卡2 PIN**：双卡才需要，留空表示与卡1相同；
   - **允许在 BFU 阶段自动解锁 SIM**：默认关闭，见上文。
3. 保存后模块支持热重载，一般立即生效；未生效时重启 SystemUI 或重启手机。

## 排障

日志 tag 为 `OAutoPIN`（同时写入 logcat 与框架日志）：

```sh
adb logcat -s OAutoPIN
```

- `deferred ... user locked` 表示处于 BFU，正在等首次解锁，属正常。
- `cannot resolve slot` 后会 dump 控制器的 int/boolean/String 字段，可用于确认真机上的
  槽位字段名；把这段日志贴到 Issue 即可。
- 若日志显示 PIN 已填入但 SIM 仍未解锁，多为 PIN 本身不对——**连续三次错误就会锁到 PUK**，
  调试时请务必用确认过的 PIN。
- BFU 模式下开机后若又弹出 SIM PIN 页：模块先等 modem 状态（最多 8×400ms）；已解锁的卡不再重复提交，会自动刷新 keyguard，必要时做一次息屏/点亮（每次开机只做一次）。

## 构建

不在本地构建，仓库自带 GitHub Actions：

- `.github/workflows/android.yml`：每次 push 构建并上传 APK artifact；
- `.github/workflows/release.yml`：打 tag 时发布 Release。

两者都会现场生成签名密钥（口令写在 workflow 里），因此**签名是公开的**，任何人都能复现同一个
签名，不要据此判断来源可信度。

## 许可与声明

- 上游 [achyuki/OAutoPIN](https://github.com/achyuki/OAutoPIN) 采用 MIT License，本修改版
  保留该许可。
- 本项目为非官方修改版，与原作者无隶属或授权关系。
- 仅在 ColorOS 16（Android 16）验证，其余系统版本请自测；修改系统行为有风险，请自备恢复手段。
- 如有侵权请联系删除：<https://github.com/SQL0106/OAutoPIN/issues>
