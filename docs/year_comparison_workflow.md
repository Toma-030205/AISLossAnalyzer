# June AIS year-comparison workflow

This workflow applies one loss-estimation method to the June 2007, 2017, and
2026 observations and produces the metrics used in `research.py`.

## 1. Compile the analyzer

From the project root in PowerShell:

```powershell
$sources = @(
    Get-ChildItem -Recurse src -Filter *.java |
        ForEach-Object { $_.FullName }
)
javac -encoding UTF-8 -d bin $sources
```

## 2. Produce the same daily CSV files for each year

```powershell
java -cp bin ais.main.Main `
    C:\Users\Owner\AISData\2007 `
    outputs\yearly_loss\2007

java -cp bin ais.main.Main `
    C:\Users\Owner\AISData\2017 `
    outputs\yearly_loss\2017

java -cp bin ais.main.Main `
    C:\Users\Owner\AISData\2026 `
    outputs\yearly_loss\2026
```

The loader accepts the decoded `*.csv.gz` files used in 2007 and the NMEA
`*.ais.gz` files used in 2017 and 2026. Each output directory contains the
same six distance-loss CSV files.

## 3. Generate year-specific and cross-year figures

```powershell
python src\plot_year_comparison.py --years 2007 2017 2026
```

To hide unstable loss metrics in distance bins represented by fewer than five
vessels:

```powershell
python src\plot_year_comparison.py `
    --years 2007 2017 2026 `
    --min-vessel-count 5
```

Outputs are written to `outputs/year_comparison/`:

- `YEAR_type1_metrics.png`
- `YEAR_type5_metrics.png`
- `YEAR_type18_metrics.png`
- `loss_rate_by_year.png`
- `vessel_count_by_year.png`
- `year_comparison_summary.png`
- `distance_metrics_by_year.csv`
- `year_type_summary.csv`

## Metric definitions

The definitions follow the final section of `research.py`.

- `LOSS_RATE = LOSS / EXPECTED * 100`
- `LOSS_PER_VESSEL_PER_DAY = LOSS / (VESSEL_COUNT * DAY_COUNT)`
- `VESSEL_COUNT` is the number of distinct MMSIs represented in a distance
  bin.
- `DAY_COUNT` is the number of observation dates represented in that bin.
- The overall vessel count in `year_type_summary.csv` is deduplicated across
  all 0–80 km bins, so it should not be calculated by summing bin counts.
- A distance bin with no valid expected-message intervals is plotted as
  missing, not as a 0% loss rate.

## Shared analysis settings

- Receiver: 34.718983358515715 N, 135.29057866131427 E
- Comparison range: 0–80 km
- Distance-bin width: 10 km
- Type 1 category: AIS Types 1, 2, and 3 combined
- Type 5 category: static and voyage-related reports
- Type 18 category: Class B position reports
- Broken-track and distance-jump filters use the existing
  `StreamingVesselStatistics` rules.

## Interpretation cautions

- Loss is inferred from received-message timing; it is not directly observed
  packet-loss ground truth.
- Exact duplicate decoded rows are removed within each hourly 2007 CSV file.
- The decoded 2007 Type 18 rows do not preserve the CS/SO flag. They therefore
  use the analyzer's SO/default reporting-interval rule.
- Type 5 has no position. Its distance is assigned from the latest dynamic
  position received for the same MMSI.
- The 2007 Type 18 sample is small and concentrated in part of the month.
  Those values should not be compared with later years without displaying
  the associated vessel and day counts.
