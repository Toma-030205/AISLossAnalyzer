from __future__ import annotations

import argparse
import csv
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path


FILE_RE = re.compile(
    r"^(?P<stamp>\d{6})-(?P<part>\d+)\.ais(?P<gz>\.gz)?$",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class InputFile:
    year: int
    day: date
    part: int
    compressed: bool
    path: Path
    size: int


def parse_file(path: Path) -> InputFile | None:
    match = FILE_RE.match(path.name)
    if not match:
        return None
    stamp = datetime.strptime(match.group("stamp"), "%y%m%d").date()
    return InputFile(
        year=stamp.year,
        day=stamp,
        part=int(match.group("part")),
        compressed=bool(match.group("gz")),
        path=path,
        size=path.stat().st_size,
    )


def daterange(start: date, end: date):
    current = start
    while current <= end:
        yield current
        current += timedelta(days=1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--years", nargs="+", type=int, default=[2023, 2024, 2025, 2026])
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    summary_rows: list[dict[str, object]] = []
    day_rows: list[dict[str, object]] = []
    file_rows: list[dict[str, object]] = []

    for year in args.years:
        year_dir = args.data_root / str(year)
        parsed: list[InputFile] = []
        unparsed: list[Path] = []
        for path in sorted(year_dir.iterdir()):
            if not path.is_file():
                continue
            item = parse_file(path)
            if item is None:
                unparsed.append(path)
            else:
                parsed.append(item)

        by_day: dict[date, list[InputFile]] = defaultdict(list)
        for item in parsed:
            by_day[item.day].append(item)

        if not parsed:
            summary_rows.append(
                {
                    "YEAR": year,
                    "FIRST_DATE": "",
                    "LAST_DATE": "",
                    "CALENDAR_DAYS_IN_SPAN": 0,
                    "DATES_PRESENT": 0,
                    "MISSING_DATES": 0,
                    "INPUT_FILES": 0,
                    "MULTIPART_DATES": 0,
                    "POTENTIAL_COMPRESSED_OVERLAPS": 0,
                    "TOTAL_BYTES": 0,
                    "UNPARSED_FILES": len(unparsed),
                }
            )
            continue

        first_day = min(by_day)
        last_day = max(by_day)
        expected_days = list(daterange(first_day, last_day))
        missing = [day for day in expected_days if day not in by_day]
        multipart_dates = sum(len(files) > 1 for files in by_day.values())

        overlap_pairs = 0
        for day, files in sorted(by_day.items()):
            parts = Counter(item.part for item in files)
            compressed_pairs = 0
            for part in parts:
                variants = [item for item in files if item.part == part]
                if any(item.compressed for item in variants) and any(
                    not item.compressed for item in variants
                ):
                    compressed_pairs += 1
            overlap_pairs += compressed_pairs
            day_rows.append(
                {
                    "YEAR": year,
                    "DATE": day.isoformat(),
                    "FILE_COUNT": len(files),
                    "PARTS": ";".join(str(item.part) for item in sorted(files, key=lambda x: (x.part, x.compressed))),
                    "FILE_NAMES": ";".join(item.path.name for item in sorted(files, key=lambda x: x.path.name)),
                    "TOTAL_BYTES": sum(item.size for item in files),
                    "HAS_COMPRESSED_UNCOMPRESSED_SAME_PART": compressed_pairs > 0,
                }
            )
            for item in sorted(files, key=lambda x: x.path.name):
                file_rows.append(
                    {
                        "YEAR": year,
                        "DATE": day.isoformat(),
                        "PART": item.part,
                        "COMPRESSED": item.compressed,
                        "FILE_NAME": item.path.name,
                        "SIZE_BYTES": item.size,
                    }
                )

        for day in missing:
            day_rows.append(
                {
                    "YEAR": year,
                    "DATE": day.isoformat(),
                    "FILE_COUNT": 0,
                    "PARTS": "",
                    "FILE_NAMES": "",
                    "TOTAL_BYTES": 0,
                    "HAS_COMPRESSED_UNCOMPRESSED_SAME_PART": False,
                }
            )

        summary_rows.append(
            {
                "YEAR": year,
                "FIRST_DATE": first_day.isoformat(),
                "LAST_DATE": last_day.isoformat(),
                "CALENDAR_DAYS_IN_SPAN": len(expected_days),
                "DATES_PRESENT": len(by_day),
                "MISSING_DATES": len(missing),
                "INPUT_FILES": len(parsed),
                "MULTIPART_DATES": multipart_dates,
                "POTENTIAL_COMPRESSED_OVERLAPS": overlap_pairs,
                "TOTAL_BYTES": sum(item.size for item in parsed),
                "UNPARSED_FILES": len(unparsed),
            }
        )

    def write_csv(name: str, rows: list[dict[str, object]]) -> None:
        path = args.output_dir / name
        if not rows:
            path.write_text("", encoding="utf-8")
            return
        with path.open("w", newline="", encoding="utf-8-sig") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

    write_csv("coverage_summary.csv", summary_rows)
    write_csv("coverage_by_date.csv", sorted(day_rows, key=lambda r: (r["YEAR"], r["DATE"])))
    write_csv("input_files.csv", file_rows)

    for row in summary_rows:
        print(
            f"{row['YEAR']}: {row['FIRST_DATE']}..{row['LAST_DATE']} "
            f"dates={row['DATES_PRESENT']}/{row['CALENDAR_DAYS_IN_SPAN']} "
            f"files={row['INPUT_FILES']} missing={row['MISSING_DATES']} "
            f"multipart={row['MULTIPART_DATES']} overlap={row['POTENTIAL_COMPRESSED_OVERLAPS']}"
        )


if __name__ == "__main__":
    main()
