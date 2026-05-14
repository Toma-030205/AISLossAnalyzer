package ais.parser;

import ais.model.AisMessage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FileLoader {

    private static final DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public List<AisMessage> loadFile(String filePath) {

        List<AisMessage> messages =
                new ArrayList<>();

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

                msg.timestamp =
                    LocalDateTime.parse(
                    timestamp,
                    formatter
                );

                messages.add(msg);
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

        return messages;
    }
}