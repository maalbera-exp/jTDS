// jTDS JDBC Driver for Microsoft SQL Server and Sybase
// Copyright (C) 2004 The jTDS Project
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
package net.sourceforge.jtds.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.BatchUpdateException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * jTDS implementation of the java.sql.Statement interface.<p>
 * NB. As allowed by the JDBC standard and like most other drivers,
 * this implementation only allows one open result set at a time.
 * <p>
 * Implementation notes:
 * <p>
 * I experimented with allowing multiple open result sets as supported
 * by the origianal jTDS but rejected this approach for the following
 * reasons:
 * <ol>
 * <li>It is more difficult to ensure that there are no memory leaks and that
 *     cursors are closed if multiple open sets are allowed.
 * <li>The use of one result set allows cursor and non cursor result sets to
 *     be derived from exeuteQuery() or execute() and getResultSet() in the
 *     same way that other drivers do.
 * </ol>
 * In the event of an IO failure the setClosed() method forces this statement
 * and associated result set to close preventing the propogation of errors.
 * This class includes a finalize method which increases the chances of the
 * statement being closed tidly in a pooled environment where the user has
 * forgotten to explicitly close the statement before it goes out of scope.
 *
 * @see java.sql.Statement
 * @see java.sql.Connection#createStatement
 * @see java.sql.ResultSet
 *
 * @author Mike Hutchinson
 * @version $Id: JtdsStatement.java,v 1.20 2004-11-05 12:10:40 alin_sinpalean Exp $
 */
public class JtdsStatement implements java.sql.Statement {
    /*
     * Constants used for backwards compatibility with JDK 1.3
     */
    static final int RETURN_GENERATED_KEYS = 1;
    static final int NO_GENERATED_KEYS = 2;
    static final int CLOSE_CURRENT_RESULT = 1;
    static final int KEEP_CURRENT_RESULT = 2;
    static final int CLOSE_ALL_RESULTS = 3;
    static final int EXECUTE_FAILED = -3;
    static final int BOOLEAN = 16;
    static final int DATALINK = 70;

    /** The connection owning this statement object. */
    protected ConnectionJDBC2 connection;
    /** The TDS object used for server access. */
    protected TdsCore tds = null;
    /** The read query timeout in seconds */
    protected int queryTimeout = 0;
    /** The result set generated by getMoreResults(). */
    protected JtdsResultSet currentResult = null;
    /** The fetch direction for result sets. */
    protected int fetchDirection = ResultSet.FETCH_FORWARD;
    /** The type of result sets created by this statement. */
    protected int resultSetType = ResultSet.TYPE_FORWARD_ONLY;
    /** The concurrency of result sets created by this statement. */
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    /** The fetch size (not used at present). */
    protected int fetchSize = 1;
    /** True if this statement is closed. */
    protected boolean closed = false;
    /** The maximum field size (not used at present). */
    protected int maxFieldSize = 0;
    /** The maximum number of rows to return (not used at present). */
    protected int maxRows = 0;
    /** True if SQL statements should be preprocessed. */
    protected boolean escapeProcessing = true;
    /** SQL Diagnostic exceptions and warnings. */
    protected SQLDiagnostic messages;
    /** Batched SQL Statement array. */
    protected ArrayList batchValues = null;
    /** Dummy result set for getGeneratedKeys. */
    protected JtdsResultSet genKeyResultSet;
    /** List of queued results (update counts, possibly followed by a ResultSet) */
    protected LinkedList resultQueue = new LinkedList();

    /**
     * Construct a new Statement object.
     *
     * @param connection The parent connection.
     * @param resultSetType The result set type for example TYPE_FORWARD_ONLY.
     * @param resultSetConcurrency The concurrency for example CONCUR_READ_ONLY.
     */
    JtdsStatement(ConnectionJDBC2 connection,
                  int resultSetType,
                  int resultSetConcurrency) {
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.messages = new SQLDiagnostic(connection.getServerType());
        this.tds = new TdsCore(this.connection, messages);
    }

    /**
     * Called when this object goes out of scope
     * to close any resultSet object and this statement.
     */
    protected void finalize() {
       try {
           close();
       } catch (SQLException e) {
           // Ignore errors
       }
   }

    /**
     * Get the Statement's TDS object.
     *
     * @return The TDS support as a <code>TdsCore</core> Object.
     */
    TdsCore getTds() {
        return tds;
    }

    /**
     * Get the statement's warnings list.
     *
     * @return The warnings list as a <code>SQLDiagnostic</code>.
     */
    SQLDiagnostic getMessages() {
        return messages;
    }

    /**
     * Check that this statement is still open.
     *
     * @throws SQLException if statement closed.
     */
    protected void checkOpen() throws SQLException {
        if (closed || connection == null || connection.isClosed()) {
            throw new SQLException(
                    Messages.get("error.generic.closed", "Statement"), "HY010");
        }
    }

    /**
     * Report that user tried to call a method which has not been implemented.
     *
     * @param method The method name to report in the error message.
     * @throws SQLException
     */
    void notImplemented(String method) throws SQLException {
        throw new SQLException(
                Messages.get("error.generic.notimp", method), "HYC00");
    }

    /**
     * Close current result set (if any).
     */
    void closeCurrentResultSet() {
        try {
            if (currentResult != null) {
                currentResult.close();
            }
        } catch (SQLException e) {
            // Ignore
        } finally {
            currentResult = null;
        }
    }

    /**
     * Add an SQLWarning object to the statment warnings list.
     *
     * @param w The SQLWarning to add.
     */
    void addWarning(SQLWarning w) {
        messages.addWarning(w);
    }

    /**
     * This method should be over-ridden by any sub-classes that place values other than
     * Strings into the batchValues list to handle execution properly.
     */
    protected int executeBatchOther(Object value) throws SQLException {
        throw new SQLException(
                Messages.get("error.statement.badbatch",
                        value.toString()), "HYC00");
    }

    /**
     * Execute SQL to obtain a result set.
     *
     * @param sql The SQL statement to execute.
     * @param spName Optional stored procedure name.
     * @param params Optional parameters.
     * @param readAhead Force result set next() to read ahead so that
     * stored procedure output parameters are read and processed even if
     * the result set is not closed.
     * <p>NB. The user must still read all result set rows to ensure
     * that the output parameters are processed.
     * @return The result set as a <code>ResultSet</code>.
     * @throws SQLException
     */
    protected ResultSet executeSQLQuery(String sql,
                                        String spName,
                                        ParamInfo[] params,
                                        boolean readAhead)
        throws SQLException {
        closeCurrentResultSet();
        resultQueue.clear();
        genKeyResultSet = null;
        String warningMessage = null;

        //
        // Try to open a cursor result set if required
        //
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY
            || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            try {
                if (connection.getServerType() == Driver.SQLSERVER) {
                    currentResult =
                            new MSCursorResultSet(this,
                                    sql,
                                    spName,
                                    params,
                                    resultSetType,
                                    resultSetConcurrency);

                    return currentResult;
                } else {
                    // Use client side cursor for Sybase
                    currentResult =
                        new CachedResultSet(this,
                                sql,
                                spName,
                                params,
                                resultSetType,
                                resultSetConcurrency);

                    return currentResult;
                }
            } catch (SQLException e) {
                if (connection == null || connection.isClosed()) {
                    // Serious error so return exception to caller
                    throw e;
                }
                warningMessage = "[" + e.getSQLState() + "] " + e.getMessage();
            }
        }

        //
        // Could not open a Cursor so try a direct select
        //
        tds.executeSQL(sql, spName, params, false, queryTimeout, maxRows);

        while (!tds.getMoreResults() && !tds.isEndOfResponse());

        if (tds.isResultSet()) {
            currentResult = new JtdsResultSet(this,
                                              ResultSet.TYPE_FORWARD_ONLY,
                                              ResultSet.CONCUR_READ_ONLY,
                                              tds.getColumns(),
                                              readAhead);
        } else {
                throw new SQLException(
                            Messages.get("error.statement.noresult"), "24000");
        }

        if (warningMessage != null) {
            //
            // Update warning chain if cursor was downgraded
            //
            addWarning(new SQLWarning(
                    Messages.get("warning.cursordowngraded", warningMessage), "01000"));
        }

        return currentResult;
    }

    /**
     * Execute any type of SQL.
     *
     * @param sql The SQL statement to execute.
     * @param spName Optional stored procedure name.
     * @param params Optional parameters.
     * @return <code>boolean</code> true if a result set is returned by the statement.
     * @throws SQLException if an error condition occurs
     */
    protected boolean executeSQL(String sql,
                                 String spName,
                                 String sqlWord,
                                 ParamInfo[] params,
                                 boolean returnKeys,
                                 boolean update)
        throws SQLException {
        closeCurrentResultSet();
        resultQueue.clear();
        genKeyResultSet = null;
        String warningMessage = null;

        //
        // Try to open a cursor result set if required (and possible)
        //
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY
            || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY)
            && (sqlWord.equals("select") || sqlWord.startsWith("exec"))
            && !returnKeys) {
            try {
                if (connection.getServerType() == Driver.SQLSERVER) {
                    currentResult = new MSCursorResultSet(
                            this,
                            sql,
                            spName,
                            params,
                            resultSetType,
                            resultSetConcurrency);

                    return true;
                } else {
                    // Use client side cursor for Sybase
                    currentResult = new CachedResultSet(
                            this,
                            sql,
                            spName,
                            params,
                            resultSetType,
                            resultSetConcurrency);

                    return true;
                }
            } catch (SQLException e) {
                if (connection == null || connection.isClosed()) {
                    // Serious error so return exception to caller
                    throw e;
                }
                warningMessage = "[" + e.getSQLState() + "] " + e.getMessage();
            }
        }

        //
        // Could not open a Cursor or not a SELECT so just execute
        //
        tds.executeSQL(sql, spName, params, false, queryTimeout, maxRows);

        if (warningMessage != null) {
            // Update warning chain if cursor was downgraded
            addWarning(new SQLWarning(Messages.get(
                    "warning.cursordowngraded", warningMessage), "01000"));
        }

        return processResults(returnKeys, update);
    }

    /**
     * Queue up update counts until the end of the response is reached or a
     * <code>ResultSet</code> is encountered into {@link #resultQueue}.
     *
     * @param returnKeys <code>true</code> if a generated keys
     *                   <code>ResultSet</code> is expected
     * @param update     <code>true</code> if the method is called from within
     *                   <code>executeUpdate</code>
     * @return           <false> if the first result is an update count,
     *                   <true> if it's a <code>ResultSet</code>
     * @throws SQLException if an error condition occurs
     */
    private boolean processResults(boolean returnKeys, boolean update) throws SQLException {
        while (!tds.isEndOfResponse()) {
            if (!tds.getMoreResults()) {
                if (tds.isUpdateCount()) {
                    if (update && connection.isLastUpdateCount()) {
                        resultQueue.clear();
                    }
                    resultQueue.addLast(new Integer(tds.getUpdateCount()));
                }
            } else {
                if (returnKeys) {
                    // This had better be the generated key
                    // FIXME We could use SELECT @@IDENTITY AS jTDS_SOMETHING and check the column name to make sure
                    if (tds.getNextRow()) {
                        genKeyResultSet = new DummyResultSet(this,
                                tds.getColumns(),
                                tds.getRowData());
                    }
                } else {
                    // TODO Should we allow execution of multiple statements via executeUpdate?
                    if (update && resultQueue.isEmpty()) {
                        throw new SQLException(
                                Messages.get("error.statement.nocount"), "07000");
                    }

                    JtdsResultSet rs = new JtdsResultSet(
                            this,
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                            tds.getColumns(),
                            false);

                    if (resultQueue.isEmpty()) {
                        // This is the first result. Return it.
                        currentResult = rs;
                        return true;
                    } else {
                        // There were some update counts before. Queue it.
                        resultQueue.add(rs);
                        return false;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Returns the first update count in {@link #resultQueue} or the specified
     * default value if the queue is empty.
     *
     * @param defaultValue the value to return if there are no queued update
     *                     counts (0 if called from <code>executeUpdate</code>,
     *                     -1 if called from <code>getMoreResults</code>)
     * @return             the first queued update count or the default value
     *                     if the queue is empty
     */
    protected int getUpdateCount(int defaultValue) {
        return resultQueue.isEmpty()
                ? defaultValue
                : ((Integer) resultQueue.getFirst()).intValue();
    }

// ------------------ java.sql.Statement methods ----------------------

    public int getFetchDirection() throws SQLException {
        checkOpen();

        return this.fetchDirection;
    }

    public int getFetchSize() throws SQLException {
        checkOpen();

        return this.fetchSize;
    }

    public int getMaxFieldSize() throws SQLException {
        checkOpen();

        return this.maxFieldSize;
    }

    public int getMaxRows() throws SQLException {
        checkOpen();

        return this.maxRows;
    }

    public int getQueryTimeout() throws SQLException {
        checkOpen();

        return this.queryTimeout;
    }

    public int getResultSetConcurrency() throws SQLException {
        checkOpen();

        return this.resultSetConcurrency;
    }

    public int getResultSetHoldability() throws SQLException {
        checkOpen();

        return JtdsResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    public int getResultSetType() throws SQLException {
        checkOpen();

        return resultSetType;
    }

    public int getUpdateCount() throws SQLException {
        checkOpen();

        return getUpdateCount(-1);
    }

    public void cancel() throws SQLException {
        checkOpen();

        if (tds != null) {
            tds.cancel();
        }
    }

    public synchronized void clearBatch() throws SQLException {
        checkOpen();

        if (batchValues != null) {
            batchValues.clear();
        }
    }

    public void clearWarnings() throws SQLException {
        checkOpen();

        messages.clearWarnings();
    }

    public void close() throws SQLException {
        if (!closed) {
            try {
                closeCurrentResultSet();

                if (!connection.isClosed()) {
                    tds.clearResponseQueue();
                    tds.close();
                }
            } finally {
                closed = true;
                tds = null;
                connection.removeStatement(this);
                connection = null;
            }
        }
    }

    public boolean getMoreResults() throws SQLException {
        checkOpen();

        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    public synchronized int[] executeBatch() throws SQLException {
        checkOpen();

        if (batchValues == null) {
            return new int[0];
        }

        int size = batchValues.size();
        int[] updateCounts = new int[size];
        int i = 0;

        try {
            for (; i < size; i++) {
                Object value = batchValues.get(i);

                if (value instanceof String) {
                    updateCounts[i] = executeUpdate((String) value);
                } else {
                    updateCounts[i] = executeBatchOther(value);
                }
            }
        } catch (SQLException e) {
            int[] tmpUpdateCounts = new int[i + 1];

            System.arraycopy(updateCounts, 0, tmpUpdateCounts, 0, i + 1);

            tmpUpdateCounts[i] = EXECUTE_FAILED;
            throw new BatchUpdateException(e.getMessage(), tmpUpdateCounts);
        } finally {
            clearBatch();
        }

        return updateCounts;
    }

    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        switch (direction) {
        case ResultSet.FETCH_UNKNOWN:
        case ResultSet.FETCH_REVERSE:
        case ResultSet.FETCH_FORWARD:
            this.fetchDirection = direction;
            break;

        default:
            throw new SQLException(Messages.get("error.generic.badoption",
                                   Integer.toString(direction),
                                   "setFetchDirection"), "24000");
        }
    }

    public void setFetchSize(int rows) throws SQLException {
        checkOpen();

        if (rows < 0 || (maxRows > 0 && rows > maxRows)) {
            throw new SQLException(
                Messages.get("error.generic.optltzero", "setFetchSize"),
                    "HY092");
        }

        this.fetchSize = rows;
    }

    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();

        if (max < 0) {
            throw new SQLException(
                Messages.get("error.generic.optltzero", "setMaxFieldSize"),
                    "HY092");
        }

        if (max == 0) {
            max = 2147483647;
        }

        tds.submitSQL("set textsize " + max);
        maxFieldSize = max;
    }

    public void setMaxRows(int max) throws SQLException {
        checkOpen();

        if (max < 0) {
            throw new SQLException(
                Messages.get("error.generic.optltzero", "setMaxRows"),
                    "HY092");
        }

        this.maxRows = max;
    }

    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();

        if (seconds < 0) {
            throw new SQLException(
                Messages.get("error.generic.optltzero", "setQueryTimeout"),
                    "HY092");
        }

        this.queryTimeout = seconds;
    }

    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();

        switch (current) {
            case CLOSE_ALL_RESULTS:
            case CLOSE_CURRENT_RESULT:
                closeCurrentResultSet();
                break;
            case KEEP_CURRENT_RESULT:
                throw new SQLException(Messages.get("error.generic.optvalue",
                                                          "KEEP_CURRENT_RESULT",
                                                          "getMoreResults"),
                                       "HYC00");
            default:
                throw new SQLException(Messages.get("error.generic.badoption",
                                                          Integer.toString(current),
                                                          "getMoreResults"),
                                       "HY092");
        }

        // Dequeue any results
        if (!resultQueue.isEmpty()) {
            // Remove the current update count
            resultQueue.removeFirst();

            if (!resultQueue.isEmpty()) {
                Object nextResult = resultQueue.getFirst();

                // Next result is an update count
                if (nextResult instanceof Integer) {
                    return false;
                }

                // Next result is a ResultSet. Set currentResult and remove it.
                currentResult = (JtdsResultSet) nextResult;
                resultQueue.removeFirst();
                return true;
            }
        }

        // Queue update counts until the end of response or a ResultSet is reached
        return processResults(false, false);
    }

    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();

        this.escapeProcessing = enable;
    }

    public int executeUpdate(String sql) throws SQLException {
        return executeUpdate(sql, NO_GENERATED_KEYS);
    }

    public synchronized void addBatch(String sql) throws SQLException {
        checkOpen();

        if (batchValues == null) {
            batchValues = new ArrayList();
        }

        batchValues.add(sql);
    }

    public void setCursorName(String name) throws SQLException {
        checkOpen();
        // No operation
    }

    public boolean execute(String sql) throws SQLException {
        return execute(sql, NO_GENERATED_KEYS);
    }

    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();

        if (sql == null || sql.length() == 0) {
            throw new SQLException(Messages.get("error.generic.nosql"), "HY000");
        }

        closeCurrentResultSet();
        boolean returnKeys;
        String sqlWord = "";
        if (escapeProcessing) {
            ArrayList params = new ArrayList();
            String tmp[] = new SQLParser(sql, params, connection.getServerType()).parse(false);

            if (tmp[1].length() != 0 || params.size() > 0) {
                throw new SQLException(
                    Messages.get("error.statement.badsql"), "07000");
            }

            sql = tmp[0];
            sqlWord = tmp[2];
        } else {
            // Escape processing turned off so
            // see if we can extract "insert" from start of statement
            sql = sql.trim();
            if (sql.length() > 5) {
                sqlWord = sql.substring(0,6).toLowerCase();
            }
        }
        if (autoGeneratedKeys == RETURN_GENERATED_KEYS) {
            returnKeys = sqlWord.equals("insert");
        } else if (autoGeneratedKeys == NO_GENERATED_KEYS) {
            returnKeys = false;
        } else {
            throw new SQLException(Messages.get("error.generic.badoption",
                                                      Integer.toString(autoGeneratedKeys),
                                                      "executeUpate"),
                                   "HY092");
        }

        if (returnKeys) {
            if (connection.getServerType() == Driver.SQLSERVER
                && connection.getDatabaseMajorVersion() >= 8) {
                sql += " SELECT SCOPE_IDENTITY() AS ID";
            } else {
                sql += " SELECT @@IDENTITY AS ID";
            }
        }

        executeSQL(sql, null, sqlWord, null, returnKeys, true);

        return getUpdateCount(0);
    }

    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();

        if (sql == null || sql.length() == 0) {
            throw new SQLException(Messages.get("error.generic.nosql"), "HY000");
        }

        boolean returnKeys;
        String sqlWord = "";
        if (escapeProcessing) {
            ArrayList params = new ArrayList();
            String tmp[] = new SQLParser(sql, params, connection.getServerType()).parse(false);

            if (tmp[1].length() != 0 || params.size() > 0) {
                throw new SQLException(
                    Messages.get("error.statement.badsql"), "07000");
            }

            sql = tmp[0];
            sqlWord = tmp[2];
        } else {
            // Escape processing turned off so
            // see if we can extract "insert" from start of statement
            sql = sql.trim();
            if (sql.length() > 5) {
                sqlWord = sql.substring(0,6).toLowerCase();
            }
        }

        if (autoGeneratedKeys == RETURN_GENERATED_KEYS) {
            returnKeys = sqlWord.equals("insert");
        } else if (autoGeneratedKeys == NO_GENERATED_KEYS) {
            returnKeys = false;
        } else {
            throw new SQLException(Messages.get("error.generic.badoption",
                                                      Integer.toString(autoGeneratedKeys),
                                                      "executeUpate"),
                                   "HY092");
        }

        if (returnKeys) {
            if (connection.getServerType() == Driver.SQLSERVER
                && connection.getDatabaseMajorVersion() >= 8) {
                sql += " SELECT SCOPE_IDENTITY() AS ID";
            } else {
                sql += " SELECT @@IDENTITY AS ID";
            }
        }

        return executeSQL(sql, null, sqlWord, null, returnKeys, false);
    }

    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();

        if (columnIndexes == null) {
            throw new SQLException(
                Messages.get("error.generic.nullparam", "executeUpdate"),"HY092");
        } else if (columnIndexes.length != 1) {
            throw new SQLException(
                Messages.get("error.generic.needcolindex", "executeUpdate"),"HY092");
        }

        return executeUpdate(sql, RETURN_GENERATED_KEYS);
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();

        if (columnIndexes == null) {
            throw new SQLException(
                Messages.get("error.generic.nullparam", "execute"),"HY092");
        } else if (columnIndexes.length != 1) {
            throw new SQLException(
                Messages.get("error.generic.needcolindex", "execute"),"HY092");
        }

        return execute(sql, RETURN_GENERATED_KEYS);
    }

    public Connection getConnection() throws SQLException {
        checkOpen();

        return this.connection;
    }

    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();

        if (genKeyResultSet == null) {
            ColInfo[] dummy =  new ColInfo[1];
            ColInfo col = new ColInfo();
            col.realName = "ID";
            col.name = "ID";
            col.jdbcType = java.sql.Types.INTEGER;
            col.isWriteable = false;
            dummy[0] = col;
            genKeyResultSet = new DummyResultSet(this, dummy, null);
        }

        return genKeyResultSet;
    }

    public ResultSet getResultSet() throws SQLException {
        checkOpen();

        return currentResult;
    }

    public SQLWarning getWarnings() throws SQLException {
        checkOpen();

        return messages.getWarnings();
    }

    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        checkOpen();

        if (columnNames == null) {
            throw new SQLException(
                Messages.get("error.generic.nullparam", "executeUpdate"),"HY092");
        } else if (columnNames.length != 1) {
            throw new SQLException(
                Messages.get("error.generic.needcolname", "executeUpdate"),"HY092");
        }

        return executeUpdate(sql, RETURN_GENERATED_KEYS);
    }

    public boolean execute(String sql, String[] columnNames) throws SQLException {
        if (columnNames == null) {
            throw new SQLException(
                Messages.get("error.generic.nullparam", "execute"),"HY092");
        } else if (columnNames.length != 1) {
            throw new SQLException(
                Messages.get("error.generic.needcolname", "execute"),"HY092");
        }

        return execute(sql, RETURN_GENERATED_KEYS);
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();

        if (sql == null || sql.length() == 0) {
            throw new SQLException(Messages.get("error.generic.nosql"), "HY000");
        }
        if (escapeProcessing) {
            ArrayList params = new ArrayList();
            String tmp[] = new SQLParser(sql, params, connection.getServerType()).parse(false);

            if (tmp[1].length() != 0 || params.size() > 0) {
                throw new SQLException(
                    Messages.get("error.statement.badsql"), "07000");
            }

            sql = tmp[0];
        }

        return this.executeSQLQuery(sql, null, null, false);
    }
}
