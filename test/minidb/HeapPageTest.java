package minidb;

import minidb.TestUtil.SkeletonFile;
import minidb.systemtest.MiniDbTestBase;
import minidb.systemtest.SystemTestUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import junit.framework.JUnit4TestAdapter;

public class HeapPageTest extends MiniDbTestBase {
    private HeapPageId pid;

    public static final int[][] EXAMPLE_VALUES = new int[][] {
        { 31933, 862 },
        { 29402, 56883 },
        { 1468, 5825 },
        { 17876, 52278 },
        { 6350, 36090 },
        { 34784, 43771 },
        { 28617, 56874 },
        { 19209, 23253 },
        { 56462, 24979 },
        { 51440, 56685 },
        { 3596, 62307 },
        { 45569, 2719 },
        { 22064, 43575 },
        { 42812, 44947 },
        { 22189, 19724 },
        { 33549, 36554 },
        { 9086, 53184 },
        { 42878, 33394 },
        { 62778, 21122 },
        { 17197, 16388 }
    };

    public static final byte[] EXAMPLE_DATA;
    static {
        // Build the input table
        ArrayList<ArrayList<Integer>> table = new ArrayList<ArrayList<Integer>>();
        for (int[] tuple : EXAMPLE_VALUES) {
            ArrayList<Integer> listTuple = new ArrayList<Integer>();
            for (int value : tuple) {
                listTuple.add(value);
            }
            table.add(listTuple);
        }

        // Convert it to a HeapFile and read in the bytes
        try {
            File temp = File.createTempFile("table", ".dat");
            temp.deleteOnExit();
            HeapFileEncoder.convert(table, temp, BufferPool.PAGE_SIZE, 2);
            EXAMPLE_DATA = TestUtil.readFileBytes(temp.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Set up initial resources for each unit test.
     */
    @Before public void addTable() throws Exception {
        this.pid = new HeapPageId(-1, -1);
        Database.getCatalog().addTable(new SkeletonFile(-1, Utility.getTupleDesc(2)), SystemTestUtil.getUUID());
    }

    /**
     * Unit test for HeapPage.getId()
     */
    @Test public void getId() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        assertEquals(pid, page.getId());
    }

    /**
     * Unit test for HeapPage.iterator()
     */
    @Test public void testIterator() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        Iterator<Tuple> it = page.iterator();

        int row = 0;
        while (it.hasNext()) {
            Tuple tup = it.next();
            IntField f0 = (IntField) tup.getField(0);
            IntField f1 = (IntField) tup.getField(1);

            assertEquals(EXAMPLE_VALUES[row][0], f0.getValue());
            assertEquals(EXAMPLE_VALUES[row][1], f1.getValue());
            row++;
        }
    }

    /**
     * Unit test for HeapPage.getNumEmptySlots()
     */
    @Test public void getNumEmptySlots() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        assertEquals(484, page.getNumEmptySlots());
    }

    /**
     * Unit test for HeapPage.getSlot()
     */
    @Test public void getSlot() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);

        for (int i = 0; i < 20; ++i)
            assertTrue(page.getSlot(i));

        for (int i = 20; i < 504; ++i)
            assertFalse(page.getSlot(i));
    }

    /**
     * Unit test for HeapPage.isDirty()
     */
    @Test public void testDirty() throws Exception {
        TransactionId tid = new TransactionId();
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        page.markDirty(true, tid);
        TransactionId dirtier = page.isDirty();
        assertEquals(true, dirtier != null);
        assertEquals(true, dirtier == tid);

        page.markDirty(false, tid);
        dirtier = page.isDirty();
        assertEquals(false, dirtier != null);
    }

    /**
     * Unit test for HeapPage.addTuple()
     */
    @Test public void addTuple() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        int free = page.getNumEmptySlots();

        // NOTE(ghuo): this nested loop existence check is slow, but it
        // shouldn't make a difference for n = 504 slots.

        for (int i = 0; i < free; ++i) {
            Tuple addition = Utility.getHeapTuple(i, 2);
            page.addTuple(addition);
            assertEquals(free-i-1, page.getNumEmptySlots());

            // loop through the iterator to ensure that the tuple actually exists
            // on the page
            Iterator<Tuple >it = page.iterator();
            boolean found = false;
            while (it.hasNext()) {
                Tuple tup = it.next();
                if (TestUtil.compareTuples(addition, tup)) {
                    found = true;

                    // verify that the RecordId is sane
                    assertEquals(page.getId(), tup.getRecordId().getPageId());
                    break;
                }
            }
            assertTrue(found);
        }

        // now, the page should be full.
        try {
            page.addTuple(Utility.getHeapTuple(0, 2));
            throw new Exception("page should be full; expected DbException");
        } catch (DbException e) {
            // explicitly ignored
        }
    }

    /**
     * Unit test for HeapPage.deleteTuple() with false tuples
     */
    @Test(expected=DbException.class)
        public void deleteNonexistentTuple() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        page.deleteTuple(Utility.getHeapTuple(2, 2));
    }

    /**
     * Unit test for HeapPage.deleteTuple()
     */
    @Test public void deleteTuple() throws Exception {
        HeapPage page = new HeapPage(pid, EXAMPLE_DATA);
        int free = page.getNumEmptySlots();

        // first, build a list of the tuples on the page.
        Iterator<Tuple> it = page.iterator();
        LinkedList<Tuple> tuples = new LinkedList<Tuple>();
        while (it.hasNext())
            tuples.add(it.next());
        Tuple first = tuples.getFirst();

        // now, delete them one-by-one from both the front and the end.
        int deleted = 0;
        while (tuples.size() > 0) {
            page.deleteTuple(tuples.removeFirst());
            page.deleteTuple(tuples.removeLast());
            deleted += 2;
            assertEquals(free + deleted, page.getNumEmptySlots());
        }

        // now, the page should be empty.
        try {
            page.deleteTuple(first);
            throw new Exception("page should be empty; expected DbException");
        } catch (DbException e) {
            // explicitly ignored
        }
    }

    /**
     * JUnit suite target
     */
    public static junit.framework.Test suite() {
        return new JUnit4TestAdapter(HeapPageTest.class);
    }
}
