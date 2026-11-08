package org.concentus;

import org.concentus.mdct.MDCT;

/**
 * CELT's inverse MDCT: the transform itself plus windowing and overlap-add with the previous block.
 * <p>
 * CELT's window is only {@code overlap} samples long at each end, flat in between, so of the {@code N}
 * output samples only {@code N/2 + overlap} around the middle are used. Each block leaves its windowed
 * falling edge in the output buffer for the next block to add onto.
 * <p>
 * One instance per decoder; the transforms hold scratch buffers.
 */
final class CeltImdct {

    /* One transform per block size, indexed by shift: 1920, 960, 480 and 240 samples at 48 kHz */
    private final MDCT[] mdct;
    private final float[] coeffs;
    private final float[] y;

    CeltImdct(CeltMode mode) {
        int n = mode.shortMdctSize << (mode.maxLM + 1);
        mdct = new MDCT[mode.maxLM + 1];
        for (int shift = 0; shift <= mode.maxLM; shift++) {
            mdct[shift] = new MDCT(n >> shift);
        }
        coeffs = new float[n >> 1];
        y = new float[n];
    }

    /**
     * Inverse transforms one block of {@code N2 = 960 >> shift} coefficients.
     * <p>
     * Coefficient {@code k} is read from {@code in[inPtr + k * stride]}, as short blocks are interleaved.
     * {@code out[outPtr .. outPtr + overlap)} must hold the previous block's windowed tail, which this
     * block's rising edge is added to. {@code out[.. outPtr + N2)} then holds finished samples and
     * {@code out[outPtr + N2 .. outPtr + N2 + overlap)} this block's windowed tail.
     */
    void backward(float[] in, int inPtr, float[] out, int outPtr, float[] window, int overlap, int shift, int stride) {
        MDCT m = mdct[shift];
        int n2 = coeffs.length >> shift;
        int n4 = n2 >> 1;

        /* Short blocks are interleaved */
        for (int k = 0; k < n2; k++) {
            coeffs[k] = in[inPtr + k * stride];
        }
        m.backward(coeffs, 0, y, 0);

        /* The window's rising edge starts overlap/2 before the middle half of the output */
        int s = n4 - (overlap >> 1);
        for (int i = 0; i < overlap; i++) {
            out[outPtr + i] = out[outPtr + i] + window[i] * y[s + i];
        }
        for (int i = overlap; i < n2; i++) {
            out[outPtr + i] = y[s + i];
        }
        for (int i = 0; i < overlap; i++) {
            out[outPtr + n2 + i] = window[overlap - 1 - i] * y[s + n2 + i];
        }
    }
}
