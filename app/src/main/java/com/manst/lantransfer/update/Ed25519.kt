package com.manst.lantransfer.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * 纯 JVM/Kotlin 的 Ed25519 验签（RFC 8032）。
 *
 * 为什么不用 java.security.Signature("Ed25519")：Android 系统默认 Provider（Conscrypt）
 * 并未提供 Ed25519，运行时会抛 "KeyFactory not available"，因此自带实现，跨版本稳定。
 * 单次验签约 1~3 ms，只用于更新清单，开销可忽略。
 */
object Ed25519 {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val TWO = BigInteger.TWO

    /** 扩展坐标点 (X:Y:Z:T)，满足 x = X/Z, y = Y/Z, x*y = T/Z。 */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BY: BigInteger = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val BX: BigInteger = xRecover(BY)
    private val BASE: Point = Point(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

    /** 校验 64 字节签名。 */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val s = littleEndianToBigInt(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val r = decompress(rBytes) ?: return false
        val h = MessageDigest.getInstance("SHA-512").digest(rBytes + publicKey + message)
        val k = littleEndianToBigInt(h).mod(L)
        val left = multiply(BASE, s)
        val right = add(r, multiply(a, k))
        return left.encode().contentEquals(right.encode())
    }

    private fun xRecover(y: BigInteger): BigInteger {
        val yy = y.multiply(y).mod(P)
        val xx = yy.subtract(BigInteger.ONE)
            .multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P))
            .mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) {
            x = x.multiply(I).mod(P)
        }
        if (x.testBit(0)) x = P.subtract(x)
        return x
    }

    private fun decompress(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val y = littleEndianToBigInt(bytes.mapIndexed { index, b ->
            if (index == 31) (b.toInt() and 0x7f).toByte() else b
        }.toByteArray())
        if (y >= P) return null
        val sign = (bytes[31].toInt() ushr 7) and 1
        val yy = y.multiply(y).mod(P)
        val xx = yy.subtract(BigInteger.ONE).multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P)).mod(P)
        if (xx.signum() == 0) {
            if (sign == 1) return null
            return Point(BigInteger.ZERO, y, BigInteger.ONE, BigInteger.ZERO)
        }
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) {
            x = x.multiply(I).mod(P)
        }
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(TWO).multiply(D).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun multiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun Point.encode(): ByteArray {
        val zInv = z.modInverse(P)
        val xp = x.multiply(zInv).mod(P)
        val yp = y.multiply(zInv).mod(P)
        val value = if (xp.testBit(0)) yp.setBit(255) else yp
        val raw = value.toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 32 -> raw.copyInto(out)
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyInto(out, 0, 1, 33)
            raw.size < 32 -> raw.copyInto(out, 32 - raw.size)
            else -> raw.copyInto(out, 0, raw.size - 32, raw.size)
        }
        return out.reversedArray()
    }

    private fun littleEndianToBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
}
