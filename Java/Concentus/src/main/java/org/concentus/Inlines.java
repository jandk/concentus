/* Copyright (c) 2006-2011 Skype Limited. 
   Copyright (c) 2007-2008 CSIRO
   Copyright (c) 2007-2011 Xiph.Org Foundation
   Ported to Java by Logan Stromberg

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

   - Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

   - Neither the name of Internet Society, IETF or IETF Trust, nor the
   names of specific contributors, may be used to endorse or promote
   products derived from this software without specific prior written
   permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
   OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.concentus;

final class Inlines {
    private Inlines() {
    }

    static void OpusAssert(boolean condition) {
        assert condition;
    }

    static void OpusAssert(boolean condition, String message) {
        assert condition : message;
    }

    static long CapToUInt32(long val) {
        return 0xFFFFFFFFL & (int) val;
    }

    static long CapToUInt32(int val) {
        return val;
    }

    // CELT-SPECIFIC INLINES
    static int MULT16_16SU(int a, int b) {
        return (int) (short) a * (b & 0xFFFF);
    }

    static int MULT16_32_P16(short a, int b) {
        return ADD32(MULT16_16(a, SHR(b, 16)), PSHR(MULT16_16SU(a, b & 0x0000ffff), 16));
    }

    static int MULT16_32_Q15(short a, int b) {
        return (a * (b >> 16) << 1) + (a * (b & 0xFFFF) >> 15);
        //return ADD32(SHL(MULT16_16((a), SHR((b), 16)), 1), SHR(MULT16_16SU((a), (ushort)((b) & 0x0000ffff)), 15));
    }

    static int MULT16_32_Q15(int a, int b) {
        return (a * (b >> 16) << 1) + (a * (b & 0xFFFF) >> 15);
        //return ADD32(SHL(MULT16_16((a), SHR((b), 16)), 1), SHR(MULT16_16SU((a), (uint)((b) & 0x0000ffff)), 15));
    }

    static int MULT32_32_Q31(int a, int b) {
        return ADD32(ADD32(SHL(MULT16_16(SHR(a, 16), SHR(b, 16)), 1), SHR(MULT16_16SU(SHR(a, 16), b & 0x0000ffff), 15)), SHR(MULT16_16SU(SHR(b, 16), a & 0x0000ffff), 15));
    }

    static int NEG16(int x) {
        return -x;
    }

    static int NEG32(int x) {
        return -x;
    }

    static short EXTRACT16(int x) {
        return (short) x;
    }

    static int EXTEND32(int x) {
        return x;
    }

    static int SHR16(int a, int shift) {
        return a >> shift;
    }

    static short SHL16(short a, int shift) {
        return (short) ((a & 0xFFFF) << shift);
    }

    static int SHL16(int a, int shift) {
        return (int) (0xFFFFFFFFL & (long) a << shift);
    }

    static int SHR32(int a, int shift) {
        return a >> shift;
    }

    static int SHL32(int a, int shift) {
        return (int) (0xFFFFFFFFL & (long) a << shift);
    }

    static int PSHR32(int a, int shift) {
        return SHR32(a + (EXTEND32(1) << shift >> 1), shift);
    }

    static int VSHR32(int a, int shift) {
        return shift > 0 ? SHR32(a, shift) : SHL32(a, -shift);
    }

    private static int SHR(int a, int shift) {
        return a >> shift;
    }

    private static int SHL(int a, int shift) {
        return SHL32(a, shift);
    }

    private static int PSHR(int a, int shift) {
        return SHR(a + (EXTEND32(1) << shift >> 1), shift);
    }

    static int SATURATE(int x, int a) {
        return x > a ? a : Math.max(x, -a);
    }

    static int HALF16(int x) {
        return SHR32(x, 1);
    }

    static int HALF32(int x) {
        return SHR32(x, 1);
    }

    static short ADD16(short a, short b) {
        return (short) (a + b);
    }

    static int ADD16(int a, int b) {
        return a + b;
    }

    static int SUB16(int a, int b) {
        return a - b;
    }

    static int ADD32(int a, int b) {
        return a + b;
    }

    static int SUB32(int a, int b) {
        return a - b;
    }

    static int MULT16_16(int a, int b) {
        return a * b;
    }

    static int MULT16_16(short a, short b) {
        return a * b;
    }

    static int MAC16_16(int c, short a, short b) {
        return c + a * b;
    }

    static int MAC16_16(int c, int a, int b) {
        return c + a * b;
    }

    static int MULT16_16_Q14(int a, int b) {
        return SHR(MULT16_16(a, b), 14);
    }

    static short MULT16_16_Q15(short a, short b) {
        return (short) SHR(MULT16_16(a, b), 15);
    }

    static int MULT16_16_Q15(int a, int b) {
        return SHR(MULT16_16(a, b), 15);
    }

    static int MULT16_16_P15(int a, int b) {
        return SHR(ADD32(16384, MULT16_16(a, b)), 15);
    }

    static int MIN16(int a, int b) {
        return Math.min(a, b);
    }

    static int MAX16(int a, int b) {
        return Math.max(a, b);
    }

    static int IMIN(int a, int b) {
        return Math.min(a, b);
    }

    static long IMIN(long a, long b) {
        return Math.min(a, b);
    }

    static int IMAX(int a, int b) {
        return Math.max(a, b);
    }

    static int MIN32(int a, int b) {
        return Math.min(a, b);
    }

    static int MAX32(int a, int b) {
        return Math.max(a, b);
    }

    static int celt_udiv(int n, int d) {
        Inlines.OpusAssert(d > 0);
        return n / d;
    }

    static int celt_sudiv(int n, int d) {
        return celt_udiv(n, d);
    }

    static int celt_div(int a, int b) {
        return MULT32_32_Q31(a, celt_rcp(b));
    }

    /**
     * Integer log in base2. Undefined for zero and negative numbers
     */
    static int celt_ilog2(int x) {
        Inlines.OpusAssert(x > 0, "celt_ilog2() only defined for strictly positive numbers");
        return EC_ILOG(x) - 1;
    }

    static int FRAC_MUL16(int a, int b) {
        return 16384 + (short) a * (short) b >> 15;
    }

    static int isqrt32(long _val) {
        int b;
        int g;
        int bshift;
        /*Uses the second method from
           http://www.azillionmonkeys.com/qed/sqroot.html
          The main idea is to search for the largest binary digit b such that
           (g+b)*(g+b) <= _val, and add it to the solution g.*/
        g = 0;
        bshift = EC_ILOG(_val) - 1 >> 1;
        b = 1 << bshift;
        do {
            long t;
            t = (g << 1) + b << bshift;
            if (t <= _val) {
                g += b;
                _val -= t;
            }
            b >>= 1;
            bshift--;
        } while (bshift >= 0);
        return g;
    }

    private static final short[] sqrt_C = {23175, 11561, -3011, 1699, -664};

    /**
     * Sqrt approximation (QX input, QX/2 output)
     */
    static int celt_sqrt(int x) {
        int k;
        short n;
        int rt;

        if (x == 0) {
            return 0;
        } else if (x >= 1073741824) {
            return 32767;
        }
        k = (celt_ilog2(x) >> 1) - 7;
        x = VSHR32(x, 2 * k);
        n = (short) (x - 32768);
        rt = ADD16(sqrt_C[0], MULT16_16_Q15(n, ADD16(sqrt_C[1], MULT16_16_Q15(n, ADD16(sqrt_C[2],
                MULT16_16_Q15(n, ADD16(sqrt_C[3], MULT16_16_Q15(n, sqrt_C[4]))))))));
        rt = VSHR32(rt, 7 - k);
        return rt;
    }

    /**
     * Reciprocal approximation (Q15 input, Q16 output)
     */
    static int celt_rcp(int x) {
        int i;
        int n;
        int r;
        Inlines.OpusAssert(x > 0, "celt_rcp() only defined for positive values");
        i = celt_ilog2(x);
        /* n is Q15 with range [0,1). */
        n = VSHR32(x, i - 15) - 32768;
        /* Start with a linear approximation:
           r = 1.8823529411764706-0.9411764705882353*n.
           The coefficients and the result are Q14 in the range [15420,30840].*/
        r = ADD16(30840, MULT16_16_Q15(-15420, n));
        /* Perform two Newton iterations:
           r -= r*((r*n)-1.Q15)
              = r*((r*n)+(r-1.Q15)). */
        r = SUB16(r, MULT16_16_Q15(r,
                ADD16(MULT16_16_Q15(r, n), ADD16(r, -32768))));
        /* We subtract an extra 1 in the second iteration to avoid overflow; it also
            neatly compensates for truncation error in the rest of the process. */
        r = SUB16(r, ADD16(1, MULT16_16_Q15(r,
                ADD16(MULT16_16_Q15(r, n), ADD16(r, -32768)))));
        /* r is now the Q15 solution to 2/(n+1), with a maximum relative error
            of 7.05346E-5, a (relative) RMSE of 2.14418E-5, and a peak absolute
            error of 1.24665/32768. */
        return VSHR32(EXTEND32(r), i - 16);
    }

    /**
     * Reciprocal sqrt approximation in the range [0.25,1) (Q16 in, Q14 out)
     */
    static int celt_rsqrt_norm(int x) {
        int n;
        int r;
        int r2;
        int y;
        /* Range of n is [-16384,32767] ([-0.5,1) in Q15). */
        n = x - 32768;
        /* Get a rough initial guess for the root.
           The optimal minimax quadratic approximation (using relative error) is
            r = 1.437799046117536+n*(-0.823394375837328+n*0.4096419668459485).
           Coefficients here, and the final result r, are Q14.*/
        r = ADD16(23557, MULT16_16_Q15(n, ADD16(-13490, MULT16_16_Q15(n, 6713))));
        /* We want y = x*r*r-1 in Q15, but x is 32-bit Q16 and r is Q14.
           We can compute the result from n and r using Q15 multiplies with some
            adjustment, carefully done to avoid overflow.
           Range of y is [-1564,1594]. */
        r2 = MULT16_16_Q15(r, r);
        y = SHL16(SUB16(ADD16(MULT16_16_Q15(r2, n), r2), 16384), 1);
        /* Apply a 2nd-order Householder iteration: r += r*y*(y*0.375-0.5).
           This yields the Q14 reciprocal square root of the Q16 x, with a maximum
            relative error of 1.04956E-4, a (relative) RMSE of 2.80979E-5, and a
            peak absolute error of 2.26591/16384. */
        return ADD16(r, MULT16_16_Q15(r, MULT16_16_Q15(y,
                SUB16(MULT16_16_Q15(y, 12288), 16384))));
    }

    static int celt_exp2_frac(int x) {
        int frac;
        frac = SHL16(x, 4);
        return ADD16(16383, MULT16_16_Q15(frac, ADD16(22804, MULT16_16_Q15(frac, ADD16(14819, MULT16_16_Q15(10204, frac))))));
    }

    /**
     * Base-2 exponential approximation (2^x). (Q10 input, Q16 output)
     */
    static int celt_exp2(int x) {
        int integer;
        int frac;
        integer = SHR16(x, 10);
        if (integer > 14) {
            return 0x7f000000;
        } else if (integer < -15) {
            return 0;
        }
        frac = (short) celt_exp2_frac((short) (x - SHL16((short) integer, 10)));
        return VSHR32(EXTEND32(frac), -integer - 2);
    }

    static int celt_cos_norm(int x) {
        x = x & 0x0001ffff;
        if (x > SHL32(EXTEND32(1), 16)) {
            x = SUB32(SHL32(EXTEND32(1), 17), x);
        }
        if ((x & 0x00007fff) != 0) {
            if (x < SHL32(EXTEND32(1), 15)) {
                return _celt_cos_pi_2(EXTRACT16(x));
            } else {
                return NEG32(_celt_cos_pi_2(EXTRACT16(65536 - x))); // opus bug: should be neg32?
            }
        } else if ((x & 0x0000ffff) != 0) {
            return 0;
        } else if ((x & 0x0001ffff) != 0) {
            return -32767;
        } else {
            return 32767;
        }
    }

    static int _celt_cos_pi_2(int x) {
        int x2;

        x2 = MULT16_16_P15(x, x);
        return ADD32(1, MIN32(32766, ADD32(SUB16(32767, x2), MULT16_16_P15(x2, ADD32(-7651, MULT16_16_P15(x2, ADD32(8277, MULT16_16_P15(-626, x2))))))));
    }

    // SILK-SPECIFIC INLINES

    static long EC_MINI(long a, long b) {
        return Math.min(a, b);
    }

    static int EC_ILOG(long x) {
        return 64 - Long.numberOfLeadingZeros(x);
    }

    public static short QCONST16(float f, int bits) {
        return (short) (0.5 + f * (1 << bits));
    }

    public static int QCONST32(float f, int bits) {
        return (int) (0.5 + (f) * (1 << (bits)));
    }
}
