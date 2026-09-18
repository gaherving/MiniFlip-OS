package com.anastasia.ai;

import java.nio.charset.StandardCharsets;

public final class NativeBrain {
    static { System.loadLibrary("anastasia_ai"); }
    private NativeBrain() {}

    public static native void nativeInit();
    public static native int nativeLoad(String modelPath);
    public static native int nativePrepare(String prompt, int maxTokens, float temperature);
    public static native byte[] nativeNext();
    public static native void nativeResetConversation();
    public static native String nativeModelInfo();
    public static native void nativeUnload();

    public static String nextPiece() {
        byte[] data = nativeNext();
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
