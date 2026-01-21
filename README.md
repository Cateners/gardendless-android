# gardendless-android
PvZ2 Gardendless Android Port

Gardendless 安卓移植

我一共有三个 Gardendless 移植仓库。

- [gardendless](https://github.com/Cateners/gardendless)。 最初的移植，使用 Flutter， flutter_inappwebview 和 shelf_io。
- [gardendless-android](https://github.com/Cateners/gardendless-android)。 使用 Kotlin， WebView 和 WebViewAssetLoader，免除 Flutter 引擎和 HTTP 服务器的资源占用，性能更好。
- [gardendless-gecko](https://github.com/Cateners/gardendless-gecko)。 使用 Kotlin， GeckoView 和 io.ktor.server，避免部分设备 WebView 加载不了游戏。

I have a total of three Gardendless port repositories.

- [gardendless](https://github.com/Cateners/gardendless). The original port, using Flutter, flutter_inappwebview, and shelf_io.
- [gardendless-android](https://github.com/Cateners/gardendless-android). Uses Kotlin, WebView, and WebViewAssetLoader; it eliminates the resource overhead of the Flutter engine and HTTP server for better performance.
- [gardendless-gecko](https://github.com/Cateners/gardendless-gecko). Uses Kotlin, GeckoView, and io.ktor.server to prevent the game from failing to load on certain devices' WebViews.

### 使用 / Usage

Gardendless 在一些场景需要使用鼠标滚轮和右键。你可以用双指划动代替鼠标滚轮，双指单击代替鼠标右键点击。双指的中间点是光标的位置。

Gardendless requires a mouse wheel and right-click in certain scenarios. You can use a two-finger swipe to simulate the mouse wheel, and a two-finger tap to simulate a right-click. The midpoint between your two fingers represents the cursor's position.

---

### 下载 / Download

前往 [releases](https://github.com/Cateners/gardendless-android/releases) 页面，下载 Assets 内的 apk 文件。签名已暴露在仓库中（keystore.jks），这意味着任何人都可以编译一份覆盖当前版本的安装包。为了安全请仅从此处下载。

Go to the [releases](https://github.com/Cateners/gardendless-android/releases) page and download the APK file from the Assets section.The signature has been exposed in the repository (keystore.jks), which means anyone can compile an installation package that could overwrite the currently installed version. For security reasons, please download only from this source.

---

### 编译 / Compile

编译前需要先从 [原 Gardendless 游戏仓库](https://github.com/Gzh0821/pvzge_web) 下载游戏（pvzge_web-master.zip，Code -> Download ZIP）并放到 `app/src/main/assets` 文件夹，然后在 Android Studio 上正常编译即可。

Before compiling, you need to download the game from the [original Gardendless repository](https://github.com/Gzh0821/pvzge_web) (pvzge_web-master.zip, via Code -> Download ZIP). Place the file into the `app/src/main/assets` folder, and then compile it as usual in Android Studio.