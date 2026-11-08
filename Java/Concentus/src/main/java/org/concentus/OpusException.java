/* Copyright (c) 2016 Logan Stromberg

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

public class OpusException extends Exception {

    private final String _message;
    private final int _opus_error_code;

    public OpusException() {
        this("", 0);
    }

    public OpusException(String message) {
        this(message, 1);
    }

    public OpusException(String message, int opus_error_code) {
        _message = message + ": " + opus_strerror(opus_error_code);
        _opus_error_code = opus_error_code;
    }

    //static void opus_pcm_soft_clip(Pointer<float> _x, int N, int C, Pointer<float> declip_mem)
    //{
    //    int c;
    //    int i;
    //    Pointer<float> x;
    //    if (C < 1 || N < 1 || _x == null || declip_mem == null) return;
    //    /* First thing: saturate everything to +/- 2 which is the highest level our
    //       non-linearity can handle. At the point where the signal reaches +/-2,
    //       the derivative will be zero anyway, so this doesn't introduce any
    //       discontinuity in the derivative. */
    //    for (i = 0; i < N * C; i++)
    //        _x[i] = Inlines.MAX16(-2.0f, Inlines.MIN16(2.0f, _x[i]));
    //    for (c = 0; c < C; c++)
    //    {
    //        float a;
    //        float x0;
    //        int curr;
    //        x = _x.Point(c);
    //        a = declip_mem[c];
    //        /* Continue applying the non-linearity from the previous frame to avoid
    //           any discontinuity. */
    //        for (i = 0; i < N; i++)
    //        {
    //            if (x[i * C] * a >= 0)
    //                break;
    //            x[i * C] = x[i * C] + a * x[i * C] * x[i * C];
    //        }
    //        curr = 0;
    //        x0 = x[0];
    //        while (true)
    //        {
    //            int start, end;
    //            float maxval;
    //            int special = 0;
    //            int peak_pos;
    //            for (i = curr; i < N; i++)
    //            {
    //                if (x[i * C] > 1 || x[i * C] < -1)
    //                    break;
    //            }
    //            if (i == N)
    //            {
    //                a = 0;
    //                break;
    //            }
    //            peak_pos = i;
    //            start = end = i;
    //            maxval = Inlines.ABS16(x[i * C]);
    //            /* Look for first zero crossing before clipping */
    //            while (start > 0 && x[i * C] * x[(start - 1) * C] >= 0)
    //                start--;
    //            /* Look for first zero crossing after clipping */
    //            while (end < N && x[i * C] * x[end * C] >= 0)
    //            {
    //                /* Look for other peaks until the next zero-crossing. */
    //                if (Inlines.ABS16(x[end * C]) > maxval)
    //                {
    //                    maxval = Inlines.ABS16(x[end * C]);
    //                    peak_pos = end;
    //                }
    //                end++;
    //            }
    //            /* Detect the special case where we clip before the first zero crossing */
    //            special = (start == 0 && x[i * C] * x[0] >= 0) ? 1 : 0;
    //            /* Compute a such that maxval + a*maxval^2 = 1 */
    //            a = (maxval - 1) / (maxval * maxval);
    //            if (x[i * C] > 0)
    //                a = -a;
    //            /* Apply soft clipping */
    //            for (i = start; i < end; i++)
    //                x[i * C] = x[i * C] + a * x[i * C] * x[i * C];
    //            if (special != 0 && peak_pos >= 2)
    //            {
    //                /* Add a linear ramp from the first sample to the signal peak.
    //                   This avoids a discontinuity at the beginning of the frame. */
    //                float delta;
    //                float offset = x0 - x[0];
    //                delta = offset / peak_pos;
    //                for (i = curr; i < peak_pos; i++)
    //                {
    //                    offset -= delta;
    //                    x[i * C] += offset;
    //                    x[i * C] = Inlines.MAX16(-1.0f, Inlines.MIN16(1.0f, x[i * C]));
    //                }
    //            }
    //            curr = end;
    //            if (curr == N)
    //            {
    //                break;
    //            }
    //        }
    //        declip_mem[c] = a;
    //    }
    //}
    public static String opus_strerror(int error) {
        String[] error_strings = {
                "success",
                "invalid argument",
                "buffer too small",
                "error",
                "corrupted stream",
                "request not implemented",
                "invalid state",
                "memory allocation failed"
        };
        if (error > 0 || error < -7) {
            return "unknown error";
        } else {
            return error_strings[-error];
        }
    }

    @Override
    public String getMessage() {
        return _message;
    }
}
