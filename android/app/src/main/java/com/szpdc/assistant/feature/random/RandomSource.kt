package com.szpdc.assistant.feature.random

import java.security.SecureRandom

/**
 * 随机源抽象。抽出来是为了单测能注入可预测的实现。
 *
 * 默认实现用 [SecureRandom] 而不是 java.util.Random：
 * Random 是线性同余伪随机、种子可预测（Android 上默认种子与时间相关），
 * 在"抛硬币决定谁请客""抽签""抽奖"这类涉及利益的场景里，可预测就等于可以被操纵。
 * SecureRandom 不可预测，代价是稍慢 —— 对这点计算量完全无感。
 */
interface RandomSource {
    /** 返回 [0, bound) 的均匀整数，bound 必须 > 0 */
    fun nextInt(bound: Int): Int

    companion object {
        /** 默认随机源：不可预测的 SecureRandom */
        val default: RandomSource = SecureRandomSource()
    }
}

class SecureRandomSource(private val random: SecureRandom = SecureRandom()) : RandomSource {
    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound 必须大于 0" }
        return random.nextInt(bound)
    }
}

/** 测试用：按给定序列循环输出，保证结果可复现 */
class SequenceRandomSource(private val values: IntArray) : RandomSource {
    private var index = 0

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound 必须大于 0" }
        if (values.isEmpty()) return 0
        val value = values[index % values.size]
        index++
        return ((value % bound) + bound) % bound
    }
}
