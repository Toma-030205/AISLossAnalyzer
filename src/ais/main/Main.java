package ais.main;

import ais.logic.VesselOrganizer;
import ais.model.AisMessage;
import ais.model.Vessel;
import ais.parser.FileLoader;

import java.util.List;
import java.util.Map;
import ais.logic.GapAnalyzer;
import ais.stats.VesselStatistics;
import ais.stats.VesselStatisticsResult;

public class Main {

    public static void main(String[] args) {

        String filePath =
                "C:/Users/Owner/AISData/260401-0.ais";

        FileLoader loader =
                new FileLoader();

        List<AisMessage> messages =
                loader.loadFile(filePath);

        System.out.println(
                "総メッセージ数: "
                        + messages.size()
        );

        VesselOrganizer organizer =
                new VesselOrganizer();

        Map<Integer, Vessel> vesselMap =
                organizer.organizeByMmsi(messages);

        System.out.println(
                "船舶数: "
                        + vesselMap.size()
        );

        // 表示
        /*
        for (Vessel vessel : vesselMap.values()) {

            System.out.println(
                    "MMSI="
                            + vessel.getMmsi()
                            + " MESSAGE_COUNT="
                            + vessel.getMessages().size()
            );
        }
        */

        GapAnalyzer analyzer =
            new GapAnalyzer();

            for (Vessel vessel : vesselMap.values()) {

            analyzer.analyze(vessel);
        }

        for (int type : new int[]{1,2,3,5}) {

                System.out.println(
                        "メッセージID：" + type
                );

                for (Vessel vessel : vesselMap.values()) {

                        VesselStatisticsResult result =
                                statistics.analyze(vessel);

                        if (result.totalMessages < 100) {
                        continue;
                        }

                        boolean hasType = vessel.getMessages()
                                .stream()
                                .anyMatch(m -> m.messageType == type);

                        if (!hasType) {
                        continue;
                        }

                        System.out.println(result);
                }
        }
   
    }
}