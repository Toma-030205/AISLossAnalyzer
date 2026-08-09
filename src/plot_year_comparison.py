"""Create reproducible AIS vessel-count and loss-rate figures by year.

This script is the command-line, multi-year version of the final analysis
section in ``research.py``.  It reads the daily distance-loss CSV files
produced by ``ais.main.Main`` and calculates, for each 10 km distance bin:

* estimated loss rate,
* estimated loss per vessel per day, and
* unique vessel (MMSI) count.

Expected directory layout:

    outputs/yearly_loss/
      2007/type1_daily_distance_loss.csv
      2007/type5_daily_distance_loss.csv
      2007/type18_daily_distance_loss.csv
      2017/...
      2026/...

Missing year directories are skipped with a warning, so the same command can
be rerun as 2017 and 2026 results become available.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import os
import sys
import tempfile

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault(
    "MPLCONFIGDIR",
    str(Path(tempfile.gettempdir()) / "aislossanalyzer-matplotlib"),
)

try:
    import matplotlib.pyplot as plt
    import numpy as np
    import pandas as pd
except ModuleNotFoundError as exc:
    raise SystemExit(
        "Missing plotting dependencies. Install them with: "
        f"{sys.executable} -m pip install matplotlib numpy pandas"
    ) from exc


TYPE_FILES = {
    "Type 1": "type1_daily_distance_loss.csv",
    "Type 5": "type5_daily_distance_loss.csv",
    "Type 18": "type18_daily_distance_loss.csv",
}
TYPE_SLUGS = {
    "Type 1": "type1",
    "Type 5": "type5",
    "Type 18": "type18",
}
TYPE_COLORS = {
    "Type 1": "#2563EB",
    "Type 5": "#EA580C",
    "Type 18": "#059669",
}
YEAR_COLORS = [
    "#2563EB",
    "#EA580C",
    "#059669",
    "#7C3AED",
    "#DC2626",
]
WEEKDAYS = {
    "ALL",
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
}

DEFAULT_MAX_DISTANCE_KM = 80
DEFAULT_BIN_WIDTH_KM = 10


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    project_dir = script_dir.parent
    parser = argparse.ArgumentParser(
        description="Plot AIS vessel counts and estimated loss rates by year."
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=project_dir / "outputs" / "yearly_loss",
        help="Root containing one result directory per year.",
    )
    parser.add_argument(
        "--years",
        nargs="*",
        help="Years to compare. By default, numeric directories are detected.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=project_dir / "outputs" / "year_comparison",
        help="Directory for comparison CSV files and PNG figures.",
    )
    parser.add_argument(
        "--weekday",
        default="ALL",
        choices=sorted(WEEKDAYS),
        help="Use all days or one weekday.",
    )
    parser.add_argument(
        "--max-distance-km",
        type=int,
        default=DEFAULT_MAX_DISTANCE_KM,
        help="Upper edge of the plotted study range.",
    )
    parser.add_argument(
        "--bin-width-km",
        type=int,
        default=DEFAULT_BIN_WIDTH_KM,
        help="Distance-bin width.",
    )
    parser.add_argument(
        "--min-vessel-count",
        type=int,
        default=0,
        help=(
            "Mask loss metrics in distance bins with fewer vessels; "
            "zero keeps every bin."
        ),
    )
    return parser.parse_args()


def discover_years(
    data_root: Path,
    requested_years: list[str] | None,
) -> list[str]:
    if requested_years:
        candidates = [str(year) for year in requested_years]
    else:
        candidates = sorted(
            path.name
            for path in data_root.iterdir()
            if path.is_dir() and path.name.isdigit()
        ) if data_root.is_dir() else []

    available: list[str] = []
    for year in candidates:
        year_dir = data_root / year
        if not year_dir.is_dir():
            print(
                f"Warning: skipping {year}; directory not found: {year_dir}",
                file=sys.stderr,
            )
            continue
        available.append(year)

    if not available:
        raise FileNotFoundError(
            f"No year result directories found under {data_root}"
        )
    return available


def empty_daily_frame() -> "pd.DataFrame":
    return pd.DataFrame(
        columns=[
            "DATE",
            "DAY_OF_WEEK",
            "MESSAGE_TYPE",
            "MMSI",
            "DISTANCE_BIN",
            "SHIP_LENGTH",
            "OBSERVED",
            "EXPECTED",
            "LOSS",
            "LOSS_RATE",
            "AVG_DISTANCE",
            "BIN_START",
            "BIN_CENTER",
        ]
    )


def load_daily_csv(
    csv_path: Path,
    max_distance_km: int,
    bin_width_km: int,
) -> "pd.DataFrame":
    if not csv_path.is_file():
        print(
            f"Warning: missing file, treating as empty: {csv_path}",
            file=sys.stderr,
        )
        return empty_daily_frame()

    frame = pd.read_csv(csv_path)
    required = {
        "DATE",
        "DAY_OF_WEEK",
        "MMSI",
        "DISTANCE_BIN",
        "OBSERVED",
        "EXPECTED",
        "LOSS",
    }
    missing = required.difference(frame.columns)
    if missing:
        raise ValueError(
            f"{csv_path} is missing columns: {', '.join(sorted(missing))}"
        )

    frame["DATE"] = pd.to_datetime(frame["DATE"], errors="coerce")
    frame["MMSI"] = pd.to_numeric(frame["MMSI"], errors="coerce")
    for column in ("OBSERVED", "EXPECTED", "LOSS"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")

    frame["BIN_START"] = pd.to_numeric(
        frame["DISTANCE_BIN"]
        .astype(str)
        .str.replace("km", "", regex=False)
        .str.split("-")
        .str[0],
        errors="coerce",
    )
    frame = frame.dropna(
        subset=[
            "DATE",
            "MMSI",
            "BIN_START",
            "OBSERVED",
            "EXPECTED",
            "LOSS",
        ]
    ).copy()
    frame = frame[
        (frame["BIN_START"] >= 0)
        & (frame["BIN_START"] < max_distance_km)
    ].copy()
    frame["BIN_CENTER"] = frame["BIN_START"] + bin_width_km / 2
    frame["DAY_OF_WEEK"] = (
        frame["DAY_OF_WEEK"].astype(str).str.upper()
    )
    return frame


def aggregate_metrics(
    frame: "pd.DataFrame",
    weekday: str,
    max_distance_km: int,
    bin_width_km: int,
    min_vessel_count: int,
) -> "pd.DataFrame":
    selected = frame
    if weekday != "ALL":
        selected = selected[selected["DAY_OF_WEEK"] == weekday]

    if selected.empty:
        grouped = pd.DataFrame(
            columns=[
                "BIN_CENTER",
                "OBSERVED",
                "EXPECTED",
                "LOSS",
                "VESSEL_COUNT",
                "DAY_COUNT",
            ]
        )
    else:
        grouped = (
            selected.groupby("BIN_CENTER", as_index=False)
            .agg(
                OBSERVED=("OBSERVED", "sum"),
                EXPECTED=("EXPECTED", "sum"),
                LOSS=("LOSS", "sum"),
                VESSEL_COUNT=("MMSI", "nunique"),
                DAY_COUNT=("DATE", "nunique"),
            )
        )

    all_bins = pd.DataFrame(
        {
            "BIN_CENTER": np.arange(
                bin_width_km / 2,
                max_distance_km,
                bin_width_km,
            )
        }
    )
    grouped = all_bins.merge(
        grouped,
        on="BIN_CENTER",
        how="left",
    ).fillna(0)

    grouped["LOSS_RATE"] = np.where(
        grouped["EXPECTED"] > 0,
        grouped["LOSS"] / grouped["EXPECTED"] * 100,
        np.nan,
    )
    denominator = grouped["VESSEL_COUNT"] * grouped["DAY_COUNT"]
    grouped["LOSS_PER_VESSEL_PER_DAY"] = np.where(
        denominator > 0,
        grouped["LOSS"] / denominator,
        np.nan,
    )
    grouped["INSUFFICIENT_SAMPLE"] = (
        grouped["VESSEL_COUNT"] < min_vessel_count
        if min_vessel_count > 0
        else False
    )
    if min_vessel_count > 0:
        grouped.loc[
            grouped["INSUFFICIENT_SAMPLE"],
            ["LOSS_RATE", "LOSS_PER_VESSEL_PER_DAY"],
        ] = np.nan
    grouped["BIN_START"] = grouped["BIN_CENTER"] - bin_width_km / 2
    return grouped


def overall_metrics(
    frame: "pd.DataFrame",
    weekday: str,
) -> dict[str, float | int]:
    selected = frame
    if weekday != "ALL":
        selected = selected[selected["DAY_OF_WEEK"] == weekday]

    if selected.empty:
        return {
            "DAY_COUNT": 0,
            "VESSEL_COUNT": 0,
            "OBSERVED": 0,
            "EXPECTED": 0,
            "LOSS": 0,
            "LOSS_RATE": 0.0,
            "LOSS_PER_VESSEL_PER_DAY": 0.0,
        }

    day_count = int(selected["DATE"].nunique())
    vessel_count = int(selected["MMSI"].nunique())
    observed = int(selected["OBSERVED"].sum())
    expected = int(selected["EXPECTED"].sum())
    loss = int(selected["LOSS"].sum())
    denominator = vessel_count * day_count
    return {
        "DAY_COUNT": day_count,
        "VESSEL_COUNT": vessel_count,
        "OBSERVED": observed,
        "EXPECTED": expected,
        "LOSS": loss,
        "LOSS_RATE": loss / expected * 100 if expected else 0.0,
        "LOSS_PER_VESSEL_PER_DAY": (
            loss / denominator if denominator else 0.0
        ),
    }


def load_all_metrics(
    data_root: Path,
    years: list[str],
    weekday: str,
    max_distance_km: int,
    bin_width_km: int,
    min_vessel_count: int,
) -> tuple["pd.DataFrame", "pd.DataFrame"]:
    distance_rows: list["pd.DataFrame"] = []
    summary_rows: list[dict[str, object]] = []

    for year in years:
        year_dir = data_root / year
        for message_type, file_name in TYPE_FILES.items():
            frame = load_daily_csv(
                year_dir / file_name,
                max_distance_km,
                bin_width_km,
            )
            grouped = aggregate_metrics(
                frame,
                weekday,
                max_distance_km,
                bin_width_km,
                min_vessel_count,
            )
            grouped.insert(0, "TYPE", message_type)
            grouped.insert(0, "YEAR", year)
            grouped.insert(2, "WEEKDAY", weekday)
            distance_rows.append(grouped)

            summary: dict[str, object] = {
                "YEAR": year,
                "TYPE": message_type,
                "WEEKDAY": weekday,
            }
            summary.update(overall_metrics(frame, weekday))
            summary_rows.append(summary)

    return (
        pd.concat(distance_rows, ignore_index=True),
        pd.DataFrame(summary_rows),
    )


def annotate_points(
    axis: "plt.Axes",
    x_values: "pd.Series",
    y_values: "pd.Series",
    format_string: str,
) -> None:
    for x_value, y_value in zip(x_values, y_values, strict=True):
        if not np.isfinite(y_value) or y_value == 0:
            continue
        axis.annotate(
            format_string.format(y_value),
            (x_value, y_value),
            xytext=(0, 5),
            textcoords="offset points",
            ha="center",
            fontsize=7,
        )


def style_distance_axis(
    axis: "plt.Axes",
    max_distance_km: int,
    bin_width_km: int,
) -> None:
    axis.set_xlim(0, max_distance_km)
    axis.set_xticks(
        np.arange(
            0,
            max_distance_km + bin_width_km,
            bin_width_km,
        )
    )
    axis.grid(True, color="#CBD5E1", linewidth=0.8, alpha=0.8)
    axis.set_axisbelow(True)


def plot_year_type_metrics(
    distance_metrics: "pd.DataFrame",
    year: str,
    message_type: str,
    output_path: Path,
    weekday: str,
    max_distance_km: int,
    bin_width_km: int,
) -> None:
    selected = distance_metrics[
        (distance_metrics["YEAR"].astype(str) == str(year))
        & (distance_metrics["TYPE"] == message_type)
    ].sort_values("BIN_CENTER")

    fig, axes = plt.subplots(3, 1, figsize=(11, 14), sharex=True)
    color = TYPE_COLORS[message_type]
    definitions = [
        ("LOSS_RATE", "Estimated Loss Rate [%]", (0, 100), "{:.1f}"),
        (
            "LOSS_PER_VESSEL_PER_DAY",
            "Estimated Loss / Vessel / Day",
            None,
            "{:.1f}",
        ),
        ("VESSEL_COUNT", "Unique Vessel Count", None, "{:.0f}"),
    ]

    for axis, (column, ylabel, ylim, value_format) in zip(
        axes, definitions, strict=True
    ):
        if column == "VESSEL_COUNT":
            bars = axis.bar(
                selected["BIN_CENTER"],
                selected[column],
                width=bin_width_km * 0.72,
                color=color,
                alpha=0.9,
            )
            for bar in bars:
                value = bar.get_height()
                if value > 0:
                    axis.annotate(
                        f"{value:.0f}",
                        (
                            bar.get_x() + bar.get_width() / 2,
                            value,
                        ),
                        xytext=(0, 4),
                        textcoords="offset points",
                        ha="center",
                        fontsize=8,
                    )
        else:
            axis.plot(
                selected["BIN_CENTER"],
                selected[column],
                color=color,
                marker="o",
                linewidth=2.2,
            )
            annotate_points(
                axis,
                selected["BIN_CENTER"],
                selected[column],
                value_format,
            )

        axis.set_ylabel(ylabel)
        if ylim is not None:
            axis.set_ylim(*ylim)
        style_distance_axis(axis, max_distance_km, bin_width_km)

    axes[-1].set_xlabel("Distance Bin Center [km]")
    fig.suptitle(
        f"{year} {message_type} — Research Metrics ({weekday})",
        fontsize=17,
        fontweight="bold",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(output_path, dpi=210, bbox_inches="tight")
    plt.close(fig)


def plot_cross_year_loss_rate(
    distance_metrics: "pd.DataFrame",
    years: list[str],
    output_path: Path,
    weekday: str,
    max_distance_km: int,
    bin_width_km: int,
) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.8), sharey=True)
    color_by_year = {
        year: YEAR_COLORS[index % len(YEAR_COLORS)]
        for index, year in enumerate(years)
    }

    for axis, message_type in zip(axes, TYPE_FILES, strict=True):
        for year in years:
            selected = distance_metrics[
                (distance_metrics["YEAR"].astype(str) == str(year))
                & (distance_metrics["TYPE"] == message_type)
            ].sort_values("BIN_CENTER")
            axis.plot(
                selected["BIN_CENTER"],
                selected["LOSS_RATE"],
                marker="o",
                linewidth=2.1,
                color=color_by_year[year],
                label=year,
            )

        axis.set_title(message_type)
        axis.set_xlabel("Distance Bin Center [km]")
        axis.set_ylim(0, 100)
        axis.set_yticks(np.arange(0, 101, 10))
        style_distance_axis(axis, max_distance_km, bin_width_km)

    axes[0].set_ylabel("Estimated Loss Rate [%]")
    axes[-1].legend(frameon=False, title="Year")
    fig.suptitle(
        f"Estimated AIS Loss Rate by Year ({weekday})",
        fontsize=17,
        fontweight="bold",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(output_path, dpi=210, bbox_inches="tight")
    plt.close(fig)


def plot_cross_year_vessel_count(
    distance_metrics: "pd.DataFrame",
    years: list[str],
    output_path: Path,
    weekday: str,
    max_distance_km: int,
    bin_width_km: int,
) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.8))
    color_by_year = {
        year: YEAR_COLORS[index % len(YEAR_COLORS)]
        for index, year in enumerate(years)
    }
    total_width = bin_width_km * 0.78
    bar_width = total_width / len(years)

    for axis, message_type in zip(axes, TYPE_FILES, strict=True):
        for index, year in enumerate(years):
            selected = distance_metrics[
                (distance_metrics["YEAR"].astype(str) == str(year))
                & (distance_metrics["TYPE"] == message_type)
            ].sort_values("BIN_CENTER")
            offset = (
                index - (len(years) - 1) / 2
            ) * bar_width
            axis.bar(
                selected["BIN_CENTER"] + offset,
                selected["VESSEL_COUNT"],
                width=bar_width * 0.92,
                color=color_by_year[year],
                label=year,
            )

        axis.set_title(message_type)
        axis.set_xlabel("Distance Bin Center [km]")
        axis.set_ylabel("Unique Vessel Count")
        style_distance_axis(axis, max_distance_km, bin_width_km)

    axes[-1].legend(frameon=False, title="Year")
    fig.suptitle(
        f"Unique Vessel Count by Year ({weekday})",
        fontsize=17,
        fontweight="bold",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(output_path, dpi=210, bbox_inches="tight")
    plt.close(fig)


def plot_overall_year_summary(
    summary: "pd.DataFrame",
    years: list[str],
    output_path: Path,
    weekday: str,
) -> None:
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    types = list(TYPE_FILES)
    x_values = np.arange(len(types))
    total_width = 0.78
    bar_width = total_width / len(years)

    for year_index, year in enumerate(years):
        selected = (
            summary[summary["YEAR"].astype(str) == str(year)]
            .set_index("TYPE")
            .reindex(types)
        )
        offset = (
            year_index - (len(years) - 1) / 2
        ) * bar_width
        color = YEAR_COLORS[year_index % len(YEAR_COLORS)]
        axes[0].bar(
            x_values + offset,
            selected["VESSEL_COUNT"],
            width=bar_width * 0.92,
            color=color,
            label=year,
        )
        axes[1].bar(
            x_values + offset,
            selected["LOSS_RATE"],
            width=bar_width * 0.92,
            color=color,
            label=year,
        )

    axes[0].set_title("Unique vessels within 0–80 km")
    axes[0].set_ylabel("Unique Vessel Count")
    axes[1].set_title("Weighted loss rate within 0–80 km")
    axes[1].set_ylabel("Estimated Loss Rate [%]")
    axes[1].set_ylim(0, 100)

    for axis in axes:
        axis.set_xticks(x_values, types)
        axis.grid(axis="y", color="#CBD5E1", linewidth=0.8, alpha=0.8)
        axis.set_axisbelow(True)

    axes[1].legend(frameon=False, title="Year")
    fig.suptitle(
        f"AIS Year Comparison Summary ({weekday})",
        fontsize=17,
        fontweight="bold",
    )
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(output_path, dpi=210, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    args = parse_args()
    if args.max_distance_km <= 0 or args.bin_width_km <= 0:
        raise ValueError("Distance and bin width must be greater than zero")
    if args.min_vessel_count < 0:
        raise ValueError("--min-vessel-count cannot be negative")
    if args.max_distance_km % args.bin_width_km != 0:
        raise ValueError(
            "--max-distance-km must be divisible by --bin-width-km"
        )

    data_root = args.data_root.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    years = discover_years(data_root, args.years)

    distance_metrics, summary = load_all_metrics(
        data_root=data_root,
        years=years,
        weekday=args.weekday,
        max_distance_km=args.max_distance_km,
        bin_width_km=args.bin_width_km,
        min_vessel_count=args.min_vessel_count,
    )

    distance_csv = output_dir / "distance_metrics_by_year.csv"
    summary_csv = output_dir / "year_type_summary.csv"
    distance_metrics.to_csv(
        distance_csv,
        index=False,
        encoding="utf-8-sig",
    )
    summary.to_csv(
        summary_csv,
        index=False,
        encoding="utf-8-sig",
    )

    output_paths = [distance_csv, summary_csv]
    for year in years:
        for message_type in TYPE_FILES:
            output_path = (
                output_dir
                / f"{year}_{TYPE_SLUGS[message_type]}_metrics.png"
            )
            plot_year_type_metrics(
                distance_metrics,
                year,
                message_type,
                output_path,
                args.weekday,
                args.max_distance_km,
                args.bin_width_km,
            )
            output_paths.append(output_path)

    loss_comparison_path = output_dir / "loss_rate_by_year.png"
    vessel_comparison_path = output_dir / "vessel_count_by_year.png"
    overall_path = output_dir / "year_comparison_summary.png"
    plot_cross_year_loss_rate(
        distance_metrics,
        years,
        loss_comparison_path,
        args.weekday,
        args.max_distance_km,
        args.bin_width_km,
    )
    plot_cross_year_vessel_count(
        distance_metrics,
        years,
        vessel_comparison_path,
        args.weekday,
        args.max_distance_km,
        args.bin_width_km,
    )
    plot_overall_year_summary(
        summary,
        years,
        overall_path,
        args.weekday,
    )
    output_paths.extend(
        [
            loss_comparison_path,
            vessel_comparison_path,
            overall_path,
        ]
    )

    print(
        summary[
            [
                "YEAR",
                "TYPE",
                "DAY_COUNT",
                "VESSEL_COUNT",
                "OBSERVED",
                "EXPECTED",
                "LOSS",
                "LOSS_RATE",
                "LOSS_PER_VESSEL_PER_DAY",
            ]
        ].to_string(index=False)
    )
    print()
    for output_path in output_paths:
        print(output_path)


if __name__ == "__main__":
    main()
