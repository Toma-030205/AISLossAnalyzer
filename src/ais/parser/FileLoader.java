package ais.parser;

import ais.model.AisMessage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class FileLoader {

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

                String timestamp = parts[0];
                String nmea = parts[1];

                AisMessage msg =
                        AisDecoder.decode(nmea);

                if (msg == null) {
                    continue;
                }

                msg.timestamp = timestamp;

                messages.add(msg);
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

        return messages;
    }
}