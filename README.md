# AISLossAnalyzer

AISメッセージの受信欠落を推定し、船舶ごと・距離帯ごと・曜日ごとに `OBSERVED` と `EXPECTED` を集計するための解析プログラムです。

現時点では大阪湾周辺の固定受信点を前提に、AISの Type 1/2/3 / Type 5 / Type 18 を対象として集計します。Type 1/2/3はClass A位置報告としてType 1の出力に集約します。将来的には、通信欠落の傾向をもとにシミュレーターへ発展させる想定です。

## 目的

このプログラムは、AISメッセージの実際の受信間隔と、AISの送信レート表から求めた想定受信間隔を比較して、欠落数を推定します。

主な出力は次の値です。

- `OBSERVED`: 解析条件を満たした受信区間（区間始点メッセージ）の数
- `EXPECTED`: 観測数と推定欠落数を足した想定メッセージ数
- `LOSS`: 推定欠落数
- `LOSS_RATE`: `LOSS / EXPECTED * 100`
- `AVG_DISTANCE`: その距離帯に入ったメッセージの平均距離

## 入力データ

入力は `.ais` ファイルです。

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
java -Dais.classBCsHighSpeedIntervalSeconds=15 -cp ..\bin ais.main.Main
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
java -Dais.trackGapMultiplier=5 -cp ..\bin ais.main.Main
```

上の例では最大正常送信間隔の5倍以上を航跡切断として扱います。

## ビルド

PowerShellでリポジトリ直下から実行します。

```powershell
javac -d bin (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName })
```

## テスト

製品ソースとテストを `test-bin` にコンパイルして実行します。

```powershell
$sources = @(
    Get-ChildItem -Recurse src,test -Filter *.java |
        ForEach-Object { $_.FullName }
)
javac -d test-bin $sources
java -cp test-bin ais.logic.AisCoreCalculationTest
java -cp test-bin ais.stats.AisStatisticsTest
```

## 実行

CSVを `src` 配下に出力するため、現状では `src` ディレクトリから実行します。

```powershell
cd C:\Users\Owner\AISLossAnalyzer\src
java -cp ..\bin ais.main.Main
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
cd C:\Users\Owner\AISLossAnalyzer\src
python plot_weekday_observed_expected.py
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
src/ais/main/Main.java
  解析の入口。AISファイルの読み込み、集計、CSV出力を行う。

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

- 入力ディレクトリはコード内に固定されています。
- 出力CSVの保存場所は実行時のカレントディレクトリに依存します。
- Message 16/23による個別の割当送信間隔は追跡していないため、割当モードの区間では自律モードの期待間隔を使用します。
- Class B SOの回線混雑による変更送信間隔は受信データだけでは確定できないため、通常送信間隔を使用します。
- コンソール表示の一部コメントや日本語文字列は文字化けしている箇所があります。
- 現在の解析は欠落傾向の把握が中心で、シミュレーション機能はまだ未実装です。
