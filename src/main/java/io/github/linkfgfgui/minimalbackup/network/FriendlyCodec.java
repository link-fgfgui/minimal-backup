package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.ui.UiCodec;
import net.minecraft.network.FriendlyByteBuf;

/** Shared FriendlyByteBuf adapters for the service codecs. */
final class FriendlyCodec {
    private FriendlyCodec() {
    }

    static UiCodec.Writer writer(FriendlyByteBuf buf) {
        return new UiCodec.Writer() {
            @Override
            public void writeInt(int value) {
                buf.writeInt(value);
            }

            @Override
            public void writeLong(long value) {
                buf.writeLong(value);
            }

            @Override
            public void writeBoolean(boolean value) {
                buf.writeBoolean(value);
            }

            @Override
            public void writeString(String value) {
                buf.writeUtf(value, 32767);
            }
        };
    }

    static UiCodec.Reader reader(FriendlyByteBuf buf) {
        return new UiCodec.Reader() {
            @Override
            public int readInt() {
                return buf.readInt();
            }

            @Override
            public long readLong() {
                return buf.readLong();
            }

            @Override
            public boolean readBoolean() {
                return buf.readBoolean();
            }

            @Override
            public String readString() {
                return buf.readUtf();
            }
        };
    }
}
