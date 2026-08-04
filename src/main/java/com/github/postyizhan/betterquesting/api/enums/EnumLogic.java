package com.github.postyizhan.betterquesting.api.enums;

public enum EnumLogic {
    AND,
    NAND,
    OR,
    NOR,
    XOR,
    XNOR;

    public boolean isTrivial() {
        return switch (this) {
            case AND, OR -> true;
            default -> false;
        };
    }

    public boolean isUnlockable(int inputs, int total) {
        return switch (this) {
            case AND, OR -> true;
            case NAND, XNOR -> inputs < total;
            case NOR -> inputs == 0;
            case XOR -> inputs <= 1;
        };
    }

    public boolean getResult(int inputs, int total) {
        return switch (this) {
            case AND -> inputs >= total;
            case NAND -> inputs < total;
            case NOR -> inputs == 0;
            case OR -> inputs > 0;
            case XNOR -> inputs == total - 1;
            case XOR -> inputs == 1;
        };
    }
}
