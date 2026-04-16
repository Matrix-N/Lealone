/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.logging;

public interface Logger {

    boolean isWarnEnabled();

    boolean isInfoEnabled();

    boolean isDebugEnabled();

    boolean isTraceEnabled();

    void fatal(Object message);

    void fatal(Object message, Throwable t);

    void error(Object message);

    void error(Object message, Object... params);

    void error(Object message, Throwable t);

    void error(Object message, Throwable t, Object... params);

    void warn(Object message);

    void warn(Object message, Object... params);

    void warn(Object message, Throwable t);

    void warn(Object message, Throwable t, Object... params);

    void info(Object message);

    void info(Object message, Object... params);

    void info(Object message, Throwable t);

    void info(Object message, Throwable t, Object... params);

    void debug(Object message);

    void debug(Object message, Object... params);

    void debug(Object message, Throwable t);

    void debug(Object message, Throwable t, Object... params);

    void trace(Object message);

    void trace(Object message, Object... params);

    void trace(Object message, Throwable t);

    void trace(Object message, Throwable t, Object... params);

    public static String format(Object message, Object... params) {
        char[] chars = message.toString().toCharArray();
        int length = chars.length;
        StringBuilder s = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (chars[i] == '{' && chars[i + 1] == '}') {
                s.append("%s");
                i++;
            } else {
                s.append(chars[i]);
            }
        }
        return String.format(s.toString(), params);
    }
}
