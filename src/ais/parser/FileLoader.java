package ais.parser;

import ais.model.AisMessage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FileLoader {

    private static final DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public List<AisMessage> loadFile(String filePath) {

        List<AisMessage> messages =
                new ArrayList<>();

        loadFile(
                filePath,
                messages::add);

        return messages;
    }

    public void loadFile(
            String filePath,
            Consumer<AisMessage> consumer) {

        try (BufferedReader br =
                     new BufferedReader(
                             new FileReader(filePath))) {

            String line;

            while ((line = br.readLine()) != null) {

                String[] parts = line.split(" ", 2);

                if (parts.length < 2) {
                    continue;
                }

                String timestamp = parts[0].trim();
                String nmea = parts[1].trim();

                AisMessage msg =
                        AisDecoder.decode(nmea);

                if (msg == null) {
                    continue;
                }

                if (msg.messageType != 1
                        && msg.messageType != 2
                        && msg.messageType != 3
                        && msg.messageType != 5
                        && msg.messageType != 18) {
                    continue;
                }

                // Dynamic AIS(Type1,2,3)だけ座標チェック
                if (msg.messageType == 1
                        || msg.messageType == 2
                        || msg.messageType == 3
                        || msg.messageType == 18
                        ) {

                if (msg.lat == null
                        || msg.lon == null) {
                    continue;
                }

                if (msg.lat > 90
                        || msg.lat < -90) {
                    continue;
                }

                if (msg.lon > 180
                        || msg.lon < -180) {
                    continue;
                }
            }

                msg.timestamp =
                    LocalDateTime.parse(
                    timestamp,
                    formatter
                );

                consumer.accept(msg);
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }
}
