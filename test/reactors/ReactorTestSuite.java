package reactors;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;


/**
 * Test suite that runs all reactor tests in the project.
 * This suite aggregates all reactor test classes and can be executed to run
 * all tests at once for comprehensive validation.
 * 
 * <p>
 * To run this suite:
 * 
 * <pre>
 * mvn test -Dtest=ReactorTestSuite
 * </pre>
 * 
 * <p>
 * Included test classes:
 * <ul>
 * </ul>
 */
@Suite
@SuiteDisplayName("Reactor Test Suite")
@SelectClasses({

})
public class ReactorTestSuite {
    // This class remains empty, it is used only as a holder for the above
    // annotations
}
