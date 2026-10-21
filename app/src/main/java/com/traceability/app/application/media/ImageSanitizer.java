package com.traceability.app.application.media;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Limpieza con la JDK (ADR-051 F4), sin librerías nuevas, en este orden:
 * <ol>
 *   <li>tamaño máximo del propósito (F8) → {@link ImageTooLargeException};</li>
 *   <li>formato por sus primeros bytes: JPEG {@code FF D8 FF}, PNG {@code 89 50 4E 47 0D 0A 1A 0A}. Ni la extensión ni
 *       el {@code Content-Type} cuentan: no llegan aquí. El lector se elige por formato, nunca por autodetección;</li>
 *   <li>dimensiones leídas <b>solo de la cabecera</b>, antes de decodificar: máximo 8000 px por lado y 40 megapíxeles
 *       (Q2) → {@link ImageTooLargeException};</li>
 *   <li>decodificar; si la JDK no puede, o si el espacio de color no es RGB ni gris (la JDK 21 decodifica los JPEG CMYK
 *       e YCCK, plan §4.6), → {@link UnsupportedImageFormatException};</li>
 *   <li>volver a codificar en el mismo formato sin metadatos (EXIF con GPS, XMP, comentarios); JPEG con calidad 0,9;</li>
 *   <li>SHA-256 de los bytes resultantes.</li>
 * </ol>
 * Todo en memoria ({@code MemoryCache*}): los bytes de una foto nunca pasan por ficheros temporales.
 */
public class ImageSanitizer {

    /** Máximo por lado (ADR-051 F4, Q2). */
    static final int MAX_SIDE = 8000;
    /** Máximo de píxeles: 40 megapíxeles (ADR-051 F4, Q2). */
    static final long MAX_PIXELS = 40_000_000L;
    /** Calidad JPEG de ADR-051 F4.3. */
    static final float JPEG_QUALITY = 0.9f;

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private enum Format {
        JPEG("jpeg", "image/jpeg"),
        PNG("png", "image/png");

        final String imageIoName;
        final String contentType;

        Format(String imageIoName, String contentType) {
            this.imageIoName = imageIoName;
            this.contentType = contentType;
        }
    }

    public SanitizedImage sanitize(MediaPurpose purpose, byte[] content) {
        if (content.length > purpose.maxBytes()) {
            throw new ImageTooLargeException("Image exceeds " + purpose.maxBytes() + " bytes for " + purpose);
        }
        Format format = detect(content);
        BufferedImage image = decode(format, content);
        byte[] clean = encode(format, image);
        return new SanitizedImage(clean, format.contentType, image.getWidth(), image.getHeight(), sha256(clean));
    }

    static void checkDimensions(int width, int height) {
        if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
            throw new ImageTooLargeException("Image dimensions exceed the maximum (8000 px per side, 40 megapixels)");
        }
    }

    private static Format detect(byte[] content) {
        if (startsWith(content, PNG_MAGIC)) {
            return Format.PNG;
        }
        if (startsWith(content, JPEG_MAGIC)) {
            return Format.JPEG;
        }
        throw new UnsupportedImageFormatException("Only JPEG and PNG are accepted");
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }

    private static BufferedImage decode(Format format, byte[] content) {
        ImageReader reader = reader(format);
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            reader.setInput(input, true, true);
            int width;
            int height;
            try {
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } catch (IOException e) {
                throw new UnsupportedImageFormatException("The image header cannot be read");
            }
            checkDimensions(width, height);
            BufferedImage image;
            try {
                image = reader.read(0);
            } catch (IOException e) {
                throw new UnsupportedImageFormatException("The image cannot be decoded");
            }
            if (image == null) {
                throw new UnsupportedImageFormatException("The image cannot be decoded");
            }
            int colorSpace = image.getColorModel().getColorSpace().getType();
            if (colorSpace != ColorSpace.TYPE_RGB && colorSpace != ColorSpace.TYPE_GRAY) {
                throw new UnsupportedImageFormatException("Only RGB or grayscale images are accepted");
            }
            return image;
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("The image cannot be read");
        } finally {
            reader.dispose();
        }
    }

    private static byte[] encode(Format format, BufferedImage image) {
        ImageWriter writer = writer(format);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == Format.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            // sin metadatos de entrada ni miniaturas: el escritor solo pone los mínimos del formato
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("In-memory image encoding failed", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static ImageReader reader(Format format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.imageIoName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("The JDK has no " + format.imageIoName + " reader");
        }
        return readers.next();
    }

    private static ImageWriter writer(Format format) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.imageIoName);
        if (!writers.hasNext()) {
            throw new IllegalStateException("The JDK has no " + format.imageIoName + " writer");
        }
        return writers.next();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
