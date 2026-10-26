# miniDB

A small relational database engine written from scratch in Java. miniDB stores tables in page-based heap files, caches pages in a buffer pool, runs queries through a pipeline of iterator-style operators, and comes with an interactive SQL shell.

It is meant to show how a database works on the inside: how rows are laid out on disk, how pages get cached and evicted, and how a SQL statement turns into a tree of operators.

## Features

- **Storage**: tables are heap files made of fixed-size 4 KB pages. Each page has a slot bitmap header followed by fixed-width tuples.
- **Buffer pool**: an in-memory page cache (50 pages by default) with a **NO-STEAL** eviction policy. Dirty pages are never evicted, so uncommitted changes never reach disk.
- **Transactions**: `COMMIT` flushes a transaction's dirty pages to disk, and `ROLLBACK` discards them.
- **Query operators** (Volcano / iterator model):
  - `SeqScan`: full table scan
  - `Filter`: `=`, `<>`, `<`, `<=`, `>`, `>=` predicates
  - `Join`: hash join for equality conditions, nested-loop join otherwise
  - `Aggregate`: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, with optional `GROUP BY`
  - `OrderBy`, `Project`, `Insert`, `Delete`
- **SQL shell**: an interactive REPL with tab completion (via JLine), or batch execution from a `.sql` file.
- **Data types**: `int` and fixed-length `string`.

## Architecture

```
 SQL text
    │
    ▼
 Parser ──► operator tree (Project / Aggregate / OrderBy / Join / Filter / SeqScan)
                │
                ▼
            HeapFile  ──►  BufferPool (page cache, NO-STEAL)  ──►  .dat files on disk
                │
             Catalog (table names and schemas)
```

| Component | Key classes | Responsibility |
|---|---|---|
| Entry point | `MiniDb` | `convert`, `print` and `parser` commands |
| SQL front end | `Parser` | Tokenises SQL and builds the operator tree |
| Operators | `SeqScan`, `Filter`, `Join`, `Aggregate`, `OrderBy`, `Project`, `Insert`, `Delete` | Iterator-based query execution |
| Storage | `HeapFile`, `HeapPage`, `HeapPageId`, `RecordId` | On-disk pages and tuple slots |
| Memory | `BufferPool` | Page caching, eviction, flushing on commit |
| Metadata | `Catalog`, `TupleDesc`, `Tuple`, `Type` | Table schemas and row representation |
| Transactions | `Transaction`, `TransactionId` | Commit/abort bookkeeping |

## Getting started

### Requirements

- Java 17 or newer
- Maven 3.x

### Build

```bash
mvn package
```

This produces a runnable jar at `target/minidb-1.0.0.jar`.

### 1. Define tables

Tables are declared in `catalog.txt`, one per line:

```
tableA (id int, val int)
tableB (id int, val int)
```

### 2. Load data

Table data starts as a comma-separated text file (`tableA.txt`). Convert it to miniDB's binary page format, passing the number of columns:

```bash
java -jar target/minidb-1.0.0.jar convert tableA.txt 2
java -jar target/minidb-1.0.0.jar convert tableB.txt 2
```

This creates `tableA.dat` and `tableB.dat`. For tables with string columns, pass the column types too:

```bash
java -jar target/minidb-1.0.0.jar convert people.txt 2 int,string
```

To check a converted file:

```bash
java -jar target/minidb-1.0.0.jar print tableA.dat 2
```

### 3. Run queries

Start the interactive shell (end each statement with `;`, and type `exit` to quit):

```bash
java -jar target/minidb-1.0.0.jar parser catalog.txt
```

Or run a file of statements:

```bash
java -jar target/minidb-1.0.0.jar parser catalog.txt -f queries.sql
```

## Supported SQL

```sql
SELECT * | col1, col2, ... | AGG(col)
FROM table1, table2, ...
[WHERE cond AND cond ...]
[GROUP BY col]
[ORDER BY col [ASC | DESC]];

INSERT INTO table VALUES (v1, v2, ...);
DELETE FROM table [WHERE cond AND cond ...];
COMMIT;
ROLLBACK;
```

- Columns are written as `table.column`.
- Joins are written as conditions in `WHERE` (for example `tableA.id = tableB.id`). Every table in `FROM` must be connected by a join condition.
- A transaction starts automatically with the first statement and ends at `COMMIT` or `ROLLBACK`.

## Example session

Running these statements against the sample `tableA` and `tableB`:

```sql
SELECT * FROM tableA WHERE tableA.val > 20 ORDER BY tableA.val DESC;
SELECT tableA.id, tableB.val FROM tableA, tableB WHERE tableA.id = tableB.id AND tableA.id < 104;
SELECT AVG(tableA.val) FROM tableA;
INSERT INTO tableA VALUES (107, 40);
SELECT MAX(tableA.val) FROM tableA;
DELETE FROM tableA WHERE tableA.id = 107;
COMMIT;
```

Output:

```
Added table : tableA with schema INT_TYPE(id),INT_TYPE(val)
Added table : tableB with schema INT_TYPE(id),INT_TYPE(val)
id	val
-------
105	31
106	31
102	22
 3 rows.
id	val
-------
101	15
102	22
103	8
 3 rows.
avg(val)
---------
21
 1 rows.
Inserted 1
max(val)
---------
40
 1 rows.
Deleted 1
Transaction committed.
```

## Running tests

```bash
mvn test
```

The suite has unit tests for the core classes (`Tuple`, `TupleDesc`, `HeapPage`, `HeapFile`, `Catalog`, predicates, aggregators) and system tests that run full scans, filters, joins, aggregates, inserts, deletes and buffer pool eviction against generated tables.

## Limitations

- No `CREATE TABLE`, `DROP TABLE` or `UPDATE`. Tables are defined in `catalog.txt` and loaded with `convert`.
- Only `int` and fixed-length `string` types.
- No indexes. Every query uses a sequential scan.
- Single-user only. There is no locking or concurrency control.
- No write-ahead log or crash recovery.
- Because of the NO-STEAL policy, a transaction can't modify more pages than the buffer pool holds.
- No query optimizer. Tables are joined in the order they appear in `FROM`.
- Joins must have a join condition (no cross joins), and `WHERE` supports only `AND`.
- `AVG` uses integer division.
