package edu.berkeley.cs186.database.index;

import edu.berkeley.cs186.database.common.Buffer;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.LockContext;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.databox.Type;
import edu.berkeley.cs186.database.databox.TypeId;
import edu.berkeley.cs186.database.io.PageException;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.table.RecordId;

import javax.xml.crypto.Data;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * A leaf of a B+ tree. Every leaf in a B+ tree of order d stores between d and
 * 2d (key, record id) pairs and a pointer to its right sibling (i.e. the page
 * number of its right sibling). Moreover, every leaf node is serialized and
 * persisted on a single page; see toBytes and fromBytes for details on how a
 * leaf is serialized. For example, here is an illustration of two order 2
 * leafs connected together:
 *
 *   leaf 1 (stored on some page)          leaf 2 (stored on some other page)
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 *   | k0:r0 | k1:r1 | k2:r2 |       | --> | k3:r3 | k4:r4 |       |       |
 *   +-------+-------+-------+-------+     +-------+-------+-------+-------+
 */
class LeafNode extends BPlusNode {
    // Metadata about the B+ tree that this node belongs to.
    private BPlusTreeMetadata metadata;

    // Buffer manager
    private BufferManager bufferManager;

    // Lock context of the B+ tree
    private LockContext treeContext;

    // The page on which this leaf is serialized.
    private Page page;

    // The keys and record ids of this leaf. `keys` is always sorted in ascending
    // order. The record id at index i corresponds to the key at index i. For
    // example, the keys [a, b, c] and the rids [1, 2, 3] represent the pairing
    // [a:1, b:2, c:3].
    //
    // Note the following subtlety. keys and rids are in-memory caches of the
    // keys and record ids stored on disk. Thus, consider what happens when you
    // create two LeafNode objects that point to the same page:
    //
    //   BPlusTreeMetadata meta = ...;
    //   int pageNum = ...;
    //   LockContext treeContext = new DummyLockContext();
    //
    //   LeafNode leaf0 = LeafNode.fromBytes(meta, bufferManager, treeContext, pageNum);
    //   LeafNode leaf1 = LeafNode.fromBytes(meta, bufferManager, treeContext, pageNum);
    //
    // This scenario looks like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2]     | | | k0:r0 | k1:r1 | k2:r2 |       |
    //   | rids = [r0, r1, r2]     | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Now imagine we perform on operation on leaf0 like leaf0.put(k3, r3). The
    // in-memory values of leaf0 will be updated and they will be synced to disk.
    // But, the in-memory values of leaf1 will not be updated. That will look
    // like this:
    //
    //   HEAP                        | DISK
    //   ===============================================================
    //   leaf0                       | page 42
    //   +-------------------------+ | +-------+-------+-------+-------+
    //   | keys = [k0, k1, k2, k3] | | | k0:r0 | k1:r1 | k2:r2 | k3:r3 |
    //   | rids = [r0, r1, r2, r3] | | +-------+-------+-------+-------+
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //   leaf1                       |
    //   +-------------------------+ |
    //   | keys = [k0, k1, k2]     | |
    //   | rids = [r0, r1, r2]     | |
    //   | pageNum = 42            | |
    //   +-------------------------+ |
    //                               |
    //
    // Make sure your code (or your tests) doesn't use stale in-memory cached
    // values of keys and rids.
    private List<DataBox> keys;
    private List<RecordId> rids;

    // If this leaf is the rightmost leaf, then rightSibling is Optional.empty().
    // Otherwise, rightSibling is Optional.of(n) where n is the page number of
    // this leaf's right sibling.
    private Optional<Long> rightSibling;

    // Constructors ////////////////////////////////////////////////////////////
    /**
     * Construct a brand new leaf node. This constructor will fetch a new pinned
     * page from the provided BufferManager `bufferManager` and persist the node
     * to that page.
     */
    LeafNode(BPlusTreeMetadata metadata, BufferManager bufferManager, List<DataBox> keys,
             List<RecordId> rids, Optional<Long> rightSibling, LockContext treeContext) {
        this(metadata, bufferManager, bufferManager.fetchNewPage(treeContext, metadata.getPartNum()),
             keys, rids,
             rightSibling, treeContext);
    }

    /**
     * Construct a leaf node that is persisted to page `page`.
     */
    private LeafNode(BPlusTreeMetadata metadata, BufferManager bufferManager, Page page,
                     List<DataBox> keys,
                     List<RecordId> rids, Optional<Long> rightSibling, LockContext treeContext) {
        try {
            assert (keys.size() == rids.size());
            assert (keys.size() <= 2 * metadata.getOrder());

            this.metadata = metadata;
            this.bufferManager = bufferManager;
            this.treeContext = treeContext;
            this.page = page;
            this.keys = new ArrayList<>(keys);
            this.rids = new ArrayList<>(rids);
            this.rightSibling = rightSibling;

            sync();
        } finally {
            page.unpin();
        }
    }

    // Core API ////////////////////////////////////////////////////////////////
    // See BPlusNode.get.
    @Override
    public LeafNode get(DataBox key) {
        return this;
    }

    // See BPlusNode.getLeftmostLeaf.
    @Override
    public LeafNode getLeftmostLeaf() {
        return this;
    }

    /**
     * return the maximum number of elements a leaf node can hold
     * @return int, 2d(:= 2 * order of B+-Tree)
     */
    private int maxSize() {
        return metadata.getOrder() * 2;
    }

    // See BPlusNode.put.
    @Override
    public Optional<Pair<DataBox, Long>> put(DataBox key, RecordId rid) {
        // insert `key` into the position where it should be
        int insertIndex = -1;
        for (int i = 0; i < keys.size(); i++) {
            DataBox curr_key = keys.get(i);
            if (key.compareTo(curr_key) < 0) { // key is smaller than curr key! this is the insert pos
                insertIndex = i;
                break;
            } else if (key.compareTo((curr_key)) == 0) {
                throw new BPlusTreeException("Duplicate key is not allowed!");
            }
        }

        if (insertIndex != -1) {
            keys.add(insertIndex, key);
            rids.add(insertIndex, rid);
        } else {
            keys.add(key);
            rids.add(rid);
        }

        // if insertion will cause overflow
        if (keys.size() > maxSize()) {
//            System.out.println(keys);
            List<DataBox> rightNodeKeys = new ArrayList<>();
            List<RecordId> rightNodeRIDs = new ArrayList<>();

            int d = metadata.getOrder();
            rightNodeKeys.addAll(keys.subList(d, 2 * d + 1)); // copy from the second half of keys to rightNodeKeys
            rightNodeRIDs.addAll(rids.subList(d, 2 * d + 1));
            for (int i = 2 * d; i >= d; i--) { // delete the second half from `keys` and `rids`
                keys.remove(i);
                rids.remove(i);
            }
//            System.out.println(rightNodeKeys);
            LeafNode rightNode = new LeafNode(metadata, bufferManager, rightNodeKeys, rightNodeRIDs, rightSibling, treeContext);
            rightNode.sync();
            long rightNodePageNum = rightNode.page.getPageNum();
            this.rightSibling = Optional.of(rightNodePageNum) ; // establish link: currNode -> rightNode

            sync();
//            System.out.printf("leaf node copied split key = %d\n", rightNode.getKeys().get(0).getInt());

            // return the rightNode's smallest key and its page number to the right node
            return Optional.
            of(new Pair<DataBox, Long>(rightNode.getKeys().get(0), rightNodePageNum));
        }
//        System.out.println(this);
        sync();
        // if no overflow
        return Optional.empty();
    }

    // See BPlusNode.bulkLoad.
    @Override
    public Optional<Pair<DataBox, Long>> bulkLoad(Iterator<Pair<DataBox, RecordId>> data,
            float fillFactor) {
        int occupancyLimit = (int) Math.ceil(maxSize() * fillFactor);
        // While there's more data to add and curr node is below occupancy limit, add a record
        while (data.hasNext() && (keys.size() < occupancyLimit)) {
            Pair<DataBox, RecordId> entry = data.next();
            keys.add(entry.getFirst());
            rids.add(entry.getSecond());
        }

        if (data.hasNext()) {
            List<DataBox> rightNodeKeys = new ArrayList<>();
            List<RecordId> rightNodeRIDs = new ArrayList<>();

            Pair<DataBox, RecordId> entry = data.next();
            rightNodeKeys.add(entry.getFirst());
            rightNodeRIDs.add(entry.getSecond());

            // Create a new node as the right sibling of curr node
            LeafNode rightNode = new LeafNode(metadata, bufferManager, rightNodeKeys, rightNodeRIDs, Optional.empty(), treeContext);
            rightNode.sync();
            long rightNodePageNum = rightNode.page.getPageNum();
            this.rightSibling = Optional.of(rightNodePageNum) ; // establish link: currNode -> rightNode
            sync();
            return Optional.
                    of(new Pair<DataBox, Long>(entry.getFirst(), rightNodePageNum));
        }

        // Should only reach here if the data iterator has been exhausted
        sync();
        return Optional.empty();
    }

    // See BPlusNode.remove.
    @Override
    public void remove(DataBox key) {
        int findIndex = keys.indexOf(key);
        if (findIndex != -1) {
            keys.remove(findIndex);
            rids.remove(findIndex);
            sync();
        }
    }

    // Iterators ///////////////////////////////////////////////////////////////
    /** Return the record id associated with `key`. */
    Optional<RecordId> getKey(DataBox key) {
        int index = keys.indexOf(key);
        return index == -1 ? Optional.empty() : Optional.of(rids.get(index));
    }

    /**
     * Returns an iterator over the record ids of this leaf in ascending order of
     * their corresponding keys.
     */
    Iterator<RecordId> scanAll() {
        return rids.iterator();
    }

    /**
     * Returns an iterator over the record ids of this leaf that have a
     * corresponding key greater than or equal to `key`. The record ids are
     * returned in ascending order of their corresponding keys.
     */
    Iterator<RecordId> scanGreaterEqual(DataBox key) {
        int index = InnerNode.numLessThan(key, keys);
        return rids.subList(index, rids.size()).iterator();
    }

    // Helpers /////////////////////////////////////////////////////////////////
    @Override
    public Page getPage() {
        return page;
    }

    public boolean hasRightSibling() {
        return rightSibling.get().longValue() != -1;
    }

    /** Returns the right sibling of this leaf, if it has one. */
    Optional<LeafNode> getRightSibling() {
        if (!rightSibling.isPresent()) {
            return Optional.empty();
        }

        long pageNum = rightSibling.get();
        return Optional.of(LeafNode.fromBytes(metadata, bufferManager, treeContext, pageNum));
    }

    /** Serializes this leaf to its page. */
    private void sync() {
        page.pin();
        try {
            Buffer b = page.getBuffer();
            byte[] newBytes = toBytes();
            byte[] bytes = new byte[newBytes.length];
            b.get(bytes);
            if (!Arrays.equals(bytes, newBytes)) {
                page.getBuffer().put(toBytes()); // m: why not just use b.put?
            }
        } finally {
            page.unpin();
        }
    }

    // Just for testing.
    List<DataBox> getKeys() {
        return keys;
    }

    // Just for testing.
    List<RecordId> getRids() {
        return rids;
    }

    /**
     * Returns the largest number d such that the serialization of a LeafNode
     * with 2d entries will fit on a single page.
     */
    static int maxOrder(short pageSize, Type keySchema) {
        // A leaf node with n entries takes up the following number of bytes:
        //
        //   1 + 8 + 4 + n * (keySize + ridSize)
        //
        // where
        //
        //   - 1 is the number of bytes used to store isLeaf,
        //   - 8 is the number of bytes used to store a sibling pointer,
        //   - 4 is the number of bytes used to store n,
        //   - keySize is the number of bytes used to store a DataBox of type
        //     keySchema, and
        //   - ridSize is the number of bytes of a RecordId.
        //
        // Solving the following equation
        //
        //   n * (keySize + ridSize) + 13 <= pageSizeInBytes
        //
        // we get
        //
        //   n = (pageSizeInBytes - 13) / (keySize + ridSize)
        //
        // The order d is half of n.
        int keySize = keySchema.getSizeInBytes();
        int ridSize = RecordId.getSizeInBytes();
        int n = (pageSize - 13) / (keySize + ridSize);
        return n / 2;
    }

    // Pretty Printing /////////////////////////////////////////////////////////
    @Override
    public String toString() {
        String rightSibString = rightSibling.map(Object::toString).orElse("None");
        return String.format("LeafNode(pageNum=%s, keys=%s, rids=%s, rightSibling=%s)",
                page.getPageNum(), keys, rids, rightSibString);
    }

    @Override
    public String toSexp() {
        List<String> ss = new ArrayList<>();
        for (int i = 0; i < keys.size(); ++i) {
            String key = keys.get(i).toString();
            String rid = rids.get(i).toSexp();
            ss.add(String.format("(%s %s)", key, rid));
        }
        return String.format("(%s)", String.join(" ", ss));
    }

    /**
     * Given a leaf with page number 1 and three (key, rid) pairs (0, (0, 0)),
     * (1, (1, 1)), and (2, (2, 2)), the corresponding dot fragment is:
     *
     *   node1[label = "{0: (0 0)|1: (1 1)|2: (2 2)}"];
     */
    @Override
    public String toDot() {
        List<String> ss = new ArrayList<>();
        for (int i = 0; i < keys.size(); ++i) {
            ss.add(String.format("%s: %s", keys.get(i), rids.get(i).toSexp()));
        }
        long pageNum = getPage().getPageNum();
        String s = String.join("|", ss);
        return String.format("  node%d[label = \"{%s}\"];", pageNum, s);
    }

    // Serialization ///////////////////////////////////////////////////////////
    @Override
    public byte[] toBytes() {
        // When we serialize a leaf node, we write:
        //
        //   a. the literal value 1 (1 byte) which indicates that this node is a
        //      leaf node,
        //   b. the page id (8 bytes) of our right sibling (or -1 if we don't have
        //      a right sibling),
        //   c. the number (4 bytes) of (key, rid) pairs this leaf node contains,
        //      and
        //   d. the (key, rid) pairs themselves.
        //
        // For example, the following bytes:              short    long                  short
        //                                               |databox | 8 Byte page number  | |entry_id|
        //   +----+-------------------------+-------------+----+-------------------------------+
        //   | 01 | 00 00 00 00 00 00 00 04 | 00 00 00 01 | 03 | 00 00 00 00 00 00 00 03 00 01 |
        //   +----+-------------------------+-------------+----+-------------------------------+
        //    \__/ \_______________________/ \___________/ \__________________________________/
        //     a               b                   c                         d
        //
        // represent a leaf node with sibling on page 4 and a single (key, rid)
        // pair with key 3 and record id (3, 1).     （(data1, index1)，

        assert (keys.size() == rids.size());
        assert (keys.size() <= 2 * metadata.getOrder());

        // All sizes are in bytes.
        int isLeafSize = 1; // a, 1B
        int siblingSize = Long.BYTES; // b, 8B
        int lenSize = Integer.BYTES; // c, 4B
        int keySize = metadata.getKeySchema().getSizeInBytes(); // size of key type
        int ridSize = RecordId.getSizeInBytes(); // record id size
        int entriesSize = (keySize + ridSize) * keys.size(); // the total size of entries on curr leaf node
        int size = isLeafSize + siblingSize + lenSize + entriesSize;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 1); // put a
        buf.putLong(rightSibling.orElse(-1L)); // put b
        buf.putInt(keys.size()); // put c
        for (int i = 0; i < keys.size(); ++i) { // put for each entry
            buf.put(keys.get(i).toBytes());
            buf.put(rids.get(i).toBytes());
        }
        return buf.array();
    }

    /**
     * Loads a leaf node from page `pageNum`.
     */
    public static LeafNode fromBytes(BPlusTreeMetadata metadata, BufferManager bufferManager,
                                     LockContext treeContext, long pageNum) {
        // TODO(proj2): implement
        // Note: LeafNode has two constructors. To implement fromBytes be sure to
        // use the constructor that reuses an existing page instead of fetching a
        // brand new one.

        // find the page to read using `pageNum`
        Page page = bufferManager.fetchPage(treeContext, pageNum);
        Buffer pageBuf = page.getBuffer();
        // rightSibling = buffArr[]
        if (pageBuf.getChar() == 0) { //is leaf node flag, 1 Byte
            throw new PageException("Current page is not a serialized leaf node.");
        }

        Optional<Long> rightSibling = Optional.of(pageBuf.getLong());
        int entrySize = pageBuf.getInt();

        List<DataBox> keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();

        Type keyType = metadata.getKeySchema();
        for (int i = 0; i < entrySize; i++) {
            keys.add(DataBox.fromBytes(pageBuf, keyType));
            rids.add(RecordId.fromBytes(pageBuf));
        }

        return new LeafNode(metadata, bufferManager, page, keys, rids, rightSibling, treeContext);
    }

    // Builtins ////////////////////////////////////////////////////////////////
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LeafNode)) {
            return false;
        }
        LeafNode n = (LeafNode) o;
        return page.getPageNum() == n.page.getPageNum() &&
               keys.equals(n.keys) &&
               rids.equals(n.rids) &&
               rightSibling.equals(n.rightSibling);
    }

    @Override
    public int hashCode() {
        return Objects.hash(page.getPageNum(), keys, rids, rightSibling);
    }
}
