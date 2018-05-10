/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.CamelCaseHelper;
import org.lealone.common.util.New;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.ServerSession;
import org.lealone.db.schema.Schema;
import org.lealone.db.schema.Sequence;
import org.lealone.db.table.Column;
import org.lealone.db.table.CreateTableData;
import org.lealone.db.table.IndexColumn;
import org.lealone.db.table.Table;
import org.lealone.db.value.CaseInsensitiveMap;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.dml.Insert;
import org.lealone.sql.dml.Query;
import org.lealone.sql.expression.Expression;

/**
 * This class represents the statement
 * CREATE TABLE
 * 
 * @author H2 Group
 * @author zhh
 */
public class CreateTable extends SchemaStatement {

    protected final CreateTableData data = new CreateTableData();
    protected IndexColumn[] pkColumns;
    protected boolean ifNotExists;

    private final ArrayList<DefineStatement> constraintCommands = New.arrayList();
    private boolean onCommitDrop;
    private boolean onCommitTruncate;
    private Query asQuery;
    private String comment;
    private String packageName;
    private boolean genCode;
    private String codePath;

    public CreateTable(ServerSession session, Schema schema) {
        super(session, schema);
        data.persistIndexes = true;
        data.persistData = true;
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_TABLE;
    }

    public void setQuery(Query query) {
        this.asQuery = query;
    }

    public void setTemporary(boolean temporary) {
        data.temporary = temporary;
    }

    public void setTableName(String tableName) {
        data.tableName = tableName;
    }

    /**
     * Add a column to this table.
     *
     * @param column the column to add
     */
    public void addColumn(Column column) {
        data.columns.add(column);
    }

    /**
     * Add a constraint statement to this statement.
     * The primary key definition is one possible constraint statement.
     *
     * @param command the statement to add
     */
    public void addConstraintCommand(DefineStatement command) {
        if (command instanceof CreateIndex) {
            constraintCommands.add(command);
        } else {
            AlterTableAddConstraint con = (AlterTableAddConstraint) command;
            boolean alreadySet;
            if (con.getType() == SQLStatement.ALTER_TABLE_ADD_CONSTRAINT_PRIMARY_KEY) {
                alreadySet = setPrimaryKeyColumns(con.getIndexColumns());
            } else {
                alreadySet = false;
            }
            if (!alreadySet) {
                constraintCommands.add(command);
            }
        }
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    @Override
    public int update() {
        Database db = session.getDatabase();
        if (!db.isPersistent()) {
            data.persistIndexes = false;
        }
        synchronized (getSchema().getLock(DbObjectType.TABLE_OR_VIEW)) {
            if (getSchema().findTableOrView(session, data.tableName) != null) {
                if (ifNotExists) {
                    return 0;
                }
                throw DbException.get(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, data.tableName);
            }
            if (asQuery != null) {
                asQuery.prepare();
                if (data.columns.isEmpty()) {
                    generateColumnsFromQuery();
                } else if (data.columns.size() != asQuery.getColumnCount()) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
            }
            if (pkColumns != null) {
                for (Column c : data.columns) {
                    for (IndexColumn idxCol : pkColumns) {
                        if (c.getName().equals(idxCol.columnName)) {
                            c.setNullable(false);
                        }
                    }
                }
            }
            data.id = getObjectId();
            data.create = create;
            data.session = session;
            boolean isSessionTemporary = data.temporary && !data.globalTemporary;
            // if (!isSessionTemporary) {
            // db.lockMeta(session);
            // }
            Table table = getSchema().createTable(data);
            ArrayList<Sequence> sequences = New.arrayList();
            for (Column c : data.columns) {
                if (c.isAutoIncrement()) {
                    int objId = getObjectId();
                    c.convertAutoIncrementToSequence(session, getSchema(), objId, data.temporary);
                }
                Sequence seq = c.getSequence();
                if (seq != null) {
                    sequences.add(seq);
                }
            }
            table.setComment(comment);
            table.setPackageName(packageName);
            if (isSessionTemporary) {
                if (onCommitDrop) {
                    table.setOnCommitDrop(true);
                }
                if (onCommitTruncate) {
                    table.setOnCommitTruncate(true);
                }
                session.addLocalTempTable(table);
            } else {
                // db.lockMeta(session);
                db.addSchemaObject(session, table);
            }
            try {
                for (Column c : data.columns) {
                    c.prepareExpression(session);
                }
                for (Sequence sequence : sequences) {
                    table.addSequence(sequence);
                }
                for (DefineStatement command : constraintCommands) {
                    command.update();
                }
                if (asQuery != null) {
                    Insert insert = new Insert(session);
                    insert.setQuery(asQuery);
                    insert.setTable(table);
                    insert.setInsertFromSelect(true);
                    insert.prepare();
                    insert.update();
                }
            } catch (DbException e) {
                db.checkPowerOff();
                db.removeSchemaObject(session, table);
                throw e;
            }

            if (genCode)
                genCode();
        }
        return 0;
    }

    private void generateColumnsFromQuery() {
        int columnCount = asQuery.getColumnCount();
        ArrayList<Expression> expressions = asQuery.getExpressions();
        for (int i = 0; i < columnCount; i++) {
            Expression expr = expressions.get(i);
            int type = expr.getType();
            String name = expr.getAlias();
            long precision = expr.getPrecision();
            int displaySize = expr.getDisplaySize();
            DataType dt = DataType.getDataType(type);
            if (precision > 0 && //
                    (dt.defaultPrecision == 0 //
                            || (dt.defaultPrecision > precision && dt.defaultPrecision < Byte.MAX_VALUE))) {
                // dont' set precision to MAX_VALUE if this is the default
                precision = dt.defaultPrecision;
            }
            int scale = expr.getScale();
            if (scale > 0 && (dt.defaultScale == 0 || (dt.defaultScale > scale && dt.defaultScale < precision))) {
                scale = dt.defaultScale;
            }
            if (scale > precision) {
                precision = scale;
            }
            Column col = new Column(name, type, precision, scale, displaySize);
            addColumn(col);
        }
    }

    /**
     * Sets the primary key columns, but also check if a primary key
     * with different columns is already defined.
     *
     * @param columns the primary key columns
     * @return true if the same primary key columns where already set
     */
    private boolean setPrimaryKeyColumns(IndexColumn[] columns) {
        if (pkColumns != null) {
            int len = columns.length;
            if (len != pkColumns.length) {
                throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
            }
            for (int i = 0; i < len; i++) {
                if (!columns[i].columnName.equals(pkColumns[i].columnName)) {
                    throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
                }
            }
            return true;
        }
        this.pkColumns = columns;
        return false;
    }

    public void setPersistIndexes(boolean persistIndexes) {
        data.persistIndexes = persistIndexes;
    }

    public void setPersistData(boolean persistData) {
        data.persistData = persistData;
        if (!persistData) {
            data.persistIndexes = false;
        }
    }

    public void setGlobalTemporary(boolean globalTemporary) {
        data.globalTemporary = globalTemporary;
    }

    public void setHidden(boolean isHidden) {
        data.isHidden = isHidden;
    }

    /**
     * This temporary table is dropped on commit.
     */
    public void setOnCommitDrop() {
        this.onCommitDrop = true;
    }

    /**
     * This temporary table is truncated on commit.
     */
    public void setOnCommitTruncate() {
        this.onCommitTruncate = true;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setStorageEngineName(String storageEngineName) {
        data.storageEngineName = storageEngineName;
    }

    public void setStorageEngineParams(Map<String, String> storageEngineParams) {
        if (storageEngineParams instanceof CaseInsensitiveMap) {
            data.storageEngineParams = (CaseInsensitiveMap<String>) storageEngineParams;
        } else {
            data.storageEngineParams = new CaseInsensitiveMap<>();
            data.storageEngineParams.putAll(storageEngineParams);
        }
    }

    @Override
    public boolean isReplicationStatement() {
        return true;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setGenCode(boolean genCode) {
        this.genCode = genCode;
    }

    public void setCodePath(String codePath) {
        this.codePath = codePath;
    }

    public boolean isGenCode() {
        return genCode;
    }

    public String getCodePath() {
        return codePath;
    }

    private void genCode() {
        StringBuilder buff = new StringBuilder();
        StringBuilder methods = new StringBuilder();
        String className = CamelCaseHelper.toCamelFromUnderscore(data.tableName);
        className = Character.toUpperCase(className.charAt(0)) + className.substring(1);

        String qclassName = 'Q' + className;
        StringBuilder qbuff = new StringBuilder();
        StringBuilder qfields = new StringBuilder();
        StringBuilder qinit = new StringBuilder();
        HashSet<String> qimportSet = new HashSet<>();

        String queryTypePackageName = TQ;
        int queryTypePackageNameLength = queryTypePackageName.length();

        buff.append("package ").append(packageName).append(";\r\n");
        buff.append("\r\n");
        buff.append("import org.lealone.orm.Table;\r\n");
        buff.append("\r\n");
        buff.append("/**\r\n");
        buff.append(" * Model bean for table '").append(data.tableName).append("'.\r\n");
        buff.append(" *\r\n");
        buff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\r\n");
        buff.append(" */\r\n");
        buff.append("public class ").append(className).append(" {\r\n");
        buff.append("\r\n");
        buff.append("    public static ").append(className).append(" create(String url) {\r\n");
        buff.append("        Table t = new Table(url, \"").append(data.tableName).append("\");\r\n");
        buff.append("        return new ").append(className).append("(t);\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");
        buff.append("    private Table _t_;\r\n");
        buff.append("\r\n");
        for (Column c : data.columns) {
            int type = c.getType();
            String typeClassName = DataType.getTypeClassName(type);
            if (typeClassName.startsWith("java.lang.")) {
                typeClassName = typeClassName.substring(10);
            }

            String queryTypeClassName = getQueryTypeClassName(type);
            queryTypeClassName = queryTypeClassName.substring(queryTypePackageNameLength + 1);
            qimportSet.add(queryTypeClassName);
            String columnName = CamelCaseHelper.toCamelFromUnderscore(c.getName());
            String columnNameFirstUpperCase = Character.toUpperCase(columnName.charAt(0)) + columnName.substring(1);
            buff.append("    private ").append(typeClassName).append(" ").append(columnName).append(";\r\n");

            qfields.append("    public final ").append(queryTypeClassName).append('<').append(qclassName).append("> ")
                    .append(columnName).append(";\r\n");

            // 例如: this.id = new PLong<>("id", this);
            qinit.append("        this.").append(columnName).append(" = new ").append(queryTypeClassName)
                    .append("<>(\"").append(columnName).append("\", this);\r\n");

            // setter
            methods.append("\r\n");
            methods.append("    public ").append(className).append(" set").append(columnNameFirstUpperCase).append('(')
                    .append(typeClassName);
            methods.append(' ').append(columnName).append(") {\r\n");
            methods.append("        this.").append(columnName).append(" = ").append(columnName).append("; \r\n");
            methods.append("        return this;\r\n");
            methods.append("    }\r\n");

            // getter
            methods.append("\r\n");
            methods.append("    public ").append(typeClassName).append(" get").append(columnNameFirstUpperCase)
                    .append("() { \r\n");
            methods.append("        return ").append(columnName).append("; \r\n");
            methods.append("    }\r\n");
        }
        buff.append("\r\n");
        buff.append("    public ").append(className).append("() {\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");
        buff.append("    private ").append(className).append("(Table t) {\r\n");
        buff.append("        this._t_ = t;\r\n");
        buff.append("    }\r\n");
        buff.append(methods);
        buff.append("\r\n");
        buff.append("    public void save() {\r\n");
        buff.append("        _t_.save(this);\r\n");
        buff.append("    }\r\n");
        buff.append("\r\n");
        buff.append("    public boolean delete() {\r\n");
        buff.append("       return _t_.delete(this);\r\n");
        buff.append("    }\r\n");
        buff.append("}\r\n");
        // System.out.println(buff);

        // 查询器类
        qbuff.append("package ").append(packageName).append(";\r\n\r\n");
        qbuff.append("import org.lealone.orm.Query;\r\n");
        qbuff.append("import org.lealone.orm.Table;\r\n\r\n");
        for (String i : qimportSet) {
            qbuff.append("import ").append(queryTypePackageName).append('.').append(i).append(";\r\n");
        }
        qbuff.append("\r\n");
        qbuff.append("/**\r\n");
        qbuff.append(" * Query bean for model '").append(className).append("'.\r\n");
        qbuff.append(" *\r\n");
        qbuff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\r\n");
        qbuff.append(" */\r\n");
        // 例如: public class QCustomer extends Query<Customer, QCustomer> {
        qbuff.append("public class ").append(qclassName).append(" extends Query<").append(className).append(", ")
                .append(qclassName).append("> {\r\n");
        qbuff.append("\r\n");
        qbuff.append("    public static ").append(qclassName).append(" create(String url) {\r\n");
        qbuff.append("        Table t = new Table(url, \"").append(data.tableName).append("\");\r\n");
        qbuff.append("        return new ").append(qclassName).append("(t);\r\n");
        qbuff.append("    }\r\n");
        qbuff.append("\r\n");
        qbuff.append(qfields);
        qbuff.append("\r\n");
        qbuff.append("    private ").append(qclassName).append("(Table t) {\r\n");
        qbuff.append("        super(t);\r\n");
        qbuff.append("        setRoot(this);\r\n");
        qbuff.append("\r\n");
        qbuff.append(qinit);
        qbuff.append("    }\r\n");
        qbuff.append("}\r\n");
        // System.out.println(qbuff);

        CreateService.writeFile(codePath, packageName, className, buff);
        CreateService.writeFile(codePath, packageName, qclassName, qbuff);
    }

    private static final String TQ = "org.lealone.orm.typequery";

    private static String getQueryTypeClassName(int type) {
        String TQ = CreateTable.TQ + ".";
        switch (type) {
        case Value.BOOLEAN:
            return TQ + "PBoolean";
        case Value.BYTE:
            return TQ + "PByte";
        case Value.SHORT:
            return TQ + "PShort";
        case Value.INT:
            return TQ + "PInteger";
        case Value.LONG:
            return TQ + "PLong";
        case Value.DECIMAL:
            return TQ + "PBigDecimal";
        case Value.TIME:
            return TQ + "PTime";
        case Value.DATE:
            return TQ + "PSqlDate";
        case Value.TIMESTAMP:
            return TQ + "PTimestamp";
        case Value.BYTES:
            // "[B", not "byte[]";
            return byte[].class.getName(); // TODO
        case Value.UUID:
            return TQ + "PUuid";
        case Value.STRING:
        case Value.STRING_IGNORECASE:
        case Value.STRING_FIXED:
            return TQ + "PString";
        case Value.BLOB:
            // "java.sql.Blob";
            throw DbException.throwInternalError("type=" + type); // return java.sql.Blob.class.getName(); // TODO
        case Value.CLOB:
            // "java.sql.Clob";
            throw DbException.throwInternalError("type=" + type); // return java.sql.Clob.class.getName(); // TODO
        case Value.DOUBLE:
            return TQ + "PDouble";
        case Value.FLOAT:
            return TQ + "PFloat";
        case Value.NULL:
            return null;
        case Value.JAVA_OBJECT:
            // "java.lang.Object";
            throw DbException.throwInternalError("type=" + type); // return Object.class.getName(); // TODO
        case Value.UNKNOWN:
            // anything
            return Object.class.getName();
        case Value.ARRAY:
            return TQ + "PArray";
        case Value.RESULT_SET:
            throw DbException.throwInternalError("type=" + type); // return ResultSet.class.getName(); // TODO
        default:
            throw DbException.throwInternalError("type=" + type);
        }
    }
}
