package ais.logic;

import ais.model.AisMessage;
import ais.model.Vessel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VesselOrganizer {

    public Map<Integer, Vessel>
    organizeByMmsi(
            List<AisMessage> messages) {

        Map<Integer, Vessel>
                vesselMap =
                new HashMap<>();

        for (AisMessage msg : messages) {

            // Type18追加
            if (!(msg.messageType == 1
                    || msg.messageType == 2
                    || msg.messageType == 3
                    || msg.messageType == 5
                    || msg.messageType == 18)) {

                continue;
            }

            // 座標チェック
            if (msg.messageType == 1
                    || msg.messageType == 2
                    || msg.messageType == 3
                    || msg.messageType == 18) {

                if (msg.lat == null
                        || msg.lon == null) {

                    continue;
                }

                if (msg.lat == 91.0
                        || msg.lon == 181.0) {

                    continue;
                }
            }

            int mmsi =
                    msg.mmsi;

            Vessel vessel =
                    vesselMap.get(mmsi);

            if (vessel == null) {

                vessel =
                        new Vessel(mmsi);

                vesselMap.put(
                        mmsi,
                        vessel);
            }

            vessel.addMessage(msg);

            if (vessel.getShipLength()== null && msg.shipLength != null && msg.shipLength > 0) {

                vessel.setShipLength(
                        msg.shipLength
                );

            }
            
        }

        for (Vessel vessel
        : vesselMap.values()) {

    vessel.sortMessagesByTime();

    // shipLength後補完
    if (vessel.getShipLength() == null) {

        for (AisMessage msg
                : vessel.getMessages()) {

            if (msg.shipLength != null
                    && msg.shipLength > 0) {

                vessel.setShipLength(
                        msg.shipLength
                );

                break;
            }
        }
    }
}

return vesselMap;
    }
}