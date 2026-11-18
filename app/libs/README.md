# app/libs

Sherpa-ONNX 1.13.8 的 AAR 放在这里，**不要入库**（.gitignore 已忽略 `*.aar`）。

集成第一步（用户审过 diff 后、走 GitHub Actions 打包验证前，在有 SDK 的机器上执行）：

```sh
curl -SL -o app/libs/sherpa-onnx-1.13.8.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
```

先到 https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 核对附件名与 SHA-256，
再把文件放进来。没放 AAR，`./gradlew assembleDebug` 会在依赖解析阶段失败。
