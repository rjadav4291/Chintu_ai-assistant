package com.chintu.assistant

class CalcException(message: String) : Exception(message)

object Calc {
    fun eval(input: String): Double {
        val s = input.replace(" ", "").replace("x", "*").replace("X", "*")
            .replace("×", "*").replace("÷", "/")
        return Parser(s).parse()
    }

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw CalcException("The result is too large")
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        return java.math.BigDecimal(v).setScale(8, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i < s.length) throw CalcException("Unexpected '${s[i]}'")
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = unary()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = unary()
                if (op == '*') {
                    v *= r
                } else {
                    if (r == 0.0) throw CalcException("Division by zero")
                    v /= r
                }
            }
            return v
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return power()
        }

        fun power(): Double {
            val base = primary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(base, unary()) }
            return base
        }

        fun primary(): Double {
            if (i >= s.length) throw CalcException("The expression is incomplete")
            if (s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw CalcException("A closing bracket is missing")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw CalcException("Unexpected '${s[i]}'")
            return s.substring(start, i).toDoubleOrNull() ?: throw CalcException("Bad number")
        }
    }
}
