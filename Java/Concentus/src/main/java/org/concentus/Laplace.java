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

class Laplace {

    /* The minimum probability of an energy delta (out of 32768). */
    private static final int LAPLACE_LOG_MINP = 0;
    private static final int LAPLACE_MINP = (1 << LAPLACE_LOG_MINP);
    /* The minimum number of guaranteed representable energy deltas (in one
        direction). */
    private static final int LAPLACE_NMIN = 16;

    /* When called, decay is positive and at most 11456. */
    static int ec_laplace_get_freq1(int fs0, int decay) {
        int ft = 32768 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0;
        return ft * (16384 - decay) >> 15;
    }

    static int ec_laplace_decode(EntropyCoder dec, int fs, int decay) {
        int val = 0;
        int fl = 0;
        int fm = dec.decode_bin(15);

        if (fm >= fs) {
            val++;
            fl = fs;
            fs = ec_laplace_get_freq1(fs, decay) + LAPLACE_MINP;
            /* Search the decaying part of the PDF.*/
            while (fs > LAPLACE_MINP && fm >= fl + 2 * fs) {
                fs *= 2;
                fl += fs;
                fs = (fs - 2 * LAPLACE_MINP) * decay >> 15;
                fs += LAPLACE_MINP;
                val++;
            }
            /* Everything beyond that has probability LAPLACE_MINP. */
            if (fs <= LAPLACE_MINP) {
                int di = (fm - fl) >> (LAPLACE_LOG_MINP + 1);
                val += di;
                fl += 2 * di * LAPLACE_MINP;
            }
            if (fm < fl + fs) {
                val = -val;
            } else {
                fl += fs;
            }
        }

        Inlines.OpusAssert(fl < 32768);
        Inlines.OpusAssert(fs > 0);
        Inlines.OpusAssert(fl <= fm);
        Inlines.OpusAssert(fm < Math.min(fl + fs, 32768));

        dec.dec_update(fl, Math.min(fl + fs, 32768), 32768);
        return val;
    }
}
