/* Copyright (c) 2007-2008 CSIRO
   Copyright (c) 2007-2011 Xiph.Org Foundation
   Originally written by Jean-Marc Valin, Gregory Maxwell, Koen Vos,
   Timothy B. Terriberry, and the Opus open-source contributors
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

class CeltLPC {

    static void celt_lpc(
            int[] _lpc, /* out: [0...p-1] LPC coefficients      */
            int[] ac, /* in:  [0...p] autocorrelation values  */
            int p) {
        int i, j;
        int r;
        int error = ac[0];
        int[] lpc = new int[p];

        //Arrays.MemSet(lpc, 0, p); strictly, this is not necessary since the runtime zeroes memory for us
        if (ac[0] != 0) {
            for (i = 0; i < p; i++) {
                /* Sum up this iteration's reflection coefficient */
                int rr = 0;
                for (j = 0; j < i; j++) {
                    rr += Inlines.MULT32_32_Q31(lpc[j], ac[i - j]);
                }
                rr += Inlines.SHR32(ac[i + 1], 3);
                r = -Inlines.frac_div32(Inlines.SHL32(rr, 3), error);
                /*  Update LPC coefficients and total error */
                lpc[i] = Inlines.SHR32(r, 3);

                for (j = 0; j < (i + 1) >> 1; j++) {
                    int tmp1, tmp2;
                    tmp1 = lpc[j];
                    tmp2 = lpc[i - 1 - j];
                    lpc[j] = tmp1 + Inlines.MULT32_32_Q31(r, tmp2);
                    lpc[i - 1 - j] = tmp2 + Inlines.MULT32_32_Q31(r, tmp1);
                }

                error = error - Inlines.MULT32_32_Q31(Inlines.MULT32_32_Q31(r, r), error);

                /* Bail out once we get 30 dB gain */
                if (error < Inlines.SHR32(ac[0], 10)) {
                    break;
                }
            }
        }

        for (i = 0; i < p; i++) {
            _lpc[i] = Inlines.ROUND16((lpc[i]), 16);
        }
    }

}
