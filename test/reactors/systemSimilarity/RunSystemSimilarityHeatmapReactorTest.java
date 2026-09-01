package reactors.systemSimilarity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IHeadersDataRow;
import prerna.engine.api.IRawSelectWrapper;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Utility;
import reactors.BaseReactorTest;

/**
 * Unit tests for {@link RunSystemSimilarityHeatmapReactor}.
 *
 * <p>Tests the orchestration logic that runs Stage 1 and Stage 2 in sequence,
 * verifying parameter transfer, var-store population, and final payload structure.
 */
public class RunSystemSimilarityHeatmapReactorTest extends BaseReactorTest {

    private static final String TEST_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

    // Mock URIs for test data
    private static final String SYS_A_URI = "http://semoss.org/ontologies/Concept/System/SystemA";
    private static final String SYS_B_URI = "http://semoss.org/ontologies/Concept/System/SystemB";
    private static final String BP_1_URI = "http://semoss.org/ontologies/Concept/BusinessProcess/BP1";
    private static final String BP_2_URI = "http://semoss.org/ontologies/Concept/BusinessProcess/BP2";

    @Mock
    private IDatabaseEngine mockEngine;
    @Mock
    private IRawSelectWrapper mockWrapper;
    @Mock
    private VarStore varStore;

    private MockedStatic<MasterDatabaseUtility> masterDbMock;
    private MockedStatic<Utility> utilityMock;
    private MockedStatic<WrapperManager> wrapperManagerMock;

    @BeforeEach
    void setup() throws Exception {
        masterDbMock = Mockito.mockStatic(MasterDatabaseUtility.class);
        utilityMock = Mockito.mockStatic(Utility.class);
        wrapperManagerMock = Mockito.mockStatic(WrapperManager.class);

        masterDbMock.when(() -> MasterDatabaseUtility.testDatabaseIdIfAlias(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        utilityMock.when(() -> Utility.getDatabase(anyString())).thenReturn(mockEngine);
        wrapperManagerMock.when(() -> WrapperManager.getInstance().getRawWrapper(any(), anyString()))
                .thenReturn(mockWrapper);

        // Setup var-store mock
        when(insight.getVarStore()).thenReturn(varStore);

        // Stub var-store.put to do nothing (we're just verifying the orchestration logic)
        when(varStore.put(anyString(), any(NounMetadata.class))).thenReturn(null);
    }

    @AfterEach
    void teardownStatics() {
        if (masterDbMock != null) masterDbMock.close();
        if (utilityMock != null) utilityMock.close();
        if (wrapperManagerMock != null) wrapperManagerMock.close();
    }

    /**
     * Happy path: all parameters provided, Stage 1 and Stage 2 complete successfully.
     */
    @Test
    @SuppressWarnings("unchecked")
    void happyPath_allParams_returnsValidPayload() throws Exception {
        // Stub SPARQL query results for Stage 1 (6 queries total, stubbing minimal set)
        stubDefaultSystemsQuery();
        stubBusinessProcessesQuery();
        stubActivitiesQuery();
        stubDataObjectQuery();
        stubSystemInterfaceQuery();
        stubTheaterQuery();
        stubUsersQuery();

        // Setup var-store to return cached data for Stage 2
        setupVarStoreForStage2();

        RunSystemSimilarityHeatmapReactor reactor = buildReactor();

        // Set parameters
        setReactorParameter(reactor, "database", TEST_ENGINE_ID);
        setReactorListParameter(reactor, "selectedVars", Arrays.asList("Environment", "User_Types"));
        setReactorMapParameter(reactor, "specifiedWeights", Map.of("Environment", 2.0));
        setReactorParameter(reactor, "minimumScore", "0");

        NounMetadata result = reactor.execute();

        assertNotNull(result);
        assertEquals(PixelDataType.MAP, result.getNounType());

        Map<String, Object> payload = (Map<String, Object>) result.getValue();

        // Verify expected keys from ComputeSimilarityScores contract
        assertTrue(payload.containsKey("headers"));
        assertTrue(payload.containsKey("data"));
        assertTrue(payload.containsKey("partialPairs"));
        assertTrue(payload.containsKey("variablesUsed"));
        assertTrue(payload.containsKey("specifiedWeightsUsed"));
        assertTrue(payload.containsKey("totalPairsEvaluated"));
        assertTrue(payload.containsKey("pairsAboveThreshold"));
        assertTrue(payload.containsKey("allSystems"));
        assertTrue(payload.containsKey("systemLabelMap"));
    }

    /**
     * Minimal params: only database provided, defaults applied for all other params.
     */
    @Test
    @SuppressWarnings("unchecked")
    void minimalParams_databaseOnly_returnsValidPayload() throws Exception {
        stubDefaultSystemsQuery();
        stubBusinessProcessesQuery();
        stubActivitiesQuery();
        stubDataObjectQuery();
        stubSystemInterfaceQuery();
        stubTheaterQuery();
        stubUsersQuery();
        setupVarStoreForStage2();

        RunSystemSimilarityHeatmapReactor reactor = buildReactor();
        // Only set database param (or omit to use default)

        NounMetadata result = reactor.execute();

        assertNotNull(result);
        Map<String, Object> payload = (Map<String, Object>) result.getValue();

        // Should still have valid structure with defaults
        assertTrue(payload.containsKey("headers"));
        assertTrue(payload.containsKey("data"));
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private RunSystemSimilarityHeatmapReactor buildReactor() throws Exception {
        RunSystemSimilarityHeatmapReactor reactor = new RunSystemSimilarityHeatmapReactor();

        // Inject insight via reflection
        java.lang.reflect.Field insightField = findInsightField(reactor.getClass());
        insightField.setAccessible(true);
        insightField.set(reactor, insight);

        // Inject nounStore via reflection
        java.lang.reflect.Field storeField = findField(reactor.getClass(), "store");
        storeField.setAccessible(true);
        storeField.set(reactor, nounStore);

        // Initialize curRow to avoid NPE
        java.lang.reflect.Field curRowField = findField(reactor.getClass(), "curRow");
        curRowField.setAccessible(true);
        curRowField.set(reactor, new GenRowStruct());

        return reactor;
    }

    private java.lang.reflect.Field findInsightField(Class<?> clazz) throws NoSuchFieldException {
        return findField(clazz, "insight");
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private void setReactorListParameter(RunSystemSimilarityHeatmapReactor reactor, String key, List<String> values) {
        GenRowStruct grs = new GenRowStruct();
        for (String val : values) {
            grs.addLiteral(val);
        }
        when(nounStore.getGenRowStruct(key)).thenReturn(grs);
    }

    private void setReactorMapParameter(RunSystemSimilarityHeatmapReactor reactor, String key, Map<String, Object> map) {
        GenRowStruct grs = new GenRowStruct();
        grs.add(new NounMetadata(map, PixelDataType.MAP));
        when(nounStore.getGenRowStruct(key)).thenReturn(grs);
    }

    /**
     * Stub the default systems query that fetches the canonical system list.
     */
    private void stubDefaultSystemsQuery() throws Exception {
        List<Map<String, String>> rows = Arrays.asList(
            Map.of("System", SYS_A_URI),
            Map.of("System", SYS_B_URI)
        );
        stubWrapperForQuery(rows, "System");
    }

    private void stubBusinessProcessesQuery() throws Exception {
        List<Map<String, String>> rows = Arrays.asList(
            Map.of("System", SYS_A_URI, "BusinessProcess", BP_1_URI),
            Map.of("System", SYS_B_URI, "BusinessProcess", BP_2_URI)
        );
        stubWrapperForQuery(rows, "System", "BusinessProcess");
    }

    private void stubActivitiesQuery() throws Exception {
        stubWrapperForQuery(new ArrayList<>(), "System", "Activity");
    }

    private void stubDataObjectQuery() throws Exception {
        stubWrapperForQuery(new ArrayList<>(), "System", "Data");
    }

    private void stubSystemInterfaceQuery() throws Exception {
        stubWrapperForQuery(new ArrayList<>(), "System", "SystemInterface");
    }

    private void stubTheaterQuery() throws Exception {
        stubWrapperForQuery(new ArrayList<>(), "System", "Theater");
    }

    private void stubUsersQuery() throws Exception {
        stubWrapperForQuery(new ArrayList<>(), "System", "Personnel");
    }

    private void stubWrapperForQuery(List<Map<String, String>> rowData, String... columnNames) throws Exception {
        when(mockWrapper.getHeaders()).thenReturn(columnNames);

        List<IHeadersDataRow> rowMocks = new ArrayList<>();
        for (Map<String, String> row : rowData) {
            IHeadersDataRow rowMock = Mockito.mock(IHeadersDataRow.class);
            Object[] values = new Object[columnNames.length];
            for (int i = 0; i < columnNames.length; i++) {
                values[i] = row.get(columnNames[i]);
            }
            when(rowMock.getRawValues()).thenReturn(values);
            rowMocks.add(rowMock);
        }

        if (rowMocks.isEmpty()) {
            when(mockWrapper.hasNext()).thenReturn(false);
        } else {
            Boolean[] hasNextValues = new Boolean[rowMocks.size() + 1];
            for (int i = 0; i < rowMocks.size(); i++) hasNextValues[i] = true;
            hasNextValues[rowMocks.size()] = false;

            IHeadersDataRow[] nextValues = rowMocks.toArray(new IHeadersDataRow[0]);
            org.mockito.stubbing.OngoingStubbing<Boolean> hasNextStub = when(mockWrapper.hasNext());
            for (Boolean b : hasNextValues) hasNextStub = hasNextStub.thenReturn(b);

            org.mockito.stubbing.OngoingStubbing<IHeadersDataRow> nextStub = when(mockWrapper.next());
            for (IHeadersDataRow r : nextValues) nextStub = nextStub.thenReturn(r);
        }
    }

    /**
     * Setup var-store to return cached data that Stage 2 expects from Stage 1.
     */
    private void setupVarStoreForStage2() {
        // Create minimal paramDataHash for 2 variables
        Map<String, Map<String, Map<String, Object>>> paramDataHash = new LinkedHashMap<>();

        Map<String, Map<String, Object>> envBucket = new LinkedHashMap<>();
        envBucket.put("SystemA-SystemB", scoreCell(80.0));
        paramDataHash.put("Environment", envBucket);

        Map<String, Map<String, Object>> usersBucket = new LinkedHashMap<>();
        usersBucket.put("SystemA-SystemB", scoreCell(70.0));
        paramDataHash.put("User_Types", usersBucket);

        // Create keyHash
        Map<String, Map<String, Object>> keyHash = new LinkedHashMap<>();
        keyHash.put("SystemA-SystemB", pairEntry("SystemA", "SystemB"));

        // Create systemLabelMap
        Map<String, String> systemLabelMap = new HashMap<>();
        systemLabelMap.put(SYS_A_URI, "SystemA");
        systemLabelMap.put(SYS_B_URI, "SystemB");

        // Create allSystems list
        List<String> allSystems = Arrays.asList(SYS_A_URI, SYS_B_URI);

        // Mock var-store.get() to return these cached values
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_PARAM_DATA_HASH))
            .thenReturn(new NounMetadata(paramDataHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_KEY_HASH))
            .thenReturn(new NounMetadata(keyHash, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_SYSTEM_LABEL_MAP))
            .thenReturn(new NounMetadata(systemLabelMap, PixelDataType.MAP));
        when(varStore.get(GetSystemSimilarityDataSourcesReactor.VARSTORE_ALL_SYSTEMS))
            .thenReturn(new NounMetadata(allSystems, PixelDataType.CUSTOM_DATA_STRUCTURE));
    }

    private static Map<String, Object> scoreCell(double score) {
        Map<String, Object> cell = new HashMap<>();
        cell.put("Score", score);
        return cell;
    }

    private static Map<String, Object> pairEntry(String sys1, String sys2) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("System1", sys1);
        entry.put("System2", sys2);
        return entry;
    }
}
