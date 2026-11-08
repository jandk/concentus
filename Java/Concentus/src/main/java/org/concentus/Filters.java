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

class Filters {

    static void silk_biquad_alt(
            short[] input,
            int input_ptr,
            int[] B_Q28,
            int[] A_Q28,
            int[] S,
            int S_ptr,
            short[] output,
            int output_ptr,
            int len,
            int stride) {
        /* DIRECT FORM II TRANSPOSED (uses 2 element state vector) */
        int k;
        int inval, A0_U_Q28, A0_L_Q28, A1_U_Q28, A1_L_Q28, out32_Q14;

        /* Negate A_Q28 values and split in two parts */
        A0_L_Q28 = (-A_Q28[0]) & 0x00003FFF;
        /* lower part */
        A0_U_Q28 = Inlines.silk_RSHIFT(-A_Q28[0], 14);
        /* upper part */
        A1_L_Q28 = (-A_Q28[1]) & 0x00003FFF;
        /* lower part */
        A1_U_Q28 = Inlines.silk_RSHIFT(-A_Q28[1], 14);
        /* upper part */

        for (k = 0; k < len; k++) {
            int s1 = S_ptr + 1;
            /* S[ 0 ], S[ 1 ]: Q12 */
            inval = input[input_ptr + k * stride];
            out32_Q14 = Inlines.silk_LSHIFT(Inlines.silk_SMLAWB(S[S_ptr], B_Q28[0], inval), 2);

            S[S_ptr] = S[s1] + Inlines.silk_RSHIFT_ROUND(Inlines.silk_SMULWB(out32_Q14, A0_L_Q28), 14);
            S[S_ptr] = Inlines.silk_SMLAWB(S[S_ptr], out32_Q14, A0_U_Q28);
            S[S_ptr] = Inlines.silk_SMLAWB(S[S_ptr], B_Q28[1], inval);

            S[s1] = Inlines.silk_RSHIFT_ROUND(Inlines.silk_SMULWB(out32_Q14, A1_L_Q28), 14);
            S[s1] = Inlines.silk_SMLAWB(S[s1], out32_Q14, A1_U_Q28);
            S[s1] = Inlines.silk_SMLAWB(S[s1], B_Q28[2], inval);

            /* Scale back to Q0 and saturate */
            output[output_ptr + k * stride] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT(out32_Q14 + (1 << 14) - 1, 14));
        }
    }

    /* (opus_int16)(20623 << 1) */

}
