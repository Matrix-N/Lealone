/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueInt;
import com.lealone.sql.expression.visitor.ExpressionVisitor;
import com.lealone.sql.optimizer.ColumnResolver;

/**
 * Represents the ROW_VERSION function.
 */
public class RowVersion extends Rownum {

    private ColumnResolver resolver;

    public RowVersion() {
        super(null);
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        if (this.resolver == null)
            this.resolver = resolver;
    }

    @Override
    public Value getValue(ServerSession session) {
        return ValueInt.get(resolver.getRowVersion());
    }

    @Override
    public int getType() {
        return Value.INT;
    }

    @Override
    public Expression optimize(ServerSession session) {
        return this;
    }

    @Override
    public int getScale() {
        return 0;
    }

    @Override
    public long getPrecision() {
        return ValueInt.PRECISION;
    }

    @Override
    public int getDisplaySize() {
        return ValueInt.DISPLAY_SIZE;
    }

    @Override
    public String getSQL() {
        return "ROW_VERSION()";
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append(getSQL());
    }

    @Override
    public int getCost() {
        return 0;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitRownum(this);
    }
}
