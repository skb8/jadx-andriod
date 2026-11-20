package io.github.skb8.jadxandroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import jadx.api.ResourceFile;
import jadx.core.xmlgen.ResContainer;

final class AndroidNinePatch {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long PNG_SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int NINE_PATCH_CHUNK = 0x6e705463;

    private AndroidNinePatch() {}

    static ResContainer decode(ResourceFile file, InputStream input) throws IOException {
        byte[] png = input.readNBytes(MAX_BYTES + 1);
        if (png.length > MAX_BYTES) {
            return ResContainer.resourceFileLink(file);
        }
        ByteBuffer chunk = findChunk(png);
        if (chunk == null || chunk.remaining() < 32) {
            return ResContainer.resourceFileLink(file);
        }
        int countX = Byte.toUnsignedInt(chunk.get(1));
        int countY = Byte.toUnsignedInt(chunk.get(2));
        if (countX % 2 != 0 || countY % 2 != 0 || chunk.remaining() < 32 + 4 * (countX + countY)) {
            return ResContainer.resourceFileLink(file);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, options);
        int width = options.outWidth;
        int height = options.outHeight;
        if (width < 1 || height < 1 || (long) width * height > 4_000_000) {
            return ResContainer.resourceFileLink(file);
        }
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        if (bitmap == null) {
            return ResContainer.resourceFileLink(file);
        }
        Bitmap restored = Bitmap.createBitmap(width + 2, height + 2, Bitmap.Config.ARGB_8888);
        try {
            new Canvas(restored).drawBitmap(bitmap, 1, 1, null);
            int left = chunk.getInt(12);
            int right = chunk.getInt(16);
            int top = chunk.getInt(20);
            int bottom = chunk.getInt(24);
            mark(restored, true, height + 1, left + 1, width - right, width);
            mark(restored, false, width + 1, top + 1, height - bottom, height);
            chunk.position(32);
            for (int i = 0; i < countX; i += 2) {
                mark(restored, true, 0, chunk.getInt() + 1, chunk.getInt(), width);
            }
            for (int i = 0; i < countY; i += 2) {
                mark(restored, false, 0, chunk.getInt() + 1, chunk.getInt(), height);
            }
            var output = new ByteArrayOutputStream();
            return restored.compress(Bitmap.CompressFormat.PNG, 100, output)
                    ? ResContainer.decodedData(file.getDeobfName(), output.toByteArray())
                    : ResContainer.resourceFileLink(file);
        } finally {
            bitmap.recycle();
            restored.recycle();
        }
    }

    private static ByteBuffer findChunk(byte[] png) {
        ByteBuffer buffer = ByteBuffer.wrap(png);
        if (buffer.remaining() < 8 || buffer.getLong() != PNG_SIGNATURE) {
            return null;
        }
        while (buffer.remaining() >= 12) {
            int size = buffer.getInt();
            int type = buffer.getInt();
            if (size < 0 || size > buffer.remaining() - 4) {
                return null;
            }
            if (type == NINE_PATCH_CHUNK) {
                ByteBuffer chunk = buffer.slice();
                chunk.limit(size);
                return chunk;
            }
            buffer.position(buffer.position() + size + 4);
        }
        return null;
    }

    private static void mark(Bitmap bitmap, boolean horizontal, int fixed, int first, int last, int size) {
        if (first < 1 || last > size || first > last) {
            return;
        }
        for (int position = first; position <= last; position++) {
            bitmap.setPixel(horizontal ? position : fixed, horizontal ? fixed : position, Color.BLACK);
        }
    }
}
