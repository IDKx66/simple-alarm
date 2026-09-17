# 发布规则

每个正式版本只创建一次版本提交，提交信息使用：

`Release v版本号: 简短说明`

发布时同步完成：

1. 提高 `versionCode` 和 `versionName`。
2. 编译并验证 APK、签名、SDK 和包对齐。
3. 更新 `README.md`、`update-example.json` 和安装包。
4. 把该版本的全部改动合并为一次提交。

签名密钥、密码、构建缓存和本地配置不得提交到仓库。
