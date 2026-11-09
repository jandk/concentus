/* Copyright (c) 2001-2011 Timothy B. Terriberry
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

/*A range decoder.
This is an entropy decoder based upon \cite{Mar79}, which is itself a
rediscovery of the FIFO arithmetic code introduced by \cite{Pas76}.
It is very similar to arithmetic encoding, except that encoding is done with
digits in any base, instead of with bits, and so it is faster when using
larger bases (i.e.: a byte).
The author claims an average waste of $\frac{1}{2}\log_b(2b)$ bits, where $b$
is the base, longer than the theoretical optimum, but to my knowledge there
is no published justification for this claim.
This only seems true when using near-infinite precision arithmetic so that
the process is carried out with no rounding errors.

An excellent description of implementation details is available at
http://www.arturocampos.com/ac_range.html
A recent work \cite{MNW98} which proposes several changes to arithmetic
encoding for efficiency actually re-discovers many of the principles
behind range encoding, and presents a good theoretical analysis of them.

End of stream is handled by writing out the smallest number of bits that
ensures that the stream will be correctly decoded regardless of the value of
any subsequent bits.
ec_tell() can be used to determine how many bits were needed to decode
all the symbols thus far; other data can be packed in the remaining bits of
the input buffer.
@PHDTHESIS{Pas76,
author="Richard Clark Pasco",
title="Source coding algorithms for fast data compression",
school="Dept. of Electrical Engineering, Stanford University",
address="Stanford, CA",
month=May,
year=1976
}
@INPROCEEDINGS{Mar79,
author="Martin, G.N.N.",
title="Range encoding: an algorithm for removing redundancy from a digitised
message",
booktitle="Video & Data Recording Conference",
year=1979,
address="Southampton",
month=Jul
}
@ARTICLE{MNW98,
author="Alistair Moffat and Radford Neal and Ian H. Witten",
title="Arithmetic Coding Revisited",
journal="{ACM} Transactions on Information Systems",
year=1998,
volume=16,
number=3,
pages="256--294",
month=Jul,
URL="http://www.stanford.edu/class/ee398a/handouts/papers/Moffat98ArithmCoding.pdf"
}*/
final class EntropyCoder {

    private static final int EC_WINDOW_SIZE = 32;

    /// *The number of bits to use for the range-coded part of uint integers.*/
    private static final int EC_UINT_BITS = 8;

    /// *The resolution of fractional-precision bit usage measurements, i.e.,
    //   3 => 1/8th bits.*/
    static final int BITRES = 3;

    /*The number of bits to output at a time.*/
    private static final int EC_SYM_BITS = 8;

    /*The total number of bits in each of the state registers.*/
    private static final int EC_CODE_BITS = 32;

    /*The maximum symbol value.*/
    private static final int EC_SYM_MAX = 0x000000FF;

    /*Carry bit of the high-order range symbol.*/
    private static final int EC_CODE_TOP = 0x80000000;

    /*Low-order bit of the high-order range symbol.*/
    private static final int EC_CODE_BOT = 0x00800000;

    /*The number of bits available for the last, partial symbol in the code field.*/
    private static final int EC_CODE_EXTRA = 0x00000007;

    /// ///////////// Coder State ////////////////////

    /*Buffered input/output.*/
    byte[] buf;
    int buf_ptr;
    /*The size of the buffer.*/
    int storage;
    /*The offset at which the last byte containing raw bits was read/written.*/
    int end_offs;
    /*Bits that will be read from/written at the end.*/
    int end_window;
    /*Number of valid bits in end_window.*/
    int nend_bits;
    /*The total number of whole bits read/written.
      This does not include partial bits currently in the range coder.*/
    int nbits_total;
    /*The offset at which the next range coder byte will be read/written.*/
    int offs;
    /*The number of values in the current range.*/
    int rng;
    /*In the decoder: the difference between the top of the current range and
       the input value, minus one.
      In the encoder: the low end of the current range.*/
    int val;
    /*In the decoder: the saved normalization factor from ec_decode().
      In the encoder: the number of oustanding carry propagating symbols.*/
    int ext;
    /*A buffered input/output symbol, awaiting carry propagation.*/
    int rem;
    /*Nonzero if an error occurred.*/
    int error;

    EntropyCoder() {
        Reset();
    }

    void Reset() {
        buf = null;
        buf_ptr = 0;
        storage = 0;
        end_offs = 0;
        end_window = 0;
        nend_bits = 0;
        offs = 0;
        rng = 0;
        val = 0;
        ext = 0;
        rem = 0;
        error = 0;
    }

    int read_byte() {
        return offs < storage ? Byte.toUnsignedInt(buf[buf_ptr + this.offs++]) : 0;
    }

    int read_byte_from_end() {
        return end_offs < storage ? Byte.toUnsignedInt(buf[buf_ptr + (storage - ++(this.end_offs))]) : 0;
    }

    /// <summary>
    /// Normalizes the contents of val and rng so that rng lies entirely in the high-order symbol.
    /// </summary>
    /// <param name="this"></param>
    void dec_normalize() {
        /*If the range is too small, rescale it and input some bits.*/
        while (Integer.compareUnsigned(rng, EC_CODE_BOT) <= 0) {
            nbits_total += EC_SYM_BITS;
            rng <<= EC_SYM_BITS;

            /*Use up the remaining bits from our last symbol.*/
            int sym = rem;

            /*Read the next value from the input.*/
            rem = read_byte();

            /*Take the rest of the bits we need from this new symbol.*/
            sym = (sym << EC_SYM_BITS | rem) >>> (EC_SYM_BITS - EC_CODE_EXTRA);

            /*And subtract them from val, capped to be less than EC_CODE_TOP.*/
            val = ((val << EC_SYM_BITS) + (EC_SYM_MAX & ~sym)) & (EC_CODE_TOP - 1);
        }
    }

    void dec_init(byte[] _buf, int _buf_ptr, int _storage) {
        this.buf = _buf;
        this.buf_ptr = _buf_ptr;
        this.storage = _storage;
        this.end_offs = 0;
        this.end_window = 0;
        this.nend_bits = 0;
        /*This is the offset from which ec_tell() will subtract partial bits.
          The final value after the ec_dec_normalize() call will be the same as in
           the encoder, but we have to compensate for the bits that are added there.*/
        this.nbits_total = EC_CODE_BITS + 1
                - ((EC_CODE_BITS - EC_CODE_EXTRA) / EC_SYM_BITS) * EC_SYM_BITS;
        this.offs = 0;
        this.rng = 1 << EC_CODE_EXTRA;
        this.rem = read_byte();
        this.val = rng - 1 - (rem >>> (EC_SYM_BITS - EC_CODE_EXTRA));
        this.error = 0;
        /*Normalize the interval.*/
        dec_normalize();
    }

    int decode(int _ft) {
        ext = Integer.divideUnsigned(rng, _ft);
        int s = val / ext;
        return _ft - Math.min(s + 1, _ft);
    }

    int decode_bin(int _bits) {
        this.ext = rng >>> _bits;
        int s = val / ext;
        return (1 << _bits) - Math.min(s + 1, 1 << _bits);
    }

    void dec_update(int _fl, int _fh, int _ft) {
        int s = ext * (_ft - _fh);
        this.val = val - s;
        this.rng = _fl > 0 ? ext * (_fh - _fl) : rng - s;
        dec_normalize();
    }

    /// <summary>
    /// The probability of having a "one" is 1/(1<<_logp).
    /// </summary>
    /// <param name="this"></param>
    /// <param name="_logp"></param>
    /// <returns></returns>
    int dec_bit_logp(int _logp) {
        int r = rng;
        int d = val;
        int s = r >>> _logp;
        int ret = d < s ? 1 : 0;
        if (ret == 0) {
            this.val = d - s;
        }
        this.rng = ret != 0 ? s : r - s;
        dec_normalize();
        return ret;
    }

    int dec_icdf(short[] _icdf, int _ftb) {
        int t;
        int s = rng;
        int d = val;
        int r = s >>> _ftb;
        int ret = -1;
        do {
            t = s;
            s = r * _icdf[++ret];
        } while (d < s);
        this.val = d - s;
        this.rng = t - s;
        dec_normalize();
        return ret;
    }

    /**
     * Decodes a uniformly distributed integer in [0, _ft). Both {@code _ft} and the result are
     * unsigned 32-bit values (opus_uint32): PVQ codebook sizes can exceed 2^31.
     */
    int dec_uint(int _ft) {
        /*In order to optimize EC_ILOG(), it is undefined for the value 0.*/
        Inlines.OpusAssert(Integer.compareUnsigned(_ft, 1) > 0);
        _ft--;
        int ftb = Inlines.EC_ILOG(_ft);
        if (ftb > EC_UINT_BITS) {
            ftb -= EC_UINT_BITS;
            int ft = (_ft >>> ftb) + 1;
            int s = decode(ft);
            dec_update(s, s + 1, ft);
            int t = s << ftb | dec_bits(ftb);
            if (Integer.compareUnsigned(t, _ft) <= 0) {
                return t;
            }
            this.error = 1;
            return _ft;
        } else {
            _ft++;
            int s = decode(_ft);
            dec_update(s, s + 1, _ft);
            return s;
        }
    }

    int dec_bits(int _bits) {
        int window = end_window;
        int available = nend_bits;
        if (available < _bits) {
            do {
                window |= read_byte_from_end() << available;
                available += EC_SYM_BITS;
            } while (available <= EC_WINDOW_SIZE - EC_SYM_BITS);
        }
        int ret = window & ((1 << _bits) - 1);
        window >>>= _bits;
        available -= _bits;
        this.end_window = window;
        this.nend_bits = available;
        this.nbits_total = nbits_total + _bits;
        return ret;
    }

    int get_error() {
        return error;
    }

    /// <summary>
    /// Returns the number of bits "used" by the encoded or decoded symbols so far.
    /// This same number can be computed in either the encoder or the decoder, and is
    /// suitable for making coding decisions.
    /// This will always be slightly larger than the exact value (e.g., all
    /// rounding error is in the positive direction).
    /// </summary>
    /// <param name="this"></param>
    /// <returns>The number of bits.</returns>
    int tell() {
        return nbits_total - Inlines.EC_ILOG(rng);
    }

    private static final int[] correction = {35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535};

    /// <summary>
    /// This is a faster version of ec_tell_frac() that takes advantage
    /// of the low(1/8 bit) resolution to use just a linear function
    /// followed by a lookup to determine the exact transition thresholds.
    /// </summary>
    /// <param name="this"></param>
    /// <returns></returns>
    int tell_frac() {
        int nbits = nbits_total << BITRES;
        int l = Inlines.EC_ILOG(rng);
        int r = rng >>> (l - 16);
        int b = (r >>> 12) - 8;
        b += r > correction[b] ? 1 : 0;
        l = (l << 3) + b;
        return nbits - l;
    }
}
