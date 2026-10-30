# 當貝桌面重建版 V5.4.0

## 本版更新：移出資料夾後保留鄰近 APP 焦點
- 修正 GitHub Actions APK 建置時的重複桌布資源：保留 `bg_launcher_default.webp`，移除同名 JPG，避免 Android 資源合併失敗。
- 在資料夾內透過「更多操作 → 移出資料夾」將 APP 移回桌面後，不再一律跳到資料夾第一個 APP。
- 系統會依照被移出 APP 原本在資料夾中的位置，優先選取後方相鄰的 APP；若被移出的是最後一個，則選取前一個。
- 若資料夾已沒有其他 APP，關閉資料夾並提示 APP 已移回桌面。
- 不改變既有 UI、資料夾排序及 APP 安裝狀態。
- 更新版本資訊：versionCode 53、versionName 5.4.0。

## 建置方式
使用 Android Studio 開啟專案根目錄，執行 Gradle Sync，再選擇 Build > Build APK(s)。

## 驗證狀態
已檢查本版焦點選取程式碼、版本資訊、Android XML 與 Drawable 資源引用。此環境未提供完整 Android Gradle 建置工具，因此尚未執行 Kotlin 編譯、APK 建置或 Android TV 實機測試。

## 雲端 APK 建置（GitHub Actions）
專案已附上 `.github/workflows/build-apk.yml`。將完整專案推送至 GitHub 的 `main` 或 `master` 分支後，Actions 會自動安裝 JDK 17、Gradle 8.9 與 Android SDK 35，執行 `assembleDebug`，並將 `app-debug.apk` 保存為名為 `DangbeiLauncher-5.4.0-debug` 的工作流程產物。也可在 GitHub Actions 頁面手動執行 `Build Android TV APK`。

## Windows 本機建置
在已安裝 JDK 17、Gradle 8.9 與 Android SDK 35 的 Windows 電腦上，設定 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），雙擊 `build-apk-windows.bat`。成功後 APK 會輸出至 `output/DangbeiLauncher-5.4.0-debug.apk`。此批次檔不會自行安裝開發工具。

## APK 直接下載／GitHub Release
GitHub Actions 建置成功後，`workflow_dispatch` 或 main/master 推送會產生 30 天保留期的 APK Artifact。若要建立 GitHub Release 並提供長期下載，將版本標籤推送為 `v5.4.0`（例如 `git tag v5.4.0`、`git push origin v5.4.0`）；工作流程會把 `DangbeiLauncher-5.4.0-debug.apk` 附加到該 Release。此步驟仍需在 GitHub 上實際執行，當前尚未產生 APK。
