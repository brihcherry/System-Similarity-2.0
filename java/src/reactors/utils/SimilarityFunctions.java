package reactors.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reactor-local port of legacy similarity helper logic used by SysSimHeatMapSheet.
 *
 * <p>This class intentionally mirrors legacy method names and behavior so the
 * migrated reactor can be validated against known playsheet outputs.
 */
public class SimilarityFunctions {

  public static final String COUNT = "Count";
  public static final String VALUE = "Value";

  private List<Map<String, String>> list = new ArrayList<>();
  private List<String> comparisonObjectList = new ArrayList<>();

  /**
   * Executes a SPARQL SELECT and stores rows in class-level list state.
   */
  public void createTable(String dbName, String query) {
    QueryExecutor executor = new QueryExecutor(dbName);
    List<Map<String, String>> rows = executor.executeSelect(query);
    this.list = rows != null ? rows : new ArrayList<Map<String, String>>();
  }

  /**
   * Builds sorted system list from the first column (?System) of the query result.
   */
  public List<String> createComparisonObjectList(String database, String query) {
    createTable(database, query);
    List<String> retList = new ArrayList<>();
    for (Map<String, String> row : list) {
      String comparisonObjectName = row.get("System");
      if (comparisonObjectName != null && !comparisonObjectName.isEmpty()) {
        retList.add(comparisonObjectName);
      }
    }
    Collections.sort(retList);
    return retList;
  }

  public void setComparisonObjectList(List<String> comparisonObjectList) {
    this.comparisonObjectList = comparisonObjectList != null
        ? new ArrayList<>(comparisonObjectList)
        : new ArrayList<String>();
  }

  /**
   * Legacy flow for Data + BLU scoring.
   */
  public Map<String, Map<String, Double>> getDataBLUDataSet(
      String dbName,
      String dataQuery,
      String bluQuery,
      String option) {

    createTable(dbName, dataQuery);
    Map<String, Map<String, String>> dataBLUHash = makeDataHash();

    createTable(dbName, bluQuery);
    for (Map<String, String> row : list) {
      String sysName = row.get("System");
      String bluName = row.get("BLU");
      if (sysName == null || sysName.isEmpty() || bluName == null || bluName.isEmpty()) {
        continue;
      }

      Map<String, String> sysSpecDataBLUHash = dataBLUHash.get(sysName);
      if (sysSpecDataBLUHash == null) {
        sysSpecDataBLUHash = new HashMap<>();
        dataBLUHash.put(sysName, sysSpecDataBLUHash);
      }
      // Keep legacy behavior: BLUs are treated as CRM Create (C).
      sysSpecDataBLUHash.put(bluName, "C");
    }

    return makeComparisonWithCRM(dataBLUHash, option);
  }

  /**
   * Data-only variant used by legacy code paths.
   */
  public Map<String, Map<String, Double>> getDataSet(
      String dbName,
      String dataQuery,
      String option) {

    createTable(dbName, dataQuery);
    Map<String, Map<String, String>> dataBLUHash = makeDataHash();
    return makeComparisonWithCRM(dataBLUHash, option);
  }

  /**
   * Converts rows with columns System/Data/CRM into System -> (Element -> CRM).
   */
  public Map<String, Map<String, String>> makeDataHash() {
    Map<String, Map<String, String>> dataHash = new HashMap<>();
    for (Map<String, String> row : list) {
      String sysName = row.get("System");
      String dataName = row.get("Data");
      String crm = row.get("CRM");
      if (sysName == null || sysName.isEmpty() || dataName == null || dataName.isEmpty()) {
        continue;
      }

      String cleanCrm = crm == null ? "" : crm.replace("\"", "");
      Map<String, String> sysSpecDataHash = dataHash.get(sysName);
      if (sysSpecDataHash == null) {
        sysSpecDataHash = new HashMap<>();
        dataHash.put(sysName, sysSpecDataHash);
      }
      sysSpecDataHash.put(dataName, cleanCrm);
    }
    return dataHash;
  }

  /**
   * CRM-aware pairwise similarity, ported from legacy SimilarityFunctions.makeComparisonWithCRM.
   */
  public Map<String, Map<String, Double>> makeComparisonWithCRM(
      Map<String, Map<String, String>> dataBLUHash,
      String option) {

    Map<String, Map<String, Double>> dataRetHash = new HashMap<>();

    for (String sysName : comparisonObjectList) {
      if (!dataBLUHash.containsKey(sysName)) {
        continue;
      }

      Map<String, String> currentSysHash = dataBLUHash.get(sysName);
      Map<String, Double> sysElementHash = new HashMap<>();
      double totalElement = currentSysHash.size();

      for (Map.Entry<String, Map<String, String>> e : dataBLUHash.entrySet()) {
        String otherSys = e.getKey();
        Map<String, String> otherSysHash = e.getValue();
        double matchingElement = 0;

        for (Map.Entry<String, String> currentElementEntry : currentSysHash.entrySet()) {
          String element = currentElementEntry.getKey();
          String elementCRM = currentElementEntry.getValue();

          if (otherSysHash.containsKey(element)) {
            String otherElementCRM = otherSysHash.get(element);
            if (elementCRM != null && elementCRM.equals(otherElementCRM)) {
              matchingElement++;
            } else {
              boolean readSatisfy = otherElementCRM != null
                  && "R".equals(elementCRM)
                  && ("C".equals(otherElementCRM) || "M".equals(otherElementCRM));
              boolean modifySatisfy = otherElementCRM != null
                  && "M".equals(elementCRM)
                  && "C".equals(otherElementCRM);
              if (readSatisfy || modifySatisfy) {
                matchingElement++;
              }
            }
          }
        }

        if (COUNT.equals(option)) {
          sysElementHash.put(otherSys, matchingElement);
        }
        if (VALUE.equals(option)) {
          double score = totalElement == 0 ? 0.0 : matchingElement / totalElement;
          sysElementHash.put(otherSys, score);
        }
      }

      dataRetHash.put(sysName, sysElementHash);
    }

    return dataRetHash;
  }

  /**
   * Legacy binary comparison function used for Theater/Garrison and Transactional paths.
   */
  public Map<String, Map<String, Double>> stringCompareBinaryResultGetter(
      String dbName,
      String query,
      String valueCheckA,
      String valueCheckB,
      String doubleOverlapCheck) {

    Map<String, Map<String, Double>> dataRetHash = new HashMap<>();
    createTable(dbName, query);

    Map<String, String> valueHash = new HashMap<>();
    for (Map<String, String> row : list) {
      String comparisonObjectName = row.get("System");
      String value = row.values().stream()
          .filter(v -> v != null)
          .findFirst()
          .orElse("");

      // Prefer explicit variable names when available.
      if (row.containsKey("Theater")) {
        value = row.get("Theater");
      } else if (row.containsKey("Trans")) {
        value = row.get("Trans");
      }

      if (value != null) {
        value = value.replace("\"", "");
      }

      if (comparisonObjectName != null
          && comparisonObjectList.contains(comparisonObjectName)
          && value != null) {
        valueHash.put(comparisonObjectName, value);
      }
    }

    for (Map.Entry<String, String> e : valueHash.entrySet()) {
      String comparisonObjectName = e.getKey();
      String valueA = e.getValue();
      boolean comparisonObjectBooleanA = valueA.equals(valueCheckA);
      boolean comparisonObjectBooleanB = valueA.equals(valueCheckB);
      boolean doubleBoolean = valueA.equals(doubleOverlapCheck);
      Map<String, Double> comparisonObjectElementHash = new HashMap<>();

      for (Map.Entry<String, String> other : valueHash.entrySet()) {
        String comparisonObjectName2 = other.getKey();
        String valueB = other.getValue();

        boolean comparisonObjectBooleanA2 = valueB.equals(valueCheckA);
        boolean comparisonObjectBooleanB2 = valueB.equals(valueCheckB);
        boolean doubleBoolean2 = valueB.equals(doubleOverlapCheck);
        double score = 0.0;

        boolean a_a = comparisonObjectBooleanA && comparisonObjectBooleanA2;
        boolean b_b = comparisonObjectBooleanB && comparisonObjectBooleanB2;
        boolean c_a = doubleBoolean && comparisonObjectBooleanA2;
        boolean c_b = doubleBoolean && comparisonObjectBooleanB2;
        boolean c_c = doubleBoolean && doubleBoolean2;

        if (a_a || b_b || c_a || c_b || c_c) {
          score = 1.0;
        }
        if (comparisonObjectName.equals(comparisonObjectName2)) {
          score = 1.0;
        }

        comparisonObjectElementHash.put(comparisonObjectName2, score);
      }

      dataRetHash.put(comparisonObjectName, comparisonObjectElementHash);
    }

    return dataRetHash;
  }

  /**
   * Generic set-overlap score used by BP/Activity/User/UI query groups.
   */
  public Map<String, Map<String, Double>> compareObjectParameterScore(
      String dbName,
      String query,
      String option) {

    createTable(dbName, query);
    Map<String, Map<String, Double>> dataRetHash = new HashMap<>();
    Map<String, List<String>> dataStoreHash = new HashMap<>();

    for (Map<String, String> row : list) {
      String comparisonObjectName = row.get("System");
      if (comparisonObjectName == null || comparisonObjectName.isEmpty()) {
        continue;
      }

      String elementName = "";
      if (row.containsKey("BusinessProcess")) {
        elementName = row.get("BusinessProcess");
      } else if (row.containsKey("Activity")) {
        elementName = row.get("Activity");
      } else if (row.containsKey("Personnel")) {
        elementName = row.get("Personnel");
      } else if (row.containsKey("UserInterface")) {
        elementName = row.get("UserInterface");
      } else {
        for (Map.Entry<String, String> entry : row.entrySet()) {
          if (!"System".equals(entry.getKey())) {
            elementName = entry.getValue();
            break;
          }
        }
      }

      if (elementName == null) {
        elementName = "";
      }

      List<String> elementArray = dataStoreHash.get(comparisonObjectName);
      if (elementArray == null) {
        elementArray = new ArrayList<>();
        dataStoreHash.put(comparisonObjectName, elementArray);
      }
      elementArray.add(elementName);
    }

    for (String comparisonObjectName : comparisonObjectList) {
      if (!dataStoreHash.containsKey(comparisonObjectName)) {
        continue;
      }

      List<String> currentComparisonObjectList = dataStoreHash.get(comparisonObjectName);
      Map<String, Double> comparisonObjectElementHash = new HashMap<>();
      double totalElement = currentComparisonObjectList.size();

      for (Map.Entry<String, List<String>> comparisonObjectArrayEntry : dataStoreHash.entrySet()) {
        String comparisonObjectName2 = comparisonObjectArrayEntry.getKey();
        List<String> otherComparisonObjectList = comparisonObjectArrayEntry.getValue();
        double matchingElement = 0;

        for (String element : currentComparisonObjectList) {
          if (otherComparisonObjectList.contains(element)) {
            matchingElement++;
          }
        }

        if (COUNT.equals(option)) {
          comparisonObjectElementHash.put(comparisonObjectName2, matchingElement);
        }
        if (VALUE.equals(option)) {
          double score = totalElement == 0 ? 0.0 : matchingElement / totalElement;
          comparisonObjectElementHash.put(comparisonObjectName2, score);
        }
      }

      dataRetHash.put(comparisonObjectName, comparisonObjectElementHash);
    }

    return dataRetHash;
  }
}
