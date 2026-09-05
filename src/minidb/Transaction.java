package minidb;

import java.io.*;

/**
 * Transaction encapsulates information about the state of
 * a transaction and manages transaction commit / abort.
 */

public class Transaction {
    TransactionId tid;
    boolean started = false;

    public Transaction() {
        tid = new TransactionId();
    }

    /** Start the transaction running */
    public void start() {
        started = true;
    }

    public TransactionId getId() {
        return tid;
    }

    /** Finish the transaction */
    public void commit() throws IOException {
        transactionComplete(false);
    }

    /**
     * Handle the details of transaction commit / abort. On commit, dirty
     * pages touched by this transaction are flushed to disk; on abort they
     * are simply discarded from the buffer pool (since the buffer pool never
     * evicts a dirty page, nothing dirtied by this transaction can have
     * reached disk yet).
     */
    public void transactionComplete(boolean abort) throws IOException {
        if (started) {
            Database.getBufferPool().transactionComplete(tid, !abort);
            started = false;
        }
    }

}
