# イヤホン探すんだホン

Androidスマートフォンに接続中のBluetoothイヤホンを、探索音で家の中から探すためのアプリです。

## 初期MVP
- 接続中のBluetoothイヤホンを検出
- 探索音を再生
- 音量を30% → 50% → 70% → 100%と段階的に上昇
- 停止時に開始前のメディア音量へ復元
- Bluetooth切断時に探索音を停止

## 開発環境
- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- JDK 17
- compileSdk / targetSdk: 36
- minSdk: 26

GitHub Actionsでdebug APKを自動ビルドします。

## 今後追加予定
- RSSIによる「遠い / 近い / かなり近い」表示
- 最終接続時刻の記録
- 置き忘れ通知
