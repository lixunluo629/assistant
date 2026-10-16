package com.szpdc.assistant.feature.random

/**
 * 随机工具的核心算法：纯函数，不碰 UI，全部走 [RandomSource]，因此可以完整单测。
 */
object RandomOps {

    /** 密码字符集：刻意去掉了 l/1/I/0/O 这些肉眼易混的字符 */
    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+"

    /** 抛 [times] 次硬币，true 表示正面 */
    fun flipCoins(times: Int, source: RandomSource = RandomSource.default): List<Boolean> {
        require(times > 0) { "次数必须大于 0" }
        return List(times) { source.nextInt(2) == 0 }
    }

    /** 摇 [count] 个 [sides] 面骰子，返回每颗的点数（1..sides） */
    fun rollDice(count: Int, sides: Int = 6, source: RandomSource = RandomSource.default): List<Int> {
        require(count > 0) { "骰子数量必须大于 0" }
        require(sides > 1) { "面数必须大于 1" }
        return List(count) { source.nextInt(sides) + 1 }
    }

    /**
     * 从 [from, until] 闭区间内抽 [count] 个整数。
     *
     * @param unique true 时不重复（此时要求 count <= 区间长度），结果升序返回
     */
    fun sampleNumbers(
        from: Int,
        until: Int,
        count: Int,
        unique: Boolean = true,
        source: RandomSource = RandomSource.default,
    ): List<Int> {
        require(until >= from) { "区间无效：最大值必须不小于最小值" }
        require(count > 0) { "抽取个数必须大于 0" }
        val size = until - from + 1
        require(size > 0) { "区间过大" }

        if (!unique) {
            return List(count) { from + source.nextInt(size) }
        }
        require(count <= size) { "不重复抽取最多只能抽 " + size + " 个" }

        // 部分 Fisher–Yates：只洗前 count 个，区间很大时也不会先建整个数组
        val pool = IntArray(size) { from + it }
        val result = IntArray(count)
        for (i in 0 until count) {
            val j = i + source.nextInt(size - i)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
            result[i] = pool[i]
        }
        return result.toList().sorted()
    }

    /**
     * 从选项里随机挑一个；没有选项返回 null。
     *
     * 当前界面没有"帮我选"入口（已按需求移除），但算法与测试保留：
     * 后续要加的"抽签 / 点名"直接用这个函数。
     */
    fun pickOne(options: List<String>, source: RandomSource = RandomSource.default): String? =
        if (options.isEmpty()) null else options[source.nextInt(options.size)]

    /** Fisher–Yates 洗牌（不改动原列表） */
    fun <T> shuffled(items: List<T>, source: RandomSource = RandomSource.default): List<T> {
        val list = items.toMutableList()
        for (i in list.size - 1 downTo 1) {
            val j = source.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
        return list
    }

    /**
     * 生成随机密码。被选中的字符类型至少各出现一次，避免"勾了符号却一个都没有"。
     *
     * 当前界面没有"随机密码"入口（已按需求移除），算法与测试保留备用。
     */
    fun generatePassword(
        length: Int,
        useUpper: Boolean = true,
        useDigits: Boolean = true,
        useSymbols: Boolean = false,
        source: RandomSource = RandomSource.default,
    ): String {
        require(length >= 4) { "长度至少 4 位" }

        val pools = ArrayList<String>()
        pools.add(LOWER)
        if (useUpper) pools.add(UPPER)
        if (useDigits) pools.add(DIGITS)
        if (useSymbols) pools.add(SYMBOLS)

        val all = pools.joinToString("")
        val chars = ArrayList<Char>(length)
        for (pool in pools) {
            if (chars.size < length) chars.add(pool[source.nextInt(pool.length)])
        }
        while (chars.size < length) {
            chars.add(all[source.nextInt(all.length)])
        }
        return shuffled(chars, source).joinToString("")
    }

    /** 骰子点数的图形表示（1~6 有对应字符，其它面数退回数字） */
    fun diceFace(value: Int): String = when (value) {
        1 -> "⚀"
        2 -> "⚁"
        3 -> "⚂"
        4 -> "⚃"
        5 -> "⚄"
        6 -> "⚅"
        else -> value.toString()
    }
}
