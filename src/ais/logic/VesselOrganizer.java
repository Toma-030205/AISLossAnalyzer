package ais.logic;

import ais.model.AisMessage;
import ais.model.Vessel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VesselOrganizer {

    public Map<Integer, Vessel>
    organizeByMmsi(List<AisMessage> messages) {

        Map<Integer, Vessel> vesselMap =
                new HashMap<>();

        for (AisMessage msg : messages) {

            // Dynamic AISのみ対象
            if (!(msg.messageType == 1
                    || msg.messageType == 2
                    || msg.messageType == 3)) {

                continue;
            }

            // 無効座標除外
            if (msg.lat == null
                    || msg.lon == null) {

                continue;
            }

            if (msg.lat == 91.0
                    || msg.lon == 181.0) {

                continue;
            }

            int mmsi = msg.mmsi;

            Vessel vessel =
                    vesselMap.get(mmsi);

            if (vessel == null) {

                vessel = new Vessel(mmsi);

                vesselMap.put(mmsi, vessel);
            }

            vessel.addMessage(msg);
        }

        for (Vessel vessel : vesselMap.values()) {

            vessel.sortMessagesByTime();
        }

        return vesselMap;
    }
}