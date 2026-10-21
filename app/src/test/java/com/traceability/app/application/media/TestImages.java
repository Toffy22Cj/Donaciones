package com.traceability.app.application.media;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Imágenes de prueba generadas con la JDK (ADR-051, definición de hecho 1 a 5): sin binarios en el repositorio. Los
 * metadatos (EXIF con GPS, XMP, comentarios y fragmentos de texto PNG) se insertan a mano, y las "bombas" son imágenes
 * reales con las dimensiones de la cabecera cambiadas.
 */
public final class TestImages {

    /** Texto que solo existe dentro de los metadatos insertados: si aparece en la salida, la limpieza falló. */
    public static final String SECRET_MARK = "PAXFIDE-METADATA-MARK";

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return write(gradient(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return write(gradient(width, height), "png");
    }

    public static byte[] bmp(int width, int height) {
        return write(gradient(width, height), "bmp");
    }

    public static byte[] gif(int width, int height) {
        return write(gradient(width, height), "gif");
    }

    /** PNG RGBA: cuatro componentes en el espacio de color RGB. */
    public static byte[] pngRgba(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 30 % 256) << 24) | 0x00FF8040);
            }
        }
        return write(image, "png");
    }

    /** PNG gris con alfa: dos componentes en el espacio de color gris. */
    public static byte[] pngGrayAlpha(int width, int height) {
        ColorSpace gray = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        ComponentColorModel model = new ComponentColorModel(gray, true, false, Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setPixel(x, y, new int[]{x * 20 % 256, y * 20 % 256});
            }
        }
        return write(new BufferedImage(model, raster, false, null), "png");
    }

    /** PNG con paleta ({@code IndexColorModel}). */
    public static byte[] pngPalette(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x % 2 == 0 ? 0xFF0000 : 0x0000FF);
            }
        }
        return write(image, "png");
    }

    /** El JPEG con un segmento Adobe APP14 tras el SOI: transform 0 = CMYK de Adobe, 2 = YCCK. */
    public static byte[] withAdobeTransform(byte[] jpeg, int transform) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        segment(out, 0xEE, new byte[]{'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, (byte) transform});
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** JPEG de cuatro componentes (CMYK), escrito como {@code Raster} con el escritor de la JDK. */
    public static byte[] cmykJpeg(int width, int height) {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setPixel(x, y, new int[]{x * 7 % 256, y * 5 % 256, (x + y) % 256, 40});
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** El JPEG con tres segmentos insertados tras el SOI: APP1 EXIF con GPS, APP1 XMP y COM. */
    public static byte[] withExifGpsXmpAndComment(byte[] jpeg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        segment(out, 0xE1, concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiffWithGps()));
        segment(out, 0xE1, ("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                + "<exif:GPSLatitude>4,38.0N</exif:GPSLatitude>" + SECRET_MARK + "</x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8));
        segment(out, 0xFE, ("comment " + SECRET_MARK).getBytes(StandardCharsets.UTF_8));
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** El PNG con {@code eXIf}, {@code tEXt}, {@code iTXt} y {@code zTXt} insertados tras el IHDR. */
    public static byte[] withPngMetadataChunks(byte[] png) {
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        chunk(out, "eXIf", tiffWithGps());
        chunk(out, "tEXt", ("Comment\0" + SECRET_MARK).getBytes(StandardCharsets.ISO_8859_1));
        chunk(out, "iTXt", ("XML:com.adobe.xmp\0\0\0\0\0<x:xmpmeta>" + SECRET_MARK + "</x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8));
        chunk(out, "zTXt", concat(("Comment\0").getBytes(StandardCharsets.ISO_8859_1), new byte[]{0},
                deflate(SECRET_MARK.getBytes(StandardCharsets.ISO_8859_1))));
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /** Un PNG real con otras dimensiones en el IHDR: sus datos ya no alcanzan, así que decodificarlo falla. */
    public static byte[] withPngDimensions(byte[] png, int width, int height) {
        byte[] copy = png.clone();
        ByteBuffer.wrap(copy, 16, 8).putInt(width).putInt(height);
        CRC32 crc = new CRC32();
        crc.update(copy, 12, 4 + 13);
        ByteBuffer.wrap(copy, 29, 4).putInt((int) crc.getValue());
        return copy;
    }

    /** Un JPEG real con otras dimensiones en el SOF0. */
    public static byte[] withJpegDimensions(byte[] jpeg, int width, int height) {
        byte[] copy = jpeg.clone();
        for (int i = 2; i < copy.length - 8; i++) {
            if ((copy[i] & 0xFF) == 0xFF && (copy[i + 1] & 0xFF) == 0xC0) {
                ByteBuffer.wrap(copy, i + 5, 4).putShort((short) height).putShort((short) width);
                return copy;
            }
        }
        throw new IllegalArgumentException("sin SOF0");
    }

    /** Los marcadores de un JPEG desde el SOI hasta el SOS (incluido). */
    public static List<Integer> jpegMarkers(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xDA) {
                break;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            i += 2 + length;
        }
        return markers;
    }

    /** Los tipos de fragmento de un PNG, en orden. */
    public static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int i = 8;
        while (i + 8 <= png.length) {
            int length = ByteBuffer.wrap(png, i, 4).getInt();
            types.add(new String(png, i + 4, 4, StandardCharsets.ISO_8859_1));
            i += 12 + length;
        }
        return types;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 255 / Math.max(1, width - 1)) << 16) | ((y * 255 / Math.max(1, height - 1)) << 8) | 0x40);
            }
        }
        return image;
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("sin escritor para " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** TIFF mínimo: IFD0 con el puntero GPS (0x8825) y un IFD GPS con latitud 4° 38' N. */
    private static byte[] tiffWithGps() {
        ByteBuffer b = ByteBuffer.allocate(80);
        b.put(new byte[]{'M', 'M', 0, 42}).putInt(8);
        b.putShort((short) 1).putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(26).putInt(0);
        b.putShort((short) 2);
        b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[]{'N', 0, 0, 0});
        b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(56);
        b.putInt(0);
        b.putInt(4).putInt(1).putInt(38).putInt(1).putInt(0).putInt(1);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(payload);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[data.length + 64];
        int n = deflater.deflate(buffer);
        deflater.end();
        return Arrays.copyOf(buffer, n);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
