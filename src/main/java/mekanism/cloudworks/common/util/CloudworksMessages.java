package mekanism.cloudworks.common.util;

/** Translation keys carried by the server; clients resolve them in their own language. */
public final class CloudworksMessages {
    public static final String SAVED = "cloudworks.editor.saved";
    public static final String STORAGE_ERROR = "cloudworks.error.storage";

    private CloudworksMessages() {
    }

    public static String errorKey(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message != null && message.startsWith("cloudworks.error.") ? message : STORAGE_ERROR;
    }
}
