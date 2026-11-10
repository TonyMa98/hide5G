# hide5G

一个基于 LSPosed 的状态栏定制模块，用于隐藏 ColorOS 状态栏里的网络制式文字（`5G` / `5GA` / `5G+` / `5G UC` / `5G UW` / `5.5G` / `4.5G` / `4G` / `4G+` / `LTE` / `3G` / `H+` / `2G` …），并对状态栏图标、字号、间距提供细粒度微调。信号格、上下行箭头、漫游标记均保留，只去除多余的字样。

## 功能

| 分组 | 功能 |
|---|---|
| 网络 | 隐藏 5G / 4G 等制式文字；隐藏 Wi-Fi 收发箭头；隐藏信号上下箭头 |
| 图标 | Wi-Fi 图标左右微调；手机信号图标左右微调；信号右侧加空白，推开相邻图标 |
| 双卡 | 双卡信号合并（与系统设置同步）；只显示上网卡信号 |
| 电池 | 电量百分比文字字号；电池文字上下微调 |
| 时钟 | 时钟字号；时钟上下微调 |
| 边距 | 左侧向内、右侧向内、通知图标与时钟间距 |
| 图标包 | 导入自定义图标包（zip，同名自动替换）；导出官方图标包；清除替换 |

所有滑块与开关实时生效，少数改动（如隐藏 5G、自定义图标）需重启一次系统界面。界面采用 COS17 风格设计，滑块带震动反馈。

## 兼容性

- 系统：ColorOS（Android 13 及以上）；已在 ColorOS 17（Android 17）上实测。
- 需要 LSPosed；作用域为 `com.android.systemui`。

## 下载

前往 [Releases](https://github.com/TonyMa98/hide5G/releases) 页面获取最新版本，附件包括：

| 文件 | 说明 |
|---|---|
| `hide5G-x.x.apk` | 模块安装包 |
| `icon-pack-xml.zip` | 可直接导入的图标包（应用内「上传图标包」导入） |
| `icon.ai` | 图标包设计源文件（Adobe Illustrator），供二次修改 |

## 安装

1. 安装 APK 后，在 LSPosed 管理器中启用 **hide5G** 模块。
2. 作用域勾选 **系统界面**（`com.android.systemui`）。
3. 重启系统界面或重启手机使其生效。

## 卸载 / 回滚

在 LSPosed 中取消启用 hide5G 即可，无需卸载应用。

## 构建

无 Gradle 依赖，使用 `aapt2 + javac + d8 + apksigner` 直接构建：

```bash
./build.sh
```

产物输出到 `release/`。签名使用 `~/.android/hide5g.keystore`（首次自动生成）。

## 实现说明

ColorOS 的状态栏信号图标由 Jetpack Compose 绘制，5G 字样与信号格属于同一块 Compose 内容，因此无法通过 View / Drawable / TextView 层面的 hook 拦截。

本模块改为在数据来源处处理：状态栏制式文字来自 `com.android.systemui.statusbar.pipeline.mobile.data.OplusNetworkTypeText$*` 这一族单例对象，每个对象提供 `prefix` / `suffix` 两段字符串（例如 `FiveGA` = `"5G"` + `"A"`）。模块将这些对象的 `getPrefix()` / `getSuffix()` 统一改写为空串：

- Compose 路径拿到空文本后不绘制，布局随之收窄，信号格右移贴齐；
- 旧的 TextView 路径（`SpanHelper` / `BigType` 布局）同样拿到空文本，一并覆盖。

相比直接跳过整个 composable，清空字符串不会留下渲染残影。

## 已知限制

- 下拉快捷面板顶部的运营商名「5G」走 `OplusQSCarrierTextController`，属另一条链路，本模块未覆盖。
- 无障碍朗读的 contentDescription 未作修改。

## 许可

仅供个人学习与自用。
