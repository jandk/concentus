/* Copyright (c) 2006-2011 Skype Limited. All Rights Reserved
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

class Autocorrelation {

    static int _celt_autocorr(
            int[] x, /*  in: [0...n-1] samples x   */
            int[] ac, /* out: [0...lag-1] ac values */
            int[] window,
            int overlap,
            int lag,
            int n) {
        int d;
        int i, k;
        int fastN = n - lag;
        int shift;
        int[] xptr;
        int[] xx = new int[n];

        Inlines.OpusAssert(n > 0);
        Inlines.OpusAssert(overlap >= 0);

        if (overlap == 0) {
            xptr = x;
        } else {
            for (i = 0; i < n; i++) {
                xx[i] = x[i];
            }
            for (i = 0; i < overlap; i++) {
                xx[i] = Inlines.MULT16_16_Q15(x[i], window[i]);
                xx[n - i - 1] = Inlines.MULT16_16_Q15(x[n - i - 1], window[i]);
            }
            xptr = xx;
        }

        shift = 0;

        int ac0;
        ac0 = 1 + (n << 7);
        if ((n & 1) != 0) {
            ac0 += Inlines.SHR32(Inlines.MULT16_16(xptr[0], xptr[0]), 9);
        }

        for (i = (n & 1); i < n; i += 2) {
            ac0 += Inlines.SHR32(Inlines.MULT16_16(xptr[i], xptr[i]), 9);
            ac0 += Inlines.SHR32(Inlines.MULT16_16(xptr[i + 1], xptr[i + 1]), 9);
        }

        shift = Inlines.celt_ilog2(ac0) - 30 + 10;
        shift = (shift) / 2;
        if (shift > 0) {
            for (i = 0; i < n; i++) {
                xx[i] = (Inlines.PSHR32(xptr[i], shift));
            }
            xptr = xx;
        } else {
            shift = 0;
        }

        CeltPitchXCorr.pitch_xcorr(xptr, xptr, ac, fastN, lag + 1);
        for (k = 0; k <= lag; k++) {
            for (i = k + fastN, d = 0; i < n; i++) {
                d = Inlines.MAC16_16(d, xptr[i], xptr[i - k]);
            }
            ac[k] += d;
        }

        shift = 2 * shift;
        if (shift <= 0) {
            ac[0] += Inlines.SHL32(1, -shift);
        }
        if (ac[0] < 268435456) {
            int shift2 = 29 - Inlines.EC_ILOG(ac[0]);
            for (i = 0; i <= lag; i++) {
                ac[i] = Inlines.SHL32(ac[i], shift2);
            }
            shift -= shift2;
        } else if (ac[0] >= 536870912) {
            int shift2 = 1;
            if (ac[0] >= 1073741824) {
                shift2++;
            }
            for (i = 0; i <= lag; i++) {
                ac[i] = Inlines.SHR32(ac[i], shift2);
            }
            shift += shift2;
        }

        return shift;
    }

}
