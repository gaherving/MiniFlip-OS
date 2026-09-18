package com.anastasia.ai;

import java.nio.charset.StandardCharsets;

public final class NativeBrain {
    static { System.loadLibrary("anastasia_ai"); }
    private NativeBrain() {}
    public static native void nativeInit();
    public static native int nativeLoad(String modelPath);
    public static native byte[] nativeGenerate(String prompt, int maxTokens, float temperature);
    public static native String nativeModelInfo();
    public static native void nativeUnload();

    public static String generate(String prompt, int maxTokens, float temperature) {
        byte[] data = nativeGenerate(prompt, maxTokens, temperature);
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }
}
