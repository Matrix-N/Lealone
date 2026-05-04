/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.logging.impl;

import com.lealone.common.logging.Logger;
import com.lealone.common.trace.Trace;

class DefaultLogger implements Logger {

    private final Trace trace;

    DefaultLogger(Trace trace) {
        this.trace = trace;
    }

    @Override
    public boolean isWarnEnabled() {
        return trace.isInfoEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return trace.isInfoEnabled();
    }

    @Override
    public boolean isDebugEnabled() {
        return trace.isDebugEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return trace.isDebugEnabled();
    }

    @Override
    public void fatal(Object message) {
        error(message);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        error(message, t);
    }

    @Override
    public void error(Object message) {
        trace.error(null, message.toString());
    }

    @Override
    public void error(Object message, Object... params) {
        trace.error(null, Logger.format(message, params));
    }

    @Override
    public void error(Object message, Throwable t) {
        trace.error(t, message.toString());
    }

    @Override
    public void error(Object message, Throwable t, Object... params) {
        trace.error(t, Logger.format(message, params));
    }

    @Override
    public void warn(Object message) {
        info(message);
    }

    @Override
    public void warn(Object message, Object... params) {
        info(message, params);
    }

    @Override
    public void warn(Object message, Throwable t) {
        info(message, t);
    }

    @Override
    public void warn(Object message, Throwable t, Object... params) {
        info(message, t, params);
    }

    @Override
    public void info(Object message) {
        trace.info(message.toString());
    }

    @Override
    public void info(Object message, Object... params) {
        trace.info(Logger.format(message, params));
    }

    @Override
    public void info(Object message, Throwable t) {
        trace.info(t, message.toString());
    }

    @Override
    public void info(Object message, Throwable t, Object... params) {
        trace.info(t, Logger.format(message, params));
    }

    @Override
    public void debug(Object message) {
        trace.debug(message.toString());
    }

    @Override
    public void debug(Object message, Object... params) {
        trace.debug(Logger.format(message, params));
    }

    @Override
    public void debug(Object message, Throwable t) {
        trace.debug(t, message.toString());
    }

    @Override
    public void debug(Object message, Throwable t, Object... params) {
        trace.debug(t, Logger.format(message, params));
    }

    @Override
    public void trace(Object message) {
        debug(message);
    }

    @Override
    public void trace(Object message, Object... params) {
        debug(message, params);
    }

    @Override
    public void trace(Object message, Throwable t) {
        debug(message, t);
    }

    @Override
    public void trace(Object message, Throwable t, Object... params) {
        debug(message, t, params);
    }
}
