"""Create the distance-bin comparison figures used in the AIS loss study.

The script produces:
1. A loss-rate comparison across AIS message types.
2. One observed-versus-expected comparison for each message type.

By default, CSV files are read from this script's directory and figures are
written to ``../outputs/distance_comparisons``.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

import matplotlib

if "--show" not in sys.argv:
    matplotlib.use("Agg")

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


FILES = {
    "Type 1": "type1_distance_loss.csv",
    "Type 5": "type5_distance_loss.csv",
    "Type 18": "type18_distance_loss.csv",
}

MAX_DISTANCE_KM = 80
BIN_WIDTH_KM = 10
TYPE_COLORS = {
    "Type 1": "#2563EB",
    "Type 5": "#EA580C",
    "Type 18": "#059669",
}


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description="Plot AIS loss metrics by distance bin."
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=script_dir,
        help="Directory containing type*_distance_loss.csv files.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=script_dir.parent / "outputs" / "distance_comparisons",
        help="Directory where PNG figures are saved.",
    )
    parser.add_argument(
        "--show",
        action="store_true",
        help="Also open the figures in interactive windows.",
    )
    return parser.parse_args()


def load_distance_csv(csv_path: Path) -> pd.DataFrame:
    if not csv_path.is_file():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    df = pd.read_csv(csv_path)
    required_columns = {"DISTANCE_BIN", "OBSERVED", "EXPECTED", "LOSS"}
    missing_columns = required_columns.difference(df.columns)
    if missing_columns:
        missing = ", ".join(sorted(missing_columns))
        raise ValueError(f"{csv_path.name} is missing columns: {missing}")

    df["BIN_START"] = pd.to_numeric(
        df["DISTANCE_BIN"]
        .astype(str)
        .str.replace("km", "", regex=False)
        .str.split("-")
        .str[0],
        errors="coerce",
    )
    for column in ("OBSERVED", "EXPECTED", "LOSS"):
        df[column] = pd.to_numeric(df[column], errors="coerce")

    df = df.dropna(
        subset=["BIN_START", "OBSERVED", "EXPECTED", "LOSS"]
    ).copy()
    return df[df["BIN_START"] < MAX_DISTANCE_KM]


def aggregate_by_distance(df: pd.DataFrame) -> pd.DataFrame:
    grouped = (
        df.groupby("BIN_START", as_index=False)[
            ["OBSERVED", "EXPECTED", "LOSS"]
        ]
        .sum()
        .sort_values("BIN_START")
    )

    all_bins = pd.DataFrame(
        {"BIN_START": np.arange(0, MAX_DISTANCE_KM, BIN_WIDTH_KM)}
    )
    grouped = all_bins.merge(grouped, on="BIN_START", how="left").fillna(0)
    grouped["BIN_CENTER"] = grouped["BIN_START"] + BIN_WIDTH_KM / 2
    grouped["LOSS_RATE"] = np.where(
        grouped["EXPECTED"] > 0,
        grouped["LOSS"] / grouped["EXPECTED"] * 100,
        np.nan,
    )
    return grouped


def style_distance_axis(ax: plt.Axes) -> None:
    ax.set_xlim(0, MAX_DISTANCE_KM)
    ax.set_xticks(np.arange(0, MAX_DISTANCE_KM + 1, BIN_WIDTH_KM))
    ax.grid(True, color="#CBD5E1", linewidth=0.8, alpha=0.8)
    ax.set_axisbelow(True)


def plot_loss_rate_comparison(
    aggregated: dict[str, pd.DataFrame], output_dir: Path
) -> Path:
    fig, ax = plt.subplots(figsize=(11, 7))

    for message_type, grouped in aggregated.items():
        ax.plot(
            grouped["BIN_CENTER"],
            grouped["LOSS_RATE"],
            marker="o",
            linewidth=2.4,
            markersize=6,
            color=TYPE_COLORS[message_type],
            label=message_type,
        )

    ax.set_xlabel("Distance Bin Center [km]")
    ax.set_ylabel("Estimated Loss Rate [%]")
    ax.set_title("Estimated AIS Message Loss Rate by Distance Bin")
    ax.set_ylim(0, 100)
    ax.set_yticks(np.arange(0, 101, 10))
    style_distance_axis(ax)
    ax.legend(frameon=False, ncol=3)
    fig.tight_layout()

    output_path = output_dir / "distance_loss_rate_by_message_type.png"
    fig.savefig(output_path, dpi=200, bbox_inches="tight")
    return output_path


def plot_observed_expected(
    message_type: str,
    grouped: pd.DataFrame,
    output_dir: Path,
) -> Path:
    fig, ax = plt.subplots(figsize=(11, 7))
    ax.plot(
        grouped["BIN_CENTER"],
        grouped["EXPECTED"],
        marker="o",
        linewidth=2.4,
        markersize=6,
        color="#DC2626",
        label="EXPECTED",
    )
    ax.plot(
        grouped["BIN_CENTER"],
        grouped["OBSERVED"],
        marker="o",
        linewidth=2.4,
        markersize=6,
        color="#2563EB",
        label="OBSERVED",
    )

    ax.set_xlabel("Distance Bin Center [km]")
    ax.set_ylabel("Message Count")
    ax.set_title(f"{message_type}: Observed and Expected Messages by Distance Bin")
    style_distance_axis(ax)
    ax.legend(frameon=False)
    fig.tight_layout()

    type_slug = message_type.lower().replace(" ", "")
    output_path = output_dir / f"{type_slug}_observed_expected_by_distance.png"
    fig.savefig(output_path, dpi=200, bbox_inches="tight")
    return output_path


def main() -> None:
    args = parse_args()
    input_dir = args.input_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    aggregated = {
        message_type: aggregate_by_distance(
            load_distance_csv(input_dir / file_name)
        )
        for message_type, file_name in FILES.items()
    }

    output_paths = [plot_loss_rate_comparison(aggregated, output_dir)]
    output_paths.extend(
        plot_observed_expected(message_type, grouped, output_dir)
        for message_type, grouped in aggregated.items()
    )

    for output_path in output_paths:
        print(output_path)

    if args.show:
        plt.show()
    else:
        plt.close("all")


if __name__ == "__main__":
    main()
