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

class QuantizeBands {

    /* prediction coefficients: 0.9, 0.8, 0.65, 0.5 */
    private static final int[] pred_coef = new int[]{29440, 26112, 21248, 16384};
    private static final int[] beta_coef = new int[]{30147, 22282, 12124, 6554};
    private static final int beta_intra = 4915;
    private static final short[] small_energy_icdf = {2, 1, 0};

    static void unquant_coarse_energy(CeltMode m, int start, int end, int[] oldEBands, int intra, EntropyCoder dec, int C, int LM) {
        short[] prob_model = CeltTables.e_prob_model[LM][intra];
        int i, c;
        int[] prev = {0, 0};
        int coef;
        int beta;
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
        for (i = start; i < end; i++) {
            c = 0;
            do {
                int qi;
                int q;
                int tmp;
                /* It would be better to express this invariant as a
                   test on C at function entry, but that isn't enough
                   to make the static analyzer happy. */
                Inlines.OpusAssert(c < 2);
                tell = dec.tell();
                if (budget - tell >= 15) {
                    int pi;
                    pi = 2 * Inlines.IMIN(i, 20);
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
                q = Inlines.SHL32(qi, CeltConstants.DB_SHIFT); // opus bug: useless extend32

                oldEBands[i + c * m.nbEBands] = Inlines.MAX16((-((short) (0.5 + (9.0f) * (1 << (CeltConstants.DB_SHIFT))))/*Inlines.QCONST16(9.0f, CeltConstants.DB_SHIFT)*/), oldEBands[i + c * m.nbEBands]);
                tmp = Inlines.PSHR32(Inlines.MULT16_16(coef, oldEBands[i + c * m.nbEBands]), 8) + prev[c] + Inlines.SHL32(q, 7);
                tmp = Inlines.MAX32(-((int) (0.5 + (28.0f) * (1 << (CeltConstants.DB_SHIFT + 7))))/*Inlines.QCONST32(28.0f, CeltConstants.DB_SHIFT + 7)*/, tmp);
                oldEBands[i + c * m.nbEBands] = (Inlines.PSHR32(tmp, 7));
                prev[c] = prev[c] + Inlines.SHL32(q, 7) - Inlines.MULT16_16(beta, Inlines.PSHR32(q, 8));
            } while (++c < C);
        }
    }

    static void unquant_fine_energy(CeltMode m, int start, int end, int[] oldEBands, int[] fine_quant, EntropyCoder dec, int C) {
        int i, c;
        /* Decode finer resolution */
        for (i = start; i < end; i++) {
            if (fine_quant[i] <= 0) {
                continue;
            }
            c = 0;
            do {
                int q2;
                int offset;
                q2 = dec.dec_bits(fine_quant[i]);
                offset = Inlines.SUB16((Inlines.SHR32(
                                Inlines.SHL32(q2, CeltConstants.DB_SHIFT)
                                        + ((short) (0.5 + (.5f) * (1 << (CeltConstants.DB_SHIFT))))/*Inlines.QCONST16(.5f, CeltConstants.DB_SHIFT)*/, fine_quant[i])),
                        ((short) (0.5 + (.5f) * (1 << (CeltConstants.DB_SHIFT))))/*Inlines.QCONST16(.5f, CeltConstants.DB_SHIFT)*/); // opus bug: unnecessary extend32
                oldEBands[i + c * m.nbEBands] += offset;
            } while (++c < C);
        }
    }

    static void unquant_energy_finalise(CeltMode m, int start, int end, int[] oldEBands, int[] fine_quant, int[] fine_priority, int bits_left, EntropyCoder dec, int C) {
        int i, prio, c;

        /* Use up the remaining bits */
        for (prio = 0; prio < 2; prio++) {
            for (i = start; i < end && bits_left >= C; i++) {
                if (fine_quant[i] >= CeltConstants.MAX_FINE_BITS || fine_priority[i] != prio) {
                    continue;
                }
                c = 0;
                do {
                    int q2;
                    int offset;
                    q2 = dec.dec_bits(1);
                    offset = Inlines.SHR16((Inlines.SHL16((q2), CeltConstants.DB_SHIFT) - ((short) (0.5 + (.5f) * (1 << (CeltConstants.DB_SHIFT))))/*Inlines.QCONST16(.5f, CeltConstants.DB_SHIFT)*/), fine_quant[i] + 1);
                    oldEBands[i + c * m.nbEBands] += offset;
                    bits_left--;
                } while (++c < C);
            }
        }
    }

}
