package reactors.debug;

import java.lang.reflect.Field;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.ui.components.api.IPlaySheet;
import reactors.AbstractProjectReactor;
import reactors.examples.GetSystemSimilarityHeatmapReactor;

/**
 * Extracts the {@code keyHash} from the cached OldInsight's playsheet via
 * reflection and returns it as a serializable Map.
 *
 * <p>The {@code keyHash} maps each pair key (e.g. "SAMHS-EIS-DENCAS") to a
 * hash containing the pre-split system names:
 * <pre>
 *   { "System1": "SAMHS-EIS", "System2": "DENCAS" }
 * </pre>
 *
 * <p>This provides a definitive mapping for ALL pair keys, resolving the
 * ambiguity when system names contain hyphens.
 *
 * <h3>Pixel call</h3>
 * <pre>
 *   GetKeyHash();
 * </pre>
 */
@SuppressWarnings("deprecation")
public class GetKeyHashReactor extends AbstractProjectReactor {

  private static final String KEY_HASH_FIELD = "keyHash";

  public GetKeyHashReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  protected NounMetadata doExecute() {

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

    // ── 3. Extract keyHash via reflection ────────────────────────────────────
    Object rawKeyHash = extractField(playSheet, KEY_HASH_FIELD);

    if (rawKeyHash == null) {
      throw new IllegalStateException(
          "Field '" + KEY_HASH_FIELD + "' not found or is null on "
          + playSheet.getClass().getName());
    }

    // ── 4. Convert to serializable Map ───────────────────────────────────────
    Map<String, Object> serializable = toSerializableMap(rawKeyHash);

    // ── 5. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("cachedInsightId", cachedInsightId);
    result.put("playsheetClass", playSheet.getClass().getName());
    result.put("entryCount", serializable.size());
    result.put("data", serializable);

    return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
  }

  private static Object extractField(Object target, String fieldName) {
    Class<?> current = target.getClass();
    while (current != null && current != Object.class) {
      try {
        Field field = current.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new RuntimeException(
            "Cannot access field '" + fieldName + "' on " + current.getName(), e);
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toSerializableMap(Object obj) {
    Map<String, Object> result = new LinkedHashMap<>();

    Map<?, ?> source;
    if (obj instanceof Hashtable) {
      source = (Hashtable<?, ?>) obj;
    } else if (obj instanceof Map) {
      source = (Map<?, ?>) obj;
    } else {
      result.put("_raw", obj.toString());
      return result;
    }

    for (Map.Entry<?, ?> entry : source.entrySet()) {
      String key = String.valueOf(entry.getKey());
      Object value = entry.getValue();

      if (value instanceof Map || value instanceof Hashtable) {
        result.put(key, toSerializableMap(value));
      } else if (value == null) {
        result.put(key, null);
      } else if (value instanceof Number) {
        result.put(key, value);
      } else {
        result.put(key, value.toString());
      }
    }

    return result;
  }
}
