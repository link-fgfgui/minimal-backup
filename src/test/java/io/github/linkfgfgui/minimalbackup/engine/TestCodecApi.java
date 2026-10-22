package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.api.CodecProvider;
import io.github.linkfgfgui.minimalbackup.engine.api.CodecRegistry;
import java.io.IOException;

/** E15: the four-method CodecProvider surface and the static facade. */
public final class TestCodecApi {

    private TestCodecApi() {
    }

    public static void run() throws Exception {
        CodecRegistry.reset();
        try {
            TestSupport.eq(CodecRegistry.DEFAULT_CODEC_ID, CodecRegistry.active().codecId(),
                    "default provider id");
            TestSupport.eq(CodecRegistry.DEFAULT_CODEC_ID, Codec.id(),
                    "settings sees the default provider");

            byte[] raw = TestSupport.text("codec-api payload");
            byte[] full = Codec.compressFull(raw, 3, Block.TYPE_FILE);
            TestSupport.bytesEq(raw,
                    Codec.decompress(full, null, raw.length, Block.TYPE_FILE),
                    "default FULL round trip");

            byte[] dict = TestSupport.text("old codec payload");
            byte[] target = TestSupport.mutate(dict, 7);
            byte[] delta = Codec.compressDelta(target, dict, TestSupport.SETTINGS,
                    Block.TYPE_CHUNK);
            TestSupport.bytesEq(target,
                    Codec.decompress(delta, dict, target.length, Block.TYPE_CHUNK),
                    "default DELTA round trip");

            FakeCodec fake = new FakeCodec("test/1");
            CodecRegistry.register(fake);
            TestSupport.eq("test/1", Codec.id(), "registered codec id");

            TestSupport.bytesEq(raw, Codec.compressFull(raw, 9, Block.TYPE_FILE),
                    "FULL routed to provider");
            TestSupport.eq(9, fake.lastLevel, "FULL level forwarded");
            TestSupport.eq(Block.TYPE_FILE, fake.lastType, "FULL type forwarded");

            EngineSettings deltaSettings = new EngineSettings(5, true, 24, -1, true);
            TestSupport.bytesEq(target,
                    Codec.compressDelta(target, dict, deltaSettings, Block.TYPE_CHUNK),
                    "DELTA routed to provider");
            TestSupport.eq(5, fake.lastLevel, "DELTA level forwarded");
            TestSupport.eq(Block.TYPE_CHUNK, fake.lastType, "DELTA type forwarded");
            TestSupport.check(fake.lastLongMode, "DELTA long mode forwarded");
            TestSupport.eq(24, fake.lastWindowLog, "DELTA long window forwarded");

            byte[] patch = TestSupport.text("patch");
            TestSupport.bytesEq(patch, Codec.decompress(patch, dict, 42, Block.TYPE_CHUNK),
                    "decompress routed to provider");
            TestSupport.eq(42, fake.lastOriginalLength, "original length forwarded");

            boolean rejected = false;
            try {
                CodecRegistry.register(new FakeCodec("other/1"));
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            TestSupport.check(rejected, "a second codec id must be rejected");

            CodecRegistry.register(new FakeCodec("test/1"));
            TestSupport.eq("test/1", Codec.id(), "same id may be re-registered");

            CodecRegistry.reset();
            TestSupport.eq(CodecRegistry.DEFAULT_CODEC_ID, Codec.id(),
                    "reset restores the default");
        } finally {
            CodecRegistry.reset();
        }
    }

    private static final class FakeCodec implements CodecProvider {
        private final String id;
        private int lastLevel;
        private int lastType;
        private int lastOriginalLength;
        private boolean lastLongMode;
        private int lastWindowLog;

        private FakeCodec(String id) {
            this.id = id;
        }

        @Override
        public String codecId() {
            return id;
        }

        @Override
        public byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
            lastLevel = level;
            lastType = subTargetType;
            return raw;
        }

        @Override
        public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
                throws IOException {
            lastLevel = level;
            lastType = subTargetType;
            return target;
        }

        @Override
        public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType,
                                    boolean longMode, int longWindowLog) throws IOException {
            lastLongMode = longMode;
            lastWindowLog = longWindowLog;
            return compressDelta(target, dict, level, subTargetType);
        }

        @Override
        public byte[] decompress(byte[] patch, byte[] dict, int originalLength, int subTargetType)
                throws IOException {
            lastOriginalLength = originalLength;
            lastType = subTargetType;
            return patch;
        }
    }
}
