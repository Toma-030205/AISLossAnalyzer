# 共同開発の手順

このプロジェクトは、各自のPCで編集・テストし、GitHubのPull Request（変更の確認・取り込み依頼）で変更を共有します。

## 1. 管理者が参加者を招待する

リポジトリ: https://github.com/Toma-030205/AISLossAnalyzer

所有者のアカウントでリポジトリの `Settings` → `Collaborators` から参加者のGitHubアカウントを追加します。参加者が招待を承諾すると共同開発できます。アカウントやパスワードは各自のものを使います。

公式手順: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/inviting-collaborators-to-a-personal-repository

## 2. 参加者のPCを準備する

以下はWindows / PowerShell向けです。

- Gitをインストールする。
- 既存READMEの開発環境に合わせてJDK 26をインストールする（ビルド対象はJava 25）。`JAVA_HOME` をJDKのフォルダーに設定し、その `bin` を `PATH` に追加する。
- 好みのJava対応エディターを用意する。
- Mavenは同梱のWrapperが取得するため、別途インストール不要。初回ビルドはインターネット接続が必要。

新しく開いたPowerShellで確認します。

```powershell
git --version
java -version
javac -version
git clone https://github.com/Toma-030205/AISLossAnalyzer.git
cd AISLossAnalyzer
```

GitHubの認証を求められたら自分のアカウントで認証します。続いて、コミットに記録する名前・メールアドレスを設定します。以下の値は自分のものに置き換えます（メールはGitHubの非公開用メールアドレスも利用可能）。

```powershell
git config user.name "自分の名前"
git config user.email "自分のメールアドレス"
.\mvnw.cmd clean package
```

## 3. データを配置して起動する

AISログの `ais/` と地図データの `senc/` はGitの管理対象外です。必要なデータをプロジェクト管理者から別途受け取り、リポジトリ直下に配置します。

```powershell
.\mvnw.cmd exec:java
```

別の場所のデータを使う場合:

```powershell
.\mvnw.cmd exec:java "-Dexec.args=--senc C:\data\senc --ais-data C:\data\ais"
```

解析結果のSQLiteは既定で `%LOCALAPPDATA%\AISLossAnalyzer\data\aisloss.db` に保存され、各PCで管理します。研究データ・DB・生成したCSVや画像・認証情報はソースコードの変更に含めないでください。独自の保存先を使ったファイルが自動でGit対象外になるとは限らないため、コミット前に確認します。

## 4. 変更ごとにブランチを作る

作業開始時は未コミットの変更がないことを `git status` で確認します。変更が残っている場合は、先に現在の作業ブランチで保存します。

```powershell
git switch main
git pull --ff-only origin main
git switch -c codex/improve-map-labels
```

ブランチ名は作業内容ごとに変えます。担当者同士で変更する機能を事前に共有すると、同じファイルの競合を減らせます。

編集が終わったらテストと差分を確認します。

```powershell
.\mvnw.cmd test
git status --short
git diff
```

意図したファイルだけを追加します。以下のパスとメッセージは実際の変更に置き換えてください。

```powershell
git add src/main/java/ais/ui/変更したファイル.java
git diff --cached
git commit -m "Improve map labels"
git push -u origin codex/improve-map-labels
```

## 5. Pull Requestで確認して取り込む

1. GitHubで作業ブランチから `main` に向けたPull Requestを作成する。
2. 変更の目的、変更内容、実行したテスト、画面変更なら画像を記載する。
3. 別の参加者が差分を確認し、必要なら修正する。
4. 確認が済んだらGitHubでマージする。
5. 各PCで `git switch main` と `git pull --ff-only origin main` を実行し、取り込まれた変更を取得する。

この手順はチーム内の運用ルールです。レビュー必須などをGitHub側で強制する設定や、自動テストのワークフローは、この手順書の追加だけでは有効になりません。

## 6. 変更が競合した場合

作業ブランチで自分の変更をコミットした後に実行します。

```powershell
git fetch origin
git merge origin/main
```

競合が出た場合は担当者と内容を確認し、競合箇所を編集して `git add`、`git commit` で確定します。その後にテストを再実行し、`git push` します。解決を中断してマージ開始前に戻す場合は `git merge --abort` を使います。

共有ブランチの履歴を書き換える強制pushは通常の作業手順に含めません。

## 設計を確認する

- [基本設計書](docs/basic_design_ja.md)
- [画面詳細設計書](docs/screen_detail_design_ja.md)
- [プログラム構成設計書](docs/program_structure_design_ja.md)
- [ビルド・実行の詳細](README.md)
