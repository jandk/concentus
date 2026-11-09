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

final class VQ {
    private static final int[] SPREAD_FACTOR = {15, 10, 5};

    private VQ() {
    }

    static void exp_rotation1(float[] X, int X_ptr, int len, int stride, float c, float s) {
        int i;
        int Xptr = X_ptr;
        float ms = -s;
        for (i = 0; i < len - stride; i++) {
            float x1 = X[Xptr];
            float x2 = X[Xptr + stride];
            X[Xptr + stride] = c * x2 + s * x1;
            X[Xptr] = c * x1 + ms * x2;
            Xptr++;
        }
        Xptr = X_ptr + (len - 2 * stride - 1);
        for (i = len - 2 * stride - 1; i >= 0; i--) {
            float x1 = X[Xptr];
            float x2 = X[Xptr + stride];
            X[Xptr + stride] = c * x2 + s * x1;
            X[Xptr] = c * x1 + ms * x2;
            Xptr--;
        }
    }

    static void exp_rotation(float[] X, int X_ptr, int len, int dir, int stride, int K, int spread) {
        if (2 * K >= len || spread == Spread.SPREAD_NONE) {
            return;
        }
        int factor = SPREAD_FACTOR[spread - 1];

        float gain = (float) len / (float) (len + factor * K);
        float theta = 0.5f * (gain * gain);

        float c = InlinesF.celt_cos_norm(theta);
        float s = InlinesF.celt_cos_norm(1.0f - theta);

        int stride2 = 0;
        if (len >= 8 * stride) {
            stride2 = 1;
            /* This is just a simple (equivalent) way of computing sqrt(len/stride) with rounding.
               It's basically incrementing long as (stride2+0.5)^2 < len/stride. */
            while ((stride2 * stride2 + stride2) * stride + (stride >> 2) < len) {
                stride2++;
            }
        }

        /*NOTE: As a minor optimization, we could be passing around log2(B), not B, for both this and for
           extract_collapse_mask().*/
        len /= stride;
        for (int i = 0; i < stride; i++) {
            if (dir < 0) {
                if (stride2 != 0) {
                    exp_rotation1(X, X_ptr + (i * len), len, stride2, s, c);
                }

                exp_rotation1(X, X_ptr + (i * len), len, 1, c, s);
            } else {
                exp_rotation1(X, X_ptr + (i * len), len, 1, c, -s);

                if (stride2 != 0) {
                    exp_rotation1(X, X_ptr + (i * len), len, stride2, s, -c);
                }
            }
        }
    }

    /**
     * Takes the pitch vector and the decoded residual vector, computes the gain
     * that will give ||p+g*y||=1 and mixes the residual with the pitch.
     */
    static void normalise_residual(int[] iy, float[] X, int X_ptr, int N, float Ryy, float gain) {
        float g = InlinesF.celt_rsqrt(Ryy) * gain;

        int i = 0;
        do {
            X[X_ptr + i] = g * iy[i];
        } while (++i < N);
    }

    static int extract_collapse_mask(int[] iy, int N, int B) {
        if (B <= 1) {
            return 1;
        }
        /* NOTE: As a minor optimization, we could be passing around log2(B), not B, for both this and for
           exp_rotation().*/
        int N0 = N / B;
        int collapse_mask = 0;
        int i = 0;
        do {
            int j = 0;
            int tmp = 0;
            do {
                tmp |= iy[i * N0 + j];
            } while (++j < N0);
            collapse_mask |= (tmp != 0 ? 1 : 0) << i;
        } while (++i < B);
        return collapse_mask;
    }

    /**
     * Decode pulse vector and combine the result with the pitch vector to
     * produce the final normalised signal in the current band.
     */
    static int alg_unquant(float[] X, int X_ptr, int N, int K, int spread, int B, EntropyCoder dec, float gain) {
        Inlines.OpusAssert(K > 0, "alg_unquant() needs at least one pulse");
        Inlines.OpusAssert(N > 1, "alg_unquant() needs at least two dimensions");
        int[] iy = new int[N];
        int Ryy = CWRS.decode_pulses(iy, N, K, dec);
        normalise_residual(iy, X, X_ptr, N, Ryy, gain);
        exp_rotation(X, X_ptr, N, -1, B, K, spread);
        return extract_collapse_mask(iy, N, B);
    }

    static void renormalise_vector(float[] X, int X_ptr, int N, float gain) {
        float E = InlinesF.EPSILON + Kernels.celt_inner_prod(X, X_ptr, X, X_ptr, N);
        float g = InlinesF.celt_rsqrt(E) * gain;

        for (int i = 0; i < N; i++) {
            X[X_ptr + i] *= g;
        }
    }
}
