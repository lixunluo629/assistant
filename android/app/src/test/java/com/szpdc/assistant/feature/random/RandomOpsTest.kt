package com.szpdc.assistant.feature.random

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随机工具的核心算法测试。
 *
 * 随机结果本身不可断言，所以断言的是"不变量"：范围、数量、去重、包含性、边界校验。
 * 需要确定性时用 [SequenceRandomSource] 注入固定序列。
 */
class RandomOpsTest {

    private val seq = { values: IntArray -> SequenceRandomSource(values) }

    // ---------- 抛硬币 ----------

    @Test
    fun flipReturnsRequestedCount() {
        assertEquals(5, RandomOps.flipCoins(5, seq(intArrayOf(0))).size)
    }

    @Test
    fun flipIsDeterministicWithSequence() {
        // nextInt(2): 0 -> 正面(true)，1 -> 反面(false)
        val result = RandomOps.flipCoins(4, seq(intArrayOf(0, 1, 0, 1)))
        assertEquals(listOf(true, false, true, false), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun flipRejectsZeroTimes() {
        RandomOps.flipCoins(0, seq(intArrayOf(0)))
    }

    // ---------- 骰子 ----------

    @Test
    fun diceAreWithinRangeAndCount() {
        repeat(200) {
            val rolls = RandomOps.rollDice(3, 6)
            assertEquals(3, rolls.size)
            assertTrue(rolls.all { it in 1..6 })
        }
    }

    @Test
    fun diceRespectsSideCount() {
        repeat(100) {
            assertTrue(RandomOps.rollDice(1, 20).single() in 1..20)
        }
    }

    @Test
    fun diceSequenceIsDeterministic() {
        // nextInt(6)=0,1,2 -> 点数 1,2,3
        assertEquals(listOf(1, 2, 3), RandomOps.rollDice(3, 6, seq(intArrayOf(0, 1, 2))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun diceRejectsSingleSide() {
        RandomOps.rollDice(1, 1, seq(intArrayOf(0)))
    }

    // ---------- 抽数字 ----------

    @Test
    fun uniqueSampleHasNoDuplicatesAndStaysInRange() {
        repeat(100) {
            val picked = RandomOps.sampleNumbers(1, 10, 10)
            assertEquals(10, picked.toSet().size)
            assertTrue(picked.all { it in 1..10 })
            assertEquals(picked.sorted(), picked) // 升序返回
        }
    }

    @Test
    fun uniqueSampleWorksOnFullRange() {
        val picked = RandomOps.sampleNumbers(5, 7, 3, unique = true)
        assertEquals(listOf(5, 6, 7), picked.sorted())
    }

    @Test
    fun nonUniqueSampleAllowsDuplicatesAndCount() {
        repeat(50) {
            val picked = RandomOps.sampleNumbers(1, 3, 20, unique = false)
            assertEquals(20, picked.size)
            assertTrue(picked.all { it in 1..3 })
        }
    }

    @Test
    fun sampleHandlesLargeRangeWithoutAllocatingIt() {
        // 1 亿的区间、只抽 3 个：不应该 OOM，也不能抛异常
        val picked = RandomOps.sampleNumbers(-50_000_000, 50_000_000, 3)
        assertEquals(3, picked.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun uniqueSampleRejectsTooMany() {
        RandomOps.sampleNumbers(1, 3, 5, unique = true, source = seq(intArrayOf(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sampleRejectsReversedRange() {
        RandomOps.sampleNumbers(10, 1, 1)
    }

    @Test
    fun sampleCanDrawSingleNumber() {
        assertEquals(listOf(42), RandomOps.sampleNumbers(42, 42, 1, unique = true))
    }

    // ---------- 帮我选一个 ----------

    @Test
    fun pickOneReturnsMemberOfOptions() {
        val options = listOf("火锅", "烧烤", "面条")
        repeat(50) {
            assertTrue(RandomOps.pickOne(options) in options)
        }
    }

    @Test
    fun pickOneOnEmptyReturnsNull() {
        assertNull(RandomOps.pickOne(emptyList()))
    }

    @Test
    fun pickOneWithSequenceIsDeterministic() {
        val options = listOf("a", "b", "c")
        assertEquals("b", RandomOps.pickOne(options, seq(intArrayOf(1))))
        assertEquals("c", RandomOps.pickOne(options, seq(intArrayOf(2))))
    }

    // ---------- 洗牌 ----------

    @Test
    fun shuffleKeepsAllElements() {
        val items = (1..50).toList()
        repeat(20) {
            val shuffled = RandomOps.shuffled(items)
            assertEquals(items.size, shuffled.size)
            assertEquals(items.toSet(), shuffled.toSet())
        }
    }

    @Test
    fun shuffleDoesNotMutateInput() {
        val items = listOf(1, 2, 3, 4, 5)
        RandomOps.shuffled(items)
        assertEquals(listOf(1, 2, 3, 4, 5), items)
    }

    // ---------- 随机密码 ----------

    @Test
    fun passwordHasRequestedLengthAndCharsets() {
        repeat(50) {
            val pwd = RandomOps.generatePassword(16, useUpper = true, useDigits = true, useSymbols = true)
            assertEquals(16, pwd.length)
            assertTrue("缺小写：" + pwd, pwd.any { it.isLowerCase() })
            assertTrue("缺大写：" + pwd, pwd.any { it.isUpperCase() })
            assertTrue("缺数字：" + pwd, pwd.any { it.isDigit() })
            assertTrue("缺符号：" + pwd, pwd.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun passwordWithoutSymbolsHasNoSymbols() {
        repeat(30) {
            val pwd = RandomOps.generatePassword(12, useUpper = false, useDigits = false, useSymbols = false)
            assertTrue(pwd.all { it.isLowerCase() })
        }
    }

    @Test
    fun passwordAvoidsLookalikeCharacters() {
        val pwd = RandomOps.generatePassword(200, useUpper = true, useDigits = true, useSymbols = false)
        for (bad in listOf('l', 'I', 'O', '0', '1')) {
            assertTrue("不应出现易混字符 " + bad + "：" + pwd, !pwd.contains(bad))
        }
    }

    @Test
    fun passwordMinimumLengthEnforced() {
        assertEquals(4, RandomOps.generatePassword(4).length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun passwordRejectsTooShort() {
        RandomOps.generatePassword(3)
    }

    @Test
    fun passwordDiffersBetweenCalls() {
        val a = RandomOps.generatePassword(24, useSymbols = true)
        val b = RandomOps.generatePassword(24, useSymbols = true)
        assertNotEquals(a, b) // 24 位含符号，撞车概率可忽略
    }

    // ---------- 骰子固定 6 面（界面已去掉面数选择） ----------

    @Test
    fun diceAlwaysSixSidesOnUiPath() {
        repeat(100) {
            assertTrue(RandomOps.rollDice(3, 6).all { it in 1..6 })
        }
    }
}
