# AISLossAnalyzer

新しく確定した第1段階の要件、解析仕様、データ保存、実装順序は[日本語基本設計書](docs/basic_design_ja.md)、画面一覧、操作、状態、入力検証などは[画面詳細設計書](docs/screen_detail_design_ja.md)、クラス、パッケージ、処理の受け渡し、現行コードの移行方針は[プログラム構成設計書](docs/program_structure_design_ja.md)を参照してください。現在のREADME以下は、既存解析プログラムの実装・実行方法を説明しています。

AISメッセージの受信欠落を推定し、船舶ごと・距離帯ごと・曜日ごとに `OBSERVED` と `EXPECTED` を集計するための解析プログラムです。

現時点では大阪湾周辺の固定受信点を前提に、AISのType 1/2/3/18位置報告とType 5/24船舶情報を共通入力形式へ変換します。既存CLIの集計ではType 1/2/3をClass A位置報告としてType 1の出力へ集約し、Type 5とType 18も扱います。将来的には、通信欠落の傾向をもとにシミュレーターへ発展させる想定です。

## 開発状況

反復1として、Maven Wrapper、JUnit 5、新しい不変データモデル、期待送信間隔・変針・欠落数の解析コア、現行`AisMessage`からの移行用アダプターを追加済みです。

反復2として、次の共通入力パイプラインを追加済みです。

- `!AIVDM`/`!AIVDO`の構文・チェックサム検証、複数断片の再構成
- Type 1/2/3/5/18/24のType別デコードと正規化
- 同一MMSI・Type・完成payloadを1秒以内で重複除外
- 日本時間17桁時刻の`.ais`/`.ais.gz`読込、日付カタログ、同日複数ファイルの時系列マージ
- 同名の`.ais`/`.ais.gz`で内容が同一の場合の二重読込防止と入力SHA-256
- UDP受信、UTF-8行分割、PC到着時刻の付与（初期ポート候補17020）
- 過去入力と模擬リアルタイム入力が同じ正規化結果になる一致試験

反復3として、次の欠落・鮮度・空間集計を追加済みです。

- 区間始点の状態を使う期待送信間隔、30分以上の空白・30km超の距離差・一時停止境界の除外
- 推定欠落数と推定送信時刻、UTM 53N上の直線補間位置
- 期待間隔の2倍・3倍・5倍に対応できる情報鮮度状態と鮮度違反時間
- Proj4JによるEPSG:32653投影、2km格子、0～70kmの5km距離帯
- 5分×格子×Classおよび5分×距離帯×Classの疎な集計
- 推定欠落率、鮮度違反率、期待送信数30件・異なる3隻によるデータ不足判定
- 日・曜日・月・暦年・時間帯への再集計
- 過去入力と模擬リアルタイム入力を解析結果まで比較する一致試験

反復4として、次のSQLite保存基盤を追加済みです。

- Xerial SQLite JDBC、`schema_version`による再実行可能なスキーマ移行、WAL・外部キー・busy timeout設定
- 受信局プロファイルと適用期間重複検査、解析条件プロファイルの保存・読込
- 解析実行、5分格子・距離帯集計、5分・日別の船舶存在、Type 5/24船舶情報履歴の保存
- 診断コード別件数・最初/最後の時刻、解析除外期間の保存（生NMEA、payload、全航跡は保存しない）
- 同一日・同一入力・同一受信局・同一解析条件の置換と、失敗時に旧結果を維持する一括トランザクション
- SQLite書込を直列化する単一バックグラウンドキュー
- 既定DB保存先`%LOCALAPPDATA%\AISLossAnalyzer\data\aisloss.db`

反復5として、次の地図・過去ログ再生画面を追加済みです。

- `senc`フォルダーから海岸線、陸地、河川を抽出し、Mercator投影・パン・ポインター中心ズームで描画
- 日付または`.ais`/`.ais.gz`直接選択、1/5/10/30/60/300倍再生（初期60倍）、一時停止、先頭復帰、時刻スライダー
- 後方シーク時に解析エンジンを日初から再生成し、指定時刻まで再計算
- 2km格子の鮮度違反率/推定欠落率9色表示、データ不足斜線、10km/30km圏、受信局
- 船舶の向き、鮮度塗り色、Class A/B枠色、クリック選択、選択船だけの60分航跡と詳細表示
- 格子クリック選択、分子・分母・船舶数・不足理由・受信局距離の詳細表示
- 再生位置を変えない明示的な一日分解析と、SQLiteへの一括保存

反復6として、次のリアルタイム受信・長期集計・出力を追加済みです。

- 研究室LAN向けUDP受信の開始、停止、同一セッション再開、表示リセットと受信中の画面切替防止（開始直後の停止や異常終了時の部分保存にも対応）
- 上限付き受信キュー、あふれた区間の解析カーソル切断・欠落数とは別のPC処理遅延診断、1秒単位の地図更新
- 生NMEAを保存せず、5分境界と停止・終了時に集計・診断・Type 5/24船舶情報だけをSQLiteへ保存
- 保存済み結果を選択した受信局・解析条件で分離し、日別・曜日別・月別・暦年別に再集計
- 0～70kmの5km距離帯グラフ、0～23時の時間帯グラフ、Class別線種、母数範囲とデータ不足判定を含む集計表
- 現在の表をBOM付きUTF-8 CSV、グラフをPNG、展開済み凡例と解析条件注記を含む地図をPNGとして個別保存
- リアルタイムの停止・再開・保存、複数日検索、CSV/PNG生成の自動試験

現行CLIと旧`OsakaBayMap`は回帰比較のため一時的に残しています。

## 目的

このプログラムは、AISメッセージの実際の受信間隔と、AISの送信レート表から求めた想定受信間隔を比較して、欠落数を推定します。

主な出力は次の値です。

- `OBSERVED`: 解析条件を満たした受信区間（区間始点メッセージ）の数
- `EXPECTED`: 観測数と推定欠落数を足した想定メッセージ数
- `LOSS`: 推定欠落数
- `LOSS_RATE`: `LOSS / EXPECTED * 100`
- `AVG_DISTANCE`: その距離帯に入ったメッセージの平均距離

## 入力データ

新しい共通入力層は`.ais`、`.ais.gz`、UDPを扱います。過去ログの各行は、研究室ログの形式に合わせて`yyyyMMddHHmmssSSS`の日本時間、空白、NMEA文の順とします。

```text
20260904093000123 !AIVDM,1,1,,A,...*HH
```

UDPはUTF-8の`!AIVDM`/`!AIVDO`を受け取り、データグラム内に複数行があれば行単位に分けます。ポートは`UdpSourceConfig`で変更でき、初期候補値は17020です。

以下は、移行期間中の既存CLIに限った入力方法です。

現在のメインプログラムでは、入力ディレクトリが以下に固定されています。

```text
C:/Users/Owner/AISData
```

このディレクトリ内の `.ais` ファイルをファイル名順に読み込みます。

## 解析対象

対象メッセージタイプは以下です。

- Type 1/2/3: Class A position report（Type 1として集計）
- Type 5: Static and voyage related data
- Type 18: Class B position report

集計対象にする最小メッセージ数は、現在以下の設定です。

- Type 1: 100件以上
- Type 5: 10件以上
- Type 18: 100件以上

## 欠落推定の考え方

`ReportRateTable` でメッセージタイプ、速力、旋回状態、航行状態、Class BのSO/CS方式から期待送信間隔を求めます。

同じ船舶から受信した、同じ解析カテゴリの次メッセージまでの時間を `actualDelta`、期待送信間隔を `expectedDelta` として、概ね以下のように欠落数を推定します。

- Type 1: 次のType 1/2/3まで
- Type 5: 次のType 5まで
- Type 18: 次のType 18まで

すべてのメッセージタイプで、ミリ秒精度の実測時間を丸めずに次の共通式へ渡します。

```text
estimatedTransmissions = round(actualDelta / expectedDelta)
LOSS = max(0, estimatedTransmissions - 1)
```

例えばType 5の期待間隔360秒に対して361秒ならLOSSは0、540秒以上ならLOSSは1です。

### 期待送信間隔

- Type 1/2/3: 航行状態とSOGにより2～180秒。旋回中は2秒または10/3秒
- Type 5: 360秒
- Type 18 Class B SO: SOGと旋回状態により5～180秒
- Type 18 Class B CS: 2kt以下は180秒、2kt超は既定で30秒

旋回状態は、受信AISから再現できる近似として、現在方位と過去30秒の受信方位平均との差が5度を超えた場合に開始し、5度以下の状態が20秒を超えて続くまで維持します。真方位（HDG）を優先し、利用できない場合のみ2kt超でCOGを使用します。

Class B CSは規格世代差があります。既定値は実データとITU-R M.1371-5に合わせて高速時も30秒です。M.1371-6の14kt超15秒を評価する場合は、次のように変更できます。

```powershell
java -Dais.classBCsHighSpeedIntervalSeconds=15 -cp target\classes ais.main.Main
```

## 外れ値除外

現在は、長時間ギャップや明らかな位置飛びが全体傾向を歪めないように、以下の区間を除外します。

- 現在メッセージ距離と次メッセージ距離の差が `30km` を超える区間
- 実測間隔が「メッセージ種別の最大正常送信間隔 × 10」以上の区間

10倍以上の区間は通信欠落として加算せず、「航跡または観測セッションが途切れた区間」として扱います。現在のしきい値は以下のとおりです。

| 解析カテゴリ | 最大正常送信間隔 | 航跡切断のしきい値 |
| --- | ---: | ---: |
| Type 1/2/3 | 180秒 | 1800秒（30分） |
| Type 5 | 360秒 | 3600秒（1時間） |
| Type 18 | 180秒 | 1800秒（30分） |

倍率はJavaのシステムプロパティで変更できます。

```powershell
java -Dais.trackGapMultiplier=5 -cp target\classes ais.main.Main
```

上の例では最大正常送信間隔の5倍以上を航跡切断として扱います。

## ビルド

JDK 26を使用し、Java 25互換のクラスを生成します。Mavenの事前インストールは不要です。PowerShellでリポジトリ直下から実行してください。

```powershell
.\mvnw.cmd clean package
```

初回だけ、固定したMaven本体とテスト用ライブラリをダウンロードします。生成物は`target`に出力されます。

## テスト

JUnitテスト、新旧解析コアの一致テスト、既存のmain形式回帰テストをまとめて実行します。

```powershell
.\mvnw.cmd test
```

## 実行

新GUIはリポジトリ直下から次のコマンドで起動します。既定では`./senc`と`./ais`を参照します。

```powershell
.\mvnw.cmd exec:java
```

別のデータ場所またはSQLiteファイルを使う場合は次のように指定できます。

```powershell
.\mvnw.cmd exec:java "-Dexec.args=--senc C:\data\senc --ais-data C:\data\ais --database C:\data\aisloss.db"
```

画面を開かずにSENC・AISログ・SQLiteの初期化だけを確認する場合:

```powershell
.\mvnw.cmd exec:java "-Dexec.args=--validate-only"
```

現行の解析用CLIを比較確認のために実行する場合は、ビルド後に次を実行します。

```powershell
Push-Location src
java -cp ..\target\classes ais.main.Main
Pop-Location
```

実行すると、コンソールにメッセージタイプ別の概要が表示され、CSVが出力されます。

## 出力CSV

船舶ごと・距離帯ごとの集計:

- `type1_distance_loss.csv`
- `type5_distance_loss.csv`
- `type18_distance_loss.csv`

日付・曜日・船舶・距離帯ごとの集計:

- `type1_daily_distance_loss.csv`
- `type5_daily_distance_loss.csv`
- `type18_daily_distance_loss.csv`

距離帯は10km刻みです。

## 可視化

`src/plot_weekday_observed_expected.py` で、曜日別に `OBSERVED` と `EXPECTED` を距離帯ごとに比較できます。

必要なPythonライブラリ:

- pandas
- matplotlib
- numpy

実行例:

```powershell
python src\plot_weekday_observed_expected.py
```

グラフ上のラジオボタンで、メッセージタイプと曜日を切り替えられます。

距離帯別の `LOSS_RATE` 比較と、各メッセージタイプの `OBSERVED` / `EXPECTED`
比較を静的なPNGとして出力する場合は、次を実行します。

```powershell
python src/plot_distance_comparisons.py
```

既定の出力先は `outputs/distance_comparisons/` です。入力・出力ディレクトリを
変更する場合は `--input-dir` と `--output-dir` を指定できます。

## 主な構成

```text
pom.xml / mvnw.cmd
  Java 25互換ビルド、JUnit、固定Mavenバージョンを管理する。

src/main/java/ais/domain/
  位置報告、船舶情報、受信局、解析条件などの不変データモデル。

src/main/java/ais/analysis/
  期待送信間隔、変針、区間除外、欠落位置、鮮度、船舶状態を統括する解析エンジン。

src/main/java/ais/spatial/
  Haversine距離、UTM 53N投影、2km格子、5km距離帯、航跡の時間配分。

src/main/java/ais/aggregate/
  5分×空間×Class集計、船舶数、率・データ不足判定、期間別再集計。

src/main/java/ais/input/, ais/nmea/, ais/decode/
  過去ログとUDPに共通する受信、検証、断片再構成、Type別デコード、重複除外。

src/main/java/ais/input/history/, ais/input/live/
  日別ログのストリーミング読込とUDP受信。それぞれ同じ共通入力へ接続する。

src/main/java/ais/storage/
  SQLiteスキーマ移行、プロファイル・解析実行・集計・船舶情報・診断の永続化、置換トランザクション、単一書込キュー。

src/main/java/ais/map/
  SENCカタログ・読込、Mercator投影、viewport、船舶・格子のクリック判定。

src/main/java/ais/app/, ais/ui/, ais/ui/viewmodel/
  過去ログ再生・後方再計算・全日保存、リアルタイムセッション、長期集計、メイン画面、地図描画、操作・詳細パネル、Swing非依存ViewModel。

src/main/java/ais/export/
  表CSV、JFreeChartによるグラフPNG、凡例付き地図PNG、出力ファイル名と注記。

src/main/java/ais/decode/LegacyMessageAdapter.java
  現行AisMessageを新しい正規化イベントへ変換する移行用アダプター。

src/ais/main/Main.java
  移行期間だけ保持する旧解析CLI。新旧結果の一致確認後に削除する。

src/ais/parser/
  AISファイルの読み込みとNMEA/AISメッセージのデコード。

src/ais/logic/ReportRateTable.java
  AISの期待送信間隔を返すテーブル。

src/ais/logic/ReportRateTracker.java
  過去30秒の方位から旋回状態を管理する。

src/ais/logic/LossEstimator.java
  全メッセージタイプ共通の欠落数計算。

src/ais/logic/AisAnalysisRules.java
  解析カテゴリと航跡切断の10倍ルールを管理する。

src/ais/stats/StreamingVesselStatistics.java
  ストリーミング形式で船舶ごとの欠落統計を集計する中心処理。

src/ais/stats/VesselStatisticsResult.java
  集計結果と距離帯別統計を保持する。

src/plot_weekday_observed_expected.py
  出力CSVを使った曜日別グラフ表示。
```

## 現時点の注意点

- 既存CLIの入力ディレクトリはコード内に固定されています。新GUIの過去ログは日付一覧またはファイル選択を使います。
- 新GUIのCSV/PNG保存先は保存ダイアログで選択します。既存CLIのCSVだけは実行時のカレントディレクトリへ出力します。
- リアルタイムUDPの初期ポートは17020です。バインド先とポートを画面から変更する設定画面は後続反復で接続します。
- 長時間連続運転時の実受信レート、メモリ使用量、SQLite容量は研究室PCと実回線での性能試験が必要です。
- Message 16/23による個別の割当送信間隔は追跡していないため、割当モードの区間では自律モードの期待間隔を使用します。
- Class B SOの回線混雑による変更送信間隔は受信データだけでは確定できないため、通常送信間隔を使用します。
- コンソール表示の一部コメントや日本語文字列は文字化けしている箇所があります。
- 現在の解析は欠落傾向の把握が中心で、シミュレーション機能はまだ未実装です。
