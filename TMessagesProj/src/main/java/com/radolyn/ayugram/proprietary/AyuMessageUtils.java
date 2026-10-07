package com.radolyn.ayugram.proprietary;

import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.messages.AyuSavePreferences;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/**
 * Stub for the closed-source AyuGram proprietary message mapper (not shipped in the public repo).
 * Kept as no-op so the open-source tree compiles standalone.
 */
public class AyuMessageUtils {

    public static void map(AyuSavePreferences prefs, EditedMessage revision) {
        // no-op stub
    }

    public static void map(AyuSavePreferences prefs, DeletedMessage deletedMessage) {
        // no-op stub
    }

    public static void mapMedia(AyuSavePreferences prefs, EditedMessage revision, boolean b) {
        // no-op stub
    }

    public static void mapMedia(AyuSavePreferences prefs, DeletedMessage deletedMessage, boolean b) {
        // no-op stub
    }

    public static void map(EditedMessage editedMessage, TLRPC.TL_message msg, int currentAccount) {
        // no-op stub
    }

    public static void mapMedia(EditedMessage editedMessage, TLRPC.TL_message msg) {
        // no-op stub
    }
}
