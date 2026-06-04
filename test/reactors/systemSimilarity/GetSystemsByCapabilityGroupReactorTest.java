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
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Utility;
import reactors.BaseReactorTest;

/**
 * Unit tests for {@link GetSystemsByCapabilityGroupReactor}.
 *
 * <p>Mocks static SEMOSS infrastructure (MasterDatabaseUtility, Utility,
 * WrapperManager) so no live engine is required.
 */
public class GetSystemsByCapabilityGroupReactorTest extends BaseReactorTest {

    private static final String TEST_ENGINE_ID = "133db94b-4371-4763-bff9-edf7e5ed021b";

    // URIs used in mock data.
    private static final String SYS_A_URI = "http://semoss.org/ontologies/Concept/System/SystemA";
    private static final String SYS_B_URI = "http://semoss.org/ontologies/Concept/System/SystemB";
    private static final String SYS_C_URI = "http://semoss.org/ontologies/Concept/System/SystemC";
    private static final String GRP_X_URI = "http://semoss.org/ontologies/Concept/CapabilityGroup/GroupX";
    private static final String GRP_Y_URI = "http://semoss.org/ontologies/Concept/CapabilityGroup/GroupY";

    @Mock
    private IDatabaseEngine mockEngine;
    @Mock
    private IRawSelectWrapper mockWrapper;
    @Mock
    private IHeadersDataRow mockRow1;
    @Mock
    private IHeadersDataRow mockRow2;
    @Mock
    private IHeadersDataRow mockRow3;

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
    }

    @AfterEach
    void teardownStatics() {
        if (masterDbMock != null) masterDbMock.close();
        if (utilityMock != null) utilityMock.close();
        if (wrapperManagerMock != null) wrapperManagerMock.close();
    }

    /**
     * Stubs the wrapper to return three rows:
     *   SystemA → GroupX
     *   SystemB → GroupX
     *   SystemC → GroupY
     */
    private void stubWrapperRows(List<Map<String, String>> rowData) throws Exception {
        when(mockWrapper.getHeaders()).thenReturn(new String[] { "System", "CapabilityGroup" });

        // Build IHeadersDataRow mocks from row data.
        List<IHeadersDataRow> rowMocks = new ArrayList<>();
        for (Map<String, String> row : rowData) {
            IHeadersDataRow rowMock = Mockito.mock(IHeadersDataRow.class);
            when(rowMock.getRawValues()).thenReturn(new Object[] {
                    row.get("System"), row.get("CapabilityGroup")
            });
            rowMocks.add(rowMock);
        }

        // Stub hasNext/next sequence.
        if (rowMocks.isEmpty()) {
            when(mockWrapper.hasNext()).thenReturn(false);
        } else {
            Boolean[] hasNextValues = new Boolean[rowMocks.size() + 1];
            for (int i = 0; i < rowMocks.size(); i++) hasNextValues[i] = true;
            hasNextValues[rowMocks.size()] = false;

            IHeadersDataRow[] nextValues = rowMocks.toArray(new IHeadersDataRow[0]);
            org.mockito.stubbing.OngoingStubbing<Boolean> hasNextStub =
                    when(mockWrapper.hasNext());
            for (Boolean b : hasNextValues) hasNextStub = hasNextStub.thenReturn(b);

            org.mockito.stubbing.OngoingStubbing<IHeadersDataRow> nextStub =
                    when(mockWrapper.next());
            for (IHeadersDataRow r : nextValues) nextStub = nextStub.thenReturn(r);
        }
    }

    private GetSystemsByCapabilityGroupReactor buildReactor() throws Exception {
        GetSystemsByCapabilityGroupReactor reactor = new GetSystemsByCapabilityGroupReactor();
        // Inject mock insight via reflection (same pattern as BaseReactorTest helper).
        java.lang.reflect.Field insightField = findInsightField(reactor.getClass());
        insightField.setAccessible(true);
        insightField.set(reactor, insight);
        return reactor;
    }

    private java.lang.reflect.Field findInsightField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField("insight");
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("insight");
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void testHappyPath_twoGroups() throws Exception {
        List<Map<String, String>> rows = Arrays.asList(
                row(SYS_A_URI, GRP_X_URI),
                row(SYS_B_URI, GRP_X_URI),
                row(SYS_C_URI, GRP_Y_URI));
        stubWrapperRows(rows);

        GetSystemsByCapabilityGroupReactor reactor = buildReactor();
        NounMetadata result = reactor.doExecute();

        assertNotNull(result);
        assertEquals(PixelDataType.MAP, result.getNounType());

        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>) result.getValue();

        assertEquals(2, map.size(), "Expected 2 capability groups");
        assertTrue(map.containsKey("GroupX"), "Expected GroupX key");
        assertTrue(map.containsKey("GroupY"), "Expected GroupY key");
        assertEquals(2, map.get("GroupX").size(), "GroupX should have 2 systems");
        assertEquals(1, map.get("GroupY").size(), "GroupY should have 1 system");

        // Systems in GroupX should be sorted.
        List<String> groupXSystems = map.get("GroupX");
        List<String> sorted = new ArrayList<>(groupXSystems);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, groupXSystems, "Systems within a group should be sorted");
    }

    @Test
    void testEmptyResult_returnsEmptyMap() throws Exception {
        stubWrapperRows(new ArrayList<>());

        GetSystemsByCapabilityGroupReactor reactor = buildReactor();
        NounMetadata result = reactor.doExecute();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>) result.getValue();
        assertTrue(map.isEmpty(), "Empty SPARQL result should produce empty map");
    }

    @Test
    void testMissingDatabaseParam_usesDefaultEngineId() throws Exception {
        List<Map<String, String>> rows = List.of(row(SYS_A_URI, GRP_X_URI));
        stubWrapperRows(rows);

        GetSystemsByCapabilityGroupReactor reactor = buildReactor();
        // Do NOT set database param — should fall back to default engine ID.
        NounMetadata result = reactor.doExecute();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> map = (Map<String, List<String>>) result.getValue();
        assertEquals(1, map.size());

        // Verify the default engine ID was used (Utility.getDatabase called with default).
        utilityMock.verify(() -> Utility.getDatabase("133db94b-4371-4763-bff9-edf7e5ed021b"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Map<String, String> row(String systemUri, String groupUri) {
        Map<String, String> m = new HashMap<>();
        m.put("System", systemUri);
        m.put("CapabilityGroup", groupUri);
        return m;
    }
}
