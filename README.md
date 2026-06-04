# NAS随身听

NAS随身听是一款第三方私有 NAS 音乐播放器，适合将音乐文件保存在家庭 NAS 中的用户使用。它可以通过远程访问地址、FN Connect、WebDAV 等方式连接个人 NAS，浏览音乐目录并在线播放或缓存个人音乐文件。

本项目为第三方开源工具，非飞牛官方应用，未与飞牛官方建立授权、合作或从属关系。项目中提到的飞牛、fnOS、FN Connect 仅用于说明兼容场景和连接方式。

This project is an independent third-party open-source tool. It is not an official Feiniu/fnOS app and is not affiliated with, endorsed by, or authorized by Feiniu. Feiniu, fnOS, and FN Connect are mentioned only to describe compatible usage scenarios and connection methods.

## 功能特性

* 连接个人 NAS 音乐目录
* 支持 FN Connect / 远程访问地址 / WebDAV 等连接方式
* FN Connect 场景会明确提示文件访问服务和共享目录权限状态
* 支持浏览 NAS 文件夹并选择音乐目录
* 支持扫描个人音乐库
* 支持 mp3、flac、m4a、aac、wav、ogg、opus 等常见音频格式
* 支持同目录 `.lrc` 歌词显示、自动滚动和点击跳转
* 支持在线播放
* 支持后台播放
* 支持通知栏播放控制
* 支持收藏歌曲
* 支持最近播放
* 支持本地歌单
* 支持单曲缓存
* 支持浅色 / 深色主题
* 支持中文界面

## 截图

> 截图待补充

截图中不要出现真实 NAS 地址、FN ID、用户名、音乐路径或其他隐私信息。

## 使用说明

1. 在 NAS 中开启文件访问服务，例如 WebDAV。
2. 如果使用 FN Connect，请确认远程访问能力已经开启，并给当前账号授权音乐共享目录。
3. 打开 NAS随身听。
4. 输入远程访问地址或 FN Connect 相关地址。
5. 输入 NAS 用户名和密码。
6. 测试连接。
7. 登录成功后，在目录选择器中选择音乐目录。
8. 扫描音乐库。
9. 如需显示歌词，请将同名 `.lrc` 文件放在音乐文件同目录下。
10. 开始播放。

## 隐私和安全

* App 不提供云端账号系统。
* App 不会上传用户 NAS 地址、用户名、密码、音乐列表或播放记录。
* NAS 登录信息仅保存在用户本机。
* 建议使用 HTTPS。
* 不建议在不可信网络中使用明文 HTTP。
* 建议为 App 创建一个只读 NAS 账号，不要使用管理员账号。

## 构建

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Release 构建需要开发者自行配置签名文件。仓库不会包含任何 keystore 或签名配置。

当前项目仍处于开发测试阶段，建议开发者自行编译安装。暂不自动发布 APK。

## 技术栈

* Kotlin
* Jetpack Compose
* Media3 / ExoPlayer
* Room
* DataStore
* OkHttp
* Android Keystore / 加密存储
* MVVM

## 贡献

欢迎提交 Issue 和 Pull Request。请不要提交包含真实 NAS 地址、账号、密码、Token、Cookie、签名文件或个人隐私数据的内容。

## License

This project is licensed under the MIT License.
