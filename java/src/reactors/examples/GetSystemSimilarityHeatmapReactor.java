package reactors.examples;

import java.util.Map;

import prerna.reactor.PixelPlanner;
import prerna.reactor.legacy.playsheets.RunPlaysheetReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/**
 * Reactor that executes the legacy System Similarity heatmap playsheet via
 * {@link RunPlaysheetReactor} and returns the raw playsheet output.
 *
 * <p>This reactor mirrors the noun-store contract established by
 * {@code OldEngineResource.createOutput()} while reusing the caller's live
 * {@link prerna.om.Insight} context (user, session, var-store) instead of
 * constructing a synthetic one.
 *
 * <h3>Pixel call (from frontend {@code runPixel})</h3>
 * <pre>
 *   GetSystemSimilarityHeatmap(id=["341"], database=["133db94b-4371-4763-bff9-edf7e5ed021b"]);
 * </pre>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li>{@code id}       — <b>required</b> — the legacy playsheet/insight ID (e.g. {@code "341"})</li>
 *   <li>{@code database} — <b>optional</b> — the TAP_Core_Data engine UUID;
 *       defaults to {@code "133db94b-4371-4763-bff9-edf7e5ed021b"}</li>
 * </ul>
 *
 * <h3>Noun-store wiring (mirrors OldEngineResource.createOutput)</h3>
 * <table>
 *   <tr><th>Noun key</th><th>Value</th></tr>
 *   <tr><td>{@code "database"} ({@link ReactorKeysEnum#DATABASE})</td><td>engine UUID</td></tr>
 *   <tr><td>{@code "app"} (legacy alias)</td><td>engine UUID</td></tr>
 *   <tr><td>{@code "id"} ({@link ReactorKeysEnum#ID})</td><td>playsheet ID</td></tr>
 * </table>
 *
 * @see RunPlaysheetReactor
 * @see reactors.AbstractProjectReactor
 */
public class GetSystemSimilarityHeatmapReactor extends AbstractProjectReactor {

  /** Default TAP_Core_Data engine UUID (confirmed via server debug session). */
  private static final String DEFAULT_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

  /**
   * Legacy noun key that {@link RunPlaysheetReactor} falls back to when the
   * {@code "project"} key is not present. Must hold the same engine UUID as
   * {@link ReactorKeysEnum#DATABASE}.
   */
  private static final String APP_KEY = "app";

  /**
   * Var-store key used to cache the runtime insight ID produced by
   * {@link RunPlaysheetReactor}. The Refresh reactor reads this key to
   * look up the cached OldInsight (and its populated paramDataHash)
   * from {@link prerna.om.InsightStore} without re-executing the 7
   * SPARQL queries.
   */
  public static final String CACHED_INSIGHT_ID_KEY = "SYS_SIM_CACHED_INSIGHT_ID";

  public GetSystemSimilarityHeatmapReactor() {
    this.keysToGet = new String[]{
        ReactorKeysEnum.ID.getKey(),       // "id"       — playsheet ID (required)
        ReactorKeysEnum.DATABASE.getKey()  // "database" — engine UUID  (optional)
    };
    this.keyRequired = new int[]{1, 0};
  }

  /**
   * Builds, configures, and executes a {@link RunPlaysheetReactor} that reproduces
   * the same execution path as {@code OldEngineResource.createOutput()}.
   *
   * @return {@link NounMetadata} containing the playsheet result map
   */
  @Override
  protected NounMetadata doExecute() {

    // ── 1. Read incoming pixel parameters ────────────────────────────────────────
    String playsheetId = this.keyValue.get(ReactorKeysEnum.ID.getKey());
    String engineId    = this.keyValue.get(ReactorKeysEnum.DATABASE.getKey());

    if (engineId == null || engineId.trim().isEmpty()) {
      engineId = DEFAULT_ENGINE_ID;
    }

    // ── 2. Create and initialise RunPlaysheetReactor ─────────────────────────────
    RunPlaysheetReactor playsheetRunReactor = new RunPlaysheetReactor();
    playsheetRunReactor.In();

    // Reuse the live insight so the playsheet inherits the caller's user/session.
    playsheetRunReactor.setInsight(this.insight);

    // Wire a PixelPlanner backed by the same var-store the insight already owns,
    // matching the planner setup OldEngineResource performed on its dummy insight.
    PixelPlanner planner = new PixelPlanner();
    planner.setVarStore(this.insight.getVarStore());
    playsheetRunReactor.setPixelPlanner(planner);

    // ── 3. Populate the noun store (mirrors OldEngineResource.createOutput) ──────
    //
    //  OldEngineResource wired the following nouns:
    //    "database" → engineId (UUID)       ← ReactorKeysEnum.DATABASE.getKey()
    //    "app"      → engineId (UUID)       ← legacy fallback used by RunPlaysheetReactor
    //    "id"       → insightId ("341")     ← ReactorKeysEnum.ID.getKey()
    //
    //  RunPlaysheetReactor.execute() resolves the project by first checking the
    //  "project" key, then falling back to store.getGenRowStruct("app").get(0).
    //  Populating both "database" and "app" ensures backward compatibility.

    GenRowStruct grsEngine = new GenRowStruct();
    grsEngine.add(new NounMetadata(engineId, PixelDataType.CONST_STRING));
    playsheetRunReactor.getNounStore().addNoun(ReactorKeysEnum.DATABASE.getKey(), grsEngine);
    playsheetRunReactor.getNounStore().addNoun(APP_KEY, grsEngine);

    GenRowStruct grsId = new GenRowStruct();
    grsId.add(new NounMetadata(playsheetId, PixelDataType.CONST_STRING));
    playsheetRunReactor.getNounStore().addNoun(ReactorKeysEnum.ID.getKey(), grsId);

    // ── 4. Execute and return ────────────────────────────────────────────────────
    NounMetadata result = playsheetRunReactor.execute();

    // ── 5. Cache the runtime insight ID in the caller's var-store ─────────────────
    //
    // RunPlaysheetReactor stores the fully-initialised OldInsight (with its
    // populated paramDataHash) in InsightStore and embeds its runtime ID at
    //   pkqlOutput → insights[0] → insightID
    //
    // We extract that ID and persist it in the caller's var-store so the
    // RefreshSystemSimilarityHeatmapReactor can retrieve the cached insight
    // without re-running the 7 SPARQL queries.
    String cachedInsightId = extractRuntimeInsightId(result);
    if (cachedInsightId != null) {
      this.insight.getVarStore().put(
          CACHED_INSIGHT_ID_KEY,
          new NounMetadata(cachedInsightId, PixelDataType.CONST_STRING));
    }

    return result;
  }

  /**
   * Extracts the runtime insight ID from the nested response map produced by
   * {@link RunPlaysheetReactor}:
   * {@code result.value → pkqlOutput → insights[0] → insightID}.
   *
   * @return the runtime insight ID string, or {@code null} if not found
   */
  @SuppressWarnings("unchecked")
  private static String extractRuntimeInsightId(NounMetadata result) {
    try {
      Map<String, Object> outer = (Map<String, Object>) result.getValue();
      Map<String, Object> pkqlOutput = (Map<String, Object>) outer.get("pkqlOutput");
      Object[] insights = (Object[]) pkqlOutput.get("insights");
      Map<String, Object> first = (Map<String, Object>) insights[0];
      return (String) first.get("insightID");
    } catch (Exception e) {
      return null;
    }
  }
}
