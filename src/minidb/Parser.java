package minidb;

import java.io.*;
import java.util.*;

import jline.ArgumentCompletor;
import jline.ConsoleReader;
import jline.SimpleCompletor;

/**
 * A small hand-written SQL front end for MiniDB (no third-party SQL grammar).
 * <p>
 * Supported statements:
 * <pre>
 *   SELECT (* | field [, field ...] | AGG(field)) FROM table [, table ...]
 *          [WHERE cond [AND cond ...]] [GROUP BY field] [ORDER BY field [ASC|DESC]];
 *   INSERT INTO table VALUES (value [, value ...]);
 *   DELETE FROM table [WHERE cond [AND cond ...]];
 *   COMMIT;
 *   ROLLBACK;
 * </pre>
 * Joins are inferred from equality/comparison predicates between two table
 * columns in the WHERE clause, joined left-to-right in FROM order. There is
 * no query optimizer: tables are joined in the order they're written.
 */
public class Parser {

    private static Transaction curtrans = null;

    private static final Set<String> AGG_FUNCS =
            new HashSet<String>(Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX"));

    // ---------------------------------------------------------------
    // Tokenizer
    // ---------------------------------------------------------------

    /** String literal tokens are stored with a leading quote marker, e.g. "'hello" for 'hello'. */
    private static List<String> tokenize(String sql) throws ParsingException {
        List<String> tokens = new ArrayList<String>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && sql.charAt(j) != '\'') {
                    sb.append(sql.charAt(j));
                    j++;
                }
                if (j >= n) throw new ParsingException("Unterminated string literal");
                tokens.add("'" + sb.toString());
                i = j + 1;
            } else if (c == '(' || c == ')' || c == ',' || c == ';' || c == '*') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '<' || c == '>' || c == '=' || c == '!') {
                if (i + 1 < n && sql.charAt(i + 1) == '=') {
                    tokens.add(sql.substring(i, i + 2));
                    i += 2;
                } else if (c == '<' && i + 1 < n && sql.charAt(i + 1) == '>') {
                    tokens.add("<>");
                    i += 2;
                } else {
                    tokens.add(String.valueOf(c));
                    i++;
                }
            } else if (Character.isLetterOrDigit(c) || c == '_'
                    || (c == '-' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '.'))
                    j++;
                tokens.add(sql.substring(i, j));
                i = j;
            } else {
                throw new ParsingException("Unexpected character '" + c + "' in SQL");
            }
        }
        return tokens;
    }

    private static class TokenStream {
        final List<String> toks;
        int pos = 0;

        TokenStream(List<String> t) { toks = t; }

        boolean eof() { return pos >= toks.size(); }

        String peek() { return eof() ? null : toks.get(pos); }

        String next() throws ParsingException {
            if (eof()) throw new ParsingException("Unexpected end of statement");
            return toks.get(pos++);
        }

        boolean isKeyword(String kw) { return !eof() && toks.get(pos).equalsIgnoreCase(kw); }

        void expect(String kw) throws ParsingException {
            if (!isKeyword(kw))
                throw new ParsingException("Expected '" + kw + "' but found " + (eof() ? "end of statement" : toks.get(pos)));
            pos++;
        }
    }

    // ---------------------------------------------------------------
    // Statement dispatch
    // ---------------------------------------------------------------

    public static void processNextStatement(InputStream is) {
        try {
            processNextStatement(readAll(is));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void processNextStatement(String sql) {
        for (String raw : sql.split(";")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            executeStatement(s);
        }
    }

    private static void executeStatement(String s) {
        try {
            TokenStream ts = new TokenStream(tokenize(s));
            if (ts.eof()) return;
            String kw = ts.peek().toUpperCase();
            if (kw.equals("SELECT")) handleSelect(ts);
            else if (kw.equals("INSERT")) handleInsert(ts);
            else if (kw.equals("DELETE")) handleDelete(ts);
            else if (kw.equals("COMMIT")) handleCommit();
            else if (kw.equals("ROLLBACK")) handleRollback();
            else System.out.println("Can't parse " + s
                    + "\n -- parser only handles select, insert, delete, commit, and rollback statements");
        } catch (ParsingException e) {
            System.out.println("Invalid SQL expression: \n \t" + e.getMessage());
        } catch (DbException | TransactionAbortedException | IOException e) {
            e.printStackTrace();
        }
    }

    private static void ensureTransaction() throws IOException {
        if (curtrans == null) {
            curtrans = new Transaction();
            curtrans.start();
            System.out.println("Started a new transaction tid = " + curtrans.getId().getId());
        }
    }

    // ---------------------------------------------------------------
    // SELECT
    // ---------------------------------------------------------------

    private static void handleSelect(TokenStream ts)
            throws ParsingException, DbException, TransactionAbortedException, IOException {
        ts.expect("SELECT");

        List<String> plainFields = new ArrayList<String>();
        String aggFunc = null, aggFieldTok = null;

        String first = ts.peek();
        if ("*".equals(first)) {
            ts.next();
            plainFields.add("*");
        } else if (first != null && AGG_FUNCS.contains(first.toUpperCase())
                && ts.pos + 1 < ts.toks.size() && "(".equals(ts.toks.get(ts.pos + 1))) {
            aggFunc = ts.next().toUpperCase();
            ts.expect("(");
            aggFieldTok = ts.next();
            ts.expect(")");
        } else {
            while (true) {
                plainFields.add(ts.next());
                if (ts.isKeyword(",")) { ts.next(); continue; }
                break;
            }
        }

        ts.expect("FROM");
        List<String> tables = new ArrayList<String>();
        while (true) {
            tables.add(ts.next());
            if (ts.isKeyword(",")) { ts.next(); continue; }
            break;
        }

        List<String[]> conds = parseWhere(ts);

        String groupByField = null;
        if (ts.isKeyword("GROUP")) {
            ts.next();
            ts.expect("BY");
            groupByField = ts.next();
        }

        String orderByField = null;
        boolean asc = true;
        if (ts.isKeyword("ORDER")) {
            ts.next();
            ts.expect("BY");
            orderByField = ts.next();
            if (ts.isKeyword("ASC")) ts.next();
            else if (ts.isKeyword("DESC")) { ts.next(); asc = false; }
        }

        ensureTransaction();
        TransactionId tid = curtrans.getId();

        DbIterator plan = buildFromAndJoins(tid, tables, conds);
        plan = applyFilters(plan, conds);

        DbIterator resultPlan;
        if (aggFunc != null) {
            TupleDesc ctd = plan.getTupleDesc();
            int afield = "*".equals(aggFieldTok) ? 0 : resolveField(ctd, aggFieldTok);
            int gfield = groupByField == null ? Aggregator.NO_GROUPING : resolveField(ctd, groupByField);
            resultPlan = new Aggregate(plan, afield, gfield, Aggregator.Op.valueOf(aggFunc));
        } else {
            TupleDesc ctd = plan.getTupleDesc();
            ArrayList<Integer> fieldIds = new ArrayList<Integer>();
            ArrayList<Type> types = new ArrayList<Type>();
            if (plainFields.size() == 1 && plainFields.get(0).equals("*")) {
                for (int i = 0; i < ctd.numFields(); i++) {
                    fieldIds.add(i);
                    types.add(ctd.getType(i));
                }
            } else {
                for (String f : plainFields) {
                    int idx = resolveField(ctd, f);
                    fieldIds.add(idx);
                    types.add(ctd.getType(idx));
                }
            }
            resultPlan = new Project(fieldIds, types, plan);
        }

        if (orderByField != null) {
            int idx = resolveField(resultPlan.getTupleDesc(), orderByField);
            resultPlan = new OrderBy(idx, asc, resultPlan);
        }

        runAndPrint(resultPlan, tid);
    }

    private static List<String[]> parseWhere(TokenStream ts) throws ParsingException {
        List<String[]> conds = new ArrayList<String[]>();
        if (ts.isKeyword("WHERE")) {
            ts.next();
            while (true) {
                String left = ts.next();
                String op = ts.next();
                String right = ts.next();
                conds.add(new String[]{left, op, right, null});
                if (ts.isKeyword("AND")) { ts.next(); continue; }
                break;
            }
        }
        return conds;
    }

    /** Joins tables left-to-right in FROM order, consuming any WHERE conditions that connect two columns. */
    private static DbIterator buildFromAndJoins(TransactionId tid, List<String> tables, List<String[]> conds)
            throws ParsingException {
        DbIterator plan = new SeqScan(tid, Database.getCatalog().getTableId(tables.get(0)), tables.get(0));

        for (int idx = 1; idx < tables.size(); idx++) {
            String tbl = tables.get(idx);
            DbIterator rightScan = new SeqScan(tid, Database.getCatalog().getTableId(tbl), tbl);

            String[] chosen = null;
            int leftIdx = -1, rightIdx = -1;
            Predicate.Op op = null;

            for (String[] c : conds) {
                if (c[3] != null || !isColumnRef(c[0]) || !isColumnRef(c[2])) continue; // already consumed, or not a join cond

                Integer aInPlan = tryFieldIndex(plan.getTupleDesc(), c[0]);
                Integer bInNew = tryFieldIndex(rightScan.getTupleDesc(), c[2]);
                Integer aInNew = tryFieldIndex(rightScan.getTupleDesc(), c[0]);
                Integer bInPlan = tryFieldIndex(plan.getTupleDesc(), c[2]);

                if (aInPlan != null && bInNew != null) {
                    chosen = c;
                    leftIdx = aInPlan;
                    rightIdx = bInNew;
                    op = getOp(c[1]);
                    break;
                } else if (aInNew != null && bInPlan != null) {
                    chosen = c;
                    leftIdx = bInPlan;
                    rightIdx = aInNew;
                    op = reverseOp(getOp(c[1]));
                    break;
                }
            }

            if (chosen == null) {
                throw new ParsingException("No join condition found to connect table '" + tbl
                        + "' -- cross joins are not supported");
            }
            chosen[3] = "used";
            plan = new Join(new JoinPredicate(leftIdx, op, rightIdx), plan, rightScan);
        }
        return plan;
    }

    private static DbIterator applyFilters(DbIterator plan, List<String[]> conds) throws ParsingException {
        for (String[] c : conds) {
            if (c[3] != null) continue; // consumed as a join condition already
            boolean leftIsCol = isColumnRef(c[0]);
            boolean rightIsCol = isColumnRef(c[2]);
            if (leftIsCol == rightIsCol) {
                throw new ParsingException("Unsupported condition: " + c[0] + " " + c[1] + " " + c[2]
                        + " (expected one column and one literal value)");
            }
            String colTok = leftIsCol ? c[0] : c[2];
            String valTok = leftIsCol ? c[2] : c[0];
            Predicate.Op op = leftIsCol ? getOp(c[1]) : reverseOp(getOp(c[1]));
            int fieldIdx = resolveField(plan.getTupleDesc(), colTok);
            Field constant = parseConstant(valTok, plan.getTupleDesc().getType(fieldIdx));
            plan = new Filter(new Predicate(fieldIdx, op, constant), plan);
        }
        return plan;
    }

    private static void runAndPrint(DbIterator plan, TransactionId tid)
            throws DbException, TransactionAbortedException, IOException {
        Query q = new Query(plan, tid);
        TupleDesc td = plan.getTupleDesc();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < td.numFields(); i++) names.append(td.getFieldName(i)).append("\t");
        System.out.println(names);
        for (int i = 0; i < names.length(); i++) System.out.print("-");
        System.out.println();

        q.start();
        int cnt = 0;
        while (q.hasNext()) {
            System.out.println(q.next());
            cnt++;
        }
        System.out.println("\n " + cnt + " rows.");
        q.close();
    }

    // ---------------------------------------------------------------
    // INSERT / DELETE / COMMIT / ROLLBACK
    // ---------------------------------------------------------------

    private static void handleInsert(TokenStream ts)
            throws ParsingException, DbException, TransactionAbortedException, IOException {
        ts.expect("INSERT");
        ts.expect("INTO");
        String tableName = ts.next();
        int tableId = resolveTable(tableName);
        TupleDesc td = Database.getCatalog().getTupleDesc(tableId);

        ts.expect("VALUES");
        ts.expect("(");
        List<String> values = new ArrayList<String>();
        while (true) {
            values.add(ts.next());
            if (ts.isKeyword(",")) { ts.next(); continue; }
            break;
        }
        ts.expect(")");

        if (values.size() != td.numFields())
            throw new ParsingException("INSERT statement does not contain same number of fields as table " + tableName);

        Tuple t = new Tuple(td);
        for (int i = 0; i < values.size(); i++)
            t.setField(i, parseConstant(values.get(i), td.getType(i)));

        ensureTransaction();
        TransactionId tid = curtrans.getId();

        ArrayList<Tuple> tups = new ArrayList<Tuple>();
        tups.add(t);
        DbIterator newTups = new TupleArrayIterator(tups);

        Query q = new Query(new Insert(tid, newTups, tableId), tid);
        q.start();
        System.out.print("Inserted ");
        while (q.hasNext()) System.out.println(q.next());
        q.close();
    }

    private static void handleDelete(TokenStream ts)
            throws ParsingException, DbException, TransactionAbortedException, IOException {
        ts.expect("DELETE");
        ts.expect("FROM");
        String tableName = ts.next();
        int tableId = resolveTable(tableName);

        ensureTransaction();
        TransactionId tid = curtrans.getId();

        DbIterator plan = new SeqScan(tid, tableId, tableName);
        List<String[]> conds = parseWhere(ts);
        plan = applyFilters(plan, conds);

        Query q = new Query(new Delete(tid, plan), tid);
        q.start();
        System.out.print("Deleted ");
        while (q.hasNext()) System.out.println(q.next());
        q.close();
    }

    private static void handleCommit() throws IOException {
        if (curtrans == null) { System.out.println("No active transaction."); return; }
        curtrans.transactionComplete(false);
        curtrans = null;
        System.out.println("Transaction committed.");
    }

    private static void handleRollback() throws IOException {
        if (curtrans == null) { System.out.println("No active transaction."); return; }
        curtrans.transactionComplete(true);
        curtrans = null;
        System.out.println("Transaction aborted.");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static int resolveTable(String name) throws ParsingException {
        try {
            return Database.getCatalog().getTableId(name);
        } catch (NoSuchElementException e) {
            throw new ParsingException("Unknown table: " + name);
        }
    }

    private static int resolveField(TupleDesc td, String ref) throws ParsingException {
        try {
            return td.nameToId(ref);
        } catch (NoSuchElementException e) {
            throw new ParsingException("Unknown field: " + ref);
        }
    }

    private static Integer tryFieldIndex(TupleDesc td, String ref) {
        try {
            return td.nameToId(ref);
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private static boolean isColumnRef(String tok) {
        if (tok.startsWith("'")) return false;
        return !tok.matches("-?\\d+");
    }

    private static Field parseConstant(String tok, Type t) throws ParsingException {
        if (t == Type.INT_TYPE) {
            try {
                return new IntField(Integer.parseInt(tok));
            } catch (NumberFormatException e) {
                throw new ParsingException("Expected an integer, got " + tok);
            }
        }
        String s = tok.startsWith("'") ? tok.substring(1) : tok;
        return new StringField(s, Type.STRING_LEN);
    }

    static Predicate.Op getOp(String s) throws ParsingException {
        if (s.equals("=")) return Predicate.Op.EQUALS;
        if (s.equals(">")) return Predicate.Op.GREATER_THAN;
        if (s.equals(">=")) return Predicate.Op.GREATER_THAN_OR_EQ;
        if (s.equals("<")) return Predicate.Op.LESS_THAN;
        if (s.equals("<=")) return Predicate.Op.LESS_THAN_OR_EQ;
        if (s.equals("<>") || s.equals("!=")) return Predicate.Op.NOT_EQUALS;
        throw new ParsingException("Unknown operator " + s);
    }

    static Predicate.Op reverseOp(Predicate.Op op) {
        switch (op) {
            case GREATER_THAN: return Predicate.Op.LESS_THAN;
            case GREATER_THAN_OR_EQ: return Predicate.Op.LESS_THAN_OR_EQ;
            case LESS_THAN: return Predicate.Op.GREATER_THAN;
            case LESS_THAN_OR_EQ: return Predicate.Op.GREATER_THAN_OR_EQ;
            default: return op;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return new String(buf.toByteArray(), "UTF-8");
    }

    // Basic SQL completions
    static final String[] SQL_COMMANDS = {
            "select", "from", "where", "and", "group by", "order by", "asc", "desc",
            "count(", "min(", "max(", "avg(", "sum(",
            "insert into", "values", "delete from", "commit", "rollback"
    };

    public static void main(String argv[]) throws IOException {
        String usage = "Usage: parser catalogFile [-f queryFile]";

        if (argv.length < 1 || argv.length > 3) {
            System.out.println("Invalid number of arguments.\n" + usage);
            System.exit(0);
        }

        Database.getCatalog().loadSchema(argv[0]);

        boolean interactive = true;
        String queryFile = null;

        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals("-f")) {
                interactive = false;
                i++;
                if (i == argv.length) {
                    System.out.println("Expected file name after -f\n" + usage);
                    System.exit(0);
                }
                queryFile = argv[i];
            } else {
                System.out.println("Unknown argument " + argv[i] + "\n " + usage);
            }
        }

        if (!interactive) {
            try {
                curtrans = new Transaction();
                curtrans.start();
                processNextStatement(readAll(new FileInputStream(new File(queryFile))));
            } catch (FileNotFoundException e) {
                System.out.println("Unable to find query file " + queryFile);
            }
        } else {
            ConsoleReader reader = new ConsoleReader();

            ArgumentCompletor completor = new ArgumentCompletor(new SimpleCompletor(SQL_COMMANDS));
            completor.setStrict(false);
            reader.addCompletor(completor);

            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine("MiniDB> ")) != null) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("quit;")
                        || trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("exit;")) {
                    System.out.println("Bye");
                    System.exit(0);
                }
                while (line.indexOf(';') >= 0) {
                    int split = line.indexOf(';');
                    buffer.append(line.substring(0, split + 1));
                    String statement = buffer.toString();

                    long startTime = System.currentTimeMillis();
                    processNextStatement(statement);
                    long time = System.currentTimeMillis() - startTime;
                    System.out.printf("----------------\n%.2f seconds\n\n", ((double) time / 1000.0));

                    line = line.substring(split + 1);
                    buffer = new StringBuilder();
                }
                if (line.length() > 0) {
                    buffer.append(line);
                    buffer.append("\n");
                }
            }
        }
    }
}

class TupleArrayIterator implements DbIterator {
    ArrayList<Tuple> tups;
    Iterator<Tuple> it = null;

    public TupleArrayIterator(ArrayList<Tuple> tups) {
        this.tups = tups;
    }

    public void open() throws DbException, TransactionAbortedException {
        it = tups.iterator();
    }

    public boolean hasNext() throws DbException, TransactionAbortedException {
        return it.hasNext();
    }

    public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
        return it.next();
    }

    public void rewind() throws DbException, TransactionAbortedException {
        it = tups.iterator();
    }

    public TupleDesc getTupleDesc() {
        return tups.get(0).getTupleDesc();
    }

    public void close() {
    }
}
