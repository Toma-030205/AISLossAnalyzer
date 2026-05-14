package ais.main;

import ais.model.AisMessage;
import ais.parser.FileLoader;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        String filePath =
                "C:/Users/Owner/AISData/260401-0.ais";

        FileLoader loader =
                new FileLoader();

        List<AisMessage> messages =
                loader.loadFile(filePath);

        System.out.println(
                "デコード成功件数: "
                        + messages.size()
        );

        for (AisMessage msg : messages) {

            // Type1,2,3だけ表示
            if (msg.messageType == 1
                    || msg.messageType == 2
                    || msg.messageType == 3) {

                System.out.println(
                        "TIME=" + msg.timestamp
                                + " MMSI=" + msg.mmsi
                                + " TYPE=" + msg.messageType
                                + " LAT=" + msg.lat
                                + " LON=" + msg.lon
                                + " SOG=" + msg.sog
                );
            }
        }
    }
}