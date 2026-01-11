package name.kingdoms.diplomacy;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

public record Letter(
        UUID id,
        UUID fromKingdomId,
        UUID toPlayer,
        boolean fromIsAi,
        String fromName,
        Kind kind,
        Status status,
        long createdTick,
        long expiresTick,

        // Primary payload (for resource demand/offer/trade)
        ResourceType aType,
        double aAmount,

        // Secondary payload (for CONTRACT only)
        ResourceType bType,
        double bAmount,

        // Contract cap (used by CONTRACT)
        double maxAmount,

        // war legitimacy / reason 
        CasusBelli cb,

        // short message/tone text (
        String note
) {
    public Letter {
        // harden against nulls (network + disk safety)
        if (id == null) id = UUID.randomUUID();
        if (fromName == null) fromName = "Unknown Kingdom";
        if (kind == null) kind = Kind.REQUEST;
        if (status == null) status = Status.PENDING;
        if (aType == null) aType = ResourceType.GOLD;
        if (note == null) note = "";
        // bType may be null unless CONTRACT; that's fine
        // cb may be null for non-war letters; that's fine
    }

    public enum Kind {
        REQUEST, OFFER, CONTRACT,
        COMPLIMENT, INSULT, WARNING,
        ULTIMATUM, WAR_DECLARATION, ALLIANCE_PROPOSAL,
        WHITE_PEACE, SURRENDER, ALLIANCE_BREAK
    }

    public enum Status { PENDING, ACCEPTED, REFUSED, EXPIRED }

    public enum CasusBelli {
        ULTIMATUM_REFUSED,
        BORDER_VIOLATION,
        BROKEN_TREATY,
        INSULT,
        RESOURCE_DISPUTE,
        UNKNOWN
    }

    /**
     * Sentinel to keep the wire format stable even when bType is null (REQUEST/OFFER/etc).
     * We also write a boolean so the decoder never has to guess.
     */
    private static final ResourceType BT_SENTINEL = ResourceType.GOLD;

    /**
     * Sentinel for cb (since StreamCodec enum cannot be null without a flag).
     */
    private static final CasusBelli CB_SENTINEL = CasusBelli.UNKNOWN;

    /**
     * This codec is symmetric: encode() and decode() read/write the exact same fields in the exact same order.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, Letter> CODEC =
            new StreamCodec<>() {
                @Override
                public Letter decode(RegistryFriendlyByteBuf buf) {
                    UUID id = buf.readUUID();
                    UUID from = buf.readUUID();
                    UUID to = buf.readUUID();

                    boolean fromIsAi = buf.readBoolean();
                    String fromName = buf.readUtf(32767);
                    if (fromName == null || fromName.isBlank()) fromName = "Unknown Kingdom";

                    Kind kind = buf.readEnum(Kind.class);
                    Status status = buf.readEnum(Status.class);

                    long created = buf.readLong();
                    long expires = buf.readLong();

                    ResourceType aType = buf.readEnum(ResourceType.class);
                    double aAmt = buf.readDouble();

                    boolean hasB = buf.readBoolean();
                    ResourceType bTypeRaw = buf.readEnum(ResourceType.class); // always present on wire
                    ResourceType bType = (kind == Kind.CONTRACT && hasB) ? bTypeRaw : null;

                    double bAmt = buf.readDouble();
                    double maxAmt = buf.readDouble();

                    // NEW: cb (nullable)
                    boolean hasCb = buf.readBoolean();
                    CasusBelli cbRaw = buf.readEnum(CasusBelli.class);
                    CasusBelli cb = hasCb ? cbRaw : null;

                    // NEW: note (nullable, but normalized to "")
                    String note = buf.readUtf(32767);
                    if (note == null) note = "";

                    return new Letter(
                            id, from, to,
                            fromIsAi, fromName,
                            kind, status,
                            created, expires,
                            aType, aAmt,
                            bType, bAmt,
                            maxAmt,
                            cb,
                            note
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, Letter value) {
                    buf.writeUUID(value.id());
                    buf.writeUUID(value.fromKingdomId());
                    buf.writeUUID(value.toPlayer());

                    buf.writeBoolean(value.fromIsAi());
                    buf.writeUtf(value.fromName() == null ? "Unknown Kingdom" : value.fromName(), 32767);

                    buf.writeEnum(value.kind());
                    buf.writeEnum(value.status());

                    buf.writeLong(value.createdTick());
                    buf.writeLong(value.expiresTick());

                    buf.writeEnum(value.aType());
                    buf.writeDouble(value.aAmount());

                    boolean hasB = (value.bType() != null) && (value.kind() == Kind.CONTRACT);
                    buf.writeBoolean(hasB);

                    ResourceType bOut = hasB ? value.bType() : BT_SENTINEL;
                    buf.writeEnum(bOut);

                    buf.writeDouble(value.bAmount());
                    buf.writeDouble(value.maxAmount());

                    // NEW: cb
                    boolean hasCb = (value.cb() != null);
                    buf.writeBoolean(hasCb);
                    buf.writeEnum(hasCb ? value.cb() : CB_SENTINEL);

                    // NEW: note
                    buf.writeUtf(value.note() == null ? "" : value.note(), 32767);
                }
            };

    // -----------------
    // factories
    // -----------------

    public static Letter request(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                 ResourceType wantType, double wantAmount,
                                 long createdTick, long expiresTick) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.REQUEST, Status.PENDING, createdTick, expiresTick,
                wantType, wantAmount,
                null, 0.0,
                0.0,
                null,
                ""
        );
    }

    public static Letter offer(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                               ResourceType giveType, double giveAmount,
                               long createdTick, long expiresTick) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.OFFER, Status.PENDING, createdTick, expiresTick,
                giveType, giveAmount,
                null, 0.0,
                0.0,
                null,
                ""
        );
    }

    public static Letter contract(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                  ResourceType kingdomGivesType, double kingdomGivesAmount,
                                  ResourceType playerGivesType, double playerGivesAmount,
                                  double maxKingdomGivesAmount,
                                  long createdTick, long expiresTick) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.CONTRACT, Status.PENDING, createdTick, expiresTick,
                kingdomGivesType, kingdomGivesAmount,
                playerGivesType, playerGivesAmount,
                maxKingdomGivesAmount,
                null,
                ""
        );
    }

    // NEW: compliments/insults/warnings (purely diplomatic, no resources)
    public static Letter compliment(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                    long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.COMPLIMENT, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    public static Letter insult(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                               long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.INSULT, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    public static Letter warning(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.WARNING, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    // NEW: alliance proposal (purely diplomatic, no resources)
    public static Letter allianceProposal(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                        long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.ALLIANCE_PROPOSAL, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    // NEW: white peace (no resources)
    public static Letter whitePeace(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                    long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.WHITE_PEACE, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    // NEW: surrender (no resources; same mechanics for now)
    public static Letter surrender(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.SURRENDER, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }


    // NEW: ultimatum (use expiresTick as deadline; aType/aAmount = demanded tribute)
    public static Letter ultimatum(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                   ResourceType demandType, double demandAmount,
                                   long createdTick, long deadlineTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.ULTIMATUM, Status.PENDING, createdTick, deadlineTick,
                demandType, demandAmount,
                null, 0.0,
                0.0,
                null,
                safeNote(note)
        );
    }

    // NEW: war declaration (cb is set; no resources)
    public static Letter warDeclaration(UUID fromId, boolean fromIsAi, String fromName, UUID toPlayer,
                                        CasusBelli cb,
                                        long createdTick, long expiresTick, String note) {
        return new Letter(
                UUID.randomUUID(), fromId, toPlayer, fromIsAi, safeName(fromName),
                Kind.WAR_DECLARATION, Status.PENDING, createdTick, expiresTick,
                ResourceType.GOLD, 0.0,
                null, 0.0,
                0.0,
                (cb == null ? CasusBelli.UNKNOWN : cb),
                safeNote(note)
        );
    }

    private static String safeName(String s) {
        return (s == null || s.isBlank()) ? "Unknown Kingdom" : s;
    }


    private static String safeNote(String s) {
        return (s == null) ? "" : s;
    }

    // -----------------
    // helpers
    // -----------------

    public boolean isExpired(long nowTick) {
        return expiresTick > 0 && nowTick >= expiresTick;
    }

    public Letter withStatus(Status next) {
        return new Letter(
                id, fromKingdomId, toPlayer, fromIsAi, fromName, kind, next,
                createdTick, expiresTick,
                aType, aAmount,
                bType, bAmount,
                maxAmount,
                cb,
                note
        );
    }
}
