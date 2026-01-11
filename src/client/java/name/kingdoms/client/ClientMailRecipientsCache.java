package name.kingdoms.client;

import name.kingdoms.payload.mailRecipientsSyncS2CPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientMailRecipientsCache {
    private static volatile List<mailRecipientsSyncS2CPayload.Entry> recipients = List.of();

    private ClientMailRecipientsCache() {}

    public static void set(List<mailRecipientsSyncS2CPayload.Entry> list) {
        recipients = (list == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static List<mailRecipientsSyncS2CPayload.Entry> get() {
        return recipients;
    }
}
