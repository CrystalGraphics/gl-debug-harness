package io.github.somehussar.crystalgraphics.harness.util;

import java.nio.ByteOrder;

public class ColorUtil {

    /**
     *  For packing int ARGB colors into float vertex buffers 
     */
    public static float col(int argb) {
        int r = argb >> 16 & 0xff;
        int g = argb >> 8 & 0xff;
        int b = argb & 0xff;
        int a = argb >> 24 & 0xff;

        int packed;

        boolean littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        if (littleEndian) packed = a << 24 | b << 16 | g << 8 | r;
        else packed = r << 24 | g << 16 | b << 8 | a;

        return Float.intBitsToFloat(packed);
    }
}
