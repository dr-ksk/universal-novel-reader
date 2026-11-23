# 実装内容の確認・解説 (Walkthrough)

マルチサイト対応Web小説リーダーアプリ「UniversalNovelReader」の全機能実装が完了し、デバッグ用APKのビルドに成功しました。

---

## 1. 実装のハイライト

### ① デュアル・アダプターアーキテクチャの実装
- **`YamlJsoupAdapter` (90%のサイト向け)**:
  - Jsoup + OkHttp による爆速セレクタ解析。WebViewを介さず直接HTMLテキストをパースし、メモリ消費極小・ミリ秒単位のパースを実現。
  - セレクタ置換（`regex_replace`）、属性抽出（`@href`, `@src`）、不要タグ削除（`remove`）、完結判定（`contains_text`）に対応。
  - **仕様の穴**: `crawl.auto_cache_on_sync: true` 設定時、目次更新チェックと同時に未キャッシュ話を順次ダウンロードしてキャッシュ保存。
  - **挿絵キャッシュ**: 本文中の `<img>` を検出し、ローカル（`.../images/<hash>.jpg`）へ保存＆HTML差し替え。
- **`JsWebViewAdapter` & `AppBridge` (10%のSPA/難読化サイト向け)**:
  - 非表示WebView上でJavaScriptを実行し、ネイティブ支援API（`httpGet`, `sleep`, `isCached`, `saveNovelIndex`, `saveEpisodeContent`）を提供。

### ② クリーンリーダー画面 (`ReaderScreen`) & 2段階CSSカスタマイズ
- **ローカル特化型WebViewエンジン**:
  - 外部通信・JS実行を遮断した超軽量テキストレンダラー。
  - **日本語組版**: 縦書き（`writing-mode: vertical-rl`）／横書きのワンタップ切り替え、ルビ（`<ruby>`）、傍点（`text-emphasis`）、禁則処理、縦中横。
- **2段階カスタマイズ ＆ ドメイン別上書き**:
  - **かんたん設定（GUI）**: 背景色（白/セピア/ダーク/漆黒）、文字サイズ、行間、余白。CSS変数により1フレームで即時反映。
  - **カスタムCSS**: 全体デフォルトCSS ＋ ドメイン別専用CSSのカスケード適用（例: ハーメルンだけAA用等幅フォントにするなど）。
- **しおり（読書位置復元）＆ 90%既読判定**:
  - 読書進行率（`scrollProgress`）の自動保存＆次回開いた際の自動スクロール復元。
  - 進行率が 90% 以上に達した時点で自動的に「既読」へ更新。
  - 本文最下部に「← 前の話」「次の話 →」ボタンを配置。

### ③ 本棚 ＆ 未対応サイト案内 ＆ CSV連携
- **本棚画面 (`BookshelfScreen`)**:
  - 小説一覧表示（すべて/連載中/完結タブ）、一括更新チェック、右下FABでのURL直接登録ダイアログ（クリップボード自動貼り付け）。
- **未対応ドメイン案内カード**:
  - 該当アダプターが存在しないURLを登録した際、小説詳細画面に警告カードを表示し、「定義を作成・追加（ひな形自動展開、クリップボード貼り付け）」へ誘導。
- **CSV連携 (`CsvSyncManager`)**:
  - 本棚のCSVエクスポート（共有Intent対応）および、PC作成CSVのインポート（寛容なURL抽出）。

---

## 2. 成果物一覧

### ソースコード
| ファイルパス | 役割・内容 |
| :--- | :--- |
| `data/model/Models.kt` | `SiteAdapter`, `ReaderSettings`, `Novel`, `Episode`, `EpisodeCacheData` 等のモデル定義 |
| `data/db/DatabaseHelper.kt` | SQLiteOpenHelper（`site_adapters`, `reader_settings`, `novels`, `episodes`）＆ プリセット投入 |
| `data/db/NovelRepository.kt` | 各テーブルのDAO・リポジトリ処理、ドメイン別設定のフォールバック取得 |
| `data/storage/CacheManager.kt` | 本文JSONファイルおよび挿絵画像キャッシュのローカル保存・容量計算 |
| `core/network/NativeHttpClient.kt` | OkHttpクライアント（Cookie/User-Agent対応、画像バイトダウンロード） |
| `core/parser/YamlJsoupAdapter.kt` | SnakeYAML ＋ Jsoupによる爆速宣言的ルール抽出エンジン |
| `core/parser/SiteAdapterManager.kt` | ドメイン正規表現判定、YAML/JSエンジンの振り分け、目次更新・受動的キャッシュ |
| `engine/AppBridge.kt` | JavaScript向けネイティブ支援API（`httpGet`, `sleep`, `saveNovelIndex`, `saveEpisodeContent`） |
| `engine/ScriptExecutionBridge.kt` | 非表示WebViewによる動的JSパーサー実行ブリッジ |
| `data/csv/CsvSyncManager.kt` | CSVインポート（寛容パース）＆エクスポート・共有 |
| `ui/ReaderViewModel.kt` | 全画面のビジネスロジック、状態管理、ナビゲーション |
| `ui/BookshelfScreen.kt` | 本棚画面（タブフィルタ、一括更新、URL登録FAB） |
| `ui/NovelDetailScreen.kt` | 目次詳細画面、未対応案内カード、「続きを読む」ボタン |
| `ui/ReaderScreen.kt` | ローカルWebViewリーダー、組版、2段階CSSカスタマイズ、しおり |
| `ui/SettingsScreen.kt` | 全体デフォルトCSS、CSVデータ連携、キャッシュ管理 |
| `ui/AdapterStudioScreen.kt` | サイト定義管理（YAML/JSエディタ、ドメイン別CSS、パーステスト） |
| `Navigation.kt` | 戻るボタン連動の画面ルーティング |

### ビルド成果物 (APK)
- **APKパス**: `d:\Desktop\カユユの部屋\Antigravity\UniversalNovelReader\app\app\build\outputs\apk\debug\app-debug.apk`
- **ビルドステータス**: `BUILD SUCCESSFUL`（36 actionable tasks: 18 executed, 18 up-to-date）
- **ファイルサイズ**: 約 20.1 MB

---

## 3. 次のステップ・動作確認
端末（実機またはエミュレータ）に上記 APK をインストールして動作確認を行っていただけます。
- **インストールコマンド例**:
  ```powershell
  adb install "d:\Desktop\カユユの部屋\Antigravity\UniversalNovelReader\app\app\build\outputs\apk\debug\app-debug.apk"
  ```
