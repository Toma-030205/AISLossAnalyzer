"""Analyze the decoded June 2007 AIS CSV archive and create overview figures.

The 2007 archive differs from the newer observations: it contains one
gzip-compressed, already-decoded CSV per hour and has no header row.  This
script streams those files, removes exact duplicate records within each day,
and writes reusable summary tables plus two publication-ready PNG figures.

Only the leading fields shared by the decoded records are needed here:

0  date (YY/MM/DD)       1  time (HH:MM:SS)
2  AIS message type     3  repeat indicator
4  MMSI                 9  longitude
10 latitude

Fields 9 and 10 are interpreted as positions only for message Types 1, 2, 3,
and 18.  Type 5 rows are counted but have no position of their own.
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass, field
from datetime import datetime
from functools import lru_cache
import gzip
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Iterable

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault(
    "MPLCONFIGDIR",
    str(Path(tempfile.gettempdir()) / "aislossanalyzer-matplotlib"),
)

try:
    import matplotlib.pyplot as plt
    from matplotlib.colors import LogNorm
    from matplotlib.patches import Ellipse
    import numpy as np
except ModuleNotFoundError as exc:
    raise SystemExit(
        "Missing plotting dependencies. Install them with: "
        f"{sys.executable} -m pip install matplotlib numpy"
    ) from exc


CLASS_A_TYPES = {1, 2, 3}
CLASS_B_TYPES = {18}
POSITION_TYPES = CLASS_A_TYPES | CLASS_B_TYPES
FILE_NAME_PATTERN = re.compile(r"^(?P<stamp>\d{8})-\d+\.csv\.gz$")

DEFAULT_RECEIVER_LAT = 34.718983358515715
DEFAULT_RECEIVER_LON = 135.29057866131427
DEFAULT_MAX_DISTANCE_KM = 80.0
EARTH_RADIUS_KM = 6371.0

TYPE_COLORS = {
    "Class A (1/2/3)": "#2563EB",
    "Type 5": "#EA580C",
    "Type 18": "#059669",
    "Other": "#64748B",
}


@dataclass
class DayStatistics:
    raw_rows: int = 0
    duplicate_rows: int = 0
    deduplicated_rows: int = 0
    class_a_reports_all: int = 0
    class_a_reports_in_area: int = 0
    class_b_reports_all: int = 0
    class_b_reports_in_area: int = 0
    type5_reports: int = 0
    other_reports: int = 0
    class_a_mmsi_in_area: set[str] = field(default_factory=set)
    class_b_mmsi_in_area: set[str] = field(default_factory=set)


@dataclass
class AnalysisResult:
    input_files: list[Path]
    date_start: str
    date_end: str
    raw_rows: int
    duplicate_rows: int
    malformed_rows: int
    invalid_position_rows: int
    position_rows_outside_area: int
    message_type_counts: dict[int, int]
    column_count_distribution: dict[int, int]
    daily: dict[str, DayStatistics]
    hourly_class_a_in_area: list[int]
    hourly_class_b_in_area: list[int]
    class_a_mmsi_in_area: set[str]
    class_b_mmsi_in_area: set[str]
    density: "np.ndarray"
    longitude_edges: "np.ndarray"
    latitude_edges: "np.ndarray"

    @property
    def deduplicated_rows(self) -> int:
        return self.raw_rows - self.duplicate_rows

    @property
    def position_rows_in_area(self) -> int:
        return int(self.density.sum())


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description="Analyze decoded June 2007 AIS CSV.GZ files."
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=Path(r"C:\Users\Owner\AISData\2007"),
        help="Directory containing hourly *.csv.gz files.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=script_dir.parent / "outputs" / "2007_overview",
        help="Directory for summary files and figures.",
    )
    parser.add_argument(
        "--receiver-lat",
        type=float,
        default=DEFAULT_RECEIVER_LAT,
        help="Fixed receiver latitude in decimal degrees.",
    )
    parser.add_argument(
        "--receiver-lon",
        type=float,
        default=DEFAULT_RECEIVER_LON,
        help="Fixed receiver longitude in decimal degrees.",
    )
    parser.add_argument(
        "--max-distance-km",
        type=float,
        default=DEFAULT_MAX_DISTANCE_KM,
        help="Radius used for the study-area summaries and density map.",
    )
    parser.add_argument(
        "--grid-size",
        type=int,
        default=240,
        help="Number of density-map bins along each axis.",
    )
    parser.add_argument(
        "--keep-duplicates",
        action="store_true",
        help="Keep exact duplicate CSV records instead of removing them.",
    )
    return parser.parse_args()


@lru_cache(maxsize=64)
def normalized_date(value: str) -> str | None:
    try:
        return datetime.strptime(value.strip(), "%y/%m/%d").date().isoformat()
    except ValueError:
        return None


def parse_file_hour(path: Path) -> int | None:
    match = FILE_NAME_PATTERN.match(path.name)
    if match is None:
        return None
    try:
        return datetime.strptime(match.group("stamp"), "%y%m%d%H").hour
    except ValueError:
        return None


def valid_mmsi(value: str) -> str | None:
    stripped = value.strip()
    if len(stripped) != 9 or not stripped.isdigit():
        return None
    if stripped == "000000000":
        return None
    return stripped


def haversine_km_vectorized(
    latitudes: "np.ndarray",
    longitudes: "np.ndarray",
    receiver_lat: float,
    receiver_lon: float,
) -> "np.ndarray":
    lat1 = np.deg2rad(receiver_lat)
    lat2 = np.deg2rad(latitudes)
    delta_lat = lat2 - lat1
    delta_lon = np.deg2rad(longitudes - receiver_lon)
    a = (
        np.sin(delta_lat / 2.0) ** 2
        + np.cos(lat1) * np.cos(lat2) * np.sin(delta_lon / 2.0) ** 2
    )
    return EARTH_RADIUS_KM * 2.0 * np.arctan2(
        np.sqrt(a), np.sqrt(np.maximum(0.0, 1.0 - a))
    )


def density_edges(
    receiver_lat: float,
    receiver_lon: float,
    max_distance_km: float,
    grid_size: int,
) -> tuple["np.ndarray", "np.ndarray"]:
    latitude_delta = max_distance_km / 110.574
    longitude_delta = max_distance_km / (
        111.320 * math.cos(math.radians(receiver_lat))
    )
    longitude_edges = np.linspace(
        receiver_lon - longitude_delta,
        receiver_lon + longitude_delta,
        grid_size + 1,
    )
    latitude_edges = np.linspace(
        receiver_lat - latitude_delta,
        receiver_lat + latitude_delta,
        grid_size + 1,
    )
    return longitude_edges, latitude_edges


def iter_rows(path: Path) -> Iterable[list[str]]:
    with gzip.open(
        path,
        mode="rt",
        encoding="utf-8-sig",
        errors="replace",
        newline="",
    ) as handle:
        yield from csv.reader(handle)


def analyze(
    input_dir: Path,
    receiver_lat: float,
    receiver_lon: float,
    max_distance_km: float,
    grid_size: int,
    keep_duplicates: bool,
) -> AnalysisResult:
    if max_distance_km <= 0:
        raise ValueError("--max-distance-km must be greater than zero")
    if grid_size < 20:
        raise ValueError("--grid-size must be at least 20")

    input_files = sorted(input_dir.glob("*.csv.gz"))
    if not input_files:
        raise FileNotFoundError(f"No *.csv.gz files found in {input_dir}")

    longitude_edges, latitude_edges = density_edges(
        receiver_lat, receiver_lon, max_distance_km, grid_size
    )
    density = np.zeros((grid_size, grid_size), dtype=np.int64)

    daily: dict[str, DayStatistics] = {}
    hourly_class_a = [0] * 24
    hourly_class_b = [0] * 24
    class_a_mmsi: set[str] = set()
    class_b_mmsi: set[str] = set()
    message_type_counts: dict[int, int] = {}
    column_count_distribution: dict[int, int] = {}

    raw_rows = 0
    duplicate_rows = 0
    malformed_rows = 0
    invalid_position_rows = 0
    position_rows_outside_area = 0

    current_date: str | None = None
    seen_rows_for_date: set[tuple[str, ...]] = set()

    for file_number, path in enumerate(input_files, start=1):
        file_hour = parse_file_hour(path)
        file_positions: list[tuple[float, float, int, str, str, int]] = []

        for row in iter_rows(path):
            raw_rows += 1
            column_count_distribution[len(row)] = (
                column_count_distribution.get(len(row), 0) + 1
            )

            row_date = normalized_date(row[0]) if row else None
            if row_date is None:
                malformed_rows += 1
                continue

            if row_date != current_date:
                current_date = row_date
                seen_rows_for_date.clear()

            day = daily.setdefault(row_date, DayStatistics())
            day.raw_rows += 1

            row_key = tuple(row)
            if not keep_duplicates and row_key in seen_rows_for_date:
                duplicate_rows += 1
                day.duplicate_rows += 1
                continue
            seen_rows_for_date.add(row_key)
            day.deduplicated_rows += 1

            if len(row) < 5:
                malformed_rows += 1
                continue

            try:
                message_type = int(row[2].strip())
            except ValueError:
                malformed_rows += 1
                continue

            message_type_counts[message_type] = (
                message_type_counts.get(message_type, 0) + 1
            )

            if message_type in CLASS_A_TYPES:
                day.class_a_reports_all += 1
            elif message_type in CLASS_B_TYPES:
                day.class_b_reports_all += 1
            elif message_type == 5:
                day.type5_reports += 1
            else:
                day.other_reports += 1

            if message_type not in POSITION_TYPES:
                continue
            if len(row) <= 10:
                invalid_position_rows += 1
                continue

            try:
                longitude = float(row[9].strip())
                latitude = float(row[10].strip())
            except ValueError:
                invalid_position_rows += 1
                continue

            if (
                not math.isfinite(longitude)
                or not math.isfinite(latitude)
                or not -180.0 <= longitude <= 180.0
                or not -90.0 <= latitude <= 90.0
                or (longitude == 0.0 and latitude == 0.0)
            ):
                invalid_position_rows += 1
                continue

            mmsi = valid_mmsi(row[4])
            hour = file_hour
            if len(row) > 1:
                try:
                    parsed_hour = int(row[1].strip()[0:2])
                    if 0 <= parsed_hour <= 23:
                        hour = parsed_hour
                except ValueError:
                    pass
            if hour is None:
                hour = 0

            file_positions.append(
                (longitude, latitude, message_type, mmsi or "", row_date, hour)
            )

        if file_positions:
            longitudes = np.fromiter(
                (item[0] for item in file_positions), dtype=float
            )
            latitudes = np.fromiter(
                (item[1] for item in file_positions), dtype=float
            )
            distances = haversine_km_vectorized(
                latitudes, longitudes, receiver_lat, receiver_lon
            )
            in_area = distances <= max_distance_km
            position_rows_outside_area += int((~in_area).sum())

            if in_area.any():
                area_histogram, _, _ = np.histogram2d(
                    latitudes[in_area],
                    longitudes[in_area],
                    bins=(latitude_edges, longitude_edges),
                )
                density += area_histogram.astype(np.int64)

            for item, is_in_area in zip(file_positions, in_area, strict=True):
                if not is_in_area:
                    continue
                _, _, message_type, mmsi, row_date, hour = item
                day = daily[row_date]
                if message_type in CLASS_A_TYPES:
                    day.class_a_reports_in_area += 1
                    hourly_class_a[hour] += 1
                    if mmsi:
                        day.class_a_mmsi_in_area.add(mmsi)
                        class_a_mmsi.add(mmsi)
                else:
                    day.class_b_reports_in_area += 1
                    hourly_class_b[hour] += 1
                    if mmsi:
                        day.class_b_mmsi_in_area.add(mmsi)
                        class_b_mmsi.add(mmsi)

        if file_number % 120 == 0 or file_number == len(input_files):
            print(
                f"Processed {file_number}/{len(input_files)} files "
                f"({raw_rows:,} raw rows)"
            )

    dates = sorted(daily)
    return AnalysisResult(
        input_files=input_files,
        date_start=dates[0],
        date_end=dates[-1],
        raw_rows=raw_rows,
        duplicate_rows=duplicate_rows,
        malformed_rows=malformed_rows,
        invalid_position_rows=invalid_position_rows,
        position_rows_outside_area=position_rows_outside_area,
        message_type_counts=dict(sorted(message_type_counts.items())),
        column_count_distribution=dict(sorted(column_count_distribution.items())),
        daily=daily,
        hourly_class_a_in_area=hourly_class_a,
        hourly_class_b_in_area=hourly_class_b,
        class_a_mmsi_in_area=class_a_mmsi,
        class_b_mmsi_in_area=class_b_mmsi,
        density=density,
        longitude_edges=longitude_edges,
        latitude_edges=latitude_edges,
    )


def write_daily_summary(result: AnalysisResult, output_path: Path) -> None:
    columns = [
        "date",
        "raw_rows",
        "duplicate_rows",
        "deduplicated_rows",
        "class_a_reports_all",
        "class_a_reports_in_area",
        "class_b_reports_all",
        "class_b_reports_in_area",
        "type5_reports",
        "other_reports",
        "active_class_a_mmsi_in_area",
        "active_class_b_mmsi_in_area",
    ]
    with output_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for date in sorted(result.daily):
            day = result.daily[date]
            writer.writerow(
                {
                    "date": date,
                    "raw_rows": day.raw_rows,
                    "duplicate_rows": day.duplicate_rows,
                    "deduplicated_rows": day.deduplicated_rows,
                    "class_a_reports_all": day.class_a_reports_all,
                    "class_a_reports_in_area": day.class_a_reports_in_area,
                    "class_b_reports_all": day.class_b_reports_all,
                    "class_b_reports_in_area": day.class_b_reports_in_area,
                    "type5_reports": day.type5_reports,
                    "other_reports": day.other_reports,
                    "active_class_a_mmsi_in_area": len(
                        day.class_a_mmsi_in_area
                    ),
                    "active_class_b_mmsi_in_area": len(
                        day.class_b_mmsi_in_area
                    ),
                }
            )


def write_hourly_summary(result: AnalysisResult, output_path: Path) -> None:
    day_count = len(result.daily)
    with output_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "hour",
                "class_a_reports_in_area",
                "class_a_average_per_day",
                "class_b_reports_in_area",
                "class_b_average_per_day",
            ]
        )
        for hour in range(24):
            writer.writerow(
                [
                    hour,
                    result.hourly_class_a_in_area[hour],
                    result.hourly_class_a_in_area[hour] / day_count,
                    result.hourly_class_b_in_area[hour],
                    result.hourly_class_b_in_area[hour] / day_count,
                ]
            )


def summary_dictionary(
    result: AnalysisResult,
    input_dir: Path,
    receiver_lat: float,
    receiver_lon: float,
    max_distance_km: float,
    keep_duplicates: bool,
) -> dict[str, object]:
    class_a_count = sum(
        count
        for message_type, count in result.message_type_counts.items()
        if message_type in CLASS_A_TYPES
    )
    class_b_count = sum(
        count
        for message_type, count in result.message_type_counts.items()
        if message_type in CLASS_B_TYPES
    )
    type5_count = result.message_type_counts.get(5, 0)
    other_count = (
        result.deduplicated_rows - class_a_count - class_b_count - type5_count
    )
    return {
        "source": {
            "input_directory": str(input_dir.resolve()),
            "file_count": len(result.input_files),
            "date_start": result.date_start,
            "date_end": result.date_end,
            "exact_duplicates_removed": not keep_duplicates,
        },
        "study_area": {
            "receiver_latitude": receiver_lat,
            "receiver_longitude": receiver_lon,
            "radius_km": max_distance_km,
        },
        "rows": {
            "raw": result.raw_rows,
            "after_exact_deduplication": result.deduplicated_rows,
            "exact_duplicates": result.duplicate_rows,
            "exact_duplicate_rate_percent": (
                result.duplicate_rows / result.raw_rows * 100.0
                if result.raw_rows
                else 0.0
            ),
            "malformed": result.malformed_rows,
            "invalid_positions": result.invalid_position_rows,
            "valid_positions_outside_study_area": (
                result.position_rows_outside_area
            ),
            "position_reports_in_study_area": result.position_rows_in_area,
        },
        "message_categories_after_deduplication": {
            "class_a_types_1_2_3": class_a_count,
            "type_5": type5_count,
            "type_18": class_b_count,
            "other": other_count,
        },
        "message_type_counts_after_deduplication": {
            str(message_type): count
            for message_type, count in result.message_type_counts.items()
        },
        "column_count_distribution_raw_rows": {
            str(column_count): count
            for column_count, count in result.column_count_distribution.items()
        },
        "unique_vessels_in_study_area": {
            "class_a": len(result.class_a_mmsi_in_area),
            "class_b": len(result.class_b_mmsi_in_area),
        },
    }


def plot_overview(
    result: AnalysisResult,
    output_path: Path,
    max_distance_km: float,
) -> None:
    dates = [
        datetime.strptime(value, "%Y-%m-%d").date()
        for value in sorted(result.daily)
    ]
    day_values = [result.daily[value] for value in sorted(result.daily)]
    class_a_daily = np.array(
        [day.class_a_reports_in_area for day in day_values], dtype=float
    )
    active_vessels = np.array(
        [len(day.class_a_mmsi_in_area) for day in day_values], dtype=float
    )

    fig, axes = plt.subplots(2, 2, figsize=(14, 9))
    fig.suptitle(
        "Decoded AIS Data Overview — June 2007",
        fontsize=18,
        fontweight="bold",
    )

    ax = axes[0, 0]
    ax.plot(
        dates,
        class_a_daily / 1000.0,
        color=TYPE_COLORS["Class A (1/2/3)"],
        marker="o",
        markersize=4,
        linewidth=2,
        label="Class A reports",
    )
    ax.set_ylabel("Position reports [thousands]")
    ax.set_title(f"Daily activity within {max_distance_km:g} km")
    ax.grid(axis="y", color="#CBD5E1", linewidth=0.8, alpha=0.8)
    ax.tick_params(axis="x", rotation=45)
    vessel_axis = ax.twinx()
    vessel_axis.plot(
        dates,
        active_vessels,
        color="#DC2626",
        linewidth=1.8,
        label="Active Class A MMSIs",
    )
    vessel_axis.set_ylabel("Active Class A MMSIs", color="#DC2626")
    lines = ax.get_lines() + vessel_axis.get_lines()
    ax.legend(lines, [line.get_label() for line in lines], frameon=False)

    ax = axes[0, 1]
    hours = np.arange(24)
    average_per_day = np.array(result.hourly_class_a_in_area) / len(dates)
    ax.bar(
        hours,
        average_per_day / 1000.0,
        color=TYPE_COLORS["Class A (1/2/3)"],
        width=0.82,
    )
    ax.set_title("Mean Class A traffic by hour")
    ax.set_xlabel("Hour of day")
    ax.set_ylabel("Reports per day [thousands]")
    ax.set_xticks(np.arange(0, 24, 2))
    ax.grid(axis="y", color="#CBD5E1", linewidth=0.8, alpha=0.8)
    ax.set_axisbelow(True)

    class_a_count = sum(
        count
        for message_type, count in result.message_type_counts.items()
        if message_type in CLASS_A_TYPES
    )
    category_counts = {
        "Class A (1/2/3)": class_a_count,
        "Type 5": result.message_type_counts.get(5, 0),
        "Type 18": result.message_type_counts.get(18, 0),
    }
    category_counts["Other"] = (
        result.deduplicated_rows - sum(category_counts.values())
    )

    ax = axes[1, 0]
    labels = list(category_counts)
    values = [category_counts[label] for label in labels]
    colors = [TYPE_COLORS[label] for label in labels]
    bars = ax.barh(labels, values, color=colors)
    ax.set_xscale("log")
    ax.set_title("Message categories after exact deduplication")
    ax.set_xlabel("Records [log scale]")
    ax.grid(axis="x", color="#CBD5E1", linewidth=0.8, alpha=0.8)
    ax.set_axisbelow(True)
    for bar, value in zip(bars, values, strict=True):
        ax.text(
            value * 1.08,
            bar.get_y() + bar.get_height() / 2,
            f"{value:,}",
            va="center",
            fontsize=9,
        )

    quality_labels = [
        "Raw rows",
        "After dedup.",
        "Valid position",
        f"Within {max_distance_km:g} km",
    ]
    valid_position_count = (
        result.position_rows_in_area + result.position_rows_outside_area
    )
    quality_values = [
        result.raw_rows,
        result.deduplicated_rows,
        valid_position_count,
        result.position_rows_in_area,
    ]
    ax = axes[1, 1]
    bars = ax.bar(
        quality_labels,
        np.array(quality_values) / 1_000_000.0,
        color=["#94A3B8", "#475569", "#0F766E", "#0D9488"],
    )
    ax.set_title("Processing and quality-control counts")
    ax.set_ylabel("Records [millions]")
    ax.tick_params(axis="x", rotation=20)
    ax.grid(axis="y", color="#CBD5E1", linewidth=0.8, alpha=0.8)
    ax.set_axisbelow(True)
    for bar, value in zip(bars, quality_values, strict=True):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.08,
            f"{value / 1_000_000:.2f}M",
            ha="center",
            va="bottom",
            fontsize=9,
        )
    duplicate_rate = (
        result.duplicate_rows / result.raw_rows * 100.0
        if result.raw_rows
        else 0.0
    )
    ax.text(
        0.98,
        0.95,
        f"Exact duplicates removed: {result.duplicate_rows:,} "
        f"({duplicate_rate:.2f}%)",
        transform=ax.transAxes,
        ha="right",
        va="top",
        color="#334155",
        fontsize=10,
    )

    fig.tight_layout(rect=(0, 0, 1, 0.96))
    fig.savefig(output_path, dpi=220, bbox_inches="tight")
    plt.close(fig)


def plot_spatial_density(
    result: AnalysisResult,
    output_path: Path,
    receiver_lat: float,
    receiver_lon: float,
    max_distance_km: float,
) -> None:
    masked_density = np.ma.masked_where(result.density <= 0, result.density)
    maximum = max(1, int(result.density.max()))

    fig, ax = plt.subplots(figsize=(10.5, 9))
    image = ax.imshow(
        masked_density,
        origin="lower",
        extent=(
            result.longitude_edges[0],
            result.longitude_edges[-1],
            result.latitude_edges[0],
            result.latitude_edges[-1],
        ),
        cmap="viridis",
        norm=LogNorm(vmin=1, vmax=maximum),
        interpolation="nearest",
    )
    colorbar = fig.colorbar(image, ax=ax, pad=0.02)
    colorbar.set_label("Position reports per grid cell [log scale]")

    latitude_delta = max_distance_km / 110.574
    longitude_delta = max_distance_km / (
        111.320 * math.cos(math.radians(receiver_lat))
    )
    radius = Ellipse(
        (receiver_lon, receiver_lat),
        width=2 * longitude_delta,
        height=2 * latitude_delta,
        fill=False,
        edgecolor="#334155",
        linestyle="--",
        linewidth=1.6,
        alpha=0.9,
        label=f"{max_distance_km:g} km radius",
    )
    ax.add_patch(radius)
    ax.scatter(
        [receiver_lon],
        [receiver_lat],
        marker="*",
        s=180,
        color="#EF4444",
        edgecolor="white",
        linewidth=0.8,
        zorder=3,
        label="Receiver",
    )
    ax.set_title(
        "Spatial Density of AIS Position Reports — June 2007",
        fontsize=16,
        fontweight="bold",
    )
    ax.set_xlabel("Longitude [°E]")
    ax.set_ylabel("Latitude [°N]")
    ax.set_aspect(1.0 / math.cos(math.radians(receiver_lat)))
    ax.legend(loc="lower left", frameon=True, framealpha=0.9)
    ax.grid(color="white", linewidth=0.5, alpha=0.2)
    fig.tight_layout()
    fig.savefig(output_path, dpi=220, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    args = parse_args()
    input_dir = args.input_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    result = analyze(
        input_dir=input_dir,
        receiver_lat=args.receiver_lat,
        receiver_lon=args.receiver_lon,
        max_distance_km=args.max_distance_km,
        grid_size=args.grid_size,
        keep_duplicates=args.keep_duplicates,
    )

    daily_path = output_dir / "2007_daily_summary.csv"
    hourly_path = output_dir / "2007_hourly_summary.csv"
    json_path = output_dir / "2007_summary.json"
    overview_path = output_dir / "2007_ais_overview.png"
    density_path = output_dir / "2007_spatial_density.png"

    write_daily_summary(result, daily_path)
    write_hourly_summary(result, hourly_path)
    summary = summary_dictionary(
        result=result,
        input_dir=input_dir,
        receiver_lat=args.receiver_lat,
        receiver_lon=args.receiver_lon,
        max_distance_km=args.max_distance_km,
        keep_duplicates=args.keep_duplicates,
    )
    with json_path.open("w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    plot_overview(result, overview_path, args.max_distance_km)
    plot_spatial_density(
        result,
        density_path,
        args.receiver_lat,
        args.receiver_lon,
        args.max_distance_km,
    )

    print()
    print("Analysis complete")
    print(f"  Raw rows:              {result.raw_rows:,}")
    print(f"  Exact duplicates:      {result.duplicate_rows:,}")
    print(f"  Rows after dedup.:      {result.deduplicated_rows:,}")
    print(f"  Positions in area:     {result.position_rows_in_area:,}")
    print(f"  Class A vessels:       {len(result.class_a_mmsi_in_area):,}")
    print(f"  Class B vessels:       {len(result.class_b_mmsi_in_area):,}")
    print()
    for path in (
        daily_path,
        hourly_path,
        json_path,
        overview_path,
        density_path,
    ):
        print(path)


if __name__ == "__main__":
    main()
