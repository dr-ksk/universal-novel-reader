# Implementation Plan - UniversalNovelReader

## Goal Description
UniversalNovelReaderをAndroidアプリとして開発します。
Flutterフレームワークを使用し、WebViewを活用した拡張性の高いリーダーアプリを目指します。

## User Review Required
> [!IMPORTANT]
> - Flutter (Dart) での開発で問題ないか確認が必要です。
> - Windows環境でのAndroidビルド環境の構築が必要です。
>
> ### 開発環境の選択 (Windows Native vs WSL2)
> Androidアプリ開発における環境選択の比較検討資料です。どちらで進めるか決定が必要です。
>
> | 特徴 | **1. Windowsネイティブ (推奨)** | **2. WSL2 (Ubuntu等)** |
> | :--- | :--- | :--- |
> | **セットアップ** | **簡単**。<br>インストーラーを実行するだけでほぼ完了。 | **やや複雑**。<br>WSL側のセットアップに加え、Android SDKのパス設定やWindows側との連携設定が必要。 |
> | **Android Studio** | 問題なく動作。Emulatorとの連携もスムーズ。 | WSLgでGUI起動は可能だが、Windows側で起動する運用が一般的。<br>その場合、ファイルアクセス権限などで少し工夫が必要。 |
> | **エミュレータ** | 高速に動作。 | Windows側でエミュレータを起動し、WSLからADB接続する設定が必要（手間がかかる）。 |
> | **実機デバッグ** | USB接続するだけで認識。 | USBデバイスをWSLにパススルーする設定(`usbipd`)が必要で、**接続が不安定になりがち**。 |
> | **ビルド速度** | 普通。 | ファイルシステムがLinuxネイティブなので、ディスクI/Oは高速。<br>ただしWindows領域のファイルにアクセスすると激遅になる。 |
> | **コマンドライン** | PowerShell/Command Prompt。<br>Unixコマンドを使いたい場合はGit Bash等が必要。 | **Linuxコマンドがフルに使える**。<br>`grep`, `sed`, `awk` などに慣れているなら快適。 |
> | **総評** | **Android開発初心者〜中級者向け。**<br>トラブルが少なく、公式ドキュメント通りに進められる。 | **Linuxヘビーユーザー向け。**<br>環境構築自体のトラブルシューティングを楽しめる、またはWebバックエンド開発と並行する場合に有利。 |

## Proposed Changes
### Documentation
#### [MODIFY] [設計書.md](file:///d:/Desktop/カユユの部屋/Antigravity/UniversalNovelReader/設計書.md)
- Androidアプリ向けの要件定義、Flutter技術スタック、データ構造案を反映。

### Project Structure (Planned)
- `lib/`: Flutterソースコード
    - `models/`: データモデル
    - `screens/`: UI画面
    - `services/`: ロジック（DB、Webアクセスなど）
    - `utils/`: ユーティリティ

## Verification Plan
### Environment Setup
- `flutter doctor` コマンドで環境に問題がないか確認。
- Androidエミュレータまたは実機でのビルドと起動確認。

### Manual Verification
- 実機/エミュレータにて、指定したURL（なろう等）が開けるか。
- CSS/JSが注入され、表示が変わるか確認。
- オフライン状態でキャッシュが表示されるか確認。
