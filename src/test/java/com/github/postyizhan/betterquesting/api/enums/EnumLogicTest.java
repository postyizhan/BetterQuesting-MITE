package com.github.postyizhan.betterquesting.api.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EnumLogicTest {
    @Test
    void reportsOnlyAndAndOrAsTrivial() {
        for (EnumLogic logic : EnumLogic.values()) {
            assertEquals(logic == EnumLogic.AND || logic == EnumLogic.OR, logic.isTrivial());
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void evaluatesEveryLogicBranch(EnumLogic logic, int inputs, int total, boolean unlockable, boolean result) {
        assertEquals(unlockable, logic.isUnlockable(inputs, total));
        assertEquals(result, logic.getResult(inputs, total));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of(EnumLogic.AND, 1, 2, true, false),
            Arguments.of(EnumLogic.AND, 2, 2, true, true),
            Arguments.of(EnumLogic.NAND, 1, 2, true, true),
            Arguments.of(EnumLogic.NAND, 2, 2, false, false),
            Arguments.of(EnumLogic.OR, 0, 2, true, false),
            Arguments.of(EnumLogic.OR, 1, 2, true, true),
            Arguments.of(EnumLogic.NOR, 0, 2, true, true),
            Arguments.of(EnumLogic.NOR, 1, 2, false, false),
            Arguments.of(EnumLogic.XOR, 0, 2, true, false),
            Arguments.of(EnumLogic.XOR, 1, 2, true, true),
            Arguments.of(EnumLogic.XOR, 2, 2, false, false),
            Arguments.of(EnumLogic.XNOR, 1, 2, true, true),
            Arguments.of(EnumLogic.XNOR, 2, 2, false, false),
            Arguments.of(EnumLogic.XNOR, 0, 2, true, false)
        );
    }
}
