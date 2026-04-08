package reactors.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility functions that mirror legacy chart-shaping behavior from SimilarityHeatMapSheet.
 */
public final class SimilarityChartingUtils {

  public static final String SCORE_KEY = "Score";

  private SimilarityChartingUtils() {
    // Utility class
  }

  /**
   * Legacy-equivalent transform from pairwise system score map into chart cell map.
   *
   * <p>Input shape: systemA -> (systemB -> score[0..1])
   * <p>Output shape: "systemA-systemB" -> {"Score": score*100}
   */
  public static Map<String, Map<String, Object>> processHashForCharting(
      Map<String, Map<String, Double>> dataHash,
      Map<String, Map<String, Object>> keyHash,
      String comparisonObjectTypeX,
      String comparisonObjectTypeY,
      Map<String, String> systemLabelMap) {

    Map<String, Map<String, Object>> dataRetHash = new HashMap<>();
    if (dataHash == null || dataHash.isEmpty()) {
      return dataRetHash;
    }

    for (Map.Entry<String, Map<String, Double>> comparisonObjectEntry : dataHash.entrySet()) {
      String comparisonObjectName = toSystemLabel(comparisonObjectEntry.getKey(), systemLabelMap);
      Map<String, Double> comparisonObjectDataHash = comparisonObjectEntry.getValue();
      if (comparisonObjectDataHash == null || comparisonObjectDataHash.isEmpty()) {
        continue;
      }

      for (Map.Entry<String, Double> comparisonObjectCompEntry : comparisonObjectDataHash.entrySet()) {
        String comparisonObjectName2 = toSystemLabel(comparisonObjectCompEntry.getKey(), systemLabelMap);
        double comparisonObjectCompValue = comparisonObjectCompEntry.getValue() == null
            ? 0.0
            : comparisonObjectCompEntry.getValue();

        // Legacy behavior: skip self-comparison cells.
        if (!comparisonObjectName.equals(comparisonObjectName2)) {
          Map<String, Object> elementHash = new HashMap<>();
          elementHash.put(SCORE_KEY, comparisonObjectCompValue * 100.0);
          String key = comparisonObjectName + "-" + comparisonObjectName2;
          dataRetHash.put(key, elementHash);

          if (!keyHash.containsKey(key)) {
            Map<String, Object> keyElementHash = new HashMap<>();
            keyElementHash.put(comparisonObjectTypeX, comparisonObjectName);
            keyElementHash.put(comparisonObjectTypeY, comparisonObjectName2);
            keyHash.put(key, keyElementHash);
          }
        }
      }
    }

    return dataRetHash;
  }

  /**
   * Builds a deterministic URI-to-label map intended for UI-facing system keys.
   */
  public static Map<String, String> buildSystemLabelMap(List<String> systemUris) {
    Map<String, String> ret = new LinkedHashMap<>();
    Map<String, Integer> collisions = new HashMap<>();
    if (systemUris == null) {
      return ret;
    }

    for (String uri : systemUris) {
      String normalizedUri = normalizeUri(uri);
      String base = deriveSystemAcronym(normalizedUri);
      int seen = collisions.getOrDefault(base, 0);
      collisions.put(base, seen + 1);

      String finalLabel = seen == 0 ? base : base + "_" + (seen + 1);
      ret.put(uri, finalLabel);
      ret.put(normalizedUri, finalLabel);
    }

    return ret;
  }

  public static List<String> mapSystemsToLabels(List<String> systemUris, Map<String, String> systemLabelMap) {
    List<String> labels = new ArrayList<>();
    if (systemUris == null || systemUris.isEmpty()) {
      return labels;
    }

    for (String systemUri : systemUris) {
      labels.add(toSystemLabel(systemUri, systemLabelMap));
    }
    return labels;
  }

  public static String toSystemLabel(String rawSystemValue, Map<String, String> systemLabelMap) {
    if (rawSystemValue == null) {
      return "";
    }
    if (systemLabelMap != null && !systemLabelMap.isEmpty()) {
      String exact = systemLabelMap.get(rawSystemValue);
      if (exact != null && !exact.isEmpty()) {
        return exact;
      }

      String normalized = normalizeUri(rawSystemValue);
      String normalizedHit = systemLabelMap.get(normalized);
      if (normalizedHit != null && !normalizedHit.isEmpty()) {
        return normalizedHit;
      }

      // Fallback when map keys use mixed URI wrappers/encodings.
      for (Map.Entry<String, String> entry : systemLabelMap.entrySet()) {
        if (normalizeUri(entry.getKey()).equals(normalized)) {
          return entry.getValue();
        }
      }
    }
    return deriveSystemAcronym(rawSystemValue);
  }

  private static String normalizeUri(String rawSystemValue) {
    if (rawSystemValue == null) {
      return "";
    }
    return rawSystemValue.trim().replace("<", "").replace(">", "").replace("\"", "");
  }

  private static String deriveSystemAcronym(String rawSystemValue) {
    if (rawSystemValue == null || rawSystemValue.trim().isEmpty()) {
      return "UNKNOWN";
    }
    String cleaned = normalizeUri(rawSystemValue);
    int lastSlash = cleaned.lastIndexOf('/');
    int lastHash = cleaned.lastIndexOf('#');
    int idx = Math.max(lastSlash, lastHash);
    String token = idx >= 0 && idx < cleaned.length() - 1 ? cleaned.substring(idx + 1) : cleaned;
    token = URLDecoder.decode(token, StandardCharsets.UTF_8).trim();

    // Return the token exactly as the label (everything after the final slash/hash).
    if (token.isEmpty()) {
      return "UNKNOWN";
    }
    return token;
  }
}
