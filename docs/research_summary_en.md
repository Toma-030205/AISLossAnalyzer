# Research Summary: Estimating Missing AIS Messages

## Research context

AIS is safety-critical communication infrastructure for vessel-to-vessel and
vessel-to-shore awareness. In congested waters such as Osaka Bay and Tokyo Bay,
increasing traffic can raise concerns about message collision, interference,
and incomplete reception. The long-term research goal is to quantify these
effects and eventually support an AIS communication simulator that can assess
future capacity and technical limits.

The current study stops before simulation and map visualization. Its present
question is narrower:

> How many AIS messages were probably transmitted but not received, and how
> does the estimated loss change with distance from a fixed receiver?

## Current scope and data

- Input: timestamped NMEA/AIS records in `.ais` files.
- Observation period represented by the current CSV files: 1–30 April 2026.
- Receiver location: fixed point at 34.718983358515715 N,
  135.29057866131427 E.
- Analysis categories:
  - Type 1: AIS message Types 1, 2, and 3 combined as Class A position reports.
  - Type 5: static and voyage-related data.
  - Type 18: Class B position reports.
- Distance bands: 10 km bins; the current presentation focuses on 0–80 km.
- Minimum vessel-level sample sizes for aggregate CSV output:
  - Type 1: at least 100 messages.
  - Type 5: at least 10 messages.
  - Type 18: at least 100 messages.

## Estimation principle

Messages are grouped by MMSI and processed in timestamp order. For each
analysis category, the program compares the interval between consecutive
received messages with the transmission interval expected from the AIS report
rate rules.

```text
estimated_transmissions = round(actual_interval / expected_interval)
estimated_loss = max(0, estimated_transmissions - 1)
expected_messages = observed_intervals + estimated_loss
loss_rate = estimated_loss / expected_messages * 100
```

The expected interval depends on message type and, where applicable, speed,
navigation status, turning state, and Class B SO/CS behavior. Type 5 uses a
fixed expected interval of 360 seconds.

## Quality filters and assumptions

- A gap at least ten times the maximum normal reporting interval is treated as
  a broken track or observation session, not as communication loss.
- An interval is excluded when the distance changes by more than 30 km between
  its endpoints.
- Turning is inferred from the difference between the current direction and
  the circular mean of the previous 30 seconds. A difference above 5 degrees
  starts the turning state; the state is released after more than 20 seconds
  below the threshold.
- Type 5 does not contain a position. Its distance is assigned from the latest
  dynamic position received for the same MMSI. Type 5 distance analysis is
  therefore an approximation and must be interpreted differently from Types 1
  and 18.
- Missing messages are inferred from timing; they are not directly observed
  ground truth.

## Current evidence from 0–80 km

All three categories show a broad increase in estimated loss rate with
distance. The evidence is not equally strong across all bins:

- Type 1 has the largest sample and the smoothest distance trend. Estimated
  loss rises from 7.7% at 0–10 km to 82.4% at 50–60 km, followed by a modest
  decline in the two farthest bins.
- Type 5 rises from 8.5% at 0–10 km to 66.5% at 50–60 km. The local dip at
  40–50 km and the approximate distance assignment argue against reading the
  line as a simple physical propagation curve.
- Type 18 rises most sharply, reaching 83.6% at 30–40 km. Beyond 40 km,
  however, the number of observed messages becomes very small, so the high and
  variable percentages are unstable.
- Absolute message counts peak at 10–20 km and then fall rapidly. A high loss
  rate in a far-distance bin can coexist with very little supporting data.

The responsible conclusion is therefore not simply “distance causes loss.”
The current data support a strong association between distance and estimated
loss, but message-type behavior, sample support, filtering, and distance
attribution must be considered before making a causal claim.

## Long-term direction

The source research plan cites a 2007 AIS simulator study by Kojiro Hata,
Kazuhisa Niwa, and Kazuhiko Hasegawa as prior work. That study combined vessel
movement with an AIS communication model based on ITU-R M.1371-1. The present
loss-estimation pipeline is an empirical foundation for a later simulator that
could add SOTDMA slot allocation, radio propagation, garbling/conflict, capture
effects, and map-based visualization.

