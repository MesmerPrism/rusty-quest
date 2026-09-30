package android.media;

public final class MediaCodec {
    public static final class CodecException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public CodecException(String message) { super(message); }
    }
}
