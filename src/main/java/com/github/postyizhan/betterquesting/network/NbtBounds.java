package com.github.postyizhan.betterquesting.network;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagByteArray;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagFloat;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagIntArray;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagShort;
import net.minecraft.NBTTagString;

public final class NbtBounds {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;

    private NbtBounds() {
    }

    public static Result validate(NBTTagCompound root, NbtLimits limits) {
        MutableTotals totals = new MutableTotals();
        if (limits == null || !limits.isValid()) {
            return Result.rejected(RejectionReason.INVALID_LIMITS, totals.snapshot());
        }
        if (root == null) {
            return Result.rejected(RejectionReason.NULL_ROOT, totals.snapshot());
        }

        try {
            return new Validator(limits, totals).validate(root);
        } catch (RuntimeException failure) {
            return Result.rejected(RejectionReason.TRAVERSAL_FAILURE, totals.snapshot());
        }
    }

    public enum RejectionReason {
        NONE,
        INVALID_LIMITS,
        NULL_ROOT,
        NULL_CHILD,
        NULL_NAME,
        NULL_VALUE,
        CYCLE,
        TAG_END,
        UNKNOWN_TAG_ID,
        INVALID_TAG_TYPE,
        MAX_DEPTH,
        MAX_TOTAL_NODES,
        MAX_COMPOUND_ENTRIES,
        MAX_LIST_ITEMS,
        MAX_STRING_LENGTH,
        MAX_BYTE_ARRAY_LENGTH,
        MAX_INT_ARRAY_LENGTH,
        MAX_SERIALIZED_BYTES,
        TRAVERSAL_FAILURE
    }

    public static final class Result {
        private final boolean accepted;
        private final RejectionReason rejectionReason;
        private final Totals totals;

        private Result(boolean accepted, RejectionReason rejectionReason, Totals totals) {
            this.accepted = accepted;
            this.rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
            this.totals = Objects.requireNonNull(totals, "totals");
        }

        private static Result accepted(Totals totals) {
            return new Result(true, RejectionReason.NONE, totals);
        }

        private static Result rejected(RejectionReason reason, Totals totals) {
            return new Result(false, reason, totals);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public boolean accepted() {
            return accepted;
        }

        public RejectionReason rejectionReason() {
            return rejectionReason;
        }

        public RejectionReason getRejectionReason() {
            return rejectionReason;
        }

        public Totals totals() {
            return totals;
        }

        public Totals getTotals() {
            return totals;
        }

        public long totalNodes() {
            return totals.totalNodes();
        }

        public int maximumDepth() {
            return totals.maximumDepth();
        }

        public long compoundEntries() {
            return totals.compoundEntries();
        }

        public long listItems() {
            return totals.listItems();
        }

        public long stringCharacters() {
            return totals.stringCharacters();
        }

        public long byteArrayElements() {
            return totals.byteArrayElements();
        }

        public long intArrayElements() {
            return totals.intArrayElements();
        }

        public long serializedBytes() {
            return totals.serializedBytes();
        }

        @Override
        public String toString() {
            return "NbtBounds.Result[accepted=" + accepted
                + ", rejectionReason=" + rejectionReason
                + ", totals=" + totals + ']';
        }
    }

    public static final class Totals {
        private final long totalNodes;
        private final int maximumDepth;
        private final long compoundEntries;
        private final long listItems;
        private final long stringCharacters;
        private final long byteArrayElements;
        private final long intArrayElements;
        private final long serializedBytes;

        private Totals(
            long totalNodes,
            int maximumDepth,
            long compoundEntries,
            long listItems,
            long stringCharacters,
            long byteArrayElements,
            long intArrayElements,
            long serializedBytes
        ) {
            this.totalNodes = totalNodes;
            this.maximumDepth = maximumDepth;
            this.compoundEntries = compoundEntries;
            this.listItems = listItems;
            this.stringCharacters = stringCharacters;
            this.byteArrayElements = byteArrayElements;
            this.intArrayElements = intArrayElements;
            this.serializedBytes = serializedBytes;
        }

        public long totalNodes() {
            return totalNodes;
        }

        public long getTotalNodes() {
            return totalNodes;
        }

        public int maximumDepth() {
            return maximumDepth;
        }

        public int getMaximumDepth() {
            return maximumDepth;
        }

        public long compoundEntries() {
            return compoundEntries;
        }

        public long getCompoundEntries() {
            return compoundEntries;
        }

        public long listItems() {
            return listItems;
        }

        public long getListItems() {
            return listItems;
        }

        public long stringCharacters() {
            return stringCharacters;
        }

        public long getStringCharacters() {
            return stringCharacters;
        }

        public long byteArrayElements() {
            return byteArrayElements;
        }

        public long getByteArrayElements() {
            return byteArrayElements;
        }

        public long intArrayElements() {
            return intArrayElements;
        }

        public long getIntArrayElements() {
            return intArrayElements;
        }

        public long serializedBytes() {
            return serializedBytes;
        }

        public long getSerializedBytes() {
            return serializedBytes;
        }

        @Override
        public String toString() {
            return "NbtBounds.Totals[totalNodes=" + totalNodes
                + ", maximumDepth=" + maximumDepth
                + ", compoundEntries=" + compoundEntries
                + ", listItems=" + listItems
                + ", stringCharacters=" + stringCharacters
                + ", byteArrayElements=" + byteArrayElements
                + ", intArrayElements=" + intArrayElements
                + ", serializedBytes=" + serializedBytes + ']';
        }
    }

    private static final class Validator {
        private final NbtLimits limits;
        private final MutableTotals totals;
        private final Deque<Frame> stack = new ArrayDeque<Frame>();
        private final Map<NBTBase, Boolean> activePath = new IdentityHashMap<NBTBase, Boolean>();

        private Validator(NbtLimits limits, MutableTotals totals) {
            this.limits = limits;
            this.totals = totals;
        }

        private Result validate(NBTTagCompound root) {
            stack.push(new Frame(root, 1, true));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.entered) {
                    RejectionReason rejection = enter(frame);
                    if (rejection != RejectionReason.NONE) {
                        return rejected(rejection);
                    }
                    frame.entered = true;
                    activePath.put(frame.tag, Boolean.TRUE);
                    if (!frame.hasChildren()) {
                        finish(frame);
                    }
                    continue;
                }

                Child child = frame.nextChild();
                if (child.done) {
                    finish(frame);
                    continue;
                }
                if (child.tag == null) {
                    return rejected(RejectionReason.NULL_CHILD);
                }
                if (activePath.containsKey(child.tag)) {
                    return rejected(RejectionReason.CYCLE);
                }
                if (frame.depth == Integer.MAX_VALUE) {
                    return rejected(RejectionReason.MAX_DEPTH);
                }
                stack.push(new Frame(child.tag, frame.depth + 1, child.named));
            }
            return Result.accepted(totals.snapshot());
        }

        private RejectionReason enter(Frame frame) {
            totals.totalNodes = saturatedAdd(totals.totalNodes, 1L);
            if (frame.depth > totals.maximumDepth) {
                totals.maximumDepth = frame.depth;
            }
            if (totals.totalNodes > limits.maxTotalNodes()) {
                return RejectionReason.MAX_TOTAL_NODES;
            }
            if (frame.depth > limits.maxDepth()) {
                return RejectionReason.MAX_DEPTH;
            }

            int tagId = frame.tag.getId() & 0xff;
            if (tagId == TAG_END) {
                return RejectionReason.TAG_END;
            }
            if (tagId > TAG_INT_ARRAY) {
                return RejectionReason.UNKNOWN_TAG_ID;
            }
            if (!hasExpectedType(frame.tag, tagId)) {
                return RejectionReason.INVALID_TAG_TYPE;
            }

            if (frame.named) {
                String name = frame.tag.getName();
                if (name == null) {
                    return RejectionReason.NULL_NAME;
                }
                totals.stringCharacters = saturatedAdd(totals.stringCharacters, name.length());
                if (name.length() > limits.maxStringLength()) {
                    return RejectionReason.MAX_STRING_LENGTH;
                }
                if (!addSerializedBytes(3L + modifiedUtfLength(name))) {
                    return RejectionReason.MAX_SERIALIZED_BYTES;
                }
            }

            switch (tagId) {
                case TAG_BYTE:
                    return addPayloadBytes(1L);
                case TAG_SHORT:
                    return addPayloadBytes(2L);
                case TAG_INT:
                case TAG_FLOAT:
                    return addPayloadBytes(4L);
                case TAG_LONG:
                case TAG_DOUBLE:
                    return addPayloadBytes(8L);
                case TAG_BYTE_ARRAY:
                    return enterByteArray((NBTTagByteArray) frame.tag);
                case TAG_STRING:
                    return enterString((NBTTagString) frame.tag);
                case TAG_LIST:
                    return enterList(frame, (NBTTagList) frame.tag);
                case TAG_COMPOUND:
                    return enterCompound(frame, (NBTTagCompound) frame.tag);
                case TAG_INT_ARRAY:
                    return enterIntArray((NBTTagIntArray) frame.tag);
                default:
                    return RejectionReason.UNKNOWN_TAG_ID;
            }
        }

        private RejectionReason enterByteArray(NBTTagByteArray tag) {
            byte[] values = tag.byteArray;
            if (values == null) {
                return RejectionReason.NULL_VALUE;
            }
            totals.byteArrayElements = saturatedAdd(totals.byteArrayElements, values.length);
            if (values.length > limits.maxByteArrayLength()) {
                return RejectionReason.MAX_BYTE_ARRAY_LENGTH;
            }
            return addPayloadBytes(4L + values.length);
        }

        private RejectionReason enterString(NBTTagString tag) {
            String value = tag.data;
            if (value == null) {
                return RejectionReason.NULL_VALUE;
            }
            totals.stringCharacters = saturatedAdd(totals.stringCharacters, value.length());
            if (value.length() > limits.maxStringLength()) {
                return RejectionReason.MAX_STRING_LENGTH;
            }
            return addPayloadBytes(2L + modifiedUtfLength(value));
        }

        private RejectionReason enterList(Frame frame, NBTTagList tag) {
            int itemCount = tag.tagCount();
            if (itemCount < 0) {
                return RejectionReason.TRAVERSAL_FAILURE;
            }
            totals.listItems = saturatedAdd(totals.listItems, itemCount);
            if (itemCount > limits.maxListItems()) {
                return RejectionReason.MAX_LIST_ITEMS;
            }
            RejectionReason budget = addPayloadBytes(5L);
            if (budget != RejectionReason.NONE) {
                return budget;
            }
            frame.list = tag;
            frame.expectedChildren = itemCount;
            return RejectionReason.NONE;
        }

        private RejectionReason enterCompound(Frame frame, NBTTagCompound tag) {
            Collection<?> children = tag.getTags();
            if (children == null) {
                return RejectionReason.TRAVERSAL_FAILURE;
            }
            int entryCount = children.size();
            if (entryCount < 0) {
                return RejectionReason.TRAVERSAL_FAILURE;
            }
            totals.compoundEntries = saturatedAdd(totals.compoundEntries, entryCount);
            if (entryCount > limits.maxCompoundEntries()) {
                return RejectionReason.MAX_COMPOUND_ENTRIES;
            }
            RejectionReason budget = addPayloadBytes(1L);
            if (budget != RejectionReason.NONE) {
                return budget;
            }
            frame.compoundChildren = children;
            frame.compoundIterator = children.iterator();
            frame.expectedChildren = entryCount;
            return RejectionReason.NONE;
        }

        private RejectionReason enterIntArray(NBTTagIntArray tag) {
            int[] values = tag.intArray;
            if (values == null) {
                return RejectionReason.NULL_VALUE;
            }
            totals.intArrayElements = saturatedAdd(totals.intArrayElements, values.length);
            if (values.length > limits.maxIntArrayLength()) {
                return RejectionReason.MAX_INT_ARRAY_LENGTH;
            }
            return addPayloadBytes(4L + 4L * values.length);
        }

        private RejectionReason addPayloadBytes(long bytes) {
            return addSerializedBytes(bytes) ? RejectionReason.NONE : RejectionReason.MAX_SERIALIZED_BYTES;
        }

        private boolean addSerializedBytes(long bytes) {
            if (bytes < 0L || totals.serializedBytes > Long.MAX_VALUE - bytes) {
                totals.serializedBytes = Long.MAX_VALUE;
                return false;
            }
            totals.serializedBytes += bytes;
            return totals.serializedBytes <= limits.maxSerializedBytes();
        }

        private Result rejected(RejectionReason reason) {
            return Result.rejected(reason, totals.snapshot());
        }

        private void finish(Frame frame) {
            activePath.remove(frame.tag);
            stack.pop();
        }
    }

    private static final class Frame {
        private final NBTBase tag;
        private final int depth;
        private final boolean named;
        private boolean entered;
        private int expectedChildren;
        private int visitedChildren;
        private NBTTagList list;
        private Collection<?> compoundChildren;
        private Iterator<?> compoundIterator;

        private Frame(NBTBase tag, int depth, boolean named) {
            this.tag = tag;
            this.depth = depth;
            this.named = named;
        }

        private boolean hasChildren() {
            return list != null || compoundChildren != null;
        }

        private Child nextChild() {
            if (visitedChildren < expectedChildren) {
                Object child;
                boolean childNamed;
                if (list != null) {
                    child = list.tagAt(visitedChildren);
                    childNamed = false;
                } else {
                    if (!compoundIterator.hasNext()) {
                        throw new IllegalStateException("compound cardinality changed during NBT validation");
                    }
                    child = compoundIterator.next();
                    childNamed = true;
                }
                visitedChildren++;
                if (child != null && !(child instanceof NBTBase)) {
                    throw new IllegalStateException("NBT child is not an NBT tag");
                }
                return new Child((NBTBase) child, childNamed, false);
            }

            if (list != null) {
                if (list.tagCount() != expectedChildren) {
                    throw new IllegalStateException("list cardinality changed during NBT validation");
                }
            } else if (compoundIterator.hasNext() || compoundChildren.size() != expectedChildren) {
                throw new IllegalStateException("compound cardinality changed during NBT validation");
            }
            return Child.DONE;
        }
    }

    private static final class Child {
        private static final Child DONE = new Child(null, false, true);

        private final NBTBase tag;
        private final boolean named;
        private final boolean done;

        private Child(NBTBase tag, boolean named, boolean done) {
            this.tag = tag;
            this.named = named;
            this.done = done;
        }
    }

    private static final class MutableTotals {
        private long totalNodes;
        private int maximumDepth;
        private long compoundEntries;
        private long listItems;
        private long stringCharacters;
        private long byteArrayElements;
        private long intArrayElements;
        private long serializedBytes;

        private Totals snapshot() {
            return new Totals(
                totalNodes,
                maximumDepth,
                compoundEntries,
                listItems,
                stringCharacters,
                byteArrayElements,
                intArrayElements,
                serializedBytes
            );
        }
    }

    private static boolean hasExpectedType(NBTBase tag, int tagId) {
        switch (tagId) {
            case TAG_BYTE:
                return tag instanceof NBTTagByte;
            case TAG_SHORT:
                return tag instanceof NBTTagShort;
            case TAG_INT:
                return tag instanceof NBTTagInt;
            case TAG_LONG:
                return tag instanceof NBTTagLong;
            case TAG_FLOAT:
                return tag instanceof NBTTagFloat;
            case TAG_DOUBLE:
                return tag instanceof NBTTagDouble;
            case TAG_BYTE_ARRAY:
                return tag instanceof NBTTagByteArray;
            case TAG_STRING:
                return tag instanceof NBTTagString;
            case TAG_LIST:
                return tag instanceof NBTTagList;
            case TAG_COMPOUND:
                return tag instanceof NBTTagCompound;
            case TAG_INT_ARRAY:
                return tag instanceof NBTTagIntArray;
            default:
                return false;
        }
    }

    private static long modifiedUtfLength(String value) {
        long length = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 0x0001 && character <= 0x007f) {
                length++;
            } else if (character <= 0x07ff) {
                length += 2L;
            } else {
                length += 3L;
            }
        }
        return length;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
