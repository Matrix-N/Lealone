/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import java.util.ArrayList;

import com.lealone.db.session.ServerSession;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.visitor.DeterministicVisitor;
import com.lealone.sql.expression.visitor.ExpressionVisitorFactory;

public abstract class ExecuteStatement extends ManipulationStatement {

    protected final ArrayList<Expression> expressions = new ArrayList<>();

    public ExecuteStatement(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.EXECUTE;
    }

    /**
     * Set the expression at the given index.
     *
     * @param index the index (0 based)
     * @param expr the expression
     */
    public void setExpression(int index, Expression expr) {
        expressions.add(index, expr);
    }

    public boolean isDeterministic() {
        DeterministicVisitor dv = ExpressionVisitorFactory.getDeterministicVisitor();
        for (int i = 0, size = expressions.size(); i < size; i++) {
            Expression e = expressions.get(i).optimize(session);
            if (!dv.visitExpression(e))
                return false;
        }
        return true;
    }

    @Override
    public PreparedSQLStatement prepare() {
        for (int i = 0, size = expressions.size(); i < size; i++) {
            Expression e = expressions.get(i).optimize(session);
            expressions.set(i, e);
        }
        if (session.isReplicationMode())
            session.setDeterministic(isDeterministic());
        return this;
    }
}
