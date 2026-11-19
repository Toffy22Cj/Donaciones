package com.traceability.app.application.media;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * La copia censurada de la foto de entrega (ADR-051 Enmienda 2, E2-4; plan de la Tarea 3, §2.1): un pixelado fuerte con la
 * JDK, sin dependencias.
 * <ol>
 *   <li>Decodifica los bytes limpios de la original (JPEG o PNG); una imagen con transparencia se compone <b>sobre blanco</b>.</li>
 *   <li>Cuadrícula de {@value #LONG_SIDE_BLOCKS} bloques en el lado más largo y {@code N = max(1, round(24 × corto / largo))}
 *   en el corto; cada bloque promedia el color de su zona con <b>aritmética entera</b> (la zona del bloque {@code i} va de
 *   {@code floor(i·tamaño/bloques)} a {@code floor((i+1)·tamaño/bloques)}).</li>
 *   <li>Cada bloque se amplía a {@value #BLOCK_PX} × {@value #BLOCK_PX} px por vecino más próximo: 480 px en el lado largo y
 *   {@code 20·N} en el corto.</li>
 *   <li>PNG con parámetros fijos, sin metadatos.</li>
 * </ol>
 * <b>Determinista</b>: los mismos bytes de entrada dan los mismos bytes de salida con la misma JDK. La unicidad de la copia
 * <b>no depende</b> de eso (índice por {@code ownerRef}, plan §2.2). No pasa por {@link ImageSanitizer}: volver a codificar
 * con el limpiador cambiaría los bytes de la copia.
 */
@Component
public class EvidenceRedactor {

    static final int LONG_SIDE_BLOCKS = 24;
    static final int BLOCK_PX = 20;

    /** {@code width} y {@code height} son los de la copia. */
    public record Redacted(byte[] png, int width, int height) {}

    public Redacted redact(byte[] cleanImage) {
        BufferedImage source = flatten(decode(cleanImage));
        int w = source.getWidth(), h = source.getHeight();
        boolean landscape = w >= h;
        int longSize = landscape ? w : h, shortSize = landscape ? h : w;
        int longBlocks = LONG_SIDE_BLOCKS;
        int shortBlocks = Math.max(1, (int) Math.round((double) LONG_SIDE_BLOCKS * shortSize / longSize));
        int blocksX = landscape ? longBlocks : shortBlocks;
        int blocksY = landscape ? shortBlocks : longBlocks;

        BufferedImage out = new BufferedImage(blocksX * BLOCK_PX, blocksY * BLOCK_PX, BufferedImage.TYPE_INT_RGB);
        for (int by = 0; by < blocksY; by++) {
            int y0 = (int) ((long) by * h / blocksY), y1 = Math.max(y0 + 1, (int) ((long) (by + 1) * h / blocksY));
            for (int bx = 0; bx < blocksX; bx++) {
                int x0 = (int) ((long) bx * w / blocksX), x1 = Math.max(x0 + 1, (int) ((long) (bx + 1) * w / blocksX));
                long r = 0, g = 0, b = 0;
                long count = (long) (x1 - x0) * (y1 - y0);
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int rgb = source.getRGB(Math.min(x, w - 1), Math.min(y, h - 1));
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                    }
                }
                int colour = (int) ((r + count / 2) / count) << 16 | (int) ((g + count / 2) / count) << 8 | (int) ((b + count / 2) / count);
                for (int y = 0; y < BLOCK_PX; y++) {
                    for (int x = 0; x < BLOCK_PX; x++) {
                        out.setRGB(bx * BLOCK_PX + x, by * BLOCK_PX + y, colour);
                    }
                }
            }
        }
        return new Redacted(encode(out), out.getWidth(), out.getHeight());
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("The stored evidence is not a decodable image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compone sobre blanco: el resultado no depende de lo que haya detrás de la transparencia. */
    private static BufferedImage flatten(BufferedImage image) {
        BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = flat.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return flat;
    }

    /** PNG de 8 bits RGB con compresión fija y sin metadatos (ni texto, ni perfil, ni fecha, ni resolución). */
    private static byte[] encode(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.5f);
            }
            writer.setOutput(stream);
            // sin metadatos: se pasa null en lugar de los que ImageIO deduciría de la imagen
            writer.write(null, new IIOImage(image, null, null), param);
            stream.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
    }
}
