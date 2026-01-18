package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public final class DiplomacyMailboxState extends SavedData {
    private static final String DATA_NAME = "kingdoms_diplomacy_mail";

    // player -> letters
    private final Map<UUID, List<Letter>> inbox = new HashMap<>();

    public DiplomacyMailboxState() {}

    /**
     * IMPORTANT: This constructor is used by CODEC decode.
     * The incoming map/lists may be IMMUTABLE (Map.of / List.of),
     * so we must defensively copy into mutable collections.
     */
    private DiplomacyMailboxState(Map<UUID, List<Letter>> decodedInbox) {
        // inbox (existing)
        this.inbox.clear();
        for (Map.Entry<UUID, List<Letter>> e : decodedInbox.entrySet()) {
            UUID player = e.getKey();
            List<Letter> raw = e.getValue();
            if (raw == null) raw = List.of();

            ArrayList<Letter> safe = new ArrayList<>(raw.size());
            for (Letter l : raw) if (l != null) safe.add(l);

            this.inbox.put(player, safe);
        }
    }

    // -------------------------
    // CODECS (SavedData / disk)
    // -------------------------

    private record LetterPayload(
            ResourceType aType,
            double aAmt,
            Optional<ResourceType> bType,
            double bAmt,
            double maxAmt,
            Optional<Letter.CasusBelli> cb,
            String subject,
            String note
    ) {
        private static final Codec<LetterPayload> CODEC =
                RecordCodecBuilder.create(inst -> inst.group(
                        RESOURCE_CODEC.fieldOf("aType").forGetter(LetterPayload::aType),
                        Codec.DOUBLE.fieldOf("aAmt").forGetter(LetterPayload::aAmt),

                        RESOURCE_CODEC.optionalFieldOf("bType").forGetter(LetterPayload::bType),
                        Codec.DOUBLE.optionalFieldOf("bAmt", 0.0).forGetter(LetterPayload::bAmt),
                        Codec.DOUBLE.optionalFieldOf("maxAmt", 0.0).forGetter(LetterPayload::maxAmt),

                        CB_CODEC.optionalFieldOf("cb").forGetter(LetterPayload::cb),

                        Codec.STRING.optionalFieldOf("subject", "").forGetter(LetterPayload::subject),
                        Codec.STRING.optionalFieldOf("note", "").forGetter(LetterPayload::note)
                ).apply(inst, LetterPayload::new));
    }


    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<ResourceType> RESOURCE_CODEC =
            Codec.STRING.xmap(ResourceType::valueOf, ResourceType::name);

    private static final Codec<Letter.Kind> KIND_CODEC =
            Codec.STRING.xmap(Letter.Kind::valueOf, Letter.Kind::name);

    private static final Codec<Letter.Status> STATUS_CODEC =
            Codec.STRING.xmap(Letter.Status::valueOf, Letter.Status::name);

    private static final Codec<Letter.CasusBelli> CB_CODEC =
            Codec.STRING.xmap(Letter.CasusBelli::valueOf, Letter.CasusBelli::name);

    private static final Codec<Letter> LETTER_CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                UUID_CODEC.fieldOf("id").forGetter(Letter::id),
                UUID_CODEC.fieldOf("from").forGetter(Letter::fromKingdomId),
                UUID_CODEC.fieldOf("to").forGetter(Letter::toPlayer),

                Codec.BOOL.optionalFieldOf("fromIsAi", false).forGetter(Letter::fromIsAi),
                Codec.STRING.optionalFieldOf("fromName", "Unknown").forGetter(Letter::fromName),

                KIND_CODEC.fieldOf("kind").forGetter(Letter::kind),
                STATUS_CODEC.fieldOf("status").forGetter(Letter::status),

                Codec.LONG.fieldOf("created").forGetter(Letter::createdTick),
                Codec.LONG.optionalFieldOf("expires", 0L).forGetter(Letter::expiresTick),

                // ✅ bundle the rest
                LetterPayload.CODEC.fieldOf("payload").forGetter(l -> new LetterPayload(
                        l.aType(),
                        l.aAmount(),
                        Optional.ofNullable(l.bType()),
                        l.bAmount(),
                        l.maxAmount(),
                        Optional.ofNullable(l.cb()),
                        (l.subject() == null ? "" : l.subject()),
                        (l.note() == null ? "" : l.note())
                ))
        ).apply(inst, (id, from, to, fromIsAi, fromName, kind, status, created, expires, payload) -> {
            ResourceType bType = payload.bType().orElse(null);
            Letter.CasusBelli cb = payload.cb().orElse(null);

            String safeSubject = payload.subject() == null ? "" : payload.subject();
            String safeNote    = payload.note() == null ? "" : payload.note();

            return new Letter(
                    id, from, to,
                    fromIsAi, fromName,
                    kind, status,
                    created, expires,
                    payload.aType(), payload.aAmt(),
                    bType, payload.bAmt(),
                    payload.maxAmt(),
                    cb,
                    safeSubject,
                    safeNote
            );
        }));


    private static final Codec<Map<UUID, List<Letter>>> INBOX_CODEC =
            Codec.unboundedMap(UUID_CODEC, LETTER_CODEC.listOf());

    private static final Codec<DiplomacyMailboxState> STATE_CODEC =
            RecordCodecBuilder.create(inst -> inst.group(
                    INBOX_CODEC.optionalFieldOf("inbox", Map.of()).forGetter(s -> s.inbox)
            ).apply(inst, DiplomacyMailboxState::new));

    public static final SavedDataType<DiplomacyMailboxState> TYPE =
            new SavedDataType<>(
                    DATA_NAME,
                    DiplomacyMailboxState::new,
                    STATE_CODEC,
                    null
            );

    public static DiplomacyMailboxState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------
    // API
    // -------------------------

    /** Always returns a MUTABLE inbox list. */
    public List<Letter> getInbox(UUID player) {
        List<Letter> list = inbox.get(player);
        if (list == null) {
            list = new ArrayList<>();
            inbox.put(player, list);
            return list;
        }

        // Defensive: if something slipped in as immutable, wrap it.
        if (!(list instanceof ArrayList)) {
            list = new ArrayList<>(list);
            inbox.put(player, list);
        }

        // SANITIZE: remove any null letters that would crash encoding/saving
        list.removeIf(Objects::isNull);

        return list;
    }

    public void addLetter(UUID toPlayer, Letter letter) {
        if (letter == null) return; // don't allow null into state
        List<Letter> list = getInbox(toPlayer);
        list.add(letter);
        setDirty();
    }

    public void deliverPlayerMail(
            UUID recipientPlayerId,
            UUID fromKingdomId,
            String fromName,
            Letter.Kind kind,
            long createdTick,
            long expiresTick,
            ResourceType aType, double aAmount,
            ResourceType bType, double bAmount,
            double maxAmount,
            Letter.CasusBelli cb,
            String note
            
    ) {
        if (recipientPlayerId == null || fromKingdomId == null || kind == null || aType == null) return;

        String safeName = (fromName == null || fromName.isBlank()) ? "Unknown Kingdom" : fromName;
        String safeNote = (note == null) ? "" : note;

        String subject = switch (kind) {
            case OFFER -> "Offer";
            case REQUEST -> "Request";
            case CONTRACT -> "Contract";
            case ULTIMATUM -> "Ultimatum";
            case WAR_DECLARATION -> "War Declaration";
            case ALLIANCE_PROPOSAL -> "Alliance Proposal";
            case WHITE_PEACE -> "White Peace";
            case SURRENDER -> "Surrender";
            case ALLIANCE_BREAK -> "Alliance Break";
            case COMPLIMENT -> "Compliment";
            case INSULT -> "Insult";
            case WARNING -> "Warning";
            default -> kind.name();
        };


        Letter letter = new Letter(
                UUID.randomUUID(),
                fromKingdomId,
                recipientPlayerId,

                false,        // fromIsAi (players are not AI)
                safeName,

                kind,
                Letter.Status.PENDING, // your enum doesn't have UNREAD; PENDING works as "needs action"

                createdTick,
                expiresTick,

                aType, aAmount,
                bType, bAmount,
                maxAmount,

                cb,
                subject,
                safeNote
        );

        // newest-first
        getInbox(recipientPlayerId).add(0, letter);
        setDirty();
    }

    public Letter findLetter(UUID player, UUID letterId) {
        if (letterId == null) return null;
        for (Letter l : getInbox(player)) {
            if (l == null) continue;
            if (letterId.equals(l.id())) return l;
        }
        return null;
    }

    public boolean removeLetter(UUID playerId, UUID letterId) {
        if (playerId == null || letterId == null) return false;

        List<Letter> list = getInbox(playerId); // ensures mutable + sanitizes nulls
        boolean removed = list.removeIf(l -> l != null && letterId.equals(l.id()));
        if (removed) setDirty();
        return removed;
    }

    public boolean replaceLetter(UUID player, Letter updated) {
        if (updated == null || updated.id() == null) return false;

        List<Letter> list = getInbox(player);
        for (int i = 0; i < list.size(); i++) {
            Letter cur = list.get(i);
            if (cur == null) continue;

            if (cur.id().equals(updated.id())) {
                list.set(i, updated);
                setDirty();
                return true;
            }
        }
        return false;
    }
}
