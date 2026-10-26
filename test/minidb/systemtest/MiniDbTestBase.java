package minidb.systemtest;

import org.junit.Before;

import minidb.Database;

/**
 * Base class for all MiniDb test classes. 
 *
 */
public class MiniDbTestBase {
	/**
	 * Reset the database before each test is run.
	 */
	@Before	public void setUp() throws Exception {					
		Database.reset();
	}
	
}
