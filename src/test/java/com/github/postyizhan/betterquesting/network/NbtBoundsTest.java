package com.github.postyizhan.betterquesting.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByteArray;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagEnd;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagIntArray;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;

class NbtBoundsTest {
    private static final NbtLimits BASE = new NbtLimits(8, 32, 8, 8, 16, 16, 16, 1024L);

    @Test
    void acceptsAnEmptyRootAndReportsMeasuredTotals() {
        NBTTagCompound root = new NBTTagCompound();

        NbtBounds.Result result = NbtBounds.validate(root, BASE);

        assertTrue(result.isAccepted());
        assertEquals(NbtBounds.RejectionReason.NONE, result.rejectionReason());
        assertEquals(1L, result.totalNodes());
        assertEquals(1, result.maximumDepth());
        assertEquals(0L, result.compoundEntries());
        assertEquals(0L, result.listItems());
        assertEquals(4L, result.serializedBytes());
    }

    @Test
    void exactlyAtEachCardinalityLimitIsAcceptedAndOneOverIsRejected() {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setInteger("a", 1);
        compound.setInteger("b", 2);
        assertTrue(NbtBounds.validate(compound,
            new NbtLimits(8, 3, 2, 8, 16, 16, 16, 1024L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_COMPOUND_ENTRIES,
            NbtBounds.validate(compound,
                new NbtLimits(8, 3, 1, 8, 16, 16, 16, 1024L)).rejectionReason());

        NBTTagList list = new NBTTagList("list");
        list.appendTag(new NBTTagInt("", 1));
        list.appendTag(new NBTTagInt("", 2));
        compound = new NBTTagCompound();
        compound.setTag("list", list);
        assertTrue(NbtBounds.validate(compound,
            new NbtLimits(8, 4, 8, 2, 16, 16, 16, 1024L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_LIST_ITEMS,
            NbtBounds.validate(compound,
                new NbtLimits(8, 4, 8, 1, 16, 16, 16, 1024L)).rejectionReason());
    }

    @Test
    void enforcesDepthNodesStringsAndArraysAtTheBoundary() {
        NBTTagCompound depthRoot = new NBTTagCompound();
        NBTTagCompound depthChild = new NBTTagCompound();
        depthRoot.setTag("child", depthChild);
        depthChild.setInteger("leaf", 1);
        assertTrue(NbtBounds.validate(depthRoot,
            new NbtLimits(3, 3, 8, 8, 16, 16, 16, 1024L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_DEPTH,
            NbtBounds.validate(depthRoot,
                new NbtLimits(2, 3, 8, 8, 16, 16, 16, 1024L)).rejectionReason());
        assertEquals(NbtBounds.RejectionReason.MAX_TOTAL_NODES,
            NbtBounds.validate(depthRoot,
                new NbtLimits(3, 2, 8, 8, 16, 16, 16, 1024L)).rejectionReason());

        NBTTagCompound strings = new NBTTagCompound();
        strings.setTag("s", new NBTTagString("", "abcd"));
        assertTrue(NbtBounds.validate(strings,
            new NbtLimits(8, 4, 8, 8, 4, 16, 16, 1024L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_STRING_LENGTH,
            NbtBounds.validate(strings,
                new NbtLimits(8, 4, 8, 8, 3, 16, 16, 1024L)).rejectionReason());

        NBTTagCompound arrays = new NBTTagCompound();
        arrays.setTag("bytes", new NBTTagByteArray("", new byte[] {1, 2}));
        arrays.setTag("ints", new NBTTagIntArray("", new int[] {1, 2}));
        assertTrue(NbtBounds.validate(arrays,
            new NbtLimits(8, 4, 8, 8, 16, 2, 2, 1024L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_BYTE_ARRAY_LENGTH,
            NbtBounds.validate(arrays,
                new NbtLimits(8, 4, 8, 8, 16, 1, 2, 1024L)).rejectionReason());
        assertEquals(NbtBounds.RejectionReason.MAX_INT_ARRAY_LENGTH,
            NbtBounds.validate(arrays,
                new NbtLimits(8, 4, 8, 8, 16, 2, 1, 1024L)).rejectionReason());
    }

    @Test
    void enforcesTheConservativeSerializedByteBudgetAtTheBoundary() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("x", 1);

        assertTrue(NbtBounds.validate(root,
            new NbtLimits(8, 4, 8, 8, 16, 16, 16, 12L)).isAccepted());
        assertEquals(NbtBounds.RejectionReason.MAX_SERIALIZED_BYTES,
            NbtBounds.validate(root,
                new NbtLimits(8, 4, 8, 8, 16, 16, 16, 11L)).rejectionReason());
    }

    @Test
    void countsModifiedUtfNamesAtTheByteAndStringBoundaries() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("\u0800", 1);

        NbtBounds.Result accepted = NbtBounds.validate(root,
            new NbtLimits(8, 4, 8, 8, 1, 16, 16, 14L));
        assertTrue(accepted.isAccepted());
        assertEquals(1L, accepted.stringCharacters());
        assertEquals(14L, accepted.serializedBytes());
        assertEquals(NbtBounds.RejectionReason.MAX_SERIALIZED_BYTES,
            NbtBounds.validate(root,
                new NbtLimits(8, 4, 8, 8, 1, 16, 16, 13L)).rejectionReason());

        NBTTagCompound longName = new NBTTagCompound();
        longName.setInteger("ab", 1);
        assertEquals(NbtBounds.RejectionReason.MAX_STRING_LENGTH,
            NbtBounds.validate(longName,
                new NbtLimits(8, 4, 8, 8, 1, 16, 16, 1024L)).rejectionReason());
    }

    @Test
    void checksContainerCardinalityBeforeAccessingChildren() throws Exception {
        final boolean[] compoundIterated = {false};
        NBTTagCompound compound = new NBTTagCompound();
        Field tags = NBTTagCompound.class.getDeclaredField("tagMap");
        tags.setAccessible(true);
        tags.set(compound, new AbstractMap<String, NBTBase>() {
            @Override
            public Set<Entry<String, NBTBase>> entrySet() {
                return Collections.emptySet();
            }

            @Override
            public Collection<NBTBase> values() {
                return new AbstractCollection<NBTBase>() {
                    @Override
                    public java.util.Iterator<NBTBase> iterator() {
                        compoundIterated[0] = true;
                        throw new IllegalStateException("must not iterate");
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                };
            }
        });
        assertEquals(NbtBounds.RejectionReason.MAX_COMPOUND_ENTRIES,
            NbtBounds.validate(compound,
                new NbtLimits(8, 8, 1, 8, 16, 16, 16, 1024L)).rejectionReason());
        assertFalse(compoundIterated[0]);

        final boolean[] listAccessed = {false};
        NBTTagList list = new NBTTagList("");
        Field items = NBTTagList.class.getDeclaredField("tagList");
        items.setAccessible(true);
        items.set(list, new AbstractList<NBTBase>() {
            @Override
            public NBTBase get(int index) {
                listAccessed[0] = true;
                throw new IllegalStateException("must not access");
            }

            @Override
            public int size() {
                return 2;
            }
        });
        NBTTagCompound listRoot = new NBTTagCompound();
        listRoot.setTag("list", list);
        assertEquals(NbtBounds.RejectionReason.MAX_LIST_ITEMS,
            NbtBounds.validate(listRoot,
                new NbtLimits(8, 8, 8, 1, 16, 16, 16, 1024L)).rejectionReason());
        assertFalse(listAccessed[0]);
    }

    @Test
    void rejectsNullRootsChildrenCyclesAndInvalidTagIds() throws Exception {
        assertEquals(NbtBounds.RejectionReason.NULL_ROOT,
            NbtBounds.validate(null, BASE).rejectionReason());

        NBTTagCompound nullChild = new NBTTagCompound();
        rawCompoundMap(nullChild).put("null", null);
        assertEquals(NbtBounds.RejectionReason.NULL_CHILD,
            NbtBounds.validate(nullChild, BASE).rejectionReason());

        NBTTagCompound cycle = new NBTTagCompound();
        cycle.setTag("self", cycle);
        assertEquals(NbtBounds.RejectionReason.CYCLE,
            NbtBounds.validate(cycle, BASE).rejectionReason());

        NBTTagCompound end = new NBTTagCompound();
        end.setTag("end", new NBTTagEnd());
        assertEquals(NbtBounds.RejectionReason.TAG_END,
            NbtBounds.validate(end, BASE).rejectionReason());

        NBTTagCompound unknown = new NBTTagCompound();
        NBTTagInt tag = new NBTTagInt("", 1);
        Field id = NBTBase.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setByte(tag, (byte) 99);
        unknown.setTag("unknown", tag);
        assertEquals(NbtBounds.RejectionReason.UNKNOWN_TAG_ID,
            NbtBounds.validate(unknown, BASE).rejectionReason());
    }

    @Test
    void rejectsInvalidLimitConfigurationsAndTraversalFailures() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> new NbtLimits(0, 1, 1, 1, 1, 1, 1, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new NbtLimits(1, -1, 1, 1, 1, 1, 1, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new NbtLimits(1, 1, 1, 1, 1, 1, 1, -1L));
        assertEquals(NbtBounds.RejectionReason.INVALID_LIMITS,
            NbtBounds.validate(new NBTTagCompound(), null).rejectionReason());

        NBTTagCompound throwing = new NBTTagCompound();
        Field tags = NBTTagCompound.class.getDeclaredField("tagMap");
        tags.setAccessible(true);
        tags.set(throwing, new AbstractMap<String, NBTBase>() {
            @Override
            public Set<Entry<String, NBTBase>> entrySet() {
                return Collections.emptySet();
            }

            @Override
            public Collection<NBTBase> values() {
                throw new IllegalStateException("broken traversal");
            }
        });
        assertEquals(NbtBounds.RejectionReason.TRAVERSAL_FAILURE,
            NbtBounds.validate(throwing, BASE).rejectionReason());
    }

    @Test
    void rejectsNullPayloadsAndKnownIdsOnTheWrongRuntimeType() throws Exception {
        NBTTagCompound nullPayload = new NBTTagCompound();
        nullPayload.setTag("bytes", new NBTTagByteArray(""));
        assertEquals(NbtBounds.RejectionReason.NULL_VALUE,
            NbtBounds.validate(nullPayload, BASE).rejectionReason());

        NBTTagString mismatched = new NBTTagString("", "value");
        Field id = NBTBase.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setByte(mismatched, (byte) 3);
        NBTTagCompound wrongType = new NBTTagCompound();
        wrongType.setTag("value", mismatched);
        assertEquals(NbtBounds.RejectionReason.INVALID_TAG_TYPE,
            NbtBounds.validate(wrongType, BASE).rejectionReason());
    }

    @Test
    void deeplyNestedTreesUseAnIterativeTraversal() {
        int depth = 2048;
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound current = root;
        for (int index = 1; index < depth; index++) {
            NBTTagCompound child = new NBTTagCompound();
            current.setTag("n", child);
            current = child;
        }

        NbtBounds.Result result = NbtBounds.validate(root,
            new NbtLimits(depth, depth, 1, 0, 1, 0, 0, Long.MAX_VALUE));

        assertTrue(result.isAccepted());
        assertEquals(depth, result.maximumDepth());
        assertEquals(depth, result.totalNodes());
    }

    @Test
    void validationDoesNotMutateOrCopyTheMaterializedTreeAndResultIsImmutable() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound child = new NBTTagCompound();
        root.setTag("child", child);
        child.setString("value", "ok");
        String rootName = root.getName();
        String childName = child.getName();

        NbtBounds.Result result = NbtBounds.validate(root, BASE);

        assertSame(child, root.getTag("child"));
        assertEquals(rootName, root.getName());
        assertEquals(childName, child.getName());
        assertImmutableValueType(result.getClass());
        assertImmutableValueType(result.totals().getClass());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NBTBase> rawCompoundMap(NBTTagCompound compound) throws Exception {
        Field field = NBTTagCompound.class.getDeclaredField("tagMap");
        field.setAccessible(true);
        return (Map<String, NBTBase>) field.get(compound);
    }

    private static void assertImmutableValueType(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()));
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
            }
        }
    }
}
