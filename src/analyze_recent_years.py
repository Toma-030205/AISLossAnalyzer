"""Aggregate post-relocation AIS loss outputs at daily to yearly scales."""

from __future__ import annotations

import argparse
import os
import tempfile
from pathlib import Path

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault(
    "MPLCONFIGDIR", str(Path(tempfile.gettempdir()) / "aislossanalyzer-mpl")
)

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


TYPE_FILES = {
    1: "type1_daily_distance_loss.csv",
    5: "type5_daily_distance_loss.csv",
    18: "type18_daily_distance_loss.csv",
}
TYPE_COLORS = {1: "#2563EB", 5: "#EA580C", 18: "#059669"}
YEAR_COLORS = {2023: "#2563EB", 2024: "#EA580C", 2025: "#059669", 2026: "#7C3AED"}
MAX_DISTANCE_KM = 80


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--years", nargs="+", type=int, default=[2023, 2024, 2025, 2026])
    parser.add_argument("--matched-end", default="07-31")
    return parser.parse_args()


def setup_plotting() -> None:
    plt.rcParams.update(
        {
            "font.family": ["Yu Gothic", "Meiryo", "DejaVu Sans"],
            "font.size": 10,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "figure.dpi": 150,
            "savefig.dpi": 220,
        }
    )


def load_type(path: Path, year: int, message_type: int) -> pd.DataFrame:
    frame = pd.read_csv(path)
    frame["DATE"] = pd.to_datetime(frame["DATE"], errors="coerce")
    for column in ("MMSI", "OBSERVED", "EXPECTED", "LOSS"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    frame["BIN_START"] = pd.to_numeric(
        frame["DISTANCE_BIN"].astype(str).str.extract(r"^([0-9.]+)")[0],
        errors="coerce",
    )
    frame = frame[
        frame["DATE"].notna()
        & frame["MMSI"].notna()
        & frame["BIN_START"].between(0, MAX_DISTANCE_KM - 0.001)
    ].copy()
    frame["YEAR"] = year
    frame["MESSAGE_TYPE"] = message_type
    return frame


def safe_rate(loss: pd.Series | float, expected: pd.Series | float):
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(np.asarray(expected) > 0, np.asarray(loss) / np.asarray(expected) * 100, np.nan)


def aggregate_period(frame: pd.DataFrame, keys: list[str]) -> pd.DataFrame:
    totals = (
        frame.groupby(keys, observed=True)
        .agg(
            OBSERVED=("OBSERVED", "sum"),
            EXPECTED=("EXPECTED", "sum"),
            LOSS=("LOSS", "sum"),
            VESSELS=("MMSI", "nunique"),
            ACTIVE_DAYS=("DATE", "nunique"),
        )
        .reset_index()
    )
    totals["LOSS_RATE_PCT"] = safe_rate(totals["LOSS"], totals["EXPECTED"])
    return totals


def build_metrics(data_root: Path, years: list[int], matched_end: str):
    frames: list[pd.DataFrame] = []
    input_summaries: list[pd.DataFrame] = []
    for year in years:
        year_dir = data_root / str(year)
        for message_type, filename in TYPE_FILES.items():
            path = year_dir / filename
            if path.is_file():
                frames.append(load_type(path, year, message_type))
        summary_path = year_dir / "input_file_summary.csv"
        if summary_path.is_file():
            summary = pd.read_csv(summary_path)
            summary["YEAR"] = year
            summary["DATE"] = pd.to_datetime(
                summary["FILE_NAME"].astype(str).str.extract(r"^(\d{6})")[0],
                format="%y%m%d",
                errors="coerce",
            )
            input_summaries.append(summary)
    if not frames:
        raise FileNotFoundError(f"No daily result files under {data_root}")
    raw = pd.concat(frames, ignore_index=True)
    raw["MONTH"] = raw["DATE"].dt.to_period("M").astype(str)
    iso = raw["DATE"].dt.isocalendar()
    raw["ISO_WEEK"] = iso["year"].astype(str) + "-W" + iso["week"].astype(str).str.zfill(2)
    raw["DAY_OF_WEEK_NO"] = raw["DATE"].dt.dayofweek

    daily = aggregate_period(raw, ["YEAR", "DATE", "MESSAGE_TYPE"])
    daily["MONTH"] = daily["DATE"].dt.to_period("M").astype(str)
    daily["LOSS_PER_VESSEL"] = daily["LOSS"] / daily["VESSELS"].replace(0, np.nan)
    daily["EXPECTED_PER_VESSEL"] = daily["EXPECTED"] / daily["VESSELS"].replace(0, np.nan)
    daily = daily.sort_values(["MESSAGE_TYPE", "DATE"])
    daily["LOSS_RATE_7D_PCT"] = daily.groupby("MESSAGE_TYPE")["LOSS_RATE_PCT"].transform(
        lambda s: s.rolling(7, min_periods=4).mean()
    )
    daily["LOSS_RATE_30D_PCT"] = daily.groupby("MESSAGE_TYPE")["LOSS_RATE_PCT"].transform(
        lambda s: s.rolling(30, min_periods=15).mean()
    )

    weekly = aggregate_period(raw, ["YEAR", "ISO_WEEK", "MESSAGE_TYPE"])
    monthly = aggregate_period(raw, ["YEAR", "MONTH", "MESSAGE_TYPE"])
    annual_available = aggregate_period(raw, ["YEAR", "MESSAGE_TYPE"])

    matched_parts = []
    for year in years:
        end = pd.Timestamp(f"{year}-{matched_end}")
        matched_parts.append(raw[(raw["YEAR"] == year) & (raw["DATE"] <= end)])
    matched_raw = pd.concat(matched_parts, ignore_index=True)
    annual_matched = aggregate_period(matched_raw, ["YEAR", "MESSAGE_TYPE"])
    distance_matched = aggregate_period(
        matched_raw, ["YEAR", "MESSAGE_TYPE", "DISTANCE_BIN", "BIN_START"]
    )
    weekday = aggregate_period(
        raw.assign(WEEKDAY=raw["DATE"].dt.day_name()),
        ["YEAR", "MESSAGE_TYPE", "DAY_OF_WEEK_NO", "WEEKDAY"],
    )

    daily_vessels_all = (
        raw[["YEAR", "DATE", "MMSI"]].drop_duplicates().groupby(["YEAR", "DATE"]).size()
        .rename("VESSELS").reset_index()
    )
    daily_all = aggregate_period(raw, ["YEAR", "DATE"]).merge(
        daily_vessels_all, on=["YEAR", "DATE"], suffixes=("_TYPE_SUM", "")
    )
    daily_all["MONTH"] = daily_all["DATE"].dt.to_period("M").astype(str)
    daily_all = daily_all.sort_values("DATE")
    daily_all["LOSS_RATE_30D_PCT"] = daily_all["LOSS_RATE_PCT"].rolling(30, min_periods=15).mean()

    monthly_daily = (
        daily.groupby(["YEAR", "MONTH", "MESSAGE_TYPE"], observed=True)
        .agg(
            MEAN_DAILY_VESSELS=("VESSELS", "mean"),
            MEDIAN_DAILY_LOSS_RATE_PCT=("LOSS_RATE_PCT", "median"),
            DAILY_LOSS_RATE_Q1_PCT=("LOSS_RATE_PCT", lambda s: s.quantile(0.25)),
            DAILY_LOSS_RATE_Q3_PCT=("LOSS_RATE_PCT", lambda s: s.quantile(0.75)),
        )
        .reset_index()
    )
    monthly = monthly.merge(monthly_daily, on=["YEAR", "MONTH", "MESSAGE_TYPE"], how="left")

    duplicates = pd.concat(input_summaries, ignore_index=True) if input_summaries else pd.DataFrame()
    if not duplicates.empty:
        duplicates["DUPLICATE_RATE_PCT"] = safe_rate(
            duplicates["EXACT_DUPLICATE_ROWS"], duplicates["TOTAL_ROWS"]
        )
        duplicates["MONTH"] = duplicates["DATE"].dt.to_period("M").astype(str)
    return {
        "raw": raw,
        "daily": daily,
        "daily_all": daily_all,
        "weekly": weekly,
        "monthly": monthly,
        "annual_available": annual_available,
        "annual_matched": annual_matched,
        "distance_matched": distance_matched,
        "weekday": weekday,
        "duplicates": duplicates,
    }


def add_trends(metrics: dict[str, pd.DataFrame]) -> pd.DataFrame:
    rows = []
    daily = metrics["daily"]
    for (year, message_type), group in daily.groupby(["YEAR", "MESSAGE_TYPE"]):
        group = group.dropna(subset=["LOSS_RATE_PCT"]).sort_values("DATE")
        x = (group["DATE"] - group["DATE"].min()).dt.days.to_numpy(dtype=float)
        y = group["LOSS_RATE_PCT"].to_numpy(dtype=float)
        slope = np.polyfit(x, y, 1)[0] * 30 if len(group) >= 2 else np.nan
        corr = group["EXPECTED"].corr(group["LOSS_RATE_PCT"], method="spearman")
        rows.append(
            {
                "YEAR": year,
                "MESSAGE_TYPE": message_type,
                "MONTHLY_TREND_PP": slope,
                "SPEARMAN_EXPECTED_VS_LOSS_RATE": corr,
                "DAILY_LOSS_RATE_MEAN_PCT": y.mean() if len(y) else np.nan,
                "DAILY_LOSS_RATE_SD_PCT": y.std(ddof=1) if len(y) > 1 else np.nan,
            }
        )
    return pd.DataFrame(rows)


def save_tables(metrics: dict[str, pd.DataFrame], output_dir: Path) -> None:
    table_dir = output_dir / "tables"
    table_dir.mkdir(parents=True, exist_ok=True)
    for name, frame in metrics.items():
        if name == "raw":
            continue
        frame.to_csv(table_dir / f"{name}.csv", index=False, encoding="utf-8-sig")


def savefig(fig, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def make_figures(metrics: dict[str, pd.DataFrame], output_dir: Path) -> None:
    figure_dir = output_dir / "figures"
    figure_dir.mkdir(parents=True, exist_ok=True)
    monthly = metrics["monthly"].copy()
    monthly["MONTH_DATE"] = pd.to_datetime(monthly["MONTH"] + "-01")

    fig, axes = plt.subplots(3, 1, figsize=(11, 10), sharex=True)
    for ax, message_type in zip(axes, TYPE_FILES):
        part = monthly[monthly["MESSAGE_TYPE"] == message_type]
        for year, group in part.groupby("YEAR"):
            ax.plot(group["MONTH_DATE"], group["LOSS_RATE_PCT"], marker="o", ms=3,
                    color=YEAR_COLORS.get(year), label=str(year))
        ax.set_ylabel(f"Type {message_type}\n欠落率 (%)")
        ax.grid(alpha=0.25)
    axes[0].legend(ncol=4, frameon=False)
    axes[-1].set_xlabel("月")
    fig.suptitle("月別の推定欠落率（80 km以内）", y=1.01, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "01_monthly_loss_rate.png")

    fig, axes = plt.subplots(3, 1, figsize=(11, 10), sharex=True)
    for ax, message_type in zip(axes, TYPE_FILES):
        part = monthly[monthly["MESSAGE_TYPE"] == message_type]
        for year, group in part.groupby("YEAR"):
            ax.plot(group["MONTH_DATE"], group["MEAN_DAILY_VESSELS"], marker="o", ms=3,
                    color=YEAR_COLORS.get(year), label=str(year))
        ax.set_ylabel(f"Type {message_type}\n1日平均船舶数")
        ax.grid(alpha=0.25)
    axes[0].legend(ncol=4, frameon=False)
    axes[-1].set_xlabel("月")
    fig.suptitle("月別の1日平均受信船舶数（80 km以内）", y=1.01, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "02_monthly_vessel_count.png")

    fig, axes = plt.subplots(3, 1, figsize=(11, 10), sharex=True)
    daily = metrics["daily"]
    for ax, message_type in zip(axes, TYPE_FILES):
        part = daily[daily["MESSAGE_TYPE"] == message_type]
        for year, group in part.groupby("YEAR"):
            ax.plot(group["DATE"], group["LOSS_RATE_30D_PCT"], lw=1.6,
                    color=YEAR_COLORS.get(year), label=str(year))
        ax.set_ylabel(f"Type {message_type}\n30日平均 (%)")
        ax.grid(alpha=0.25)
    axes[0].legend(ncol=4, frameon=False)
    axes[-1].set_xlabel("日")
    fig.suptitle("日別推定欠落率の30日移動平均", y=1.01, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "03_daily_loss_rate_30d.png")

    distance = metrics["distance_matched"]
    fig, axes = plt.subplots(1, 3, figsize=(14, 4.5), sharey=False)
    for ax, message_type in zip(axes, TYPE_FILES):
        part = distance[distance["MESSAGE_TYPE"] == message_type]
        for year, group in part.groupby("YEAR"):
            group = group.sort_values("BIN_START")
            ax.plot(group["BIN_START"] + 5, group["LOSS_RATE_PCT"], marker="o",
                    color=YEAR_COLORS.get(year), label=str(year))
        ax.set_title(f"Type {message_type}")
        ax.set_xlabel("受信局からの距離 (km)")
        ax.set_ylabel("推定欠落率 (%)")
        ax.grid(alpha=0.25)
    axes[0].legend(frameon=False)
    fig.suptitle("同期間（1～7月）の距離帯別推定欠落率", y=1.03, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "04_distance_loss_matched.png")

    matched = metrics["annual_matched"]
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    width = 0.2
    years = sorted(matched["YEAR"].unique())
    x = np.arange(len(years))
    for offset, message_type in enumerate(TYPE_FILES):
        part = matched[matched["MESSAGE_TYPE"] == message_type].set_index("YEAR").reindex(years)
        axes[0].bar(x + (offset - 1) * width, part["LOSS_RATE_PCT"], width,
                    label=f"Type {message_type}", color=TYPE_COLORS[message_type])
        axes[1].bar(x + (offset - 1) * width, part["VESSELS"], width,
                    label=f"Type {message_type}", color=TYPE_COLORS[message_type])
    for ax in axes:
        ax.set_xticks(x, years)
        ax.grid(axis="y", alpha=0.25)
    axes[0].set_ylabel("推定欠落率 (%)")
    axes[1].set_ylabel("期間内ユニーク船舶数")
    axes[0].set_title("欠落率")
    axes[1].set_title("船舶数")
    axes[0].legend(frameon=False)
    fig.suptitle("同期間（1～7月）の年度比較", y=1.02, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "05_matched_period_overview.png")

    fig, axes = plt.subplots(1, 3, figsize=(14, 4.5))
    daily = metrics["daily"]
    for ax, message_type in zip(axes, TYPE_FILES):
        part = daily[daily["MESSAGE_TYPE"] == message_type]
        for year, group in part.groupby("YEAR"):
            ax.scatter(group["EXPECTED"], group["LOSS_RATE_PCT"], s=8, alpha=0.35,
                       color=YEAR_COLORS.get(year), label=str(year))
        ax.set_title(f"Type {message_type}")
        ax.set_xlabel("1日の推定送信機会数")
        ax.set_ylabel("推定欠落率 (%)")
        ax.grid(alpha=0.2)
    axes[0].legend(frameon=False, markerscale=2)
    fig.suptitle("受信負荷と日別推定欠落率", y=1.03, fontsize=15, fontweight="bold")
    savefig(fig, figure_dir / "06_load_vs_loss.png")

    duplicates = metrics["duplicates"]
    if not duplicates.empty:
        monthly_dup = (
            duplicates.groupby(["YEAR", "MONTH"], observed=True)
            .agg(TOTAL_ROWS=("TOTAL_ROWS", "sum"), DUPLICATES=("EXACT_DUPLICATE_ROWS", "sum"))
            .reset_index()
        )
        monthly_dup["DUPLICATE_RATE_PCT"] = safe_rate(
            monthly_dup["DUPLICATES"], monthly_dup["TOTAL_ROWS"]
        )
        monthly_dup["MONTH_DATE"] = pd.to_datetime(monthly_dup["MONTH"] + "-01")
        fig, ax = plt.subplots(figsize=(11, 4.8))
        for year, group in monthly_dup.groupby("YEAR"):
            ax.plot(group["MONTH_DATE"], group["DUPLICATE_RATE_PCT"], marker="o", ms=3,
                    color=YEAR_COLORS.get(year), label=str(year))
        ax.set_xlabel("月")
        ax.set_ylabel("完全重複行率 (%)")
        ax.grid(alpha=0.25)
        ax.legend(ncol=4, frameon=False)
        ax.set_title("月別の完全重複NMEA行率", fontsize=15, fontweight="bold")
        savefig(fig, figure_dir / "07_duplicate_rate.png")


def main() -> None:
    options = args()
    options.output_dir.mkdir(parents=True, exist_ok=True)
    setup_plotting()
    metrics = build_metrics(options.data_root, options.years, options.matched_end)
    metrics["trend_and_load"] = add_trends(metrics)
    save_tables(metrics, options.output_dir)
    make_figures(metrics, options.output_dir)
    print(f"Wrote recent-year analysis to {options.output_dir}")


if __name__ == "__main__":
    main()
