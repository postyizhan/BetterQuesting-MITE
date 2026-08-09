package com.github.postyizhan.betterquesting.api.util;

import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagFloat;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagShort;

/**
 * Reproduces the 1.7.10 NBTPrimitive target accessors using MITE's public numeric tag fields.
 * Floating tags floor before integral narrowing, while integral tags narrow directly; a shared Double
 * representation would change negative-fraction rounding and lose precision for large longs.
 *
 * <p>This is the single narrowing implementation for numeric NBT tags. Property types and the
 * JSON codec both dispatch here so a new numeric tag can never acquire a second, divergent rule.
 */
public final class NbtNumbers {
    private NbtNumbers() {
    }

    public static boolean isNumeric(NBTBase nbt) {
        return nbt != null && nbt.getId() >= 1 && nbt.getId() <= 6;
    }

    /**
     * Returns the tag's value boxed in its own source type, performing no narrowing or widening.
     * This mirrors upstream {@code NBTConverter.getNumber}, whose per-tag accessors likewise return
     * the natural type; the JSON codec relies on that so a float tag never serializes through
     * double and gains trailing precision digits.
     */
    public static Number readAsNumber(NBTBase nbt) {
        switch (nbt.getId()) {
            case 1:
                return Byte.valueOf(((NBTTagByte) nbt).data);
            case 2:
                return Short.valueOf(((NBTTagShort) nbt).data);
            case 3:
                return Integer.valueOf(((NBTTagInt) nbt).data);
            case 4:
                return Long.valueOf(((NBTTagLong) nbt).data);
            case 5:
                return Float.valueOf(((NBTTagFloat) nbt).data);
            case 6:
                return Double.valueOf(((NBTTagDouble) nbt).data);
            default:
                throw new IllegalArgumentException("Not a numeric NBT tag");
        }
    }

    public static byte readAsByte(NBTBase nbt) {
        switch (nbt.getId()) {
            case 1:
                return ((NBTTagByte) nbt).data;
            case 2:
                return (byte) (((NBTTagShort) nbt).data & 255);
            case 3:
                return (byte) (((NBTTagInt) nbt).data & 255);
            case 4:
                return (byte) (((NBTTagLong) nbt).data & 255L);
            case 5:
                return (byte) (((int) Math.floor(((NBTTagFloat) nbt).data)) & 255);
            case 6:
                return (byte) (((int) Math.floor(((NBTTagDouble) nbt).data)) & 255);
            default:
                throw new IllegalArgumentException("Not a numeric NBT tag");
        }
    }

    public static int readAsInt(NBTBase nbt) {
        switch (nbt.getId()) {
            case 1:
                return ((NBTTagByte) nbt).data;
            case 2:
                return ((NBTTagShort) nbt).data;
            case 3:
                return ((NBTTagInt) nbt).data;
            case 4:
                return (int) ((NBTTagLong) nbt).data;
            case 5:
                return (int) Math.floor(((NBTTagFloat) nbt).data);
            case 6:
                return (int) Math.floor(((NBTTagDouble) nbt).data);
            default:
                throw new IllegalArgumentException("Not a numeric NBT tag");
        }
    }

    public static float readAsFloat(NBTBase nbt) {
        switch (nbt.getId()) {
            case 1:
                return ((NBTTagByte) nbt).data;
            case 2:
                return ((NBTTagShort) nbt).data;
            case 3:
                return ((NBTTagInt) nbt).data;
            case 4:
                return ((NBTTagLong) nbt).data;
            case 5:
                return ((NBTTagFloat) nbt).data;
            case 6:
                return (float) ((NBTTagDouble) nbt).data;
            default:
                throw new IllegalArgumentException("Not a numeric NBT tag");
        }
    }

    public static double readAsDouble(NBTBase nbt) {
        switch (nbt.getId()) {
            case 1:
                return ((NBTTagByte) nbt).data;
            case 2:
                return ((NBTTagShort) nbt).data;
            case 3:
                return ((NBTTagInt) nbt).data;
            case 4:
                return ((NBTTagLong) nbt).data;
            case 5:
                return ((NBTTagFloat) nbt).data;
            case 6:
                return ((NBTTagDouble) nbt).data;
            default:
                throw new IllegalArgumentException("Not a numeric NBT tag");
        }
    }
}
