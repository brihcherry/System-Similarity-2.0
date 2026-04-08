package reactors.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IHeadersDataRow;
import prerna.engine.api.IRawSelectWrapper;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

/**
 * Utility class for executing SPARQL SELECT queries via the WrapperManager.
 *
 * <p>This class encapsulates the standard pattern for executing SPARQL queries
 * against an RDF engine via the reactor framework's WrapperManager adapter.
 *
 * <p>Usage:
 * <pre>
 *   QueryExecutor executor = new QueryExecutor(engineId);
 *   List<Map<String, String>> results = executor.executeSelect(sparqlQuery);
 * </pre>
 *
 * @see prerna.rdf.engine.wrappers.WrapperManager
 */
public class QueryExecutor {

  private static final Logger LOGGER = LogManager.getLogger(QueryExecutor.class);

  private String engineId;
  private IDatabaseEngine engine;

  /**
   * Constructor: Initialize the QueryExecutor with an engine ID.
   *
   * @param engineId UUID of the RDF engine to query
   * @throws IllegalArgumentException if engine cannot be resolved
   */
  public QueryExecutor(String engineId) {
    this.engineId = engineId;
    resolveEngine();
  }

  /**
   * Resolve the engine UUID to an actual IDatabaseEngine instance.
   * Handles engine aliases and validation.
   *
   * @throws IllegalArgumentException if engine not found or access denied
   */
  private void resolveEngine() {
    if (engineId == null || engineId.trim().isEmpty()) {
      throw new IllegalArgumentException("Engine ID cannot be null or empty");
    }

    // Test and resolve any alias
    String resolvedId = MasterDatabaseUtility.testDatabaseIdIfAlias(engineId);

    // Retrieve the actual engine
    this.engine = Utility.getDatabase(resolvedId);

    if (this.engine == null) {
      throw new IllegalArgumentException("Cannot resolve engine with ID: " + engineId);
    }

    LOGGER.debug("QueryExecutor initialized with engine: " + resolvedId);
  }

  /**
   * Execute a SPARQL SELECT query and return results as a list of row maps.
   *
   * <p>Each row is a Map where keys are SPARQL variable names and values are
   * the bound URIs or literals. Results are ordered for consistency.
   *
   * @param query SPARQL SELECT query string
   * @return List of result rows; empty list if no results
   * @throws RuntimeException if query execution fails
   */
  public List<Map<String, String>> executeSelect(String query) {
    List<Map<String, String>> results = new ArrayList<>();

    if (query == null || query.trim().isEmpty()) {
      throw new IllegalArgumentException("Query cannot be null or empty");
    }

    IRawSelectWrapper wrapper = null;
    try {
      LOGGER.debug("Executing SPARQL query against engine: " + engineId);

      // Use raw wrapper APIs (non-deprecated) for query execution.
      wrapper = WrapperManager.getInstance().getRawWrapper(engine, query);

      if (wrapper == null) {
        throw new RuntimeException("Failed to obtain query wrapper from WrapperManager");
      }

      String[] variableNames = wrapper.getHeaders();

      if (variableNames == null || variableNames.length == 0) {
        LOGGER.warn("Query returned no variables. Query: " + query);
        return results; // Empty result set
      }

      // Iterate through result rows
      try {
        while (wrapper.hasNext()) {
          IHeadersDataRow statement = wrapper.next();
          Map<String, String> row = new TreeMap<>(); // Ordered map for consistency
          Object[] values = statement.getRawValues();
          if (values == null) {
            values = statement.getValues();
          }

          for (int i = 0; i < variableNames.length; i++) {
            if (values == null || i >= values.length) {
              continue;
            }
            Object value = values[i];
            if (value != null) {
              row.put(variableNames[i], value.toString());
            }
          }

          if (!row.isEmpty()) {
            results.add(row);
          }
        }
      } catch (RuntimeException e) {
        LOGGER.error("Error iterating through query results", e);
        throw new RuntimeException("Query result iteration failed: " + e.getMessage(), e);
      }

      LOGGER.debug("Query completed. Returned " + results.size() + " rows");

    } catch (Exception e) {
      LOGGER.error("SPARQL query execution failed. Engine: " + engineId + ", Query: " + query, e);
      throw new RuntimeException("Query execution error: " + e.getMessage(), e);
    } finally {
      if (wrapper != null) {
        try {
          wrapper.close();
        } catch (Exception closeEx) {
          LOGGER.warn("Failed to close raw query wrapper cleanly", closeEx);
        }
      }
    }

    return results;
  }

  /**
   * Get the resolved engine instance (for advanced use cases).
   *
   * @return the IDatabaseEngine instance
   */
  public IDatabaseEngine getEngine() {
    return this.engine;
  }

  /**
   * Get the resolved engine ID (may differ from constructor arg if alias was used).
   *
   * @return the engine UUID
   */
  public String getEngineId() {
    return this.engineId;
  }
}
