/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.client.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.trace.TraceObjectType;
import com.lealone.common.util.MathUtils;
import com.lealone.db.command.CommandParameter;
import com.lealone.db.command.SQLCommand;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;

/**
 * Information about the parameters of a prepared statement.
 * 
 * @author H2 Group
 * @author zhh
 */
public class JdbcParameterMetaData extends JdbcWrapper implements ParameterMetaData {

    private final JdbcPreparedStatement prep;
    private final List<? extends CommandParameter> parameters;
    private final int paramCount;

    JdbcParameterMetaData(JdbcPreparedStatement prep, SQLCommand command, int id) {
        this.prep = prep;
        this.parameters = command.getParameters();
        this.paramCount = parameters.size();
        this.trace = prep.conn.getTrace(TraceObjectType.PARAMETER_META_DATA, id);
    }

    /**
     * Returns the number of parameters.
     *
     * @return the number
     */
    @Override
    public int getParameterCount() throws SQLException {
        try {
            debugCodeCall("getParameterCount");
            checkClosed();
            return paramCount;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the parameter mode.
     * Always returns parameterModeIn.
     *
     * @param param the column index (1,2,...)
     * @return parameterModeIn
     */
    @Override
    public int getParameterMode(int param) throws SQLException {
        try {
            debugCodeCall("getParameterMode", param);
            getParameter(param);
            return parameterModeIn;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the parameter type.
     * java.sql.Types.VARCHAR is returned if the data type is not known.
     *
     * @param param the column index (1,2,...)
     * @return the data type
     */
    @Override
    public int getParameterType(int param) throws SQLException {
        try {
            debugCodeCall("getParameterType", param);
            CommandParameter p = getParameter(param);
            int type = p.getType();
            if (type == Value.UNKNOWN) {
                type = Value.STRING;
            }
            return DataType.getDataType(type).sqlType;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the parameter precision.
     * The value 0 is returned if the precision is not known.
     *
     * @param param the column index (1,2,...)
     * @return the precision
     */
    @Override
    public int getPrecision(int param) throws SQLException {
        try {
            debugCodeCall("getPrecision", param);
            CommandParameter p = getParameter(param);
            return MathUtils.convertLongToInt(p.getPrecision());
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the parameter scale.
     * The value 0 is returned if the scale is not known.
     *
     * @param param the column index (1,2,...)
     * @return the scale
     */
    @Override
    public int getScale(int param) throws SQLException {
        try {
            debugCodeCall("getScale", param);
            CommandParameter p = getParameter(param);
            return p.getScale();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Checks if this is nullable parameter.
     * Returns ResultSetMetaData.columnNullableUnknown..
     *
     * @param param the column index (1,2,...)
     * @return ResultSetMetaData.columnNullableUnknown
     */
    @Override
    public int isNullable(int param) throws SQLException {
        try {
            debugCodeCall("isNullable", param);
            return getParameter(param).getNullable();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Checks if this parameter is signed.
     * It always returns true.
     *
     * @param param the column index (1,2,...)
     * @return true
     */
    @Override
    public boolean isSigned(int param) throws SQLException {
        try {
            debugCodeCall("isSigned", param);
            getParameter(param);
            return true;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the Java class name of the parameter.
     * "java.lang.String" is returned if the type is not known.
     *
     * @param param the column index (1,2,...)
     * @return the Java class name
     */
    @Override
    public String getParameterClassName(int param) throws SQLException {
        try {
            debugCodeCall("getParameterClassName", param);
            CommandParameter p = getParameter(param);
            int type = p.getType();
            if (type == Value.UNKNOWN) {
                type = Value.STRING;
            }
            return DataType.getTypeClassName(type);
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the parameter type name.
     * "VARCHAR" is returned if the type is not known.
     *
     * @param param the column index (1,2,...)
     * @return the type name
     */
    @Override
    public String getParameterTypeName(int param) throws SQLException {
        try {
            debugCodeCall("getParameterTypeName", param);
            CommandParameter p = getParameter(param);
            int type = p.getType();
            if (type == Value.UNKNOWN) {
                type = Value.STRING;
            }
            return DataType.getDataType(type).name;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    private CommandParameter getParameter(int param) {
        checkClosed();
        if (param < 1 || param > paramCount) {
            throw DbException.getInvalidValueException("param", param);
        }
        return parameters.get(param - 1);
    }

    private void checkClosed() {
        prep.checkClosed();
    }

    /**
     * INTERNAL
     */
    @Override
    public String toString() {
        return getTraceObjectName() + ": parameterCount=" + paramCount;
    }
}
