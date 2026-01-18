package name.kingdoms.sim;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class SimRunWriter implements Closeable {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public final String runId;
    public final Path runDir;

    private final BufferedWriter stepCsv;
    private final BufferedWriter eventsJsonl;
    private final BufferedWriter kingdomSnapshotsCsv;
    private final BufferedWriter warSnapshotsCsv;
    private final BufferedWriter decisionJsonl;

    private final Path metaPath;

    public static SimRunWriter create(MinecraftServer server, String label) throws IOException {
        String ts = LocalDateTime.now().format(TS);
        String runId = ts + (label == null || label.isBlank() ? "" : ("_" + sanitize(label)));

        Path runDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("kingdoms")
                .resolve("sims")
                .resolve(runId);

        Files.createDirectories(runDir);

        BufferedWriter stepCsv = Files.newBufferedWriter(
                runDir.resolve("steps.csv"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        BufferedWriter eventsJsonl = Files.newBufferedWriter(
                runDir.resolve("events.jsonl"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        BufferedWriter kingdomSnapshotsCsv = Files.newBufferedWriter(
                runDir.resolve("kingdom_snapshots.csv"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        BufferedWriter warSnapshotsCsv = Files.newBufferedWriter(
                runDir.resolve("war_snapshots.csv"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );

        BufferedWriter decisionJsonl = Files.newBufferedWriter(
            runDir.resolve("decision_snapshots.jsonl"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        );

 

        // headers
        stepCsv.write("runId,step,gameTimeStart,gameTimeEnd,actions,warsDelta,alliancesDelta,offers,requests,contracts,warDeclarations,allianceProposals,other\n");
        stepCsv.flush();

        // one row per kingdom snapshot; phase can be START/MID/END
        kingdomSnapshotsCsv.write(
                "runId,phase,step,gameTime,kingdomId,name," +
                "generosity,greed,trustBias,honor,aggression,pragmatism," +
                "soldiersAlive,soldiersMax," +
                "gold,meat,grain,fish,wood,metal,armor,weapons,gems,horses,potions\n"
        );
        kingdomSnapshotsCsv.flush();

        // one row per war per snapshot
        warSnapshotsCsv.write(
                "runId,step,gameTime,warKey,aId,bId," +
                "aAlive,aMax,bAlive,bMax," +
                "moraleA,moraleB," +
                "aGold,aFood,bGold,bFood\n"
        );
        warSnapshotsCsv.flush();

        Path metaPath = runDir.resolve("run_meta.json");

        return new SimRunWriter(runId, runDir, metaPath, stepCsv, eventsJsonl, kingdomSnapshotsCsv, warSnapshotsCsv, decisionJsonl);
    }

    private SimRunWriter(
            String runId,
            Path runDir,
            Path metaPath,
            BufferedWriter stepCsv,
            BufferedWriter eventsJsonl,
            BufferedWriter kingdomSnapshotsCsv,
            BufferedWriter warSnapshotsCsv,
            BufferedWriter decisionJsonl
    ) {
        this.runId = runId;
        this.runDir = runDir;
        this.metaPath = metaPath;
        this.stepCsv = stepCsv;
        this.eventsJsonl = eventsJsonl;
        this.kingdomSnapshotsCsv = kingdomSnapshotsCsv;
        this.warSnapshotsCsv = warSnapshotsCsv;
        this.decisionJsonl = decisionJsonl;
    }

    // -------------------------
    // Meta
    // -------------------------

    /** Write a run metadata file once at start. Call from masterSim after create(). */
    public void writeRunMeta(JsonObject meta) throws IOException {
        if (meta == null) meta = new JsonObject();
        meta.addProperty("runId", runId);

        Files.writeString(
                metaPath,
                GSON.toJson(meta),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    // -------------------------
    // Step + event logs
    // -------------------------

    public void logStep(
            int stepNum,
            long stepStartTick,
            long stepEndTick,
            int actions,
            int warsDelta,
            int alliancesDelta,
            int offers,
            int requests,
            int contracts,
            int warDeclarations,
            int allianceProposals,
            int other
    ) throws IOException {
        stepCsv.write(runId); stepCsv.write(",");
        stepCsv.write(Integer.toString(stepNum)); stepCsv.write(",");
        stepCsv.write(Long.toString(stepStartTick)); stepCsv.write(",");
        stepCsv.write(Long.toString(stepEndTick)); stepCsv.write(",");
        stepCsv.write(Integer.toString(actions)); stepCsv.write(",");
        stepCsv.write(Integer.toString(warsDelta)); stepCsv.write(",");
        stepCsv.write(Integer.toString(alliancesDelta)); stepCsv.write(",");
        stepCsv.write(Integer.toString(offers)); stepCsv.write(",");
        stepCsv.write(Integer.toString(requests)); stepCsv.write(",");
        stepCsv.write(Integer.toString(contracts)); stepCsv.write(",");
        stepCsv.write(Integer.toString(warDeclarations)); stepCsv.write(",");
        stepCsv.write(Integer.toString(allianceProposals)); stepCsv.write(",");
        stepCsv.write(Integer.toString(other));
        stepCsv.write("\n");
    }

    public void logEvent(
            int step,
            long tick,
            String kind,
            String fromId,
            String toId,
            String outcome,
            int relBefore,
            int relAfter,
            int relDelta
    ) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("runId", runId);
        obj.addProperty("step", step);
        obj.addProperty("tick", tick);
        obj.addProperty("kind", kind == null ? "" : kind);
        obj.addProperty("fromId", fromId == null ? "" : fromId);
        obj.addProperty("toId", toId == null ? "" : toId);
        obj.addProperty("outcome", outcome == null ? "" : outcome);
        obj.addProperty("relBefore", relBefore);
        obj.addProperty("relAfter", relAfter);
        obj.addProperty("relDelta", relDelta);

        eventsJsonl.write(GSON.toJson(obj));
        eventsJsonl.write("\n");
    }

    // -------------------------
    // Kingdom snapshots
    // -------------------------

    /**
     * Log a per-kingdom snapshot row.
     *
     * phase can be "START", "MID", or "END" (or any label you want).
     */
    public void logKingdomSnapshot(
            String phase,
            int step,
            long gameTime,
            String kingdomId,
            String name,
            // personality
            double generosity, double greed, double trustBias, double honor, double aggression, double pragmatism,
            // soldiers
            int soldiersAlive, int soldiersMax,
            // economy totals
            double gold, double meat, double grain, double fish,
            double wood, double metal, double armor, double weapons,
            double gems, double horses, double potions
    ) throws IOException {
        kingdomSnapshotsCsv.write(runId); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(esc(phase)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(Integer.toString(step)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(Long.toString(gameTime)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(esc(kingdomId)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(esc(name)); kingdomSnapshotsCsv.write(",");

        kingdomSnapshotsCsv.write(fmt(generosity)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt(greed)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt(trustBias)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt(honor)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt(aggression)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt(pragmatism)); kingdomSnapshotsCsv.write(",");

        kingdomSnapshotsCsv.write(Integer.toString(soldiersAlive)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(Integer.toString(soldiersMax)); kingdomSnapshotsCsv.write(",");

        kingdomSnapshotsCsv.write(fmt0(gold)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(meat)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(grain)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(fish)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(wood)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(metal)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(armor)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(weapons)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(gems)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(horses)); kingdomSnapshotsCsv.write(",");
        kingdomSnapshotsCsv.write(fmt0(potions));

        kingdomSnapshotsCsv.write("\n");
    }

    // -------------------------
    // War snapshots
    // -------------------------

    public void logWarSnapshot(
            int step,
            long gameTime,
            String warKey,
            String aId,
            String bId,
            int aAlive, int aMax,
            int bAlive, int bMax,
            double moraleA, double moraleB,
            double aGold, double aFood,
            double bGold, double bFood
    ) throws IOException {
        warSnapshotsCsv.write(runId); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(Integer.toString(step)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(Long.toString(gameTime)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(esc(warKey)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(esc(aId)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(esc(bId)); warSnapshotsCsv.write(",");

        warSnapshotsCsv.write(Integer.toString(aAlive)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(Integer.toString(aMax)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(Integer.toString(bAlive)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(Integer.toString(bMax)); warSnapshotsCsv.write(",");

        warSnapshotsCsv.write(String.format(Locale.US, "%.2f", moraleA)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(String.format(Locale.US, "%.2f", moraleB)); warSnapshotsCsv.write(",");

        warSnapshotsCsv.write(fmt0(aGold)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(fmt0(aFood)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(fmt0(bGold)); warSnapshotsCsv.write(",");
        warSnapshotsCsv.write(fmt0(bFood));

        warSnapshotsCsv.write("\n");
    }

    // -------------------------
    // Flush/close
    // -------------------------

    public void flush() throws IOException {
        stepCsv.flush();
        eventsJsonl.flush();
        kingdomSnapshotsCsv.flush();
        warSnapshotsCsv.flush();
        decisionJsonl.flush();
    }

    @Override
    public void close() throws IOException {
        try { flush(); } catch (Throwable ignored) {}
        try { stepCsv.close(); } catch (Throwable ignored) {}
        try { eventsJsonl.close(); } catch (Throwable ignored) {}
        try { kingdomSnapshotsCsv.close(); } catch (Throwable ignored) {}
        try { warSnapshotsCsv.close(); } catch (Throwable ignored) {}
        try { decisionJsonl.close(); } catch (Throwable ignored) {}
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String esc(String s) {
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmt0(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    public void logDecision(JsonObject obj) throws IOException {
        obj.addProperty("runId", runId);
        decisionJsonl.write(GSON.toJson(obj));
        decisionJsonl.write("\n");
    }

}
