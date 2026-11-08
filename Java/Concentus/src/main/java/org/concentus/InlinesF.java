package org.concentus;

final class InlinesF {
    static final float Q15ONE = 1.0f;
    static final float Q31ONE = 1.0f;
    static final float COEF_ONE = 1.0f;
    static final float NORM_SCALING = 1.f;
    static final float EPSILON = 1e-15f;
    static final float VERY_SMALL = 1e-30f;
    static final float VERY_LARGE16 = 1e15f;
    static final float Q15_ONE = 1.f;
    static final float CELT_SIG_SCALE = 32768.0f;
    private static final float HALF_PI = (float) (Math.PI * 0.5);

    private InlinesF() {
    }

    static float COEF2VAL16(float x) {
        return x;
    }

    static float ABS16(float x) {
        return Math.abs(x);
    }

    static float ABS32(float x) {
        return Math.abs(x);
    }

    static float QCONST16(float x, int bits) {
        return x;
    }

    static float QCONST32(float x, int bits) {
        return x;
    }

    static float GCONST(float x) {
        return x;
    }

    static float NEG16(float x) {
        return -(x);
    }

    static float NEG32(float x) {
        return -(x);
    }

    static float NEG32_ovflw(float x) {
        return -(x);
    }

    static float EXTRACT16(float x) {
        return x;
    }

    static float EXTEND32(float x) {
        return x;
    }

    static float SHR16(float a, int shift) {
        return a;
    }

    static float SHL16(float a, int shift) {
        return a;
    }

    static float SHR32(float a, int shift) {
        return a;
    }

    static float SHL32(float a, int shift) {
        return a;
    }

    static float PSHR32(float a, int shift) {
        return a;
    }

    static float VSHR32(float a, int shift) {
        return a;
    }

    static float SHR64(float a, int shift) {
        return a;
    }

    static float PSHR(float a, int shift) {
        return a;
    }

    static float SHR(float a, int shift) {
        return a;
    }

    static float SHL(float a, int shift) {
        return a;
    }

    static float SATURATE(float x, float a) {
        return x;
    }

    static float SATURATE16(float x) {
        return x;
    }

    static float ROUND16(float a, int shift) {
        return a;
    }

    static float SROUND16(float a, int shift) {
        return a;
    }

    static float HALF16(float x) {
        return .5f * x;
    }

    static float HALF32(float x) {
        return .5f * x;
    }

    static float ADD16(float a, float b) {
        return a + b;
    }

    static float SUB16(float a, float b) {
        return a - b;
    }

    static float SUB32(float a, float b) {
        return a - b;
    }

    static float ADD32_ovflw(float a, float b) {
        return a + b;
    }

    static float SUB32_ovflw(float a, float b) {
        return a - b;
    }

    static float SHL32_ovflw(float a, int shift) {
        return a;
    }

    static float PSHR32_ovflw(float a, int shift) {
        return a;
    }

    static float MULT16_16_16(float a, float b) {
        return a * b;
    }

    static float MULT16_16(float a, float b) {
        return a * b;
    }

    static float MAC16_16(float c, float a, float b) {
        return c + a * b;
    }

    static float MULT16_32_Q15(float a, float b) {
        return a * b;
    }

    static float MULT16_32_Q16(float a, float b) {
        return a * b;
    }

    static float MULT32_32_Q16(float a, float b) {
        return a * b;
    }

    static float MULT32_32_Q31(float a, float b) {
        return a * b;
    }

    static float MULT32_32_P31(float a, float b) {
        return a * b;
    }

    static float MULT32_32_P31_ovflw(float a, float b) {
        return a * b;
    }

    static float MAC16_32_Q15(float c, float a, float b) {
        return c + a * b;
    }

    static float MAC16_32_Q16(float c, float a, float b) {
        return c + a * b;
    }

    static float MAC_COEF_32_ARM(float c, float a, float b) {
        return c + a * b;
    }

    static float MULT16_16_Q11_32(float a, float b) {
        return a * b;
    }

    static float MULT16_16_Q11(float a, float b) {
        return a * b;
    }

    static float MULT16_16_Q13(float a, float b) {
        return a * b;
    }

    static float MULT16_16_Q14(float a, float b) {
        return a * b;
    }

    static float MULT16_16_P15(float a, float b) {
        return a * b;
    }

    static float MULT16_16_P13(float a, float b) {
        return a * b;
    }

    static float MULT16_16_P14(float a, float b) {
        return a * b;
    }

    static float MULT16_32_P16(float a, float b) {
        return a * b;
    }

    static float MULT_COEF_32(float a, float b) {
        return a * b;
    }

    static float MULT_COEF(float a, float b) {
        return a * b;
    }

    static float MULT_COEF_TAPS(float a, float b) {
        return a * b;
    }

    static float DIV32_16(float a, float b) {
        return a / b;
    }

    static float DIV32(float a, float b) {
        return a / b;
    }

    static float SIG2RES(float a) {
        return (1 / CELT_SIG_SCALE) * a;
    }

    static short RES2INT16(float a) {
        return FLOAT2INT16(a);
    }

    /* float_cast.h: float2int() is lrintf(), i.e. round half to even, hence Math.rint */
    static short FLOAT2INT16(float x) {
        x = x * CELT_SIG_SCALE;
        x = Math.max(x, -32768.f);
        x = Math.min(x, 32767.f);
        return (short) Math.rint(x);
    }

    static int RES2INT24(float a) {
        return FLOAT2INT24(a);
    }

    static int FLOAT2INT24(float x) {
        x = x * (CELT_SIG_SCALE * 256.f);
        x = Math.max(x, -8388608.f);
        x = Math.min(x, 8388607.f);
        return (int) Math.rint(x);
    }

    static float RES2FLOAT(float a) {
        return a;
    }

    static float INT16TORES(int a) {
        return a * (1 / CELT_SIG_SCALE);
    }

    static float INT24TORES(int a) {
        return (1.f / 32768.f / 256.f) * a;
    }

    static float ADD_RES(float a, float b) {
        return a + b;
    }

    static float ADD32(float a, float b) {
        return a + b;
    }

    static float FLOAT2RES(float a) {
        return a;
    }

    static float RES2SIG(float a) {
        return CELT_SIG_SCALE * a;
    }

    static float MULT16_RES_Q15(float a, float b) {
        return a * b;
    }

    static float MULT16_16_Q15(float a, float b) {
        return a * b;
    }

    static float RES2VAL16(float a) {
        return a;
    }

    static float FLOAT2SIG(float a) {
        return a * CELT_SIG_SCALE;
    }

    static float INT16TOSIG(int a) {
        return (float) a;
    }

    static float INT24TOSIG(int a) {
        return (float) a * (1.f / 256.f);
    }


    // region celt specific

    static float celt_rsqrt_norm(float x) {
        return celt_rsqrt(x);
    }

    static float celt_rsqrt(float x) {
        return 1.0f / celt_sqrt(x);
    }

    static float celt_sqrt(float x) {
        return (float) Math.sqrt(x);
    }

    static float celt_cos_norm(float x) {
        return (float) Math.cos(HALF_PI * x);
    }

    static float celt_rcp(float x) {
        return 1.0f / x;
    }

    static float celt_div(float a, float b) {
        return a / b;
    }

    static float frac_div32(float a, float b) {
        return a / b;
    }

    static float celt_log2(float x) {
        return (float) (1.442695040888963387 * Math.log(x));
    }

    static float celt_exp2(float x) {
        return (float) Math.exp(0.6931471805599453094 * x);
    }

    static int celt_udiv(int n, int d) {
        assert d > 0;
        return Integer.divideUnsigned(n, d);
    }

    static int celt_sudiv(int n, int d) {
        assert d > 0;
        return n / d;
    }

    // endregion

}
