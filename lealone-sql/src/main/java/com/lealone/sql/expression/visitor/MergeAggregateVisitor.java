/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.visitor;

import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.sql.expression.aggregate.AGroupConcat;
import com.lealone.sql.expression.aggregate.Aggregate;
import com.lealone.sql.expression.aggregate.JavaAggregate;

public class MergeAggregateVisitor extends VoidExpressionVisitor {

    private ServerSession session;
    private Value value;

    public MergeAggregateVisitor(ServerSession session, Value value) {
        this.session = session;
        this.value = value;
    }

    @Override
    public Void visitAggregate(Aggregate e) {
        e.mergeAggregate(session, value);
        return null;
    }

    @Override
    public Void visitAGroupConcat(AGroupConcat e) {
        e.mergeAggregate(session, value);
        return null;
    }

    @Override
    public Void visitJavaAggregate(JavaAggregate e) {
        e.mergeAggregate(session, value);
        return null;
    }
}
