package name.kingdoms.client;

import name.kingdoms.diplomacy.Letter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientMailCache {
    private static List<Letter> inbox = new ArrayList<>();

    private ClientMailCache() {}

    public static void setInbox(List<Letter> newInbox) {
        inbox = new ArrayList<>(newInbox);
    }

    public static List<Letter> getInbox() {
        return Collections.unmodifiableList(inbox);
    }

    public static int unreadCount() {
        int n = 0;
        for (Letter l : inbox) {
            if (l.status() == Letter.Status.PENDING) n++;
        }
        return n;
    }
}
