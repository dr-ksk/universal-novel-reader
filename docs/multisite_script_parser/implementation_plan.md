# 実装計画: マルチサイト対応Web小説リーダー (UniversalNovelReader)

ユーザーから「一旦これで作ってください」という開始指示を受領。
最新の [設計書.md](file:///d:/Desktop/カユユの部屋/Antigravity/UniversalNovelReader/設計書.md) に基づき、Android向けマルチサイトWeb小説リーダーアプリを段階的に実装する。

---

## 1. 要求仕様とアーキテクチャの要約

1. **デュアルアダプター構成**:
   - **YAML宣言的ルール (90%)**: Jsoup + OkHttp による爆速・メモリ極小パース。
   - **JavaScript動的スクリプト (10%)**: 非表示WebView + `window.AppBridge` によるSPA/動的サイト対応。
2. **透過的キャッシュ（仕様の穴）**:
   - アプリ表向きは「目次更新チェック」のみ。YAML設定やJS呼び出しにより本文データが渡された場合に受動的に保存。
3. **ローカル特化型WebViewクリーンリーダー**:
   - 外部通信・JS遮断、日本語組版（縦書き・横書き、ルビ、傍点、禁則、縦中横）。
   - **2段階カスタマイズ**: かんたんGUI設定 ＋ カスタムCSSエディタ。
   - **スコープ階層**: アプリ全体デフォルト設定 ＋ ドメイン別上書き設定。
   - **しおり・既読**: 90%スクロール到達で自動既読、読書位置（進行率）の自動保存・復元。
   - **挿絵キャッシュ**: 本文中の `<img>` をローカル保存してオフライン表示。
4. **データ連携**:
   - CSVインポート／エクスポート（PC連携・本棚共有）。
   - URL直接登録 ＆ 未対応サイト時の案内画面（テンプレート作成、クリップボード、ファイル）。

---

## 2. 段階的実装ステップ

### ステップ1: 依存関係・通信権限の追加
- `libs.versions.toml` に以下を追加:
  - Jsoup (`org.jsoup:jsoup:1.18.3`)
  - SnakeYAML (`org.yaml:snakeyaml:2.2`)
  - OkHttp (`com.squareup.okhttp3:okhttp:4.12.0`)
  - kotlinx-serialization-json (`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`)
- `app/build.gradle.kts` にライブラリ反映。
- `AndroidManifest.xml` に `android.permission.INTERNET` を追加。
- Gradle ビルドを実行し、依存関係の解決と疎通を確認。

### ステップ2: データベース層＆ストレージ層の実装
- `com.example.universalnovelreader.data.db`:
  - `DatabaseHelper`: SQLiteOpenHelper。テーブル生成（`site_adapters`, `reader_settings`, `novels`, `episodes`）。
  - 各種DAO/リポジトリ: `SiteAdapterRepository`, `NovelRepository`, `ReaderSettingsRepository`。
- `com.example.universalnovelreader.data.storage`:
  - `FileStorageManager`: 本文キャッシュ（`novels/<id>/episodes/<ep_id>.json`）、挿絵画像（`.../images/<hash>.jpg`）、CSVエクスポート出力の管理。
- 初期起動時にプリセットサイト定義（YAML版「小説家になろう」`syosetu_standard`）を自動投入。

### ステップ3: ネットワーク＆デュアルパーサーエンジンの実装
- `com.example.universalnovelreader.core.network`:
  - `NativeHttpClient`: OkHttpラッパー。ヘッダー（Cookie: `over18=yes`, User-Agent）対応、HTML取得、画像バイト取得。
- `com.example.universalnovelreader.core.parser`:
  - `SiteAdapter`: 共通インターフェース（`parseIndex`, `parseEpisode`）。
  - `YamlJsoupAdapter`: SnakeYAMLでセレクタ構造体を読み込み、JsoupでHTMLをパース。
  - `JsWebViewAdapter`: バックグラウンドWebView ＋ `AppBridgeService`（`httpGet`, `sleep`, `isCached`, `saveNovelIndex`, `saveEpisodeContent`）で動作。
  - `SiteAdapterManager`: ドメイン正規表現による最適アダプター検索・フォールバック。

### ステップ4: CSVインポート/エクスポートとビジネスロジックの実装
- `com.example.universalnovelreader.core.csv`:
  - `CsvSyncManager`: 自由なヘッダー（URL、タイトル、作者など）を許容するCSVパーサー、および本棚CSVエクスポーター。
- `com.example.universalnovelreader.domain`:
  - `NovelSyncUseCase`: 目次更新チェック＆受動的キャッシュ処理。

### ステップ5: UIレイヤ（Jetpack Compose）の実装
- `com.example.universalnovelreader.ui`:
  - `BookshelfScreen`: 本棚（リスト/グリッド、更新チェック、FAB URL登録、CSVメニュー）。
  - `NovelDetailScreen`: 目次一覧、続きを読む（しおり復元）、未対応案内カード。
  - `ReaderScreen`: ローカルHTML WebViewリーダー、CSS変数注入、GUIクイック設定シート、前後話ナビゲーション。
  - `SettingsScreen`: 全体デフォルトリーダー設定、CSVインポート/エクスポート、キャッシュ管理。
  - `AdapterStudioScreen`: サイト定義一覧、YAML/JSエディタ、ドメイン別カスタムCSSエディタ、テスト実行。

### ステップ6: 統合検証・ビルド確認
- 単体テスト（YAMLパーサー、Jsoup抽出、CSV入出力）。
- デバッグAPKのビルドと動作確認。

---

## 3. 検証計画
- `gradlew assembleDebug` によるAPKビルド成功確認。
- 小説家になろうのテストURL（またはHTMLモック）を用いた目次パース・本文パースのユニットテスト実行。
- 生成されたAPKのパッケージング検証。
