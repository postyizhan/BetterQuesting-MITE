package com.github.postyizhan.betterquesting.api.properties.basic;

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
 */
final class NbtNumbers {
    private NbtNumbers() {
    }

    static boolean isNumeric(NBTBase nbt) {
        return nbt != null && nbt.getId() >= 1 && nbt.getId() <= 6;
    }

    static byte readAsByte(NBTBase nbt) {
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

    static int readAsInt(NBTBase nbt) {
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

    static float readAsFloat(NBTBase nbt) {
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

    static double readAsDouble(NBTBase nbt) {
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
