package minidb;

import java.io.*;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;


/**
 * BufferPool manages the reading and writing of pages into memory from disk.
 * Access methods call into it to retrieve pages, and it fetches pages from the
 * appropriate location.
 * <p>
 * This is a single-user buffer pool: it does not lock pages or detect
 * deadlocks. It does track which pages each transaction has touched, so that
 * {@link #transactionComplete} knows which dirty pages to flush (on commit)
 * or discard (on abort).
 */
public class BufferPool {
	/** Bytes per page, including header. */
	public static final int PAGE_SIZE = 4096;

	/**
	 * Default number of pages passed to the constructor. This is used by other
	 * classes. BufferPool should use the numPages argument to the constructor
	 * instead.
	 */
	public static final int DEFAULT_PAGES = 50;

	private int numPages;
	private HashMap<PageId, Page> idToPage;
	private HashMap<TransactionId, HashSet<PageId>> pagesTouchedByTx;

	/**
	 * Creates a BufferPool that caches up to numPages pages.
	 *
	 * @param nPages maximum number of pages in this buffer pool.
	 */
	public BufferPool(int nPages) {
		numPages = nPages;
		idToPage = new HashMap<PageId, Page>();
		pagesTouchedByTx = new HashMap<TransactionId, HashSet<PageId>>();
	}

	/**
	 * Retrieve the specified page with the associated permissions.
	 * <p>
	 * The retrieved page should be looked up in the buffer pool. If it is present,
	 * it should be returned. If it is not present, it should be added to the buffer
	 * pool and returned. If there is insufficient space in the buffer pool, an page
	 * should be evicted and the new page should be added in its place.
	 *
	 * @param tid  the ID of the transaction requesting the page
	 * @param pid  the ID of the requested page
	 * @param perm the requested permissions on the page
	 */
	public Page getPage(TransactionId tid, PageId pid, Permissions perm)
			throws TransactionAbortedException, DbException{

        if(tid != null)
            pagesTouchedByTx.computeIfAbsent(tid, k -> new HashSet<PageId>()).add(pid);

        // evict a page if necessary to make room for this one
        if(!idToPage.containsKey(pid) && idToPage.size() >= numPages)
        	evictPage();

		if(idToPage.containsKey(pid))
			return idToPage.get(pid);
		else {
			try {
				DbFile dbf = Database.getCatalog().getDbFile(pid.getTableId());
				Page fetchedPage = dbf.readPage(pid);
				idToPage.put(pid, fetchedPage);
				return fetchedPage;
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}
			return null;
		}
	}

	/**
	 * No-op: retained so callers that release a page early (as an
	 * optimization under real locking) don't need special-casing here.
	 *
	 * @param tid the ID of the transaction requesting the unlock
	 * @param pid the ID of the page to unlock
	 */
	public void releasePage(TransactionId tid, PageId pid) {
	}

	/**
	 * Flush or discard all pages touched by a given transaction.
	 *
	 * @param tid the ID of the transaction requesting the unlock
	 */
	public void transactionComplete(TransactionId tid) throws IOException {
		transactionComplete(tid, true);
	}

	/**
	 * Commit or abort a given transaction: flush its dirty pages to disk on
	 * commit, or discard them from the buffer pool on abort.
	 *
	 * @param tid    the ID of the transaction requesting the unlock
	 * @param commit a flag indicating whether we should commit or abort
	 */
	public void transactionComplete(TransactionId tid, boolean commit) throws IOException {
		Set<PageId> touchedPageIds = pagesTouchedByTx.remove(tid);
		if(touchedPageIds == null)
			return;

		for(PageId pid : touchedPageIds) {
		    // pages missing from the buffer pool cannot be dirty, so we can ignore them
			if(!idToPage.containsKey(pid))
				continue;
			Page p = idToPage.get(pid);
			if(p.isDirty() != null)
				if(commit)
					flushPage(pid);
				else
					discardPage(pid);
		}
	}

	/**
	 * Add a tuple to the specified table on behalf of transaction tid.
	 *
	 * Marks any pages that were dirtied by the operation as dirty by calling their
	 * markDirty bit, and updates cached versions of any pages that have been
	 * dirtied so that future requests see up-to-date pages.
	 *
	 * @param tid     the transaction adding the tuple
	 * @param tableId the table to add the tuple to
	 * @param t       the tuple to add
	 */
	public void insertTuple(TransactionId tid, int tableId, Tuple t)
			throws DbException, TransactionAbortedException {
	    HeapFile hf = (HeapFile) Database.getCatalog().getDbFile(tableId);
	    try {
	    	hf.addTuple(tid, t);
		} catch (IOException ioe) {
	    	ioe.printStackTrace();
	    	System.exit(1);
		}
	}

	/**
	 * Remove the specified tuple from the buffer pool.
	 *
	 * Marks any pages that were dirtied by the operation as dirty by calling their
	 * markDirty bit. Does not need to update cached versions of any pages that have
	 * been dirtied, as it is not possible that a new page was created during the
	 * deletion (note difference from addTuple).
	 *
	 * @param tid the transaction adding the tuple.
	 * @param t   the tuple to add
	 */
	public void deleteTuple(TransactionId tid, Tuple t) throws DbException, TransactionAbortedException {
		int tableId = t.getRecordId().getPageId().getTableId();
		HeapFile hf = (HeapFile) Database.getCatalog().getDbFile(tableId);
		hf.deleteTuple(tid, t);
	}

	/**
	 * Flush all dirty pages to disk. NB: Be careful using this routine -- it writes
	 * dirty data to disk so will break minidb if running in NO STEAL mode.
	 */
	public synchronized void flushAllPages() throws IOException {
		Set<PageId> pids = new HashSet<PageId>(idToPage.keySet());
		for(PageId pid : pids) {
			flushPage(pid);
		}
	}

	/**
	 * Remove the specific page id from the buffer pool. Needed by the recovery
	 * manager to ensure that the buffer pool doesn't keep a rolled back page in its
	 * cache.
	 */
	public synchronized void discardPage(PageId pid) {
	    idToPage.remove(pid);
	}

	/**
	 * Flushes a certain page to disk
	 * 
	 * @param pid an ID indicating the page to flush
	 */
	private void flushPage(PageId pid) throws IOException {
		// page isn't in memory so skip flush
        if(!idToPage.containsKey(pid))
        	return;
        Page p = idToPage.get(pid);
        // page is dirty so flush is needed
        if(p.isDirty() != null) {
			DbFile df = Database.getCatalog().getDbFile(pid.getTableId());
			df.writePage(p);
			p.markDirty(false, null);
		}
	}

	/**
	 * Discards a page from the buffer pool, choosing a non-dirty page to evict.
	 * This is a NO-STEAL policy: a dirty page is never evicted, so nothing an
	 * uncommitted transaction has changed can reach disk before it commits.
	 */
	private synchronized void evictPage() throws DbException {
		PageId pageIdToEvict = null;
		for(Map.Entry<PageId, Page> entry : idToPage.entrySet()) {
			if(entry.getValue().isDirty() == null) {
				pageIdToEvict = entry.getKey();
				break;
			}
		}

		if(pageIdToEvict == null)
			throw new DbException("All pages in the buffer pool are dirty. Cannot perform page eviction");
		else
			idToPage.remove(pageIdToEvict);
	}

}
