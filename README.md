# AISLossAnalyzer

AISメッセージの受信欠落を推定し、船舶ごと・距離帯ごと・曜日ごとに `OBSERVED` と `EXPECTED` を集計するための解析プログラムです。

現時点では大阪湾周辺の固定受信点を前提に、AISの Type 1 / Type 5 / Type 18 を対象として集計します。将来的には、通信欠落の傾向をもとにシミュレーターへ発展させる想定です。

## 目的

このプログラムは、AISメッセージの実際の受信間隔と、AISの送信レート表から求めた想定受信間隔を比較して、欠落数を推定します。

主な出力は次の値です。

- `OBSERVED`: 実際に観測されたメッセージ数
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

- Type 1: Class A position report
- Type 5: Static and voyage related data
- Type 18: Class B position report

集計対象にする最小メッセージ数は、現在以下の設定です。

- Type 1: 100件以上
- Type 5: 10件以上
- Type 18: 100件以上

## 欠落推定の考え方

`ReportRateTable` でメッセージタイプ、速力、旋回状態、航行状態などから期待送信間隔を求めます。

実際の次メッセージまでの時間を `actualDelta`、期待送信間隔を `expectedDelta` として、概ね以下のように欠落数を推定します。

```text
LOSS ~= actualDelta / expectedDelta - 1
```

Type 1 / Type 18 は丸め処理を使い、Type 5 は長い送信間隔を前提に別扱いしています。

## 外れ値除外

現在は、長時間ギャップや明らかな位置飛びが全体傾向を歪めないように、以下の区間を除外します。

- 現在メッセージ距離と次メッセージ距離の差が `30km` を超える区間
- 現在メッセージから次メッセージまでの時間が `3600秒` を超える区間

時間しきい値はJavaのシステムプロパティで変更できます。

```powershell
java -Dais.maxIntervalSeconds=1800 -cp ..\bin ais.main.Main
```

上の例では、30分を超える区間を除外します。

## ビルド

PowerShellでリポジトリ直下から実行します。

```powershell
javac -d bin (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName })
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

## 主な構成

```text
src/ais/main/Main.java
  解析の入口。AISファイルの読み込み、集計、CSV出力を行う。

src/ais/parser/
  AISファイルの読み込みとNMEA/AISメッセージのデコード。

src/ais/logic/ReportRateTable.java
  AISの期待送信間隔を返すテーブル。

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
- コンソール表示の一部コメントや日本語文字列は文字化けしている箇所があります。
- 現在の解析は欠落傾向の把握が中心で、シミュレーション機能はまだ未実装です。
