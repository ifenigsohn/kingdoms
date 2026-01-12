package name.kingdoms.diplomacy;

import net.minecraft.util.RandomSource;

public final class AiLetterText {
    private AiLetterText() {}

    // keep notes short for your current UI (no real word wrap)
    private static final int MAX_LEN = 160;

    // -------------------------
    // Public API
    // -------------------------

        public static String generateEconomic(
                RandomSource r,
                Letter.Kind kind,
                String fromName,
                String toName,
                int rel,
                Object personality,
                ResourceType aType, double aAmt,
                ResourceType bType, double bAmt,
                double maxAmt
        ) {
                boolean harsh = isHarsh(rel, personality);

                String a = fmt(aAmt) + " " + (aType == null ? "?" : aType.name());
                String b = fmt(bAmt) + " " + (bType == null ? "?" : bType.name());
                String cap = fmt(maxAmt);

                String text = switch (kind) {
                case REQUEST -> pickTone(r, harsh, ECON_REQUEST, fromName, toName, a, b, cap, aType);
                case OFFER -> pickTone(r, harsh, ECON_OFFER, fromName, toName, a, b, cap, aType);
                case CONTRACT -> pickTone(r, harsh, ECON_CONTRACT, fromName, toName, a, b, cap, aType);
                default -> "";
                };

                return clamp(replaceTags(text, fromName, toName));
        }

        public static String generateAck(
                RandomSource r,
                Letter.Kind kind,
                boolean playerAccepted,
                boolean executedOk,
                String fromName,
                String toName,
                int rel,
                Object personality,
                ResourceType aType, double aAmt,
                ResourceType bType, double bAmt,
                double maxAmt,
                Letter.CasusBelli cb
        ) {
        Tone tone = chooseTone(r, rel, personality, kind);

        String a = (aType == null) ? "?" : (fmt(aAmt) + " " + aType.name());
        String b = (bType == null) ? "?" : (fmt(bAmt) + " " + bType.name());
        String cap = fmt(maxAmt);

        String core;
        if (!playerAccepted) {
                core = pick(r, tone, ACK_REFUSE);
        } else if (!executedOk) {
                core = pick(r, tone, ACK_ACCEPT_FAILED);
        } else {
                core = pick(r, tone, ACK_ACCEPT);
        }

        String detail = "";
        if (kind == Letter.Kind.OFFER || kind == Letter.Kind.REQUEST || kind == Letter.Kind.ULTIMATUM) {
                detail = " (" + a + ")";
        } else if (kind == Letter.Kind.CONTRACT) {
                detail = " (" + b + " → " + a + ", cap " + cap + ")";
        }

        return clamp(replaceTags(core + detail, fromName, toName));
        }




    public static String generateOutcome(
            RandomSource r,
            Letter.Kind kind,
            boolean accepted,
            String fromName,
            String toName,
            int rel,
            Object personality,
            ResourceType aType, double aAmt,
            ResourceType bType, double bAmt,
            double maxAmt,
            Letter.CasusBelli cb
    ) {
        boolean harsh = isHarsh(rel, personality);

        String a = (aType == null) ? "?" : (fmt(aAmt) + " " + aType.name());
        String b = (bType == null) ? "?" : (fmt(bAmt) + " " + bType.name());
        String cap = fmt(maxAmt);

        String text;

        if (accepted) {
            text = switch (kind) {
                case OFFER -> pickTone(r, harsh, OUT_ACCEPT_OFFER, fromName, toName, a, b, cap, aType);
                case REQUEST -> pickTone(r, harsh, OUT_ACCEPT_REQUEST, fromName, toName, a, b, cap, aType);
                case CONTRACT -> pickTone(r, harsh, OUT_ACCEPT_CONTRACT, fromName, toName, a, b, cap, aType);

                case ULTIMATUM -> pickTone(r, harsh, OUT_ACCEPT_ULTIMATUM, fromName, toName, a, b, cap, aType);
                case ALLIANCE_PROPOSAL -> pickTone(r, harsh, OUT_ACCEPT_ALLIANCE, fromName, toName, a, b, cap, aType);
                case WHITE_PEACE -> pickTone(r, harsh, OUT_ACCEPT_WHITE_PEACE, fromName, toName, a, b, cap, aType);
                case SURRENDER -> pickTone(r, harsh, OUT_ACCEPT_SURRENDER, fromName, toName, a, b, cap, aType);

                case COMPLIMENT -> pickTone(r, harsh, OUT_ACCEPT_COMPLIMENT, fromName, toName, a, b, cap, aType);
                case INSULT -> pickTone(r, harsh, OUT_ACCEPT_INSULT, fromName, toName, a, b, cap, aType);
                case WARNING -> pickTone(r, harsh, OUT_ACCEPT_WARNING, fromName, toName, a, b, cap, aType);

                case WAR_DECLARATION -> pickTone(r, harsh, OUT_ACCEPT_WAR_DECL, fromName, toName, a, b, cap, aType);
                case ALLIANCE_BREAK -> pickTone(r, harsh, OUT_ACCEPT_ALLIANCE_BREAK, fromName, toName, a, b, cap, aType);

                default -> harsh ? "Understood." : "Acknowledged.";
            };
        } else {
            text = switch (kind) {
                case OFFER -> pickTone(r, harsh, OUT_REFUSE_OFFER, fromName, toName, a, b, cap, aType);
                case REQUEST -> pickTone(r, harsh, OUT_REFUSE_REQUEST, fromName, toName, a, b, cap, aType);
                case CONTRACT -> pickTone(r, harsh, OUT_REFUSE_CONTRACT, fromName, toName, a, b, cap, aType);

                case ULTIMATUM -> pickTone(r, harsh, OUT_REFUSE_ULTIMATUM, fromName, toName, a, b, cap, aType);
                case ALLIANCE_PROPOSAL -> pickTone(r, harsh, OUT_REFUSE_ALLIANCE, fromName, toName, a, b, cap, aType);
                case WHITE_PEACE -> pickTone(r, harsh, OUT_REFUSE_WHITE_PEACE, fromName, toName, a, b, cap, aType);
                case SURRENDER -> pickTone(r, harsh, OUT_REFUSE_SURRENDER, fromName, toName, a, b, cap, aType);

                case COMPLIMENT -> pickTone(r, harsh, OUT_REFUSE_COMPLIMENT, fromName, toName, a, b, cap, aType);
                case INSULT -> pickTone(r, harsh, OUT_REFUSE_INSULT, fromName, toName, a, b, cap, aType);
                case WARNING -> pickTone(r, harsh, OUT_REFUSE_WARNING, fromName, toName, a, b, cap, aType);

                // War/alliance break are “informational/unilateral”; refusal isn’t meaningful.
                case WAR_DECLARATION -> "Understood.";
                case ALLIANCE_BREAK -> "Understood.";

                default -> harsh ? "Refused." : "We decline.";
            };
        }

        return clamp(replaceTags(text, fromName, toName));
    }

    // Your long-form “flavor letters” generator (already good).
    public static String generate(
            RandomSource r,
            Letter.Kind kind,
            String fromName,
            String toName,
            int rel,
            Object personality,
            Letter.CasusBelli cb
    ) {
        Tone tone = chooseTone(r, rel, personality, kind);

        String text = switch (kind) {
            case COMPLIMENT -> compose(r, tone, fromName, toName, COMPLIMENT_OPEN, COMPLIMENT_BODY, COMPLIMENT_CLOSE);
            case INSULT -> compose(r, tone, fromName, toName, INSULT_OPEN, INSULT_BODY, INSULT_CLOSE);
            case WARNING -> compose(r, tone, fromName, toName, WARNING_OPEN, WARNING_BODY, WARNING_CLOSE);

            case ALLIANCE_PROPOSAL -> compose(r, tone, fromName, toName, ALLY_OPEN, ALLY_BODY, ALLY_CLOSE);
            case ALLIANCE_BREAK -> compose(r, tone, fromName, toName, BREAK_OPEN, BREAK_BODY, BREAK_CLOSE);

            case WHITE_PEACE -> compose(r, tone, fromName, toName, PEACE_OPEN, PEACE_BODY, PEACE_CLOSE);
            case SURRENDER -> compose(r, tone, fromName, toName, SURRENDER_OPEN, SURRENDER_BODY, SURRENDER_CLOSE);

            case ULTIMATUM -> compose(r, tone, fromName, toName, ULT_OPEN, ULT_BODY, ULT_CLOSE);

            case WAR_DECLARATION -> {
                String cbLine = pickCbLine(r, cb);
                yield compose(r, tone, fromName, toName, WAR_OPEN, new Bank(cbLine), WAR_CLOSE);
            }

            default -> "";
        };

        return clamp(replaceTags(text, fromName, toName));
    }

    // -------------------------
    // Tone + helpers
    // -------------------------

    private static boolean isHarsh(int rel, Object personality) {
        boolean harsh = rel < -25;
        String p = (personality == null) ? "" : personality.toString().toLowerCase();
        if (p.contains("aggressive") || p.contains("warlike") || p.contains("conquer")) harsh = true;
        return harsh;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.000001) return Long.toString(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /**
     * Picks a line from a bank. Bank supports:
     *  - [P] polite
     *  - [H] harsh
     *  - untagged (neutral)
     * Then does token replacements.
     */
    private static String pickTone(
            RandomSource r,
            boolean harsh,
            String[] bank,
            String fromName,
            String toName,
            String a, String b, String cap,
            ResourceType aType
    ) {
        if (bank == null || bank.length == 0) return "";

        String wantTag = harsh ? "[H]" : "[P]";

        // Count tagged candidates
        int taggedCount = 0;
        for (String s : bank) {
            if (s != null && s.startsWith(wantTag)) taggedCount++;
        }

        String raw;
        if (taggedCount > 0) {
            int pick = r.nextInt(taggedCount);
            raw = "";
            for (String s : bank) {
                if (s != null && s.startsWith(wantTag)) {
                    if (pick-- == 0) { raw = s.substring(3); break; }
                }
            }
        } else {
            raw = bank[r.nextInt(bank.length)];
            if (raw != null && (raw.startsWith("[P]") || raw.startsWith("[H]"))) raw = raw.substring(3);
        }

        if (raw == null) raw = "";

        // simple token replacements for economics
        String aRes = (aType == null) ? "?" : aType.name();
        raw = raw.replace("{A}", a).replace("{B}", b).replace("{CAP}", cap).replace("{ARES}", aRes);

        return replaceTags(raw, fromName, toName);
    }

    private static String replaceTags(String s, String from, String to) {
        if (s == null) return "";
        return s.replace("{FROM}", safe(from)).replace("{TO}", safe(to));
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "Unknown" : s;
    }

    private static String clamp(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= MAX_LEN) return s;
        return s.substring(0, MAX_LEN - 1).trim() + "…";
    }

    // -------------------------
    // OUTCOME BANKS (20 each)
    // -------------------------
    
    private static final Bank ACK_ACCEPT = new Bank(
        "[P]We acknowledge your acceptance and will proceed accordingly.",
        "[P]Your acceptance is received. We thank you for your cooperation.",
        "[P]Terms accepted. May this serve our mutual interests.",

        "[N]Acceptance recorded.",
        "[N]Your response has been noted. Terms stand.",
        "[N]Acknowledged. Execution will proceed.",

        "[H]Accepted. Do not delay.",
        "[H]Acknowledged. See that it is done.",
        "[H]Very well. Deliver on it."
        );

        private static final Bank ACK_REFUSE = new Bank(
        "[P]We acknowledge your decision and will adjust our expectations.",
        "[P]Your refusal is noted. Perhaps another time.",
        "[P]Very well. We will reconsider our position.",

        "[N]Refusal recorded.",
        "[N]Your response has been noted.",
        "[N]Acknowledged. No further action.",

        "[H]Refusal noted.",
        "[H]We will remember this decision.",
        "[H]So be it."
        );

        private static final Bank ACK_ECON_DETAIL = new Bank(
        "[P]Regarding {A}.",
        "[N]({A})",
        "[H]({A})"
        );

        private static final Bank ACK_ACCEPT_FAILED = new Bank(
                "[P]Acknowledged. However, the agreement could not be executed.",
                "[P]We acknowledge your acceptance, but the terms could not be carried out.",
                "[P]Understood. Execution failed due to insufficient resources.",

                "[N]Acknowledged. Execution failed.",
                "[N]Acceptance recorded. Execution could not be completed.",
                "[N]Acknowledged. Terms could not be fulfilled.",

                "[H]Accepted—yet execution failed.",
                "[H]Acknowledged. Someone couldn’t pay.",
                "[H]Understood. The deal cannot be completed."
        );




    // OFFER outcome: player gives {A} to AI
    private static final String[] OUT_ACCEPT_OFFER = {
            "[P]Accepted. {A} will be received with thanks.",
            "[P]Agreed. We accept the terms for {A}.",
            "[P]We accept your offer. {A} is agreeable.",
            "[P]Accepted. Our scribes will record this exchange.",
            "[P]Accepted. {A} will strengthen our stores.",
            "[P]Agreed. Send {A} and we will honor the understanding.",
            "[P]Accepted. {A} is fair.",
            "[P]We accept. Let this improve relations.",
            "[P]Accepted. Dispatch {A} at your earliest convenience.",
            "[P]Agreed. {A} will be put to use.",

            "[H]Accepted. Send {A} at once.",
            "[H]Accepted. Deliver {A} and we will remember it.",
            "[H]We accept. {A} will do.",
            "[H]Accepted. Do not delay.",
            "[H]Agreed. {A}—immediately.",
            "[H]Accepted. Our patience is not endless.",
            "[H]We accept. See that {A} arrives intact.",
            "[H]Accepted. Don’t make us chase you for {A}.",
            "[H]Agreed. {A} buys peace for now.",
            "[H]Accepted. Do not disappoint us."
    };

    private static final String[] OUT_REFUSE_OFFER = {
            "[P]We decline. The terms do not satisfy us.",
            "[P]Refused. {A} is not what we need.",
            "[P]We must refuse your offer at this time.",
            "[P]No, thank you. The exchange is not suitable.",
            "[P]Refused. We cannot justify accepting {A}.",
            "[P]We decline. Bring better terms and we may reconsider.",
            "[P]Refused. This does not benefit us.",
            "[P]We decline. Our needs lie elsewhere.",
            "[P]No. The offer is insufficient.",
            "[P]Refused. Not today.",

            "[H]Refused. {A} is not worth our attention.",
            "[H]No. Bring real terms next time.",
            "[H]Refused. Do you think we are desperate?",
            "[H]No. Stop wasting our time.",
            "[H]Refused. We want better than {A}.",
            "[H]No. Your offer insults us.",
            "[H]Refused. Try again when you’re serious.",
            "[H]No. We do not beg for scraps.",
            "[H]Refused. Take it elsewhere.",
            "[H]No. Enough."
    };

    // REQUEST outcome: player asked AI to give {A}
    private static final String[] OUT_ACCEPT_REQUEST = {
            "[P]Accepted. We will provide {A}.",
            "[P]Granted. {A} will be delivered shortly.",
            "[P]We accept your request. {A} is yours.",
            "[P]Accepted. Our envoys will bring {A}.",
            "[P]Granted. Use {A} wisely.",
            "[P]Accepted. Consider this a sign of good faith.",
            "[P]Agreed. {A} can be spared.",
            "[P]Accepted. We will honor the request for {A}.",
            "[P]Granted. {A} will reach you soon.",
            "[P]Accepted. May this ease your burdens.",

            "[H]Accepted. You will receive {A}. Do not waste it.",
            "[H]Granted. {A} will be delivered. Don’t expect more.",
            "[H]Accepted. {A}—consider it a favor.",
            "[H]Accepted. Remember who helped you.",
            "[H]Granted. Use {A} or fall.",
            "[H]Accepted. Do not mistake this for weakness.",
            "[H]Granted. {A} will be sent. That’s all.",
            "[H]Accepted. We will provide {A}. Do not test us.",
            "[H]Granted. Take {A} and be quiet.",
            "[H]Accepted. We expect restraint in return."
    };

    private static final String[] OUT_REFUSE_REQUEST = {
            "[P]Refused. We cannot spare {A}.",
            "[P]We must decline. Our reserves are needed elsewhere.",
            "[P]Denied. We are unable to provide {A}.",
            "[P]Refused. That request is too costly.",
            "[P]We decline. Perhaps later, under different conditions.",
            "[P]Denied. Our stores cannot support this.",
            "[P]Refused. We cannot part with {A}.",
            "[P]We decline. Seek other means for now.",
            "[P]Denied. We must prioritize our own needs.",
            "[P]Refused. Not at this time.",

            "[H]Denied. Our stores are not yours.",
            "[H]Refused. Ask again and we may take offense.",
            "[H]No. You ask too much.",
            "[H]Denied. Stop reaching into our coffers.",
            "[H]Refused. We will not weaken ourselves for you.",
            "[H]No. We do not owe you {A}.",
            "[H]Denied. Your request is laughable.",
            "[H]Refused. Try diplomacy, not entitlement.",
            "[H]No. You get nothing.",
            "[H]Denied. Enough."
    };

    // CONTRACT outcome: player pays {B} for {A} (cap {CAP})
    private static final String[] OUT_ACCEPT_CONTRACT = {
            "[P]Accepted. Trade begins: {B} for {A} (cap {CAP}).",
            "[P]We accept the contract: {B} for {A}.",
            "[P]Agreed. A fair arrangement.",
            "[P]Accepted. Commerce will serve us both.",
            "[P]Agreed. Our clerks will record the contract.",
            "[P]Accepted. Payments of {B} will be honored with {A}.",
            "[P]Agreed. Keep to the terms and we will too.",
            "[P]Accepted. This contract is acceptable.",
            "[P]Agreed. Let trade replace suspicion.",
            "[P]Accepted. A sensible bargain.",

            "[H]Accepted. Contract stands: {B} for {A} (cap {CAP}).",
            "[H]Agreed. Do not miss payments: {B} per trade.",
            "[H]Accepted. {B} buys {A} until cap {CAP}.",
            "[H]Accepted. Follow the terms precisely.",
            "[H]Agreed. No delays. No excuses.",
            "[H]Accepted. Break terms and we end it.",
            "[H]Agreed. Pay {B}, receive {A}. Simple.",
            "[H]Accepted. Keep the paperwork clean.",
            "[H]Agreed. Do not waste our time.",
            "[H]Accepted. We will watch closely."
    };

    private static final String[] OUT_REFUSE_CONTRACT = {
            "[P]Refused. The terms are unacceptable.",
            "[P]We decline. This arrangement does not benefit us.",
            "[P]Refused. Adjust the terms and try again.",
            "[P]We refuse the contract as written.",
            "[P]Refused. The balance is not fair.",
            "[P]We decline. Our advisors reject it.",
            "[P]Refused. This contract is too costly.",
            "[P]We decline. Bring a better offer.",
            "[P]Refused. The risk is not worth it.",
            "[P]We decline. No contract.",

            "[H]Refused. Those terms are insulting.",
            "[H]No deal. We will not be bound by this.",
            "[H]Refused. Try again with better terms.",
            "[H]No. You’re asking too much.",
            "[H]Refused. We will not be cheated.",
            "[H]No. This contract favors you.",
            "[H]Refused. Bring something worthwhile.",
            "[H]No. We’re not your merchants.",
            "[H]Refused. Stop wasting ink.",
            "[H]No deal."
    };

    // ULTIMATUM outcome
    private static final String[] OUT_ACCEPT_ULTIMATUM = {
            "[P]Accepted. Pay {A} and peace holds.",
            "[P]Very well. We will comply with {A}.",
            "[P]Accepted. We will meet your demand for {A}.",
            "[P]Agreed. {A} will be delivered.",
            "[P]Accepted. Let this prevent further bloodshed.",
            "[P]Accepted. We choose restraint—this time.",
            "[P]Agreed. The tribute {A} will be paid.",
            "[P]Accepted. Do not press beyond this.",
            "[P]Accepted. We will comply.",
            "[P]Agreed. The matter will be settled by {A}.",

            "[H]Compliance accepted. Pay {A} and stay quiet.",
            "[H]Accepted. We will comply. For now.",
            "[H]Agreed. {A} will be paid—don’t ask twice.",
            "[H]Accepted. Remember who holds power.",
            "[H]Agreed. We accept your demand, begrudgingly.",
            "[H]Accepted. Do not confuse compliance with fear.",
            "[H]Agreed. {A} will be delivered. Then we talk no more.",
            "[H]Accepted. The tribute stands.",
            "[H]Agreed. {A}. Done.",
            "[H]Accepted. Do not push further."
    };

    private static final String[] OUT_REFUSE_ULTIMATUM = {
            "[P]Refused. We will not accept these demands.",
            "[P]We refuse. Your ultimatum is unacceptable.",
            "[P]Refused. We will not pay {A}.",
            "[P]We decline. This is not how diplomacy works.",
            "[P]Refused. We choose defiance.",
            "[P]We refuse. Seek peace another way.",
            "[P]Refused. Your demand will not be met.",
            "[P]We decline. There will be consequences.",
            "[P]Refused. We will not comply.",
            "[P]We refuse.",

            "[H]Refused. You will regret this.",
            "[H]No. We do not bow to threats.",
            "[H]Refused. Come take {A} yourself.",
            "[H]No. Your ultimatum means nothing.",
            "[H]Refused. We spit on your demand.",
            "[H]No. Try force if you dare.",
            "[H]Refused. You’ve chosen war.",
            "[H]No. We will not be bullied.",
            "[H]Refused. Enough.",
            "[H]No."
    };

    // Alliance proposal outcome
    private static final String[] OUT_ACCEPT_ALLIANCE = {
            "[P]Accepted. May this pact hold.",
            "[P]We accept your alliance proposal.",
            "[P]Agreed. Let our kingdoms stand together.",
            "[P]Accepted. Mutual defense benefits us both.",
            "[P]Agreed. Let suspicion end here.",
            "[P]Accepted. We welcome cooperation.",
            "[P]Agreed. We will honor the alliance.",
            "[P]Accepted. Together, we are stronger.",
            "[P]Agreed. This is wise.",
            "[P]Accepted. Let us proceed.",

            "[H]Accepted. Do not embarrass us.",
            "[H]Agreed. Stand with us.",
            "[H]Accepted. Keep up.",
            "[H]Agreed. Don’t falter.",
            "[H]Accepted. We expect results.",
            "[H]Agreed. Betray us and you’ll learn.",
            "[H]Accepted. Don’t make this a mistake.",
            "[H]Agreed. Hold your end.",
            "[H]Accepted. We’ll watch you closely.",
            "[H]Agreed."
    };

    private static final String[] OUT_REFUSE_ALLIANCE = {
            "[P]Refused. We decline an alliance at this time.",
            "[P]We decline. Trust must be earned.",
            "[P]Refused. Our interests do not align.",
            "[P]We decline. Not now.",
            "[P]Refused. We are not ready for such a pact.",
            "[P]We decline. Perhaps later.",
            "[P]Refused. We cannot commit.",
            "[P]We decline. This is premature.",
            "[P]Refused. No alliance.",
            "[P]We decline.",

            "[H]Refused. We do not tie ourselves to you.",
            "[H]No. We don’t need you.",
            "[H]Refused. You’re not worth the risk.",
            "[H]No. Find other friends.",
            "[H]Refused. We stand alone.",
            "[H]No. Don’t ask again.",
            "[H]Refused. We don’t trust you.",
            "[H]No. Keep your pact.",
            "[H]Refused.",
            "[H]No."
    };

    // Peace / surrender outcome
    private static final String[] OUT_ACCEPT_WHITE_PEACE = {
            "[P]Accepted. Let the fighting stop.",
            "[P]Accepted. Peace is preferable.",
            "[P]Agreed. We accept white peace.",
            "[P]Accepted. Withdraw your forces.",
            "[P]Agreed. No more bloodshed.",
            "[P]Accepted. Let this war end.",
            "[P]Accepted. Stand down.",
            "[P]Agreed. Peace holds.",
            "[P]Accepted. The war is over.",
            "[P]Agreed.",

            "[H]Accepted. War ends. Do not mistake this for friendship.",
            "[H]Accepted. Stand down. Now.",
            "[H]Agreed. Peace—for now.",
            "[H]Accepted. Don’t press your luck.",
            "[H]Agreed. We are done wasting soldiers.",
            "[H]Accepted. Leave our borders.",
            "[H]Agreed. Stop the fighting.",
            "[H]Accepted. Do not provoke us again.",
            "[H]Agreed. Peace.",
            "[H]Accepted."
    };

    private static final String[] OUT_REFUSE_WHITE_PEACE = {
            "[P]Refused. We will not accept peace yet.",
            "[P]We decline. The war continues.",
            "[P]Refused. Not yet.",
            "[P]We refuse. Too much remains unsettled.",
            "[P]Refused. We are not finished.",
            "[P]We decline. Continue at your peril.",
            "[P]Refused. We fight on.",
            "[P]We refuse. No peace today.",
            "[P]Refused.",
            "[P]We decline.",

            "[H]Refused. This war ends only on our terms.",
            "[H]No. We are not done.",
            "[H]Refused. Keep bleeding.",
            "[H]No. You don’t get peace.",
            "[H]Refused. We will grind you down.",
            "[H]No. Not until you break.",
            "[H]Refused. Fight.",
            "[H]No peace.",
            "[H]Refused.",
            "[H]No."
    };

    private static final String[] OUT_ACCEPT_SURRENDER = {
            "[P]Accepted. Lay down arms. Let this end.",
            "[P]Accepted. Surrender is accepted.",
            "[P]Agreed. Cease resistance.",
            "[P]Accepted. The killing stops.",
            "[P]Accepted. Stand down your troops.",
            "[P]Agreed. There will be order.",
            "[P]Accepted. Do not resist.",
            "[P]Agreed. End the war.",
            "[P]Accepted.",
            "[P]Agreed.",

            "[H]Accepted. Lay down arms and do as you’re told.",
            "[H]Accepted. Kneel, and live.",
            "[H]Agreed. Disarm. Now.",
            "[H]Accepted. No tricks.",
            "[H]Accepted. Submit and survive.",
            "[H]Agreed. Do not test mercy.",
            "[H]Accepted. You are finished.",
            "[H]Agreed. End it.",
            "[H]Accepted.",
            "[H]Agreed."
    };

    private static final String[] OUT_REFUSE_SURRENDER = {
            "[P]Refused.",
            "[P]We decline surrender terms.",
            "[P]Refused. Continue the fight.",
            "[P]We refuse.",
            "[P]Refused. Not acceptable.",
            "[P]We decline.",
            "[P]Refused. War continues.",
            "[P]We refuse.",
            "[P]Refused.",
            "[P]We decline.",

            "[H]Refused. You will be crushed.",
            "[H]No. Die standing.",
            "[H]Refused. We show no mercy.",
            "[H]No. Not yet.",
            "[H]Refused. You don’t get to choose the end.",
            "[H]No. Fight until you fall.",
            "[H]Refused. We will break you.",
            "[H]No.",
            "[H]Refused.",
            "[H]No."
    };

    // Compliment/insult/warning/war/alliance break acknowledgments
    private static final String[] OUT_ACCEPT_COMPLIMENT = {
            "[P]Your words are appreciated.",
            "[P]We thank you for the gesture.",
            "[P]A gracious message. We acknowledge it.",
            "[P]Your praise is received with respect.",
            "[P]We accept your compliment.",
            "[P]We are honored by your words.",
            "[P]A welcome sentiment.",
            "[P]We acknowledge your kindness.",
            "[P]Your message is well received.",
            "[P]We appreciate the gesture.",

            "[H]Noted.",
            "[H]We hear you.",
            "[H]Your praise is received.",
            "[H]Words are cheap. Still—noted.",
            "[H]Do not mistake this for friendship.",
            "[H]Fine. Noted.",
            "[H]We acknowledge it. Move on.",
            "[H]Keep your flattery controlled.",
            "[H]Noted. That is all.",
            "[H]Understood."
    };

    private static final String[] OUT_REFUSE_COMPLIMENT = {
            "[P]We acknowledge your message.",
            "[P]Thank you, though it changes little.",
            "[P]Noted.",
            "[P]We accept the sentiment, if not the intent.",
            "[P]We have heard you.",
            "[P]Your message is received.",
            "[P]Understood.",
            "[P]We acknowledge this.",
            "[P]Noted, with restraint.",
            "[P]Thank you.",

            "[H]Spare us the flattery.",
            "[H]Keep your praise.",
            "[H]We do not care for compliments.",
            "[H]Flattery is pointless.",
            "[H]Save it.",
            "[H]No one asked.",
            "[H]Enough.",
            "[H]We are not impressed.",
            "[H]Stop.",
            "[H]Keep your words."
    };

    private static final String[] OUT_ACCEPT_INSULT = {
            "[P]We have heard your insult.",
            "[P]Noted. Do not repeat it.",
            "[P]Your message has been received.",
            "[P]We acknowledge your hostility.",
            "[P]We will remember this.",
            "[P]Careful with your words.",
            "[P]Unwise, but noted.",
            "[P]We do not accept this tone.",
            "[P]Understood.",
            "[P]Noted.",

            "[H]Remember your words.",
            "[H]You will answer for that.",
            "[H]We will not forget this.",
            "[H]You’ve made an enemy.",
            "[H]Watch your borders.",
            "[H]You’ve invited consequences.",
            "[H]We’ll repay this.",
            "[H]Say it again and you’ll regret it.",
            "[H]Careful.",
            "[H]Noted."
    };

    private static final String[] OUT_REFUSE_INSULT = {
            "[P]Unacceptable.",
            "[P]We will not indulge this.",
            "[P]Enough.",
            "[P]We decline to engage with insults.",
            "[P]This is beneath diplomacy.",
            "[P]We reject this message.",
            "[P]Your conduct is disappointing.",
            "[P]Stop.",
            "[P]No.",
            "[P]Refused.",

            "[H]We will remember this.",
            "[H]You invite consequences.",
            "[H]Careful.",
            "[H]You’re playing with fire.",
            "[H]Keep talking and you’ll bleed.",
            "[H]Enough.",
            "[H]No.",
            "[H]Stop.",
            "[H]Refused.",
            "[H]No."
    };

    private static final String[] OUT_ACCEPT_WARNING = {
            "[P]Understood.",
            "[P]We acknowledge your warning.",
            "[P]We will take this under advisement.",
            "[P]Message received.",
            "[P]We hear you.",
            "[P]We will consider it.",
            "[P]Noted.",
            "[P]Understood. We prefer peace.",
            "[P]We acknowledge your concern.",
            "[P]Understood.",

            "[H]Understood. Tread carefully yourself.",
            "[H]We are not intimidated.",
            "[H]Your warning changes nothing.",
            "[H]Noted. Don’t push.",
            "[H]We hear you. Now leave it.",
            "[H]Keep your threats.",
            "[H]Noted. That’s all.",
            "[H]Understood. Watch yourself.",
            "[H]Noted.",
            "[H]Understood."
    };

    private static final String[] OUT_REFUSE_WARNING = {
            "[P]Refused.",
            "[P]We will not be lectured.",
            "[P]No.",
            "[P]We decline your warning.",
            "[P]Understood, but refused.",
            "[P]No.",
            "[P]We refuse.",
            "[P]Enough.",
            "[P]No.",
            "[P]Refused.",

            "[H]We reject your warning.",
            "[H]Do not presume to threaten us.",
            "[H]No.",
            "[H]Try it.",
            "[H]Enough.",
            "[H]No.",
            "[H]We refuse.",
            "[H]Stop.",
            "[H]No.",
            "[H]Refused."
    };

    private static final String[] OUT_ACCEPT_WAR_DECL = {
            "[P]Understood.",
            "[P]Then it is war.",
            "[P]We have received your declaration.",
            "[P]So be it.",
            "[P]War is acknowledged.",
            "[P]Your declaration is received.",
            "[P]We will respond in kind.",
            "[P]Understood. Prepare yourself.",
            "[P]Then we fight.",
            "[P]Understood.",

            "[H]Then come.",
            "[H]We accept war.",
            "[H]So be it.",
            "[H]Good. We wanted this.",
            "[H]Finally.",
            "[H]We will break you.",
            "[H]Prepare your walls.",
            "[H]We march.",
            "[H]Come.",
            "[H]Understood."
    };

    private static final String[] OUT_ACCEPT_ALLIANCE_BREAK = {
            "[P]Understood. The alliance is ended.",
            "[P]So be it. We dissolve our pact.",
            "[P]We acknowledge the break.",
            "[P]Understood. We will stand alone.",
            "[P]The alliance is dissolved.",
            "[P]Acknowledged.",
            "[P]Understood. Our obligations end here.",
            "[P]So it ends.",
            "[P]Understood.",
            "[P]Acknowledged.",

            "[H]We expected as much.",
            "[H]Good riddance.",
            "[H]You’ve chosen poorly.",
            "[H]Fine. Stand alone.",
            "[H]We will remember this.",
            "[H]You’ll regret it.",
            "[H]Leave. Now.",
            "[H]So be it.",
            "[H]Understood.",
            "[H]Noted."
    };

    // -------------------------
    // ECONOMIC BANKS (20 each)
    // tokens: {A} {B} {CAP} {ARES} + {FROM}/{TO}
    // -------------------------

    private static final String[] ECON_REQUEST = {
            "[P]{TO}, we are willing to provide {A}. Accept, and our envoys will deliver it.",
            "[P]From {FROM}: our stores can spare {A}. Agree, and it is yours.",
            "[P]{TO}, we offer aid: {A}. Take it, and remember who helped.",
            "[P]{TO}, consider this a gift: {A}.",
            "[P]From {FROM}: {A} can be spared. Use it well.",
            "[P]{TO}, accept and {A} will be dispatched.",
            "[P]{TO}, we can support you with {A}.",
            "[P]From {FROM}: we will send {A} as a sign of good faith.",
            "[P]{TO}, accept and let this ease your burden: {A}.",
            "[P]{TO}, we can provide {A}.",

            "[H]{TO}, we will provide {A}. Do not waste it.",
            "[H]From {FROM}: accept and take {A}. That is all.",
            "[H]{TO}, we can spare {A}. Don’t make us regret it.",
            "[H]{TO}, accept. {A} will be delivered.",
            "[H]From {FROM}: take {A} and stay quiet.",
            "[H]{TO}, accept and remember this favor: {A}.",
            "[H]{TO}, we send {A}. Do not ask again soon.",
            "[H]From {FROM}: {A}. Use it or fall.",
            "[H]{TO}, accept. {A}—no more.",
            "[H]{TO}, take {A}."
    };

    private static final String[] ECON_OFFER = {
            "[P]{TO}, we require {A}. Meet this, and we will look favorably upon you.",
            "[P]From {FROM}: send {A} and we will consider the matter settled.",
            "[P]{TO}, a simple exchange: you provide {A}.",
            "[P]{TO}, contribute {A} and relations improve.",
            "[P]From {FROM}: {A} will satisfy our need.",
            "[P]{TO}, deliver {A} and we will remember it.",
            "[P]{TO}, we request {A}.",
            "[P]From {FROM}: {A} would be appreciated.",
            "[P]{TO}, provide {A} and we will treat you kindly.",
            "[P]{TO}, send {A}.",

            "[H]{TO}, we demand {A}.",
            "[H]From {FROM}: send {A}. Don’t delay.",
            "[H]{TO}, deliver {A} or be ignored.",
            "[H]{TO}, {A}. Now.",
            "[H]From {FROM}: pay {A} and stop wasting time.",
            "[H]{TO}, send {A} to our coffers—immediately.",
            "[H]{TO}, provide {A}. Do not bargain.",
            "[H]From {FROM}: {A} buys peace for now.",
            "[H]{TO}, bring {A} or leave.",
            "[H]{TO}, {A} will do."
    };

    private static final String[] ECON_CONTRACT = {
            "[P]{TO}, a standing trade: you pay {B} per delivery; we provide {A} (cap {CAP} {ARES}).",
            "[P]From {FROM}: {B} per trade for {A}, up to {CAP} total.",
            "[P]{TO}, a practical arrangement: {B} each time for {A}, capped at {CAP}.",
            "[P]{TO}, we propose: {B} per trade → {A}, until {CAP}.",
            "[P]From {FROM}: steady commerce—{B} for {A}.",
            "[P]{TO}, agree and we begin trade immediately.",
            "[P]{TO}, keep payments of {B} and we will deliver {A}.",
            "[P]From {FROM}: fair terms—{B} buys {A} repeatedly (cap {CAP}).",
            "[P]{TO}, accept the contract and let trade replace suspicion.",
            "[P]{TO}, contract offered: {B} for {A} (cap {CAP}).",

            "[H]{TO}, contract terms: pay {B}, receive {A}. Cap {CAP}.",
            "[H]From {FROM}: {B} per trade. {A} in return. No excuses.",
            "[H]{TO}, pay {B} and get {A}. Miss payment and it ends.",
            "[H]{TO}, {B} for {A}. Cap {CAP}.",
            "[H]From {FROM}: sign and comply: {B} → {A}.",
            "[H]{TO}, keep it simple: {B} buys {A}.",
            "[H]{TO}, agree or stop wasting ink.",
            "[H]From {FROM}: terms are fixed—{B} for {A}.",
            "[H]{TO}, pay on time, receive {A}.",
            "[H]{TO}, contract stands if you accept."
    };

    // -------------------------
    // Existing long-form banks
    // -------------------------

    private enum Tone { POLITE, NEUTRAL, HARSH }

    private static Tone chooseTone(RandomSource r, int rel, Object personality, Letter.Kind kind) {
        double harsh = (rel < -40) ? 0.70 : (rel < -10) ? 0.45 : 0.15;
        double polite = (rel > 40) ? 0.70 : (rel > 10) ? 0.45 : 0.15;
        double neutral = 1.0 - harsh - polite;

        switch (kind) {
            case INSULT, WAR_DECLARATION, ULTIMATUM, ALLIANCE_BREAK -> harsh += 0.20;
            case COMPLIMENT, ALLIANCE_PROPOSAL, WHITE_PEACE, SURRENDER -> polite += 0.20;
            default -> {}
        }

        String p = (personality == null) ? "" : personality.toString().toLowerCase();
        if (p.contains("aggressive") || p.contains("warlike") || p.contains("conquer")) harsh += 0.25;
        if (p.contains("honor") || p.contains("noble") || p.contains("lawful")) polite += 0.15;
        if (p.contains("trader") || p.contains("merchant") || p.contains("pragmatic")) neutral += 0.25;
        if (p.contains("schemer") || p.contains("cunning") || p.contains("deceit")) neutral += 0.10;

        harsh = clamp01(harsh);
        polite = clamp01(polite);
        neutral = clamp01(neutral);

        double sum = harsh + polite + neutral;
        harsh /= sum; polite /= sum; neutral /= sum;

        double x = r.nextDouble();
        if (x < harsh) return Tone.HARSH;
        if (x < harsh + neutral) return Tone.NEUTRAL;
        return Tone.POLITE;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static final class Bank {
        final String[] parts;
        Bank(String... parts) { this.parts = parts; }
    }

    private static String compose(RandomSource r, Tone tone, String fromName, String toName, Bank... banks) {
        StringBuilder sb = new StringBuilder();
        for (Bank b : banks) {
            String s = pick(r, tone, b);
            if (s == null || s.isBlank()) continue;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            sb.append(s.trim());
        }
        return sb.toString().trim();
    }

    private static String pick(RandomSource r, Tone tone, Bank b) {
        if (b == null || b.parts == null || b.parts.length == 0) return "";

        String tonePrefix = switch (tone) {
            case POLITE -> "[P]";
            case NEUTRAL -> "[N]";
            case HARSH -> "[H]";
        };

        int countTone = 0;
        for (String s : b.parts) {
            if (s != null && s.startsWith(tonePrefix)) countTone++;
        }

        if (countTone > 0) {
            int pick = r.nextInt(countTone);
            for (String s : b.parts) {
                if (s != null && s.startsWith(tonePrefix)) {
                    if (pick-- == 0) return s.substring(3);
                }
            }
        }

        int anyCount = 0;
        for (String s : b.parts) {
            if (s != null && !s.startsWith("[P]") && !s.startsWith("[N]") && !s.startsWith("[H]")) anyCount++;
        }
        if (anyCount > 0) {
            int pick = r.nextInt(anyCount);
            for (String s : b.parts) {
                if (s != null && !s.startsWith("[P]") && !s.startsWith("[N]") && !s.startsWith("[H]")) {
                    if (pick-- == 0) return s;
                }
            }
        }

        return b.parts[r.nextInt(b.parts.length)];
    }

    private static String pickCbLine(RandomSource r, Letter.CasusBelli cb) {
        if (cb == null) cb = Letter.CasusBelli.UNKNOWN;
        String[] arr = switch (cb) {
            case BORDER_VIOLATION -> WAR_CB_BORDER;
            case BROKEN_TREATY -> WAR_CB_TREATY;
            case INSULT -> WAR_CB_INSULT;
            case RESOURCE_DISPUTE -> WAR_CB_RESOURCE;
            case ULTIMATUM_REFUSED -> WAR_CB_ULTIMATUM;
            case UNKNOWN -> WAR_CB_UNKNOWN;
        };
        return arr[r.nextInt(arr.length)];
    }

    // Letter sending banks
        private static final Bank COMPLIMENT_OPEN = new Bank(
                "[P]To {TO},",
                "[P]From the court of {FROM}:",
                "[P]{TO}, greetings from {FROM}.",
                "[P]{TO}, a respectful word from {FROM}:",
                "[P]To {TO}, with genuine respect,",
                "[P]{TO}, we write in good faith:",

                "[N]{TO},",
                "[N]A message to {TO} from {FROM}:",
                "[N]From {FROM} to {TO}:",
                "[N]{TO}, a note from our council:",
                "[N]{TO}, we address you plainly:",
                "[N]To {TO}—a brief letter:",

                "[H]{TO} — listen well.",
                "[H]To {TO} from {FROM}:",
                "[H]{TO}, credit where it’s due:",
                "[H]{TO}, even we can admit this:",
                "[H]{TO}, do not misunderstand this:",
                "[H]{TO}, hear this from {FROM}:",

                "[N]To {TO}, regarding your realm:",
                "[P]{TO}, let this be recorded:",
                "[H]{TO}, we speak reluctantly:"
        );

        private static final Bank COMPLIMENT_BODY = new Bank(
                "[P]Your people seem well-led and resilient.",
                "[P]Your court shows taste and restraint.",
                "[P]You’ve earned genuine respect.",
                "[P]Your banners inspire confidence rather than fear.",
                "[P]Your stewardship honors your crown.",
                "[P]Your judgment has spared your people needless hardship.",
                "[P]Your rule shows patience and foresight.",

                "[N]Your governance shows discipline.",
                "[N]Your realm has a quiet strength to it.",
                "[N]Even rivals speak of your competence.",
                "[N]Your administration appears… effective.",
                "[N]Your defenses are better than we expected.",
                "[N]Your hand keeps order where others fall to chaos.",
                "[N]Your decisions have been unusually measured.",

                "[H]Credit where it’s due: you are effective.",
                "[H]You have surprised us with competence.",
                "[H]You hold your lands better than most.",
                "[H]We expected weakness; we found resolve.",
                "[H]Your rule is tolerable—better than most.",
                "[H]Your court is not the joke we were promised.",
                "[H]For once, a ruler worth noticing."
        );

        private static final Bank COMPLIMENT_CLOSE = new Bank(
                "[P]May fortune keep you.",
                "[P]Let this stand between our crowns.",
                "[P]May our borders remain calm.",
                "[P]We offer this praise freely and without condition.",
                "[P]Carry this with pride.",
                "[P]May your reign remain steady.",

                "[N]We acknowledge what you’ve built.",
                "[N]Keep this letter — it is earned.",
                "[N]Consider this recognition, nothing more.",
                "[N]Let it be known we noticed.",
                "[N]Do not mistake this for alliance.",
                "[N]That is all.",

                "[H]Do not squander your momentum.",
                "[H]Don’t make us regret the praise.",
                "[H]Keep it up—or fall like the rest.",
                "[H]Stay competent. It suits you.",
                "[H]We will be watching.",
                "[H]Do not grow arrogant."
        );


        private static final Bank INSULT_OPEN = new Bank(
                "[P]To {TO},",
                "[P]{TO}, we write with disappointment:",
                "[P]{TO}, a sober word from {FROM}:",
                "[P]To {TO}, with regret,",
                "[P]{TO}, hear this plainly:",
                "[P]{TO}, from {FROM}, reluctantly:",

                "[N]{TO},",
                "[N]A message from {FROM}:",
                "[N]From {FROM} to {TO}:",
                "[N]{TO}, we address your conduct:",
                "[N]{TO}, this concerns your rule:",
                "[N]To {TO}—a necessary note:",

                "[H]{TO} — listen well.",
                "[H]{TO}, your name is spoken with contempt.",
                "[H]{TO}, do not mistake our patience:",
                "[H]{TO}, you embarrass yourself.",
                "[H]{TO}, this is your warning:",
                "[H]{TO}, hear the truth from {FROM}:",

                "[N]{TO}, your court should read this carefully:",
                "[P]{TO}, we expected better:",
                "[H]{TO}, we tire of you:"
        );

        private static final Bank INSULT_BODY = new Bank(
                "[P]Your judgment has been… disappointing.",
                "[P]Your conduct undermines your own people.",
                "[P]Your choices invite instability.",
                "[P]Your rule lacks the dignity your crown demands.",
                "[P]Your court has drifted into arrogance.",
                "[P]You have made enemies needlessly.",
                "[P]Your leadership falls short of basic duty.",

                "[N]Your rule is inconsistent and weak.",
                "[N]Your promises are worth less than ash.",
                "[N]Even your allies doubt you—quietly.",
                "[N]Your court is disorganized.",
                "[N]You mistake noise for strength.",
                "[N]Your diplomacy is clumsy and costly.",
                "[N]Your banner stands on borrowed time.",

                "[H]You wear a crown but lack the spine for it.",
                "[H]Your court reeks of weakness.",
                "[H]History will not remember you kindly.",
                "[H]You confuse cruelty for strength.",
                "[H]You strut like a ruler and think like a fool.",
                "[H]Your walls stand only because no one cared to push.",
                "[H]Your reign is a slow collapse."
        );

        private static final Bank INSULT_CLOSE = new Bank(
                "[P]Consider this a warning.",
                "[P]We urge you to correct your course.",
                "[P]We will not repeat ourselves.",
                "[P]Do better—your people deserve it.",
                "[P]Let this be the last time we write so harshly.",
                "[P]Choose dignity before necessity chooses for you.",

                "[N]We are done pretending.",
                "[N]Remember this when you look to your walls.",
                "[N]This is noted and recorded.",
                "[N]Do not expect further patience.",
                "[N]We will act accordingly.",
                "[N]That is all.",

                "[H]May your failures be witnessed by all.",
                "[H]The world grows tired of you.",
                "[H]You will not be taken seriously again.",
                "[H]Expect consequences.",
                "[H]We will enjoy your downfall.",
                "[H]Try us again and see what happens."
        );


        private static final Bank WARNING_OPEN = new Bank(
                "[P]To {TO},",
                "[P]{TO}, a careful warning from {FROM}:",
                "[P]{TO}, we urge restraint:",
                "[P]To {TO}, with concern,",
                "[P]{TO}, hear this in good faith:",
                "[P]{TO}, let us avoid misunderstanding:",

                "[N]{TO}, heed this warning:",
                "[N]From {FROM}:",
                "[N]A message from {FROM} to {TO}:",
                "[N]{TO}, this is a formal notice:",
                "[N]{TO}, we speak plainly:",
                "[N]To {TO}—pay attention:",

                "[H]{TO}, take this seriously:",
                "[H]Hear this, {TO}:",
                "[H]{TO}, do not test us:",
                "[H]{TO}, you are near the line:",
                "[H]{TO}, this is your last courtesy:",
                "[H]{TO}, consider this final:",

                "[N]{TO}, our patience is thinning:",
                "[P]{TO}, we prefer peace but:",
                "[H]{TO}, listen and remember:"
        );

        private static final Bank WARNING_BODY = new Bank(
                "[P]Do not let misunderstandings become conflict.",
                "[P]Step carefully. Restraint is not infinite.",
                "[P]We would rather resolve this without bloodshed.",
                "[P]Correct your course while you still can.",
                "[P]Our patience is offered, not owed.",
                "[P]Let diplomacy remain possible.",
                "[P]We advise caution for both our sakes.",

                "[N]Do not test our patience again.",
                "[N]Your next move will be answered.",
                "[N]Your actions have been noticed.",
                "[N]Do not push beyond what can be tolerated.",
                "[N]Your border games are visible.",
                "[N]We are watching your decisions closely.",
                "[N]This is your notice: stop.",

                "[H]One more provocation and we respond.",
                "[H]Cross us again and you’ll regret it.",
                "[H]Do not confuse our silence for weakness.",
                "[H]We are prepared to act.",
                "[H]You are running out of chances.",
                "[H]We will not tolerate another slight.",
                "[H]Keep your hands off what isn’t yours."
        );

        private static final Bank WARNING_CLOSE = new Bank(
                "[P]Choose wisely.",
                "[P]Let this be enough.",
                "[P]We will accept a peaceful correction.",
                "[P]Do not force a harder answer.",
                "[P]We await your better judgment.",
                "[P]May this be the last warning required.",

                "[N]We will not write twice.",
                "[N]That is all.",
                "[N]Consider the matter noted.",
                "[N]We expect change.",
                "[N]Do not test this.",
                "[N]We will act if necessary.",

                "[H]This is your last courtesy.",
                "[H]Do not force our hand.",
                "[H]We will respond without hesitation.",
                "[H]Try us and find out.",
                "[H]You’ve been warned.",
                "[H]Do not make us repeat ourselves."
        );


        private static final Bank WAR_OPEN = new Bank(
                "[P]To {TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, with regret:",
                "[P]{TO}, we wished this avoided:",
                "[P]{TO}, we write before the march:",
                "[P]{TO}, hear our final word:",

                "[N]Let it be recorded, {TO}:",
                "[N]From {FROM} to {TO}:",
                "[N]{TO}, this is the formal declaration:",
                "[N]{TO}, the matter is decided:",
                "[N]To {TO}—war is upon us:",
                "[N]{TO}, enough diplomacy:",

                "[H]{TO} — no more letters.",
                "[H]Hear the decree of {FROM}, {TO}:",
                "[H]{TO}, your time is over:",
                "[H]{TO}, we come for you:",
                "[H]{TO}, steel answers now:",
                "[H]{TO}, prepare to burn:",

                "[N]{TO}, the old rules apply:",
                "[P]{TO}, we do not do this lightly:",
                "[H]{TO}, we are done waiting:"
        );

        private static final Bank WAR_CLOSE = new Bank(
                "[P]We regret that it comes to this.",
                "[P]May the innocent be spared.",
                "[P]Let the war end swiftly.",
                "[P]We will accept peace when it is earned.",
                "[P]May your people survive what you chose.",
                "[P]We march with sorrow, not joy.",

                "[N]Steel will decide what words could not.",
                "[N]We march at dawn.",
                "[N]Your answer is irrelevant now.",
                "[N]Prepare your banners and your walls.",
                "[N]The matter will be settled by force.",
                "[N]This is the end of negotiation.",

                "[H]Prepare your walls.",
                "[H]This ends in fire or surrender.",
                "[H]We will break you.",
                "[H]Pray your gates hold.",
                "[H]We are coming.",
                "[H]You will learn fear."
        );


        private static final String[] WAR_CB_BORDER = {
                "Your trespass across our borders ends today.",
                "You tested our frontier and found our patience lacking.",
                "Your incursions will be answered in kind — by force.",
                "Your scouts cross lines you do not own.",
                "You pushed at our border. Now it pushes back.",
                "You crossed where you were not invited.",
                "Your patrols have become invasions.",
                "You violated our border markers.",
                "Your banners drifted into our lands—no longer.",
                "Your raids will be repaid.",
                "You have bled our frontier villages.",
                "Your border provocations have piled too high.",
                "You took our restraint for weakness.",
                "You advanced one step too far.",
                "Your fortifications encroach on our claim.",
                "Your soldiers will retreat—or be buried.",
                "You have treated our border as your playground.",
                "You trespassed; we respond.",
                "Your incursions end under our steel.",
                "Enough. The border is enforced by war."
        };

        private static final String[] WAR_CB_TREATY = {
                "You broke the terms we swore. Oaths have a price.",
                "You tore our treaty into rags. Now we tear back.",
                "A broken pact invites war.",
                "Betrayal ends restraint.",
                "You chose dishonor. We answer it.",
                "You violated the words you signed.",
                "Your oath meant nothing. Now peace means nothing.",
                "You spat on our agreement.",
                "You broke the pact and expect no consequences.",
                "Your signature is worthless.",
                "You turned treaty into trickery.",
                "You betrayed what we built.",
                "You proved your promises are lies.",
                "You ended diplomacy when you broke the pact.",
                "You chose treachery over trust.",
                "You broke faith; we break peace.",
                "Your treaty breach is answered by war.",
                "You have forfeited all goodwill.",
                "You used our treaty as cover.",
                "The treaty is dead. Now war follows."
        };

        private static final String[] WAR_CB_INSULT = {
                "Your insults have ripened into war.",
                "You spat in our face one time too many.",
                "Honor demands we answer your contempt.",
                "You called us weak. Now prove it.",
                "Your tongue wrote this war.",
                "You mocked us before the world.",
                "You invited war with your arrogance.",
                "You mistook restraint for fear.",
                "Your court laughed; now it will scream.",
                "You insulted our crown.",
                "You defamed our people.",
                "You made a sport of disrespect.",
                "You threw honor aside; we pick up steel.",
                "Your slander ends here.",
                "You crossed the line of dignity.",
                "Your contempt demanded an answer.",
                "You will learn what your words cost.",
                "You humiliated our envoys.",
                "Your insults echo into battle.",
                "Enough. Your mouth earned this war."
        };

        private static final String[] WAR_CB_RESOURCE = {
                "Your greed has poisoned diplomacy. We will take what is owed.",
                "You turned need into conflict. Now conflict comes for you.",
                "Your resource games end under siege.",
                "You hoarded what could have bought peace.",
                "You starved agreements. Now we starve your peace.",
                "You choked trade and expect calm.",
                "You withheld what we required.",
                "You exploited scarcity for power.",
                "You forced our hand with your greed.",
                "You made survival into leverage.",
                "You took from us and called it business.",
                "You bled our markets dry.",
                "You turned commerce into coercion.",
                "You played at monopoly; we play at war.",
                "You refused fair exchange.",
                "Your hoarding becomes your downfall.",
                "You denied us what we needed.",
                "You profited from our weakness—no longer.",
                "You made resources a weapon; we respond in kind.",
                "Enough. We will seize what you withheld."
        };

        private static final String[] WAR_CB_ULTIMATUM = {
                "You refused our ultimatum. We accept your choice — war.",
                "The ultimatum passed. The reckoning arrives.",
                "You ignored the deadline. We ignore your borders.",
                "You dismissed our demands. Now we dismiss your peace.",
                "You chose defiance. We choose invasion.",
                "You laughed at our ultimatum.",
                "You rejected the last peaceful option.",
                "You refused to pay; now you pay in blood.",
                "You declined our terms; war is the only term left.",
                "You thought the ultimatum was a bluff.",
                "You forced the next step.",
                "Your refusal is the declaration.",
                "You let the deadline pass—so peace passes.",
                "You chose arrogance over survival.",
                "You answered with silence. We answer with steel.",
                "You rejected mercy.",
                "You refused the warning. Now comes the sword.",
                "You chose war when you refused.",
                "The ultimatum ended. War begins.",
                "Your refusal sealed it."
        };

        private static final String[] WAR_CB_UNKNOWN = {
                "We have endured enough. War is declared.",
                "Diplomacy has failed. We choose war.",
                "No more letters. Only battle.",
                "This dispute ends the old way.",
                "We are done waiting for reason.",
                "You leave us no choice.",
                "Our patience has ended.",
                "This has gone too far.",
                "The time for words is over.",
                "We choose the sword.",
                "Let history judge us both.",
                "We will settle this by force.",
                "Enough. We march.",
                "There is nothing left to discuss.",
                "Peace has become impossible.",
                "You have made war inevitable.",
                "We no longer pretend diplomacy works.",
                "So it ends: with war.",
                "Prepare yourself.",
                "War is upon us."
        };


        private static final Bank ALLY_OPEN = new Bank(
                "[P]{TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, in the spirit of cooperation:",
                "[P]{TO}, let us speak of unity:",
                "[P]{TO}, we offer our hand:",
                "[P]{TO}, may this find you well:",

                "[N]{TO}, consider this proposal:",
                "[N]From {FROM} to {TO}:",
                "[N]{TO}, a practical offer:",
                "[N]{TO}, we address you plainly:",
                "[N]To {TO}—a proposal:",
                "[N]{TO}, regarding mutual defense:",

                "[H]{TO}, let us speak plainly:",
                "[H]{TO}, do not mistake this offer:",
                "[H]{TO}, we prefer allies to enemies:",
                "[H]{TO}, choose wisely:",
                "[H]{TO}, listen:",
                "[H]{TO}, we will not ask twice:",

                "[N]{TO}, our council proposes:",
                "[P]{TO}, let this strengthen both our realms:",
                "[H]{TO}, this is your opportunity:"
        );

        private static final Bank ALLY_BODY = new Bank(
                "[P]Let our crowns stand together in mutual defense.",
                "[P]We propose an alliance built on trust and strength.",
                "[P]Together we can keep our people safe.",
                "[P]Let us deter our enemies with unity.",
                "[P]Let cooperation replace suspicion.",
                "[P]We offer friendship backed by force.",
                "[P]May this pact bring stability.",

                "[N]A united front benefits us both.",
                "[N]Cooperation is cheaper than war.",
                "[N]Shared defense is simple sense.",
                "[N]Our interests align—for now.",
                "[N]Allies survive longer than loners.",
                "[N]Let us formalize cooperation.",
                "[N]This alliance is advantageous.",

                "[H]Enemies multiply when allies hesitate. Don’t hesitate.",
                "[H]Stand with us, or stand alone.",
                "[H]Choose alliance or eventually choose war.",
                "[H]We would rather fight beside you than over you.",
                "[H]Accept, and we will crush threats together.",
                "[H]Refuse, and you may stand alone when trouble comes."
        );

        private static final Bank ALLY_CLOSE = new Bank(
                "[P]We await your answer.",
                "[P]Send your assent and we will seal it.",
                "[P]May this pact hold.",
                "[P]Let our envoys meet and finalize terms.",
                "[P]We hope you choose wisely.",
                "[P]May peace follow our decision.",

                "[N]Stand with us.",
                "[N]Answer soon — opportunities pass.",
                "[N]We await your decision.",
                "[N]Respond when ready.",
                "[N]Consider it carefully.",
                "[N]That is all.",

                "[H]Choose wisely.",
                "[H]Do not make us ask twice.",
                "[H]Do not waste our time.",
                "[H]Refuse, and remember you chose it.",
                "[H]We will be watching your response.",
                "[H]Decide."
        );


        private static final Bank BREAK_OPEN = new Bank(
                "[P]{TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, with regret:",
                "[P]{TO}, this is difficult but necessary:",
                "[P]{TO}, we must end this:",
                "[P]{TO}, hear this formally:",

                "[N]To {TO} from {FROM}:",
                "[N]{TO}, this is final:",
                "[N]{TO}, we dissolve our pact:",
                "[N]{TO}, the alliance ends here:",
                "[N]{TO}, we notify you:",
                "[N]{TO}, our decision is made:",

                "[H]{TO}, we are finished:",
                "[H]Hear this, {TO}:",
                "[H]{TO}, enough:",
                "[H]{TO}, your usefulness has ended:",
                "[H]{TO}, do not protest:",
                "[H]{TO}, listen:",

                "[N]{TO}, effective immediately:",
                "[P]{TO}, we hoped this would work:",
                "[H]{TO}, you forced this:"
        );

        private static final Bank BREAK_BODY = new Bank(
                "[P]We dissolve our alliance. Trust has faded.",
                "[P]We end the pact to prevent further harm.",
                "[P]Our alliance is no longer sustainable.",
                "[P]We withdraw in the hope of avoiding worse conflict.",
                "[P]We must prioritize our people over promises.",
                "[P]We dissolve the alliance with regret.",
                "[P]We cannot continue as allies.",

                "[N]Our alliance is dissolved. The pact is ended.",
                "[N]We withdraw our support and our name from your cause.",
                "[N]We will not be bound to your decisions any longer.",
                "[N]This arrangement no longer benefits us.",
                "[N]Our obligations end today.",
                "[N]We choose separation over resentment.",
                "[N]The alliance is terminated.",

                "[H]You have made cooperation impossible. It is finished.",
                "[H]The alliance has become a liability. We end it now.",
                "[H]We are done carrying your burden.",
                "[H]Your failures make you unfit as an ally.",
                "[H]We will not be dragged down with you.",
                "[H]You have exhausted our patience."
        );

        private static final Bank BREAK_CLOSE = new Bank(
                "[P]May you find peace elsewhere.",
                "[P]We wish you no needless harm.",
                "[P]Let this end without further spite.",
                "[P]May this be a clean separation.",
                "[P]Farewell.",
                "[P]Do not force this into war.",

                "[N]From this day, you stand alone.",
                "[N]Consider the matter closed.",
                "[N]Our envoys will withdraw.",
                "[N]This is official notice.",
                "[N]That is all.",
                "[N]We will not revisit this.",

                "[H]Do not contact us again.",
                "[H]Do not mistake restraint for friendship.",
                "[H]If you cross us, you will suffer.",
                "[H]Try retaliation and see what happens.",
                "[H]Remember this.",
                "[H]Goodbye."
        );


        private static final Bank PEACE_OPEN = new Bank(
                "[P]{TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, let this end:",
                "[P]{TO}, we seek an end to bloodshed:",
                "[P]{TO}, we offer peace:",
                "[P]{TO}, may reason prevail:",

                "[N]{TO}, enough:",
                "[N]Hear our terms, {TO}:",
                "[N]{TO}, we propose white peace:",
                "[N]From {FROM} to {TO}:",
                "[N]{TO}, consider ending this war:",
                "[N]{TO}, we offer a halt:",

                "[H]{TO}, we offer a halt:",
                "[H]From {FROM}:",
                "[H]{TO}, stop this or suffer more:",
                "[H]{TO}, accept peace now:",
                "[H]{TO}, this is your chance:",
                "[H]{TO}, listen:",

                "[N]{TO}, the fighting can stop:",
                "[P]{TO}, choose mercy:",
                "[H]{TO}, decide quickly:"
        );

        private static final Bank PEACE_BODY = new Bank(
                "[P]Let us end this war without further waste.",
                "[P]The blood spilled is sufficient. We offer white peace.",
                "[P]Peace now costs less than victory later.",
                "[P]Let our people live. Accept peace.",
                "[P]We offer peace without tribute or claims.",
                "[P]Let this be the last letter of war.",
                "[P]Accept peace and we will stand down.",

                "[N]Neither of us gains by continuing. Let the fighting stop.",
                "[N]We propose an end — no tribute, no claims.",
                "[N]This war has served its lesson. End it.",
                "[N]We offer white peace. No more, no less.",
                "[N]Enough blood. Enough waste.",
                "[N]Let the war end here.",
                "[N]We will halt if you accept.",

                "[H]Peace now, or the war becomes uglier.",
                "[H]Accept peace while you still can.",
                "[H]We can keep killing, or we can stop. Choose.",
                "[H]Refuse and we continue—harder.",
                "[H]Take peace now, or lose more later.",
                "[H]You do not want what comes next."
        );

        private static final Bank PEACE_CLOSE = new Bank(
                "[P]Accept, and we will stand down.",
                "[P]May this be the end.",
                "[P]We await your reply.",
                "[P]Choose peace.",
                "[P]Let it end.",
                "[P]May fortune spare the innocent.",

                "[N]We await your reply.",
                "[N]Answer quickly.",
                "[N]That is all.",
                "[N]Respond at once.",
                "[N]Consider it.",
                "[N]Decide.",

                "[H]End it now.",
                "[H]Do not waste this offer.",
                "[H]Choose quickly.",
                "[H]Refuse and suffer.",
                "[H]We will not wait.",
                "[H]Decide."
        );


        private static final Bank SURRENDER_OPEN = new Bank(
                "[P]{TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, we cannot continue:",
                "[P]{TO}, we seek mercy:",
                "[P]{TO}, hear our surrender:",
                "[P]{TO}, let this end:",

                "[N]To {TO} from {FROM}:",
                "[N]{TO}, hear us:",
                "[N]{TO}, our strength is spent:",
                "[N]{TO}, we yield:",
                "[N]{TO}, we lay down arms:",
                "[N]{TO}, this is our final offer:",

                "[H]{TO}, we cannot continue:",
                "[H]A final message from {FROM}:",
                "[H]{TO}, we yield. Don’t gloat:",
                "[H]{TO}, take it and end this:",
                "[H]{TO}, we surrender—now:",
                "[H]{TO}, listen:",

                "[N]{TO}, the war has broken us:",
                "[P]{TO}, spare our people:",
                "[H]{TO}, make it quick:"
        );

        private static final Bank SURRENDER_BODY = new Bank(
                "[P]We cannot sustain this war. We surrender to spare what remains.",
                "[P]We lay down arms. Let this end with mercy.",
                "[P]We surrender to prevent further suffering.",
                "[P]Accept our surrender and spare our people.",
                "[P]We yield. Let the killing stop.",
                "[P]We submit. End this war.",
                "[P]Mercy now will be remembered.",

                "[N]Our strength is spent. We surrender.",
                "[N]We yield. End the fighting.",
                "[N]We can no longer hold.",
                "[N]We lay down arms to survive.",
                "[N]We submit.",
                "[N]The war is lost.",
                "[N]Enough blood.",

                "[H]We yield. Do what you will—just end it.",
                "[H]Take our banners. Leave our people.",
                "[H]We surrender. Don’t make it worse.",
                "[H]End it now.",
                "[H]Accept surrender or keep bleeding us.",
                "[H]We have nothing left."
        );

        private static final Bank SURRENDER_CLOSE = new Bank(
                "[P]Spare our people, if you can.",
                "[P]Let this end with mercy.",
                "[P]We ask only that the killing stops.",
                "[P]Do not punish the innocent.",
                "[P]We accept your judgment.",
                "[P]We beg restraint.",

                "[N]Enough blood.",
                "[N]That is all.",
                "[N]We await your answer.",
                "[N]Decide.",
                "[N]Respond.",
                "[N]End it.",

                "[H]Do not mistake this for weakness.",
                "[H]Make it quick.",
                "[H]Finish it.",
                "[H]No more games.",
                "[H]End this now.",
                "[H]Choose."
        );


        private static final Bank ULT_OPEN = new Bank(
                "[P]{TO},",
                "[P]To {TO} from {FROM}:",
                "[P]{TO}, we urge compliance:",
                "[P]{TO}, do not let this become war:",
                "[P]{TO}, heed this demand:",
                "[P]{TO}, a final chance:",

                "[N]To {TO} from {FROM}:",
                "[N]{TO}, last chance:",
                "[N]{TO}, this is a demand:",
                "[N]{TO}, hear our ultimatum:",
                "[N]{TO}, do not delay:",
                "[N]{TO}, decide now:",

                "[H]{TO}, we demand compliance:",
                "[H]Hear this ultimatum, {TO}:",
                "[H]{TO}, pay or bleed:",
                "[H]{TO}, you have one chance:",
                "[H]{TO}, do not test us:",
                "[H]{TO}, listen:",

                "[N]{TO}, this ends now:",
                "[P]{TO}, choose the peaceful path:",
                "[H]{TO}, choose quickly:"
        );

        private static final Bank ULT_BODY = new Bank(
                "[P]Pay tribute, and this ends without further harm.",
                "[P]Comply, and we remain restrained.",
                "[P]Meet our demand and we will stand down.",
                "[P]Pay, and peace holds.",
                "[P]We ask compliance to avoid worse outcomes.",
                "[P]Choose tribute over bloodshed.",
                "[P]Let this be settled without war.",

                "[N]Pay tribute, or accept the consequences.",
                "[N]Refuse, and you choose escalation.",
                "[N]This is a demand, not a request.",
                "[N]The terms are simple: pay or face war.",
                "[N]Your answer determines what follows.",
                "[N]Delay is refusal.",
                "[N]Decide now.",

                "[H]Refuse, and you choose war.",
                "[H]Pay, or we will take more later.",
                "[H]Obey, or suffer.",
                "[H]Pay now or we burn your fields.",
                "[H]Your refusal will be punished.",
                "[H]We will not warn again."
        );

        private static final Bank ULT_CLOSE = new Bank(
                "[P]Choose wisely.",
                "[P]Do not force our hand.",
                "[P]We hope you choose peace.",
                "[P]Do not let pride kill your people.",
                "[P]We await your compliance.",
                "[P]Let this end here.",

                "[N]We will not repeat ourselves.",
                "[N]Decide.",
                "[N]That is all.",
                "[N]Answer quickly.",
                "[N]Respond at once.",
                "[N]Your silence will be taken as refusal.",

                "[H]Do not force our hand.",
                "[H]You’ve been warned.",
                "[H]Choose now.",
                "[H]Refuse and suffer.",
                "[H]We are done talking.",
                "[H]Decide."
        );

}
