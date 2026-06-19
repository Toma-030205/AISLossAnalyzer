import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.widgets import RadioButtons


FILES = {
    "Type 1": "type1_daily_distance_loss.csv",
    "Type 5": "type5_daily_distance_loss.csv",
    "Type 18": "type18_daily_distance_loss.csv",
}

WEEKDAYS = [
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
]

MAX_DISTANCE_KM = 80
BIN_WIDTH_KM = 10


def load_daily_csv(file_name):
    df = pd.read_csv(file_name)

    df["BIN_START"] = (
        df["DISTANCE_BIN"]
        .astype(str)
        .str.replace("km", "", regex=False)
        .str.split("-")
        .str[0]
        .astype(float)
    )

    df["OBSERVED"] = pd.to_numeric(df["OBSERVED"], errors="coerce")
    df["EXPECTED"] = pd.to_numeric(df["EXPECTED"], errors="coerce")

    df = df[df["BIN_START"] < MAX_DISTANCE_KM].copy()
    df["BIN_CENTER"] = df["BIN_START"] + BIN_WIDTH_KM / 2

    return df


DATA = {
    msg_type: load_daily_csv(file_name)
    for msg_type, file_name in FILES.items()
}


def aggregate(msg_type, weekday):
    df = DATA[msg_type]
    df = df[df["DAY_OF_WEEK"] == weekday]

    grouped = (
        df.groupby("BIN_CENTER", as_index=False)
        .agg({
            "OBSERVED": "sum",
            "EXPECTED": "sum",
        })
    )

    all_bins = pd.DataFrame({
        "BIN_CENTER": np.arange(
            BIN_WIDTH_KM / 2,
            MAX_DISTANCE_KM,
            BIN_WIDTH_KM
        )
    })

    grouped = all_bins.merge(
        grouped,
        on="BIN_CENTER",
        how="left"
    ).fillna(0)

    return grouped


current_type = "Type 1"
current_weekday = "MONDAY"

fig, ax = plt.subplots(figsize=(12, 7))
plt.subplots_adjust(left=0.27, right=0.96, bottom=0.12)

type_ax = plt.axes([0.03, 0.55, 0.18, 0.28])
weekday_ax = plt.axes([0.03, 0.12, 0.18, 0.36])

type_radio = RadioButtons(type_ax, list(FILES.keys()))
weekday_radio = RadioButtons(weekday_ax, WEEKDAYS)


def redraw():
    ax.clear()

    grouped = aggregate(
        current_type,
        current_weekday
    )

    ax.plot(
        grouped["BIN_CENTER"],
        grouped["EXPECTED"],
        marker="o",
        linewidth=2,
        label="EXPECTED"
    )

    ax.plot(
        grouped["BIN_CENTER"],
        grouped["OBSERVED"],
        marker="o",
        linewidth=2,
        label="OBSERVED"
    )

    ax.set_xlabel("Distance Bin Center [km]")
    ax.set_ylabel("Message Count")
    ax.set_title(
        f"{current_type}: Observed vs Expected by Distance Bin ({current_weekday})"
    )

    ax.set_xlim(0, MAX_DISTANCE_KM)
    ax.set_xticks(
        np.arange(
            0,
            MAX_DISTANCE_KM + BIN_WIDTH_KM,
            BIN_WIDTH_KM
        )
    )

    ax.grid(True)
    ax.legend()
    fig.canvas.draw_idle()


def on_type_change(label):
    global current_type
    current_type = label
    redraw()


def on_weekday_change(label):
    global current_weekday
    current_weekday = label
    redraw()


type_radio.on_clicked(on_type_change)
weekday_radio.on_clicked(on_weekday_change)

redraw()
plt.show()
