/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.lealone.client.jdbc.JdbcConnection;
import com.lealone.client.jdbc.JdbcDriver;
import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.IOUtils;
import com.lealone.common.util.JdbcUtils;
import com.lealone.common.util.ScriptReader;
import com.lealone.common.util.StringUtils;
import com.lealone.db.ConnectionInfo;
import com.lealone.db.ConnectionSetting;
import com.lealone.db.Constants;
import com.lealone.db.async.Future;
import com.lealone.net.NetFactory;

/**
 * Interactive command line tool to access a database using JDBC.
 * 
 * @author H2 Group
 * @author zhh
 */
public class LealoneClient {

    public static Future<JdbcConnection> getConnection(String url) {
        return JdbcDriver.getConnection(url);
    }

    public static Future<JdbcConnection> getConnection(String url, String user, String password) {
        return JdbcDriver.getConnection(url, user, password);
    }

    public static Future<JdbcConnection> getConnection(String url, Properties info) {
        return JdbcDriver.getConnection(url, info);
    }

    protected static JdbcConnection getConnectionSync(ConnectionInfo ci) {
        return JdbcDriver.getConnection(ci).get();
    }

    protected static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    protected static boolean isChineseWindows() {
        String encoding = System.getProperty("sun.jnu.encoding");
        String lang = System.getProperty("user.language");
        return System.console() != null && isWindows() && ("GBK".equalsIgnoreCase(encoding)
                || "CN".equalsIgnoreCase(lang) || "zh".equalsIgnoreCase(lang));
    }

    private static final int MAX_ROW_BUFFER = 5000;
    private static final int HISTORY_COUNT = 20;
    // Windows: '\u00b3';
    private static final char BOX_VERTICAL = '|';

    private final PrintStream err = System.err;
    private final InputStream in = System.in;
    private final PrintStream out = System.out;
    // 中文windows命令行窗口默认是GBK
    private final BufferedReader reader = new BufferedReader(
            new InputStreamReader(in, Charset.forName(isChineseWindows() ? "GBK" : "UTF-8")));
    private final ArrayList<String> history = new ArrayList<>();
    private final String[] args;
    private JdbcConnection conn;
    private Statement stat;
    private boolean listMode;
    private int maxColumnSize = 100;
    private String url, user = "root", password;
    private String host = Constants.DEFAULT_HOST;
    private String port = Constants.DEFAULT_TCP_PORT + "";
    private String database = "lealone";
    private boolean embedded;
    private boolean safeMode;

    public static void main(String[] args) {
        LealoneClient client = new LealoneClient(args);
        main(client);
    }

    protected static void main(LealoneClient client) {
        System.setProperty("client_logger_enabled", "false");
        try {
            client.run();
        } catch (Exception e) {
            client.printException(e);
        } finally {
            client.close();
        }
    }

    protected LealoneClient(String[] args) {
        this.args = args;
    }

    private void run() throws Exception {
        String sql = null;
        boolean agent = false;
        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.isEmpty())
                continue;
            if (arg.equals("-host")) {
                host = args[++i];
            } else if (arg.equals("-port")) {
                port = args[++i];
            } else if (arg.equals("-url")) {
                url = args[++i];
            } else if (arg.equals("-user")) {
                user = args[++i];
            } else if (arg.equals("-password")) {
                password = args[++i];
            } else if (arg.equals("-database")) {
                database = args[++i];
            } else if (arg.equals("-agent")) {
                agent = true;
            } else if (arg.equals("-sql")) {
                sql = args[++i];
            } else if (arg.equals("-help") || arg.equals("-?")) {
                showUsage();
                return;
            } else if (arg.equals("-list")) {
                listMode = true;
            } else if (arg.equals("-embed")) {
                embedded = true;
            } else if (arg.equals("-safeMode")) {
                safeMode = true;
            } else if (arg.equals("-client") || arg.equals("-debug")) {
                continue;
            } else {
                if (agent && arg.charAt(0) != '-') {
                    database = arg;
                } else {
                    println("Unsupported option: " + arg);
                    showUsage();
                    return;
                }
            }
        }
        showWelcome();
        if (url == null) {
            StringBuilder buff = new StringBuilder(100);
            buff.append(Constants.URL_PREFIX);
            if (embedded) {
                buff.append(Constants.URL_EMBED);
            } else {
                buff.append(Constants.URL_TCP).append("//");
                buff.append(host).append(":").append(port).append('/');
            }
            buff.append(database);
            url = buff.toString();
            // readConnectionArgs();
        }
        println("Connect to " + url);
        // println();

        connect();
        if (sql != null) {
            executeSqlScript(sql);
        } else {
            promptLoop();
        }
    }

    @SuppressWarnings("unused")
    private void readConnectionArgs() throws Exception {
        StringBuilder buff = new StringBuilder(100);
        buff.append(Constants.URL_PREFIX).append(Constants.URL_TCP).append("//127.0.0.1:")
                .append(Constants.DEFAULT_TCP_PORT).append('/').append(Constants.PROJECT_NAME);
        url = buff.toString();
        println("[Enter] " + url);
        print("URL ");
        url = readLine(url).trim();

        user = "root";
        println("[Enter] " + user);
        print("User ");
        user = readLine(user);

        println("[Enter] Hide");
        password = readPassword();
    }

    private void connect() throws Exception {
        conn = (JdbcConnection) getConnection();
        stat = conn.createStatement();
    }

    protected ConnectionInfo getConnectionInfo() throws SQLException {
        Properties info = new Properties();
        String u = url.toLowerCase();
        // 优先使用jdbc url中指定的user和password
        if (user != null && !u.contains("user")) {
            info.put("user", user);
        }
        if (password != null && !u.contains("password")) {
            info.put("password", password);
        }
        // 交互式命令行客户端永不超时
        info.put(ConnectionSetting.NETWORK_TIMEOUT.name(), "-1");
        ConnectionInfo ci = new ConnectionInfo(url, info);
        // 交互式命令行客户端用阻塞IO更快
        if (ci.isRemote())
            ci.setNetFactoryName(NetFactory.BIO);
        ci.setSafeMode(safeMode);
        return ci;
    }

    protected Connection getConnection() throws SQLException {
        ConnectionInfo ci = getConnectionInfo();
        return getConnectionSync(ci);
    }

    private void reconnect() throws Exception {
        closeJdbc();
        connect();
        println("Reconnected");
    }

    private void close() {
        closeJdbc();
        IOUtils.closeSilently(reader);
        println("Connection closed");
    }

    private void closeJdbc() {
        JdbcUtils.closeSilently(stat);
        JdbcUtils.closeSilently(conn);
    }

    private void executeSqlScript(String sql) throws SQLException {
        ScriptReader r = new ScriptReader(new StringReader(sql));
        while (true) {
            String s = r.readStatement();
            if (s == null) {
                break;
            }
            execute(s);
        }
    }

    public String getName() {
        return "Lealone Shell";
    }

    private void showWelcome() {
        println();
        println("Welcome to " + getName() + " " + Constants.getVersion());
        // println("Exit with Ctrl+C");
    }

    private void showUsage() {
        println("Options are case sensitive. Supported options are:");
        println("-------------------------------------------------");
        println("[-help] or [-?]         Print the list of options");
        showClientOrEmbeddedModeOptions();
    }

    public void showClientOrEmbeddedModeOptions() {
        println("[-url \"<url>\"]          The database URL (jdbc:lealone:...)");
        println("[-user <user>]          The user name");
        println("[-password <pwd>]       The password");
        println("[-database <db>]        The database");
        println("[-sql \"<statements>\"]   Execute the SQL statements and exit");
        println();
        println("If special characters don't work as expected, ");
        println("you may need to use -Dfile.encoding=UTF-8 (Mac OS X) or CP850 (Windows).");
        println();
    }

    private void showHelp() {
        println("Commands are case insensitive; SQL statements end with ';'");
        println("help or ?          Display this help");
        println("list               Toggle result list / stack trace mode");
        println("maxwidth or md     Set maximum column width (default is 100)");
        println("autocommit or ac   Enable or disable autocommit");
        println("history or h       Show the last 20 statements");
        println("reconnect or rc    Reconnect the database");
        println("quit or exit       Close the connection and exit");
        println();
    }

    protected String getPrompt() {
        return "sql> ";
    }

    protected String getPromptContinue() {
        return "  -> ";
    }

    private void promptLoop() {
        showHelp();
        String statement = null;
        while (true) {
            try {
                if (statement == null) {
                    print(getPrompt());
                } else {
                    print(getPromptContinue());
                }
                String line = readLine();
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                boolean end = trimmed.endsWith(";");
                if (end) {
                    line = line.substring(0, line.lastIndexOf(';'));
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                String lower = StringUtils.toLowerEnglish(trimmed);
                if ("exit".equals(lower) || "quit".equals(lower)) {
                    break;
                } else if ("help".equals(lower) || "?".equals(lower)) {
                    showHelp();
                } else if ("list".equals(lower)) {
                    listMode = !listMode;
                    println("Result list mode is now " + (listMode ? "on" : "off"));
                } else if ("history".equals(lower) || "h".equals(lower)) {
                    for (int i = 0, size = history.size(); i < size; i++) {
                        String s = history.get(i);
                        s = s.replace('\n', ' ').replace('\r', ' ');
                        println("#" + (1 + i) + ": " + s);
                    }
                    if (history.size() > 0) {
                        println("To re-run a statement, type the number and press enter");
                    } else {
                        println("No history");
                    }
                } else if (lower.startsWith("autocommit") || lower.startsWith("ac")) {
                    if (lower.startsWith("ac"))
                        lower = lower.substring("ac".length()).trim();
                    else
                        lower = lower.substring("autocommit".length()).trim();
                    if ("true".equals(lower)) {
                        conn.setAutoCommit(true);
                    } else if ("false".equals(lower)) {
                        conn.setAutoCommit(false);
                    } else {
                        println("Usage: autocommit [true|false]");
                    }
                    println("Autocommit is now " + conn.getAutoCommit());
                } else if (lower.startsWith("maxwidth") || lower.startsWith("md")) {
                    if (lower.startsWith("md"))
                        lower = lower.substring("md".length()).trim();
                    else
                        lower = lower.substring("maxwidth".length()).trim();
                    try {
                        maxColumnSize = Integer.parseInt(lower);
                    } catch (NumberFormatException e) {
                        println("Usage: maxwidth <integer value>");
                    }
                    println("Maximum column width is now " + maxColumnSize);
                } else if ("reconnect".equals(lower) || "rc".equals(lower)) {
                    reconnect();
                } else {
                    boolean addToHistory = true;
                    if (statement == null) {
                        if (StringUtils.isNumber(line)) {
                            int pos = Integer.parseInt(line);
                            if (pos == 0 || pos > history.size()) {
                                println("Not found");
                            } else {
                                statement = history.get(pos - 1);
                                addToHistory = false;
                                println(statement);
                                end = true;
                            }
                        } else {
                            statement = line;
                        }
                    } else {
                        statement += "\n" + line;
                    }
                    if (end) {
                        if (addToHistory) {
                            history.add(0, statement);
                            if (history.size() > HISTORY_COUNT) {
                                history.remove(HISTORY_COUNT);
                            }
                        }
                        execute(statement);
                        statement = null;
                    }
                }
            } catch (SQLException e) {
                println("SQL Exception: " + e.getMessage());
                statement = null;
            } catch (IOException e) {
                println(e.getMessage());
                break;
            } catch (Exception e) {
                printException(e);
                break;
            }
        }
    }

    private void printException(Exception e) {
        println("Exception: " + e.getMessage());
        e.printStackTrace(err);
    }

    private void print(String s) {
        out.print(s);
        out.flush();
    }

    private void println() {
        println("");
    }

    private void println(String s) {
        out.println(s);
        out.flush();
    }

    private String readPassword() throws IOException {
        java.io.Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("Password  ");
            return password == null ? null : new String(password);
        } else { // In Eclipse, use the default solution
            print("Password  ");
            return readLine();
        }
    }

    private String readLine(String defaultValue) throws IOException {
        String s = readLine();
        return s.length() == 0 ? defaultValue : s;
    }

    private String readLine() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Aborted");
        }
        return line;
    }

    private void execute(String sql) {
        sql = sql.trim();
        if (sql.isEmpty()) {
            return;
        }
        try {
            if (conn.isClosed()) {
                reconnect();
            }
            long time = System.nanoTime();
            ResultSet rs = null;
            try {
                if (!conn.isClientProtocolVersionGte8() && sql.startsWith("select")) {
                    rs = stat.executeQuery(sql);
                    printQueryResult(rs, listMode, time);
                } else if (!conn.isClientProtocolVersionGte8() && (sql.startsWith("insert") //
                        || sql.startsWith("update") //
                        || sql.startsWith("delete"))) {
                    int updateCount = stat.executeUpdate(sql);
                    printUpdateResult(updateCount, time);
                } else {
                    if (stat.execute(sql)) {
                        rs = stat.getResultSet();
                        printQueryResult(rs, listMode, time);
                    } else {
                        int updateCount = stat.getUpdateCount();
                        printUpdateResult(updateCount, time);
                    }
                }
            } finally {
                JdbcUtils.closeSilently(rs);
            }
        } catch (Exception e) {
            // 如果出现网络异常了，重新创建连接再执行一次
            if (DbException.getRootCause(e) instanceof IOException) {
                try {
                    reconnect();
                    execute(sql);
                    return;
                } catch (Throwable t) {
                }
            }
            if (listMode) {
                e.printStackTrace(err);
            } else {
                println("Error: " + DbException.getRootCause(e).getMessage());
            }
        }
        println();
    }

    private void printQueryResult(ResultSet rs, boolean asList, long time) throws SQLException {
        time = System.nanoTime() - time;
        int rowCount = printResult(rs, listMode);
        time = TimeUnit.NANOSECONDS.toMillis(time);
        println("(" + rowCount + (rowCount == 1 ? " row, " : " rows, ") + time + " ms)");
    }

    private void printUpdateResult(int updateCount, long time) throws SQLException {
        time = System.nanoTime() - time;
        time = TimeUnit.NANOSECONDS.toMillis(time);
        println("(Update count: " + updateCount + ", " + time + " ms)");
    }

    private int printResult(ResultSet rs, boolean asList) throws SQLException {
        if (asList) {
            return printResultAsList(rs);
        }
        return printResultAsTable(rs);
    }

    private int printResultAsTable(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int len = meta.getColumnCount();
        boolean truncated = false;
        ArrayList<String[]> rows = new ArrayList<>();
        // buffer the header
        String[] columns = new String[len];
        for (int i = 0; i < len; i++) {
            String s = meta.getColumnLabel(i + 1);
            columns[i] = s == null ? "" : s;
        }
        rows.add(columns);
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            truncated |= loadRow(rs, len, rows);
            if (rows.size() > MAX_ROW_BUFFER) {
                printRows(rows, len);
                rows.clear();
            }
        }
        printRows(rows, len);
        rows.clear();
        if (truncated) {
            println("(data is partially truncated)");
        }
        return rowCount;
    }

    private boolean loadRow(ResultSet rs, int len, ArrayList<String[]> rows) throws SQLException {
        boolean truncated = false;
        String[] row = new String[len];
        for (int i = 0; i < len; i++) {
            String s = rs.getString(i + 1);
            if (s == null) {
                s = "null";
            }
            // only truncate if more than one column
            if (len > 1 && s.length() > maxColumnSize) {
                s = s.substring(0, maxColumnSize);
                truncated = true;
            }
            row[i] = s;
        }
        rows.add(row);
        return truncated;
    }

    private static int getDisplayWidth(String s) {
        if (s == null || s.isEmpty())
            return 0;
        int width = 0;
        for (char c : s.toCharArray()) {
            width += charWidth(c);
        }
        return width;
    }

    private static int charWidth(char c) {
        // 控制字符 = 0
        if (c <= 0x1F || (c >= 0x7F && c <= 0x9F)) {
            return 0;
        }
        // 以下全部 = 2 字符宽度
        if ((c >= 0x3000 && c <= 0x303F) || // 中日韩符号
                (c >= 0x4E00 && c <= 0x9FFF) || // 常用汉字
                (c >= 0x3400 && c <= 0x4DBF) || // 扩展汉字
                (c >= 0xF900 && c <= 0xFAFF) || // 兼容汉字
                (c >= 0xFF01 && c <= 0xFF60) || // 全角符号 ！＂＃＠【】。、～
                (c >= 0xFFE0 && c <= 0xFFE6) // 全角特殊符号
        ) {
            return 2;
        }
        // 其余所有字符 = 1
        return 1;
    }

    private int[] printRows(ArrayList<String[]> rows, int len) {
        StringBuilder buffAll = new StringBuilder();
        int[] columnSizes = new int[len];
        ArrayList<int[]> displayWidthList = new ArrayList<>();
        ArrayList<java.util.List<String>> lineListList = new ArrayList<>();
        ArrayList<int[]> lineDisplayWidthList = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            int max = 0;
            for (int j = 0, size = rows.size(); j < size; j++) {
                String[] row = rows.get(j);
                String s = row[i];
                if (len == 1 && s.indexOf('\n') >= 0) {
                    // 字段内容换行输出
                    java.util.List<String> lineList = s.lines().toList();
                    lineListList.add(lineList);
                    int[] displayWidth = new int[lineList.size()];
                    lineDisplayWidthList.add(displayWidth);
                    for (int m = 0, lsize = lineList.size(); m < lsize; m++) {
                        String s2 = lineList.get(m);
                        int w = getDisplayWidth(s2);
                        displayWidth[m] = w;
                        max = Math.max(max, w);
                    }
                    displayWidthList.add(displayWidth);
                } else {
                    int w = getDisplayWidth(row[i]);
                    int[] displayWidth;
                    if (displayWidthList.size() > j) {
                        displayWidth = displayWidthList.get(j);
                    } else {
                        displayWidth = new int[len];
                        displayWidthList.add(displayWidth);
                    }
                    displayWidth[i] = w;
                    max = Math.max(max, w);

                    lineDisplayWidthList.add(null);
                    lineListList.add(null);
                }
            }
            if (len > 1) {
                Math.min(maxColumnSize, max);
            }
            columnSizes[i] = max;
        }
        StringBuilder buffHorizontal = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i == 0) {
                buffHorizontal.append('+').append('-');
            }
            if (i > 0) {
                buffHorizontal.append('-').append('+').append('-');
            }
            for (int j = 0, size = columnSizes[i]; j < size; j++) {
                buffHorizontal.append('-');
            }

            if (i == len - 1) {
                buffHorizontal.append('-').append('+');
            }
        }

        boolean first = true;
        for (int r = 0, rsize = rows.size(); r < rsize; r++) {
            String[] row = rows.get(r);
            int[] displayWidth = displayWidthList.get(r);
            if (first) {
                println(buffAll, buffHorizontal);
            }
            StringBuilder buff = new StringBuilder();
            for (int i = 0; i < len; i++) {
                String s = row[i];
                if (len == 1 && s.indexOf('\n') >= 0) {
                    // 字段内容换行输出
                    java.util.List<String> lineList = lineListList.get(r);
                    int[] displayWidth2 = lineDisplayWidthList.get(r);
                    for (int m = 0, size = lineList.size(); m < size; m++) {
                        String s2 = lineList.get(m);
                        buff.append(BOX_VERTICAL).append(' ');
                        buff.append(s2);
                        for (int j = displayWidth2[m]; j < columnSizes[i]; j++) {
                            buff.append(' ');
                        }
                        buff.append(' ').append(BOX_VERTICAL);
                        if (m != size - 1) {
                            buff.append("\r\n");
                        }
                    }
                    continue;
                }
                if (i == 0) {
                    buff.append(BOX_VERTICAL).append(' ');
                }
                if (i > 0) {
                    buff.append(' ').append(BOX_VERTICAL).append(' ');
                }
                buff.append(s);
                for (int j = displayWidth[i]; j < columnSizes[i]; j++) {
                    buff.append(' ');
                }

                if (i == len - 1) {
                    buff.append(' ').append(BOX_VERTICAL);
                }
            }
            println(buffAll, buff);
            if (first) {
                println(buffAll, buffHorizontal);
                first = false;
            }
        }
        println(buffAll, buffHorizontal);
        print(buffAll.toString());
        return columnSizes;
    }

    private void println(StringBuilder buffAll, StringBuilder str) {
        buffAll.append(str).append("\r\n");
        if (buffAll.length() > 8096) {
            print(buffAll.toString());
            buffAll.setLength(0);
        }
    }

    private int printResultAsList(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int longestLabel = 0;
        int len = meta.getColumnCount();
        String[] columns = new String[len];
        for (int i = 0; i < len; i++) {
            String s = meta.getColumnLabel(i + 1);
            columns[i] = s;
            longestLabel = Math.max(longestLabel, s.length());
        }
        StringBuilder buff = new StringBuilder();
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            buff.setLength(0);
            if (rowCount > 1) {
                println("");
            }
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    buff.append('\n');
                }
                String label = columns[i];
                buff.append(label);
                for (int j = label.length(); j < longestLabel; j++) {
                    buff.append(' ');
                }
                buff.append(": ").append(rs.getString(i + 1));
            }
            println(buff.toString());
        }
        if (rowCount == 0) {
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    buff.append('\n');
                }
                String label = columns[i];
                buff.append(label);
            }
            println(buff.toString());
        }
        return rowCount;
    }
}
