/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.storage.aose.lob;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.lealone.common.util.DataUtils;
import com.lealone.storage.aose.btree.BTreeMap;

/**
 * A facility to store streams in a map. Streams are split into blocks, which
 * are stored in a map. Very small streams are inlined in the stream id.
 * <p>
 * The key of the map is a long (incremented for each stored block).
 * <p>
 * The format of the binary id is: An empty id represents 0 bytes of data.
 * In-place data is encoded as 0, the size (a variable size int), then the data.
 * A stored block is encoded as 1, the length of the block (a variable size
 * int), then the key (a variable size long). Multiple ids can be concatenated
 * to concatenate the data. If the id is large, it is stored itself, which is
 * encoded as 2, the total length (a variable size long), and the key of the
 * block that contains the id (a variable size long).
 * 
 * @author H2 Group
 * @author zhh
 */
public class LobStreamMap {

    private final BTreeMap<Long, byte[]> map;
    private final AtomicReference<byte[]> nextBuffer = new AtomicReference<>();

    private int minBlockSize = 256;
    private int maxBlockSize = 256 * 1024;

    public LobStreamMap(BTreeMap<Long, byte[]> map) {
        this.map = map;
    }

    public void save() {
        map.save();
    }

    public void gc() {
        map.gc();
    }

    public void clear() {
        map.clear();
    }

    public void close() {
        map.close();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Long lastKey() {
        return map.lastKey();
    }

    public byte[] remove(Long key) {
        return map.remove(key);
    }

    /**
     * Set the minimum block size. The default is 256 bytes.
     *
     * @param minBlockSize the new value
     */
    public void setMinBlockSize(int minBlockSize) {
        this.minBlockSize = minBlockSize;
    }

    public int getMinBlockSize() {
        return minBlockSize;
    }

    /**
     * Set the maximum block size. The default is 256 KB.
     *
     * @param maxBlockSize the new value
     */
    public void setMaxBlockSize(int maxBlockSize) {
        this.maxBlockSize = maxBlockSize;
    }

    public long getMaxBlockSize() {
        return maxBlockSize;
    }

    /**
     * Store the stream, and return the id. The stream is not closed.
     *
     * @param in the stream
     * @return the id (potentially an empty array)
     */
    @SuppressWarnings("resource")
    public byte[] put(InputStream in) throws IOException {
        ByteArrayOutputStream id = new ByteArrayOutputStream();
        int level = 0;
        try {
            while (true) {
                if (put(id, in, level)) {
                    break;
                }
                if (id.size() > maxBlockSize / 2) {
                    id = putIndirectId(id);
                    level++;
                }
            }
        } catch (IOException e) {
            remove(id.toByteArray());
            throw e;
        }
        if (id.size() > minBlockSize * 2) {
            id = putIndirectId(id);
        }
        return id.toByteArray();
    }

    private boolean put(ByteArrayOutputStream id, InputStream in, int level) throws IOException {
        if (level > 0) {
            ByteArrayOutputStream id2 = new ByteArrayOutputStream();
            while (true) {
                boolean eof = put(id2, in, level - 1);
                if (id2.size() > maxBlockSize / 2) {
                    id2 = putIndirectId(id2);
                    id2.writeTo(id);
                    return eof;
                } else if (eof) {
                    id2.writeTo(id);
                    return true;
                }
            }
        }
        byte[] readBuffer = nextBuffer.getAndSet(null);
        if (readBuffer == null) {
            readBuffer = new byte[maxBlockSize];
        }
        byte[] buff = read(in, readBuffer);
        if (buff != readBuffer) {
            // re-use the buffer if the result was shorter
            nextBuffer.set(readBuffer);
        }
        int len = buff.length;
        if (len == 0) {
            return true;
        }
        boolean eof = len < maxBlockSize;
        if (len < minBlockSize) {
            // in-place: 0, len (int), data
            id.write(0);
            DataUtils.writeVarInt(id, len);
            id.write(buff);
        } else {
            // block: 1, len (int), blockId (long)
            id.write(1);
            DataUtils.writeVarInt(id, len);
            DataUtils.writeVarLong(id, writeBlock(buff));
        }
        return eof;
    }

    private ByteArrayOutputStream putIndirectId(ByteArrayOutputStream id) throws IOException {
        byte[] data = id.toByteArray();
        id = new ByteArrayOutputStream();
        // indirect: 2, total len (long), blockId (long)
        id.write(2);
        DataUtils.writeVarLong(id, length(data));
        DataUtils.writeVarLong(id, writeBlock(data));
        return id;
    }

    private long writeBlock(byte[] data) {
        return map.append(data);
    }

    private static byte[] read(InputStream in, byte[] target) throws IOException {
        int copied = 0;
        int remaining = target.length;
        while (remaining > 0) {
            try {
                int len = in.read(target, copied, remaining);
                if (len < 0) {
                    return Arrays.copyOf(target, copied);
                }
                copied += len;
                remaining -= len;
            } catch (RuntimeException e) {
                throw new IOException(e);
            }
        }
        return target;
    }

    /**
     * Get the key of the biggest block, of -1 for inline data.
     * This method is used to garbage collect orphaned blocks.
     *
     * @param id the id
     * @return the key, or -1
     */
    public long getMaxBlockKey(byte[] id) {
        long maxKey = -1;
        ByteBuffer idBuffer = ByteBuffer.wrap(id);
        while (idBuffer.hasRemaining()) {
            switch (idBuffer.get()) {
            case 0:
                // in-place: 0, len (int), data
                int len = DataUtils.readVarInt(idBuffer);
                idBuffer.position(idBuffer.position() + len);
                break;
            case 1:
                // block: 1, len (int), blockId (long)
                DataUtils.readVarInt(idBuffer);
                long k = DataUtils.readVarLong(idBuffer);
                maxKey = Math.max(maxKey, k);
                break;
            case 2:
                // indirect: 2, total len (long), blockId (long)
                DataUtils.readVarLong(idBuffer);
                long k2 = DataUtils.readVarLong(idBuffer);
                maxKey = k2;
                byte[] r = map.get(k2);
                // recurse
                long m = getMaxBlockKey(r);
                if (m >= 0) {
                    maxKey = Math.max(maxKey, m);
                }
                break;
            default:
                throw DataUtils.newIllegalArgumentException("Unsupported id {0}", Arrays.toString(id));
            }
        }
        return maxKey;
    }

    /**
     * Remove all stored blocks for the given id.
     *
     * @param id the id
     */
    public void remove(byte[] id) {
        ByteBuffer idBuffer = ByteBuffer.wrap(id);
        while (idBuffer.hasRemaining()) {
            switch (idBuffer.get()) {
            case 0:
                // in-place: 0, len (int), data
                int len = DataUtils.readVarInt(idBuffer);
                idBuffer.position(idBuffer.position() + len);
                break;
            case 1:
                // block: 1, len (int), blockId (long)
                DataUtils.readVarInt(idBuffer);
                long k = DataUtils.readVarLong(idBuffer);
                map.remove(k);
                break;
            case 2:
                // indirect: 2, total len (long), blockId (long)
                DataUtils.readVarLong(idBuffer);
                long k2 = DataUtils.readVarLong(idBuffer);
                // recurse
                remove(map.get(k2));
                map.remove(k2);
                break;
            default:
                throw DataUtils.newIllegalArgumentException("Unsupported id {0}", Arrays.toString(id));
            }
        }
    }

    /**
     * Convert the id to a human readable string.
     *
     * @param id the stream id
     * @return the string
     */
    public static String toString(byte[] id) {
        StringBuilder buff = new StringBuilder();
        ByteBuffer idBuffer = ByteBuffer.wrap(id);
        long length = 0;
        while (idBuffer.hasRemaining()) {
            long block;
            int len;
            switch (idBuffer.get()) {
            case 0:
                // in-place: 0, len (int), data
                len = DataUtils.readVarInt(idBuffer);
                idBuffer.position(idBuffer.position() + len);
                buff.append("data len=").append(len);
                length += len;
                break;
            case 1:
                // block: 1, len (int), blockId (long)
                len = DataUtils.readVarInt(idBuffer);
                length += len;
                block = DataUtils.readVarLong(idBuffer);
                buff.append("block ").append(block).append(" len=").append(len);
                break;
            case 2:
                // indirect: 2, total len (long), blockId (long)
                len = DataUtils.readVarInt(idBuffer);
                length += DataUtils.readVarLong(idBuffer);
                block = DataUtils.readVarLong(idBuffer);
                buff.append("indirect block ").append(block).append(" len=").append(len);
                break;
            default:
                buff.append("error");
            }
            buff.append(", ");
        }
        buff.append("length=").append(length);
        return buff.toString();
    }

    /**
     * Calculate the number of data bytes for the given id. As the length is
     * encoded in the id, this operation does not cause any reads in the map.
     *
     * @param id the id
     * @return the length
     */
    public long length(byte[] id) {
        ByteBuffer idBuffer = ByteBuffer.wrap(id);
        long length = 0;
        while (idBuffer.hasRemaining()) {
            switch (idBuffer.get()) {
            case 0:
                // in-place: 0, len (int), data
                int len = DataUtils.readVarInt(idBuffer);
                idBuffer.position(idBuffer.position() + len);
                length += len;
                break;
            case 1:
                // block: 1, len (int), blockId (long)
                length += DataUtils.readVarInt(idBuffer);
                DataUtils.readVarLong(idBuffer);
                break;
            case 2:
                // indirect: 2, total len (long), blockId (long)
                length += DataUtils.readVarLong(idBuffer);
                DataUtils.readVarLong(idBuffer);
                break;
            default:
                throw DataUtils.newIllegalArgumentException("Unsupported id {0}", Arrays.toString(id));
            }
        }
        return length;
    }

    /**
     * Open an input stream to read data.
     *
     * @param id the id
     * @return the stream
     */
    public InputStream getInputStream(byte[] id) {
        return new Stream(this, id);
    }

    /**
     * Get the block.
     *
     * @param key the key
     * @return the block
     */
    private byte[] getBlock(long key) {
        byte[] data = map.get(key);
        if (data == null) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_BLOCK_NOT_FOUND,
                    "Block {0} not found", key);
        }
        return data;
    }

    /**
     * A stream backed by a map.
     */
    private static class Stream extends InputStream {

        private final LobStreamMap lobStreamMap;
        private final long length;
        private ByteBuffer idBuffer;
        private ByteArrayInputStream buffer;
        private byte[] oneByteBuffer;
        private long skip;
        private long pos;

        Stream(LobStreamMap lobStreamMap, byte[] id) {
            this.lobStreamMap = lobStreamMap;
            this.length = lobStreamMap.length(id);
            this.idBuffer = ByteBuffer.wrap(id);
        }

        @Override
        public int read() throws IOException {
            byte[] buffer = oneByteBuffer;
            if (buffer == null) {
                buffer = oneByteBuffer = new byte[1];
            }
            int len = read(buffer, 0, 1);
            return len == -1 ? -1 : (buffer[0] & 255);
        }

        @Override
        public long skip(long n) {
            n = Math.min(length - pos, n);
            if (n == 0) {
                return 0;
            }
            if (buffer != null) {
                long s = buffer.skip(n);
                if (s > 0) {
                    n = s;
                } else {
                    buffer = null;
                    skip += n;
                }
            } else {
                skip += n;
            }
            pos += n;
            return n;
        }

        @Override
        public void close() {
            buffer = null;
            idBuffer.position(idBuffer.limit());
            pos = length;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            while (true) {
                if (buffer == null) {
                    try {
                        buffer = nextBuffer();
                    } catch (IllegalStateException e) {
                        String msg = DataUtils.formatMessage(DataUtils.ERROR_BLOCK_NOT_FOUND,
                                "Block not found in id {0}", Arrays.toString(idBuffer.array()));
                        throw new IOException(msg, e);
                    }
                    if (buffer == null) {
                        return -1;
                    }
                }
                int result = buffer.read(b, off, len);
                if (result > 0) {
                    pos += result;
                    return result;
                }
                buffer = null;
            }
        }

        private ByteArrayInputStream nextBuffer() {
            while (idBuffer.hasRemaining()) {
                switch (idBuffer.get()) {
                case 0: {
                    int len = DataUtils.readVarInt(idBuffer);
                    if (skip >= len) {
                        skip -= len;
                        idBuffer.position(idBuffer.position() + len);
                        continue;
                    }
                    int p = (int) (idBuffer.position() + skip);
                    int l = (int) (len - skip);
                    idBuffer.position(p + l);
                    return new ByteArrayInputStream(idBuffer.array(), p, l);
                }
                case 1: {
                    int len = DataUtils.readVarInt(idBuffer);
                    long key = DataUtils.readVarLong(idBuffer);
                    if (skip >= len) {
                        skip -= len;
                        continue;
                    }
                    byte[] data = lobStreamMap.getBlock(key);
                    int s = (int) skip;
                    skip = 0;
                    return new ByteArrayInputStream(data, s, data.length - s);
                }
                case 2: {
                    long len = DataUtils.readVarLong(idBuffer);
                    long key = DataUtils.readVarLong(idBuffer);
                    if (skip >= len) {
                        skip -= len;
                        continue;
                    }
                    byte[] k = lobStreamMap.getBlock(key);
                    ByteBuffer newBuffer = ByteBuffer
                            .allocate(k.length + idBuffer.limit() - idBuffer.position());
                    newBuffer.put(k);
                    newBuffer.put(idBuffer);
                    newBuffer.flip();
                    idBuffer = newBuffer;
                    return nextBuffer();
                }
                default:
                    throw DataUtils.newIllegalArgumentException("Unsupported id {0}",
                            Arrays.toString(idBuffer.array()));
                }
            }
            return null;
        }
    }
}
