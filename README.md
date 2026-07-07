# migu-cloud-console-android

Android 12+ 单 APK 版咪咕云电脑控制台。

## 仓库脚本部署模式

项目已支持“仓库脚本模式”。

手机端设置中的 `脚本仓库 Raw Base URL` 填写示例：

```text
https://github.999cq.fun/https://raw.githubusercontent.com/<user>/<repo>/main/scripts
```

仓库中需要保留：

- `scripts/bootstrap.ps1`
- `scripts/models.json`

当填写该地址后，App 会下发短命令到远端 PowerShell：

1. 从仓库下载 `bootstrap.ps1`
2. 传入 `ModelKey`、`TunnelToken`、`TunnelHostname`、`Port` 等参数
3. 由仓库脚本统一完成下载、解压、启动模型、启动 tunnel

如果该地址留空，则回退到 App 当前内置脚本模式。
