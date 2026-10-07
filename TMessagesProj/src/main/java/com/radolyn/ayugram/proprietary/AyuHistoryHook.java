package com.radolyn.ayugram.proprietary;

import android.util.SparseArray;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;

/**
 * Stub for the closed-source AyuGram proprietary hook (not shipped in the public repo).
 * Kept as no-op so the open-source tree compiles standalone.
 */
public class AyuHistoryHook {

    public static class MinMax {
        public int first;
        public int second;
    }

    public static MinMax getMinAndMaxIds(ArrayList<MessageObject> messArr) {
        return new MinMax();
    }

    public static void doHook(int currentAccount, ArrayList<MessageObject> messArr,
                              SparseArray<MessageObject>[] messagesDict,
                              int startId, int endId, long dialogId, int limit,
                              int topicId, boolean isSecretChat) {
        // no-op stub
    }
}
