package org.concentus;

final class InlinesF {
    static final float EPSILON = 1e-15f;
    static final float VERY_SMALL = 1e-30f;
    static final float CELT_SIG_SCALE = 32768.0f;
    private static final float HALF_PI = (float) (Math.PI * 0.5);

    private InlinesF() {
    }

    static float SIG2RES(float a) {
        return (1 / CELT_SIG_SCALE) * a;
    }

    /* float_cast.h: float2int() is lrintf(), i.e. round half to even, hence Math.rint */
    static short FLOAT2INT16(float x) {
        x = x * CELT_SIG_SCALE;
        x = Math.max(x, -32768.f);
        x = Math.min(x, 32767.f);
        return (short) Math.rint(x);
    }


    // region celt specific

    static float celt_rsqrt(float x) {
        return 1.0f / celt_sqrt(x);
    }

    static float celt_sqrt(float x) {
        return (float) Math.sqrt(x);
    }

    static float celt_cos_norm(float x) {
        return (float) Math.cos(HALF_PI * x);
    }

    static float celt_exp2(float x) {
        return (float) Math.exp(0.6931471805599453094 * x);
    }

    // endregion

}
