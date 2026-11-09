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

final class QuantizeBands {

    /* prediction coefficients: 0.9, 0.8, 0.65, 0.5 */
    private static final float[] pred_coef = {29440 / 32768.0f, 26112 / 32768.0f, 21248 / 32768.0f, 16384 / 32768.0f};
    private static final float[] beta_coef = {30147 / 32768.0f, 22282 / 32768.0f, 12124 / 32768.0f, +6554 / 32768.0f};
    private static final float beta_intra = 4915 / 32768.0f;

    private static final short[] small_energy_icdf = {2, 1, 0};

    static void unquant_coarse_energy(CeltMode m, int start, int end, float[] oldEBands, int intra, EntropyCoder dec, int C, int LM) {
        short[] prob_model = CeltTables.e_prob_model[LM][intra];
        float[] prev = new float[2];
        float coef;
        float beta;
        int budget;
        int tell;

        if (intra != 0) {
            coef = 0;
            beta = beta_intra;
        } else {
            beta = beta_coef[LM];
            coef = pred_coef[LM];
        }

        budget = dec.storage * 8;

        /* Decode at a fixed coarse resolution */
        for (int i = start; i < end; i++) {
            int c = 0;
            do {
                int qi;
                /* It would be better to express this invariant as a
                   test on C at function entry, but that isn't enough
                   to make the static analyzer happy. */
                Inlines.OpusAssert(c < 2);
                tell = dec.tell();
                if (budget - tell >= 15) {
                    int pi;
                    pi = 2 * Math.min(i, 20);
                    qi = Laplace.ec_laplace_decode(dec,
                            prob_model[pi] << 7, prob_model[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.dec_icdf(small_energy_icdf, 2);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = -dec.dec_bit_logp(1);
                } else {
                    qi = -1;
                }
                float q = qi;

                oldEBands[i + c * m.nbEBands] = Math.max(-9.0f, oldEBands[i + c * m.nbEBands]);
                oldEBands[i + c * m.nbEBands] = coef * oldEBands[i + c * m.nbEBands] + prev[c] + q;
                prev[c] = prev[c] + q - beta * q;
            } while (++c < C);
        }
    }

    static void unquant_fine_energy(CeltMode m, int start, int end, float[] oldEBands, int[] fine_quant, EntropyCoder dec, int C) {
        int i, c;
        /* Decode finer resolution */
        for (i = start; i < end; i++) {
            if (fine_quant[i] <= 0) {
                continue;
            }
            c = 0;
            do {
                int q2 = dec.dec_bits(fine_quant[i]);
                float offset = (q2 + .5f) * (1 << (14 - fine_quant[i])) * (1.f / 16384) - .5f;
                oldEBands[i + c * m.nbEBands] += offset;
            } while (++c < C);
        }
    }

    static void unquant_energy_finalise(CeltMode m, int start, int end, float[] oldEBands, int[] fine_quant, int[] fine_priority, int bits_left, EntropyCoder dec, int C) {
        int i, prio, c;

        /* Use up the remaining bits */
        for (prio = 0; prio < 2; prio++) {
            for (i = start; i < end && bits_left >= C; i++) {
                if (fine_quant[i] >= CeltConstants.MAX_FINE_BITS || fine_priority[i] != prio) {
                    continue;
                }
                c = 0;
                do {
                    int q2 = dec.dec_bits(1);
                    float offset = (q2 - .5f) * (1 << (14 - fine_quant[i] - 1)) * (1.f / 16384);
                    oldEBands[i + c * m.nbEBands] += offset;
                    bits_left--;
                } while (++c < C);
            }
        }
    }

}
