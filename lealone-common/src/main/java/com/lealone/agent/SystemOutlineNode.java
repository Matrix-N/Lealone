/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent;

public enum SystemOutlineNode {
    handleRequest,
    Insert,
    MerSert_prepare,
    Insert_startInternal,
    MerSert_executeLoopUpdate,
    addRow,
    UndoLogRecord_add,
    copyAndInsertLeaf,
    stopCurrentCommand,
    asyncCommit,
    addPendingTransaction,
    UndoLogRecord_commit,
    sendResponse,
    NioEventLoop_write,
    RedoLog_save,
    UndoLogRecord_writeForRedo,
    writeRedoLog,

    executeLocalUpdate,
    addDatabaseObject,
    addSchemaObject,

    Merge,
    Update,
    updateRow,
    tryUpdate,
    Delete,
    removeRow,
    tryRemove,

    Select,
    Select_prepare,
    Select_start,
    Select_execute,
    BTreeCursor,
    BTreeCursor_next,
    Page_getKey,
}
