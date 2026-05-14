package ais.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

public class Vessel {

    private int mmsi;

    private List<AisMessage> messages =
            new ArrayList<>();

    public Vessel(int mmsi) {

        this.mmsi = mmsi;
    }

    public int getMmsi() {

        return mmsi;
    }

    public List<AisMessage> getMessages() {

        return messages;
    }

    public void addMessage(AisMessage msg) {

        messages.add(msg);
    }

    public void sortMessagesByTime() {

        messages.sort(
                Comparator.comparing(m -> m.timestamp)
        );
    }
}