# 万能小説 (`universal-novel-reader`)

複数の Web 小説サイトを 1 つのアプリで統合管理・オフライン閲覧できる Android 向けリーダーアプリケーションです。

- **アプリ表示名**: 万能小説
- **パッケージ / Application ID**: `com.example.universalnovelreader`
- **サイト定義カタログ＆Webビルダー（対となるリポジトリ）**: [dr-ksk/universal-novel-reader-catalog](https://github.com/dr-ksk/universal-novel-reader-catalog)
  - 公開ページ: https://dr-ksk.github.io/universal-novel-reader-catalog/

## 主な機能

- **マルチサイト対応（YAML / JavaScript アダプター）**: 小説家になろう・カクヨムをプリセット搭載。サイト定義マーケット（公式・野良）や共有コード（`UNR1:...` / `.unr.json`）で対応サイトを追加・更新可能。
- **快適な読書画面**: 左右スワイプでの前後話移動、ルビ表示、カラーテーマ（白 / セピア / ダーク / 漆黒）、フォント・文字サイズ・行間・余白・カスタム CSS 設定、オフライン本文＆挿絵キャッシュ。
- **定期更新チェック**: WorkManager によるバックグラウンド新着チェック＆プッシュ通知。
- **CSV バックアップ連携**: 本棚の CSV エクスポート／インポートに対応。

## ディレクトリ構成

- `app/` — Android Studio / Gradle プロジェクト（Kotlin + Jetpack Compose）
- `scripts/` — 参考用パーサースクリプト
- `docs/` — 開発ドキュメント・マニュアル
- `設計書.md` — 詳細設計書

## ビルド方法

```bash
cd app
./gradlew assembleDebug
```
