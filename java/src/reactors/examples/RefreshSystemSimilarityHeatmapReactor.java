package reactors.examples;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.ui.components.api.IPlaySheet;
import reactors.AbstractProjectReactor;

/**
 * Refreshes the System Similarity heatmap using the cached OldInsight's
 * playsheet, avoiding the 7 SPARQL queries that the initial load performs.
 *
 * <p>Requires {@link GetSystemSimilarityHeatmapReactor} to have been called
 * first so the cached insight ID is present in the var-store.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   RefreshSystemSimilarityHeatmap(selectedVars=[...], specifiedWeights={...});
 * </pre>
 */
@SuppressWarnings("deprecation")
public class RefreshSystemSimilarityHeatmapReactor extends AbstractProjectReactor {

  private static final String REFRESH_METHOD = "refreshSysSimData";

  public RefreshSystemSimilarityHeatmapReactor() {
    this.keysToGet = new String[] {"selectedVars", "specifiedWeights"};
    this.keyRequired = new int[] {0, 0};
  }

  @Override
  protected NounMetadata doExecute() {

    List<String> selectedVars = getSelectedVars();
    Map<String, Object> specifiedWeights = parseWeights(getMap("specifiedWeights"));

    // ── 1. Retrieve cached insight ID from var-store ─────────────────────────
    NounMetadata cachedIdNoun = this.insight.getVarStore()
        .get(GetSystemSimilarityHeatmapReactor.CACHED_INSIGHT_ID_KEY);

    if (cachedIdNoun == null) {
      throw new IllegalStateException(
          "No cached insight ID found in var-store. "
          + "Run GetSystemSimilarityHeatmap first.");
    }

    String cachedInsightId = cachedIdNoun.getValue().toString();

    // ── 2. Look up the OldInsight and its playsheet ──────────────────────────
    Insight cachedInsight = InsightStore.getInstance().get(cachedInsightId);
    if (cachedInsight == null) {
      throw new IllegalStateException(
          "Cached insight " + cachedInsightId + " not found in InsightStore. "
          + "The session may have expired — re-run GetSystemSimilarityHeatmap.");
    }

    if (!(cachedInsight instanceof OldInsight)) {
      throw new IllegalStateException(
          "Cached insight is " + cachedInsight.getClass().getSimpleName()
          + ", expected OldInsight.");
    }

    OldInsight oldInsight = (OldInsight) cachedInsight;
    IPlaySheet playSheet = oldInsight.getPlaySheet();

    if (playSheet == null) {
      throw new IllegalStateException(
          "Cached OldInsight has no playsheet attached.");
    }

    // ── 3. Build payload ─────────────────────────────────────────────────────
    Map<String, Object> payload = new HashMap<>();
    if (selectedVars != null && !selectedVars.isEmpty()) {
      payload.put("selectedVars", selectedVars);
    }
    // Always include specifiedWeights — the legacy playsheet method
    // accesses payload.get("specifiedWeights") unconditionally.
    payload.put("specifiedWeights",
        specifiedWeights != null ? specifiedWeights : new HashMap<>());

    // ── 4. Invoke refreshSysSimData via reflection ───────────────────────────
    //
    // We call the method directly rather than via IPlaySheet.doMethod()
    // because doMethod() silently swallows exceptions and returns null.
    Object refreshResult;
    try {
      Method refreshMethod = playSheet.getClass().getMethod(REFRESH_METHOD, Map.class);
      refreshMethod.setAccessible(true);
      refreshResult = refreshMethod.invoke(playSheet, payload);
    } catch (java.lang.reflect.InvocationTargetException ite) {
      Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
      throw new RuntimeException("refreshSysSimData threw: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke refreshSysSimData: " + e.getMessage(), e);
    }

    if (refreshResult == null) {
      throw new IllegalStateException(
          "refreshSysSimData returned null — the playsheet's paramDataHash "
          + "may not be populated.");
    }

    // ── 5. Wrap result in OutputResponse envelope ────────────────────────────
    //
    // refreshSysSimData returns a flat Map with a "data" key.
    // We wrap it to match the Get reactor's shape:
    //   { layout, pkqlOutput: { insights: [...] }, headers, data }
    Object data = null;
    if (refreshResult instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> resultMap = (Map<String, Object>) refreshResult;
      data = resultMap.get("data");
    }

    Map<String, Object> insightEntry = new HashMap<>();
    insightEntry.put("closedPanels", new ArrayList<>());
    insightEntry.put("newColumns", new HashMap<>());
    insightEntry.put("dataID", 0);
    insightEntry.put("feData", new HashMap<>());
    insightEntry.put("pkqlData", new ArrayList<>());
    insightEntry.put("clear", false);
    insightEntry.put("insightID", cachedInsightId);
    insightEntry.put("newInsights", new ArrayList<>());

    Map<String, Object> pkqlOutput = new HashMap<>();
    pkqlOutput.put("insights", new Object[] { insightEntry });

    Map<String, Object> envelope = new HashMap<>();
    envelope.put("layout", "SystemSimilarity");
    envelope.put("pkqlOutput", pkqlOutput);
    envelope.put("headers", new String[] { "System1", "System2", "Score" });
    envelope.put("data", data);

    return new NounMetadata(envelope, PixelDataType.MAP, PixelOperationType.OLD_INSIGHT);
  }

  private List<String> getSelectedVars() {
    prerna.sablecc2.om.GenRowStruct grs = this.store.getGenRowStruct("selectedVars");
    if (grs != null && !grs.isEmpty()) {
      return grs.getAllStrValues();
    }
    return null;
  }

  private Map<String, Object> parseWeights(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    Map<String, Object> weights = new HashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      if (entry.getValue() instanceof Number) {
        weights.put(entry.getKey(), entry.getValue());
      }
    }
    return weights.isEmpty() ? null : weights;
  }
}