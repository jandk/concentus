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

    static int celt_udiv(int n, int d) {
        Inlines.OpusAssert(d > 0);
        return n / d;
    }

    static int celt_sudiv(int n, int d) {
        return celt_udiv(n, d);
    }

    static int FRAC_MUL16(int a, int b) {
        return 16384 + (short) a * (short) b >> 15;
    }

    /* _val is unsigned 32-bit (opus_uint32) */
    static int isqrt32(int _val) {
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
            int t = (g << 1) + b << bshift;
            if (Integer.compareUnsigned(t, _val) <= 0) {
                g += b;
                _val -= t;
            }
            b >>= 1;
            bshift--;
        } while (bshift >= 0);
        return g;
    }

    // SILK-SPECIFIC INLINES

    static int EC_ILOG(int x) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(x);
    }

}
