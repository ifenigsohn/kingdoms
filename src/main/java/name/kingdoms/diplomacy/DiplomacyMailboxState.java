package name.kingdoms.diplomacy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import name.kingdoms.network.serverMail;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import name.kingdoms.diplomacy.DiplomacyResponseQueue;

import java.util.*;

public final class DiplomacyMailboxState extends SavedData {
    private static final String DATA_NAME = "kingdoms_diplomacy_mail";

    // player -> letters
    private final Map<UUID, List<Letter>> inbox = new HashMap<>();

    // (player|kingdom) -> tickUntilAllowed
    private final Map<String, Long> proposalCooldownUntil = new HashMap<>();

    // pending deliveries (persisted)
    private final List<PendingDelivery> pending = new ArrayList<>();

    private static String pairKey(UUID player, UUID kingdom) {
        return player.toString() + "|" + kingdom.toString();
    }

    // pending in-person proposals (player -> AI) to be delivered after a delay
    private final List<PendingToAi> pendingToAi = new ArrayList<>();


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

    private static final Codec<Map<String, Long>> COOLDOWN_CODEC =
        Codec.unboundedMap(Codec.STRING, Codec.LONG);




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

                // bundle the rest
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

        private record PendingDelivery(
                UUID toPlayer,
                Letter letter,
                long deliverTick
        ) {
            private static final Codec<PendingDelivery> CODEC =
                    RecordCodecBuilder.create(inst -> inst.group(
                            UUID_CODEC.fieldOf("to").forGetter(PendingDelivery::toPlayer),
                            LETTER_CODEC.fieldOf("letter").forGetter(PendingDelivery::letter),
                            Codec.LONG.fieldOf("deliverTick").forGetter(PendingDelivery::deliverTick)
                    ).apply(inst, PendingDelivery::new));
        }

     private record PendingToAi(
                UUID playerId,
                UUID aiKingdomId,
                Letter.Kind kind,
                ResourceType aType, double aAmount,
                Optional<ResourceType> bType, double bAmount,
                double maxAmount,
                Optional<Letter.CasusBelli> cb,
                String note,
                long deliverTick
        ) {
            private static final Codec<PendingToAi> CODEC =
                    RecordCodecBuilder.create(inst -> inst.group(
                            UUID_CODEC.fieldOf("player").forGetter(PendingToAi::playerId),
                            UUID_CODEC.fieldOf("ai").forGetter(PendingToAi::aiKingdomId),
                            KIND_CODEC.fieldOf("kind").forGetter(PendingToAi::kind),

                            RESOURCE_CODEC.fieldOf("aType").forGetter(PendingToAi::aType),
                            Codec.DOUBLE.fieldOf("aAmt").forGetter(PendingToAi::aAmount),

                            RESOURCE_CODEC.optionalFieldOf("bType").forGetter(PendingToAi::bType),
                            Codec.DOUBLE.optionalFieldOf("bAmt", 0.0).forGetter(PendingToAi::bAmount),

                            Codec.DOUBLE.optionalFieldOf("maxAmt", 0.0).forGetter(PendingToAi::maxAmount),
                            CB_CODEC.optionalFieldOf("cb").forGetter(PendingToAi::cb),
                            Codec.STRING.optionalFieldOf("note", "").forGetter(PendingToAi::note),

                            Codec.LONG.fieldOf("deliver").forGetter(PendingToAi::deliverTick)
                    ).apply(inst, PendingToAi::new));
        }

                private static final Codec<List<PendingDelivery>> PENDING_CODEC =
            PendingDelivery.CODEC.listOf();

                private static final Codec<List<PendingToAi>> PENDING_TO_AI_CODEC =
                    PendingToAi.CODEC.listOf();


    private static final Codec<Map<UUID, List<Letter>>> INBOX_CODEC =
            Codec.unboundedMap(UUID_CODEC, LETTER_CODEC.listOf());

   private static final Codec<DiplomacyMailboxState> STATE_CODEC =
        RecordCodecBuilder.create(inst -> inst.group(
                INBOX_CODEC.optionalFieldOf("inbox", Map.of()).forGetter(s -> s.inbox),
                COOLDOWN_CODEC.optionalFieldOf("proposalCooldown", Map.of()).forGetter(s -> s.proposalCooldownUntil),
                PENDING_CODEC.optionalFieldOf("pending", List.of()).forGetter(s -> s.pending),
                PENDING_TO_AI_CODEC.optionalFieldOf("pendingToAi", List.of()).forGetter(s -> s.pendingToAi)
        ).apply(inst, (decodedInbox, decodedCooldown, decodedPending, decodedPendingToAi) -> {
            DiplomacyMailboxState s = new DiplomacyMailboxState(decodedInbox);

            s.proposalCooldownUntil.clear();
            if (decodedCooldown != null) s.proposalCooldownUntil.putAll(decodedCooldown);

            s.pending.clear();
            if (decodedPending != null) s.pending.addAll(decodedPending);

            s.pendingToAi.clear();
            if (decodedPendingToAi != null) s.pendingToAi.addAll(decodedPendingToAi);

            return s;
        }));




    public void scheduleInPersonToAi(
        UUID playerId,
        UUID aiKingdomId,
        Letter.Kind kind,
        ResourceType aType, double aAmount,
        ResourceType bType, double bAmount,
        double maxAmount,
        Letter.CasusBelli cb,
        String note,
        long deliverTick
    ){
        pendingToAi.add(new PendingToAi(
                playerId, aiKingdomId, kind,
                aType, aAmount,
                Optional.ofNullable(bType), bAmount,
                maxAmount,
                Optional.ofNullable(cb),
                note == null ? "" : note,
                deliverTick
        ));
        setDirty();
    }

    public void tickPendingToAi(MinecraftServer server, long nowTick) {
        if (pendingToAi.isEmpty()) return;

        for (int i = pendingToAi.size() - 1; i >= 0; i--) {
            PendingToAi p = pendingToAi.get(i);
            if (p == null) { pendingToAi.remove(i); setDirty(); continue; }
            if (nowTick < p.deliverTick()) continue;

            DiplomacyResponseQueue.queueMail(
                    server,
                    p.playerId(),
                    p.aiKingdomId(),
                    p.kind(),
                    p.aType(), p.aAmount(),
                    p.bType().orElse(null), p.bAmount(),
                    p.maxAmount(),
                    p.cb().orElse(null),
                    p.note()
            );

            pendingToAi.remove(i);
            setDirty();

            if (DiplomacyResponseQueue.DEBUG_INSTANT) {
                DiplomacyResponseQueue.processDueNow(server);
            }
        }
    }



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

    public boolean isProposalOnCooldown(UUID player, UUID kingdom, long nowTick) {
        if (player == null || kingdom == null) return false;
        long until = proposalCooldownUntil.getOrDefault(pairKey(player, kingdom), 0L);
        return nowTick < until;
    }

    public long proposalCooldownRemaining(UUID player, UUID kingdom, long nowTick) {
        if (player == null || kingdom == null) return 0L;
        long until = proposalCooldownUntil.getOrDefault(pairKey(player, kingdom), 0L);
        return Math.max(0L, until - nowTick);
    }

    public void startProposalCooldown(UUID player, UUID kingdom, long nowTick) {
        if (player == null || kingdom == null) return;
        long until = nowTick + (20L * 60L * 2L); // 2 minutes
        proposalCooldownUntil.put(pairKey(player, kingdom), until);
        setDirty();
    }

    public void scheduleDelivery(UUID toPlayer, Letter letter, long deliverTick) {
        if (toPlayer == null || letter == null) return;
        pending.add(new PendingDelivery(toPlayer, letter, deliverTick));
        setDirty();
    }

    public void tickPendingDeliveries(MinecraftServer server, ServerLevel level, long nowTick) {
        if (pending.isEmpty()) return;

        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingDelivery p = pending.get(i);
            if (p == null || p.toPlayer() == null || p.letter() == null) {
                pending.remove(i);
                setDirty();
                continue;
            }

            if (nowTick < p.deliverTick()) continue;

            // deliver letter (newest-first)
            getInbox(p.toPlayer()).add(0, p.letter());
            pending.remove(i);
            setDirty();

            // if player is online, sync immediately
            ServerPlayer sp = server.getPlayerList().getPlayer(p.toPlayer());
            if (sp != null) {
                serverMail.syncInbox(sp, getInbox(p.toPlayer()));
            }
        }
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
