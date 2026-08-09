# AISLossAnalyzer Program Architecture

## End-to-end flow

```text
Timestamped .ais files
        |
        v
FileLoader -> AisDecoder -> normalized AisMessage objects
        |
        v
StreamingVesselStatistics
  - group by MMSI
  - retain latest vessel metadata and position
  - maintain one interval cursor per analysis category
        |
        v
ReportRateTracker + ReportRateTable
  - infer turning state
  - determine the expected transmission interval
        |
        v
Interval validation + LossEstimator
  - reject broken tracks and large distance jumps
  - estimate missing messages from actual/expected time
        |
        v
VesselStatisticsResult + DistanceBinStatistics
  - aggregate observed, expected, loss, and average distance
  - maintain whole-period and daily results
        |
        v
CSV exports -> Python figures
```

## Main components

| Component | Responsibility |
| --- | --- |
| `ais.main.Main` | Finds `.ais` files, streams them through the analysis, prints summaries, and exports six CSV files. |
| `ais.parser.FileLoader` | Reads timestamped lines, decodes NMEA/AIS content, filters target message types, and validates dynamic-message coordinates. |
| `ais.parser.AisDecoder` | Reassembles multipart messages and decodes Types 1/2/3, 5, and 18 into `AisMessage`. |
| `ais.logic.AisAnalysisRules` | Maps raw message types to three analysis categories and defines broken-track thresholds. |
| `ais.logic.ReportRateTable` | Implements expected reporting intervals for Class A, Type 5, Class B SO, and Class B CS. |
| `ais.logic.ReportRateTracker` | Estimates whether a vessel is changing course from a 30-second direction history. |
| `ais.logic.LossEstimator` | Applies the shared timing-based missing-message formula. |
| `ais.logic.DistanceCalculator` | Calculates great-circle distance using the Haversine formula. |
| `ais.stats.StreamingVesselStatistics` | Holds per-vessel state, processes valid intervals, attributes Type 5 distance, and builds whole-period/daily aggregates. |
| `ais.stats.DistanceBinStatistics` | Aggregates intervals in 10 km bands and derives expected messages and loss rate. |
| `plot_distance_comparisons.py` | Produces the distance loss-rate comparison and per-type observed/expected figures. |
| `plot_weekday_observed_expected.py` | Provides interactive weekday and message-type exploration. |

## Inputs and outputs

Current input directory, fixed in `Main.java`:

```text
C:/Users/Owner/AISData
```

Whole-period CSV files:

- `type1_distance_loss.csv`
- `type5_distance_loss.csv`
- `type18_distance_loss.csv`

Daily CSV files:

- `type1_daily_distance_loss.csv`
- `type5_daily_distance_loss.csv`
- `type18_daily_distance_loss.csv`

Important columns:

| Column | Meaning |
| --- | --- |
| `OBSERVED` | Number of valid received intervals assigned to the bin. |
| `LOSS` | Number of messages inferred to be missing within those intervals. |
| `EXPECTED` | `OBSERVED + LOSS`. |
| `LOSS_RATE` | `LOSS / EXPECTED * 100`. |
| `AVG_DISTANCE` | Mean receiver distance for intervals assigned to the bin. |

## Build, test, and visualize

```powershell
javac -d bin (Get-ChildItem -Recurse src -Filter *.java |
    ForEach-Object { $_.FullName })

$sources = @(Get-ChildItem -Recurse src,test -Filter *.java |
    ForEach-Object { $_.FullName })
javac -d test-bin $sources
java -cp test-bin ais.logic.AisCoreCalculationTest
java -cp test-bin ais.stats.AisStatisticsTest

python src/plot_distance_comparisons.py
```

## Current limitations

- The input directory and receiver coordinates are hard-coded.
- Type 5 distance is borrowed from the latest dynamic position for the vessel.
- Timing-based inference cannot distinguish propagation loss, receiver outage,
  collision, interference, data gaps, or incorrect report-rate assumptions.
- Message 16/23 assignments and Class B SO congestion-dependent interval
  changes are not reconstructed from the received stream.
- The current system estimates missing messages; it does not yet simulate
  SOTDMA slots, radio propagation, collision, garbling, or capture effects.
- Osaka Bay map visualization exists as a separate direction but is outside
  the current presentation scope.

