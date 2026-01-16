# gardendless-android
PvZ2 Gardendless Android Port

Gardendless 安卓移植

### 使用 / Usage

Gardendless 在一些场景需要使用鼠标滚轮和右键。你可以用双指划动代替鼠标滚轮，三指单击代替鼠标右键点击（点击触发的位置是第三个手指的位置。所以你可以先将两个手指按在屏幕任意位置，然后用第三个手指点击你想使用右键的位置）。

Gardendless requires the use of a mouse wheel and right-click in certain scenarios. You can use a two-finger swipe to simulate the mouse wheel, and a three-finger tap to simulate a right-click. (The click is triggered at the position of the third finger. Therefore, you can place two fingers anywhere on the screen first, then use your third finger to tap the specific location where you want to perform a right-click.)

---

### 下载 / Download

前往 [releases](https://github.com/Cateners/gardendless-android/releases) 页面，下载 Assets 内的 apk 文件。签名已暴露在仓库中（keystore.jks），这意味着任何人都可以编译一份覆盖当前版本的安装包。为了安全请仅从此处下载。

Go to the [releases](https://github.com/Cateners/gardendless-android/releases) page and download the APK file from the Assets section.The signature has been exposed in the repository (keystore.jks), which means anyone can compile an installation package that could overwrite the currently installed version. For security reasons, please download only from this source.

---

### 编译 / Compile

编译前需要先从 [原 Gardendless 游戏仓库](https://github.com/Gzh0821/pvzge_web) 下载游戏（pvzge_web-master.zip，Code -> Download ZIP）并放到 `app/src/main/assets` 文件夹，然后在 Android Studio 上正常编译即可。

Before compiling, you need to download the game from the [original Gardendless repository](https://github.com/Gzh0821/pvzge_web) (pvzge_web-master.zip, via Code -> Download ZIP). Place the file into the `app/src/main/assets` folder, and then compile it as usual in Android Studio.