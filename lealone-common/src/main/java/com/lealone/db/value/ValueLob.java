/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.value;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.DataUtils;
import com.lealone.common.util.IOUtils;
import com.lealone.common.util.MathUtils;
import com.lealone.common.util.StringUtils;
import com.lealone.common.util.Utils;
import com.lealone.db.Constants;
import com.lealone.db.DataHandler;
import com.lealone.db.SysProperties;
import com.lealone.storage.fs.FileStorage;
import com.lealone.storage.fs.FileStorageInputStream;
import com.lealone.storage.fs.FileStorageOutputStream;
import com.lealone.storage.fs.FileUtils;
import com.lealone.storage.lob.LobStorage;

/**
 * A implementation of the BLOB and CLOB data types.
 *
 * Small objects are kept in memory and stored in the record.
 * Large objects are either stored in the database, or in temporary files.
 */
public class ValueLob extends Value {

    private final int type;
    private final long lobId;
    private final byte[] hmac;
    private final byte[] small;
    private DataHandler handler;

    /**
     * For a BLOB, precision is length in bytes.
     * For a CLOB, precision is length in chars.
     */
    private final long precision;

    private final String fileName;
    private final FileStorage tempFile;
    private int tableId;
    private int hash;

    private boolean useTableLobStorage;

    private ValueLob(int type, DataHandler handler, int tableId, long lobId, byte[] hmac,
            long precision) {
        this.type = type;
        this.handler = handler;
        this.tableId = tableId;
        this.lobId = lobId;
        this.hmac = hmac;
        this.precision = precision;
        this.small = null;
        this.fileName = null;
        this.tempFile = null;
    }

    private ValueLob(int type, byte[] small, long precision) {
        this.type = type;
        this.small = small;
        this.precision = precision;
        this.lobId = 0;
        this.hmac = null;
        this.handler = null;
        this.fileName = null;
        this.tempFile = null;
    }

    /**
     * Create a CLOB in a temporary file.
     */
    private ValueLob(DataHandler handler, Reader in, long remaining) throws IOException {
        this.type = Value.CLOB;
        this.handler = handler;
        this.small = null;
        this.lobId = 0;
        this.hmac = null;
        this.fileName = createTempLobFileName(handler);
        this.tempFile = handler.openFile(fileName, "rw", false);
        this.tempFile.autoDelete();
        FileStorageOutputStream out = new FileStorageOutputStream(tempFile, null, null);
        long tmpPrecision = 0;
        try {
            char[] buff = new char[Constants.IO_BUFFER_SIZE];
            while (true) {
                int len = getBufferSize(handler, false, remaining);
                len = IOUtils.readFully(in, buff, len);
                if (len <= 0) {
                    break;
                }
                byte[] data = new String(buff, 0, len).getBytes(StandardCharsets.UTF_8);
                out.write(data);
                tmpPrecision += len;
            }
        } finally {
            out.close();
        }
        this.precision = tmpPrecision;
    }

    /**
     * Create a BLOB in a temporary file.
     */
    private ValueLob(DataHandler handler, byte[] buff, int len, InputStream in, long remaining)
            throws IOException {
        this.type = Value.BLOB;
        this.handler = handler;
        this.small = null;
        this.lobId = 0;
        this.hmac = null;
        this.fileName = createTempLobFileName(handler);
        this.tempFile = handler.openFile(fileName, "rw", false);
        this.tempFile.autoDelete();
        FileStorageOutputStream out = new FileStorageOutputStream(tempFile, null, null);
        long tmpPrecision = 0;
        boolean compress = handler.getLobCompressionAlgorithm(Value.BLOB) != null;
        try {
            while (true) {
                tmpPrecision += len;
                out.write(buff, 0, len);
                remaining -= len;
                if (remaining <= 0) {
                    break;
                }
                len = getBufferSize(handler, compress, remaining);
                len = IOUtils.readFully(in, buff, len);
                if (len <= 0) {
                    break;
                }
            }
        } finally {
            out.close();
        }
        this.precision = tmpPrecision;
    }

    private static String createTempLobFileName(DataHandler handler) throws IOException {
        String path = handler.getDatabasePath();
        if (path.isEmpty()) {
            path = SysProperties.PREFIX_TEMP_FILE;
        }
        return FileUtils.createTempFile(path, Constants.SUFFIX_TEMP_FILE, true, true);
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public long getPrecision() {
        return precision;
    }

    @Override
    public int getDisplaySize() {
        return MathUtils.convertLongToInt(getPrecision());
    }

    public void setHandler(DataHandler handler) {
        this.handler = handler;
    }

    public DataHandler getHandler() {
        return handler;
    }

    public boolean isUseTableLobStorage() {
        return useTableLobStorage;
    }

    public void setUseTableLobStorage(boolean useTableLobStorage) {
        this.useTableLobStorage = useTableLobStorage;
    }

    /**
     * Get the current table id of this lob.
     *
     * @return the table id
     */
    public int getTableId() {
        return tableId;
    }

    /**
     * Get the data if this a small lob value.
     *
     * @return the data
     */
    public byte[] getSmall() {
        return small;
    }

    public long getLobId() {
        return lobId;
    }

    /**
     * Convert a lob to another data type. The data is fully read in memory
     * except when converting to BLOB or CLOB.
     *
     * @param t the new type
     * @return the converted value
     */
    @Override
    public Value convertTo(int t) {
        if (t == type) {
            return this;
        } else if (t == Value.CLOB) {
            if (handler != null) {
                Value copy = handler.getLobStorage().createClob(getReader(), -1);
                return copy;
            } else if (small != null) {
                return ValueLob.createSmallLob(t, small);
            }
        } else if (t == Value.BLOB) {
            if (handler != null) {
                Value copy = handler.getLobStorage().createBlob(getInputStream(), -1);
                return copy;
            } else if (small != null) {
                return ValueLob.createSmallLob(t, small);
            }
        }
        return super.convertTo(t);
    }

    @Override
    public Value convertPrecision(long precision, boolean force) {
        if (this.precision <= precision) {
            return this;
        }
        ValueLob lob;
        if (type == CLOB) {
            if (handler == null) {
                try {
                    int p = MathUtils.convertLongToInt(precision);
                    String s = IOUtils.readStringAndClose(getReader(), p);
                    byte[] data = s.getBytes(Constants.UTF8);
                    lob = ValueLob.createSmallLob(type, data, s.length());
                } catch (IOException e) {
                    throw DbException.convertIOException(e, null);
                }
            } else {
                lob = ValueLob.createTempClob(getReader(), precision, handler);
            }
        } else {
            if (handler == null) {
                try {
                    int p = MathUtils.convertLongToInt(precision);
                    byte[] data = IOUtils.readBytesAndClose(getInputStream(), p);
                    lob = ValueLob.createSmallLob(type, data, data.length);
                } catch (IOException e) {
                    throw DbException.convertIOException(e, null);
                }
            } else {
                lob = ValueLob.createTempBlob(getInputStream(), precision, handler);
            }
        }
        return lob;
    }

    @Override
    public void close() {
        if (fileName != null) {
            if (tempFile != null) {
                tempFile.stopAutoDelete();
            }
            synchronized (this) {
                FileUtils.delete(fileName);
            }
        }
        if (handler != null) {
            handler.getLobStorage().removeLob(this);
        }
    }

    public boolean isStored() {
        return small == null && fileName == null;
    }

    /**
     * Check if this value is linked to a specific table. For values that are
     * kept fully in memory, this method returns false.
     *
     * @return true if it is
     */
    public boolean isLinked() {
        return tableId != LobStorage.TABLE_ID_SESSION_VARIABLE && small == null;
    }

    /**
     * Mark any underlying resource as 'not linked to any table'. For values
     * that are kept fully in memory this method has no effect.
     */
    public void unlink(DataHandler dh) {
        if (small == null && tableId != LobStorage.TABLE_ID_SESSION_VARIABLE) {
            dh.getLobStorage().setTable(this, LobStorage.TABLE_ID_SESSION_VARIABLE);
            tableId = LobStorage.TABLE_ID_SESSION_VARIABLE;
        }
    }

    /**
     * Link a large value to a given table. For values that are kept fully in
     * memory this method has no effect.
     *
     * @return the new value or itself
     */
    public ValueLob link(DataHandler dh, int tabId) {
        if (small == null) {
            if (tableId == LobStorage.TABLE_TEMP) {
                dh.getLobStorage().setTable(this, tabId);
                tableId = tabId;
            }
        } else if (small.length > dh.getMaxLengthInplaceLob()) {
            ValueLob v;
            if (type == Value.BLOB) {
                v = dh.getLobStorage().createBlob(getInputStream(), getPrecision());
            } else {
                v = dh.getLobStorage().createClob(getReader(), getPrecision());
            }
            return v.link(dh, tabId);
        }
        return this;
    }

    @Override
    public String getString() {
        int len = precision > Integer.MAX_VALUE || precision == 0 ? Integer.MAX_VALUE : (int) precision;
        try {
            if (type == Value.CLOB) {
                if (small != null) {
                    return new String(small, Constants.UTF8);
                }
                return IOUtils.readStringAndClose(getReader(), len);
            }
            byte[] buff;
            if (small != null) {
                buff = small;
            } else {
                buff = IOUtils.readBytesAndClose(getInputStream(), len);
            }
            return new String(buff, Constants.UTF8);
        } catch (IOException e) {
            throw DbException.convertIOException(e, toString());
        }
    }

    @Override
    public byte[] getBytes() {
        if (type == CLOB) {
            // convert hex to string
            return super.getBytes();
        }
        byte[] data = getBytesNoCopy();
        return Utils.cloneByteArray(data);
    }

    @Override
    public byte[] getBytesNoCopy() {
        if (type == CLOB) {
            // convert hex to string
            return super.getBytesNoCopy();
        }
        if (small != null) {
            return small;
        }
        try {
            return IOUtils.readBytesAndClose(getInputStream(), Integer.MAX_VALUE);
        } catch (IOException e) {
            throw DbException.convertIOException(e, toString());
        }
    }

    @Override
    public int hashCode() {
        if (hash == 0) {
            if (precision > 4096) {
                // TODO: should calculate the hash code when saving, and store
                // it in the database file
                return (int) (precision ^ (precision >>> 32));
            }
            if (type == CLOB) {
                hash = getString().hashCode();
            } else {
                hash = Utils.getByteArrayHash(getBytes());
            }
        }
        return hash;
    }

    @Override
    protected int compareSecure(Value v, CompareMode mode) {
        if (v instanceof ValueLob) {
            ValueLob v2 = (ValueLob) v;
            if (v == this) {
                return 0;
            }
            if (lobId == v2.lobId && small == null && v2.small == null) {
                return 0;
            }
        }
        if (type == Value.CLOB) {
            return Integer.signum(getString().compareTo(v.getString()));
        }
        byte[] v2 = v.getBytesNoCopy();
        return Utils.compareNotNullSigned(getBytes(), v2);
    }

    @Override
    public Object getObject() {
        if (type == Value.CLOB) {
            return getReader();
        }
        return getInputStream();
    }

    @Override
    public Reader getReader() {
        return IOUtils.getBufferedReader(getInputStream());
    }

    @Override
    public InputStream getInputStream() {
        if (small != null) {
            return new ByteArrayInputStream(small);
        } else if (fileName != null) {
            FileStorage fileStorage = handler.openFile(fileName, "r", true);
            boolean alwaysClose = SysProperties.LOB_CLOSE_BETWEEN_READS;
            return new BufferedInputStream(
                    new FileStorageInputStream(fileStorage, handler, false, alwaysClose),
                    Constants.IO_BUFFER_SIZE);
        }
        long byteCount = (type == Value.BLOB) ? precision : -1;
        try {
            return handler.getLobStorage().getInputStream(this, hmac, byteCount);
        } catch (IOException e) {
            throw DbException.convertIOException(e, toString());
        }
    }

    @Override
    public void set(PreparedStatement prep, int parameterIndex) throws SQLException {
        long p = getPrecision();
        if (p > Integer.MAX_VALUE || p <= 0) {
            p = -1;
        }
        if (type == Value.BLOB) {
            prep.setBinaryStream(parameterIndex, getInputStream(), (int) p);
        } else {
            prep.setCharacterStream(parameterIndex, getReader(), (int) p);
        }
    }

    @Override
    public String getSQL() {
        String s;
        if (type == Value.CLOB) {
            s = getString();
            return StringUtils.quoteStringSQL(s);
        }
        byte[] buff = getBytes();
        s = StringUtils.convertBytesToHex(buff);
        return "X'" + s + "'";
    }

    @Override
    public String getTraceSQL() {
        if (small != null && getPrecision() <= SysProperties.MAX_TRACE_DATA_LENGTH) {
            return getSQL();
        }
        StringBuilder buff = new StringBuilder();
        if (type == Value.CLOB) {
            buff.append("SPACE(").append(getPrecision());
        } else {
            buff.append("CAST(REPEAT('00', ").append(getPrecision()).append(") AS BINARY");
        }
        buff.append(" /* table: ").append(tableId).append(" id: ").append(lobId).append(" */)");
        return buff.toString();
    }

    @Override
    public int getMemory() {
        if (small != null) {
            return small.length + 104;
        }
        return 140;
    }

    @Override
    public String toString() {
        return "lob: " + fileName + " table: " + tableId + " id: " + lobId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValueLob && compareSecure((Value) other, null) == 0;
    }

    /**
     * Create a temporary CLOB value from a stream.
     *
     * @param in the reader
     * @param length the number of characters to read, or -1 for no limit
     * @param handler the data handler
     * @return the lob value
     */
    public static ValueLob createTempClob(Reader in, long length, DataHandler handler) {
        BufferedReader reader;
        if (in instanceof BufferedReader) {
            reader = (BufferedReader) in;
        } else {
            reader = new BufferedReader(in, Constants.IO_BUFFER_SIZE);
        }
        try {
            boolean compress = handler.getLobCompressionAlgorithm(Value.CLOB) != null;
            long remaining = Long.MAX_VALUE;
            if (length >= 0 && length < remaining) {
                remaining = length;
            }
            int len = getBufferSize(handler, compress, remaining);
            char[] buff;
            if (len >= Integer.MAX_VALUE) {
                String data = IOUtils.readStringAndClose(reader, -1);
                buff = data.toCharArray();
                len = buff.length;
            } else {
                buff = new char[len];
                reader.mark(len);
                len = IOUtils.readFully(reader, buff);
            }
            if (len <= handler.getMaxLengthInplaceLob()) {
                byte[] small = new String(buff, 0, len).getBytes(Constants.UTF8);
                return ValueLob.createSmallLob(Value.CLOB, small, len);
            }
            reader.reset();
            return new ValueLob(handler, reader, remaining);
        } catch (IOException e) {
            throw DbException.convertIOException(e, null);
        }
    }

    /**
     * Create a temporary BLOB value from a stream.
     *
     * @param in the input stream
     * @param length the number of characters to read, or -1 for no limit
     * @param handler the data handler
     * @return the lob value
     */
    public static ValueLob createTempBlob(InputStream in, long length, DataHandler handler) {
        try {
            long remaining = Long.MAX_VALUE;
            boolean compress = handler.getLobCompressionAlgorithm(Value.BLOB) != null;
            if (length >= 0 && length < remaining) {
                remaining = length;
            }
            int len = getBufferSize(handler, compress, remaining);
            byte[] buff;
            if (len >= Integer.MAX_VALUE) {
                buff = IOUtils.readBytesAndClose(in, -1);
                len = buff.length;
            } else {
                buff = DataUtils.newBytes(len);
                len = IOUtils.readFully(in, buff);
            }
            if (len <= handler.getMaxLengthInplaceLob()) {
                byte[] small = DataUtils.newBytes(len);
                System.arraycopy(buff, 0, small, 0, len);
                return ValueLob.createSmallLob(Value.BLOB, small, small.length);
            }
            return new ValueLob(handler, buff, len, in, remaining);
        } catch (IOException e) {
            throw DbException.convertIOException(e, null);
        }
    }

    private static int getBufferSize(DataHandler handler, boolean compress, long remaining) {
        if (remaining < 0 || remaining > Integer.MAX_VALUE) {
            remaining = Integer.MAX_VALUE;
        }
        int inplace = handler.getMaxLengthInplaceLob();
        long m = compress ? Constants.IO_BUFFER_SIZE_COMPRESS : Constants.IO_BUFFER_SIZE;
        if (m < remaining && m <= inplace) {
            // using "1L" to force long arithmetic because
            // inplace could be Integer.MAX_VALUE
            m = Math.min(remaining, inplace + 1L);
            // the buffer size must be bigger than the inplace lob, otherwise we
            // can't know if it must be stored in-place or not
            m = MathUtils.roundUpLong(m, Constants.IO_BUFFER_SIZE);
        }
        m = Math.min(remaining, m);
        m = MathUtils.convertLongToInt(m);
        if (m < 0) {
            m = Integer.MAX_VALUE;
        }
        return (int) m;
    }

    /**
     * Create a LOB object that fits in memory.
     *
     * @param type the type (Value.BLOB or CLOB)
     * @param small the byte array
     * @return the LOB
     */
    public static ValueLob createSmallLob(int type, byte[] small) {
        int precision;
        if (type == Value.CLOB) {
            precision = new String(small, Constants.UTF8).length();
        } else {
            precision = small.length;
        }
        return createSmallLob(type, small, precision);
    }

    /**
     * Create a LOB object that fits in memory.
     *
     * @param type the type (Value.BLOB or CLOB)
     * @param small the byte array
     * @param precision the precision
     * @return the LOB
     */
    public static ValueLob createSmallLob(int type, byte[] small, long precision) {
        return new ValueLob(type, small, precision);
    }

    /**
     * Create a LOB value.
     *
     * @param type the type
     * @param handler the data handler
     * @param tableId the table id
     * @param id the lob id
     * @param hmac the message authentication code
     * @param precision the precision (number of bytes / characters)
     * @return the value
     */
    public static ValueLob create(int type, DataHandler handler, int tableId, long id, byte[] hmac,
            long precision) {
        return new ValueLob(type, handler, tableId, id, hmac, precision);
    }
}
